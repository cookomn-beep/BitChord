package com.music.bitchord.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import io.github.oviron.libmihomo.Clash
import io.github.oviron.libmihomo.TunInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.coroutines.resume

/**
 * App-scoped VPN. Android routes only BitChord through this TUN; other apps stay untouched.
 * Mihomo protects its outbound sockets through the VpnService callback, avoiding a VPN loop.
 */
class EmbeddedProxyVpnService : VpnService(), TunInterface {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tunStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCore()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (!isRunning) {
            isRunning = true
            scope.launch {
                try {
                    startCore()
                } catch (error: Throwable) {
                    // Do not log the exception message: some HTTP stacks include
                    // the subscription URL in provider errors.
                    android.util.Log.e(TAG, "Embedded proxy startup failed (${error.javaClass.simpleName})")
                    stopCore()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startCore() {
        val subscription = ProxySecretStore.getSubscription(this)
        check(subscription.isNotBlank()) { "请先在 BitChord 设置中填写订阅地址" }

        val home = File(filesDir, "mihomo").apply { mkdirs() }
        val providerDir = File(home, "providers").apply { mkdirs() }
        val providerFile = File(providerDir, "bitchord.yaml")
        if (!providerFile.exists() || providerFile.length() == 0L) {
            // First import can use the already-running FlClash local mixed port.
            providerFile.writeBytes(downloadSubscription(subscription))
        }
        val config = File(home, "config.yaml")
        config.writeText(buildConfig(providerFile.absolutePath))

        Clash.load(applicationInfo.nativeLibraryDir)
        check(Clash.isLoaded()) { "Mihomo 核心加载失败" }
        val initJson = JSONObject()
            .put("home-dir", home.absolutePath)
            .put("version", Build.VERSION.SDK_INT)
            .toString()
        val setupError = try {
            suspendCancellableCoroutine<String?> { continuation ->
                Clash.quickSetup(initJson, """{"selected-map":{}}""") { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } finally {
            // The running core has the provider URL in memory. Remove its plaintext
            // copy from disk even when configuration loading fails.
            config.writeText("# BitChord generated config; subscription URL is stored in Android Keystore.\\n")
        }
        check(setupError.isNullOrEmpty()) { "Mihomo 配置加载失败：$setupError" }

        val builder = Builder()
            .setSession("BitChord 内置代理")
            .setMtu(1400)
            .addAddress("172.19.0.1", 30)
            .addRoute(InetAddress.getByName("0.0.0.0"), 0)
            .addDnsServer("198.18.0.2")
            .addAllowedApplication(packageName)
        val descriptor = builder.establish() ?: error("Android 未能建立 VPN 隧道")
        val fd = descriptor.detachFd()
        Clash.startTUN(
            fd = fd,
            cb = this,
            device = "bitchord",
            stack = "system",
            address = "172.19.0.1/30",
            dns = "198.18.0.2",
            mtu = 1400,
        )
        tunStarted = true
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("已连接 · 国内直连 / 海外代理"))

        // Refresh through the app's own TUN, so the subscription request itself
        // follows the selected proxy instead of requiring FlClash after setup.
        scope.launch {
            delay(5_000)
            while (isActive && tunStarted) {
                runCatching { refreshSubscription(subscription, providerFile) }
                    .onFailure {
                        android.util.Log.w(TAG, "Subscription refresh failed (${it.javaClass.simpleName})")
                    }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun downloadSubscription(subscription: String): ByteArray {
        fun fetch(proxy: Proxy): ByteArray {
            val connection = URL(subscription).openConnection(proxy)
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "clash.meta")
            connection.setRequestProperty("Accept", "*/*")
            val bytes = connection.getInputStream().use { it.readBytes() }
            require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) {
                "订阅内容为空或超过 10 MB"
            }
            val preview = String(bytes, Charsets.UTF_8).trimStart('\uFEFF').trimStart()
            require(
                !preview.startsWith("<html", ignoreCase = true) &&
                    !preview.startsWith("<!doctype", ignoreCase = true)
            ) { "订阅服务器返回了网页而不是节点配置" }
            return bytes
        }

        // The first run can bootstrap through FlClash. After BitChord's VPN is
        // up, the direct Java request is captured by our TUN and routed by PROXY.
        val localFlClash = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress("127.0.0.1", 7890),
        )
        return runCatching { fetch(localFlClash) }
            .getOrElse { fetch(Proxy.NO_PROXY) }
    }

    private suspend fun refreshSubscription(subscription: String, providerFile: File) {
        val bytes = downloadSubscription(subscription)
        val params = JSONObject()
            .put("providerName", "bitchord-subscription")
            .put("data", String(bytes, Charsets.UTF_8))
        val action = JSONObject()
            .put("id", "bitchord-subscription-refresh")
            .put("method", "sideLoadExternalProvider")
            .put("data", params.toString())
            .toString()
        val response = suspendCancellableCoroutine<String?> { continuation ->
            Clash.invokeAction(action) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
        val result = runCatching { JSONObject(response ?: "{}") }.getOrNull()
        check(result?.optInt("code", -1) == 0) { "Mihomo rejected refreshed subscription" }
        providerFile.writeBytes(bytes)
    }

    private fun buildConfig(providerPath: String): String {
        val safePath = providerPath.replace("\\", "/").replace("'", "''")
        return """
            mixed-port: 17890
            allow-lan: false
            mode: rule
            log-level: warning
            ipv6: false
            external-controller: 127.0.0.1:19090
            dns:
              enable: true
              listen: 127.0.0.1:1053
              ipv6: false
              enhanced-mode: fake-ip
              nameserver:
                - 223.5.5.5
                - 119.29.29.29
              fallback:
                - https://1.1.1.1/dns-query
              fallback-filter:
                geoip: true
                geoip-code: CN
            proxy-providers:
              bitchord-subscription:
                type: file
                path: '$safePath'
                health-check:
                  enable: true
                  url: https://www.gstatic.com/generate_204
                  interval: 600
            proxy-groups:
              - name: PROXY
                type: select
                use:
                  - bitchord-subscription
              - name: GLOBAL
                type: select
                proxies:
                  - PROXY
                  - DIRECT
            rules:
              - DOMAIN-SUFFIX,163.com,DIRECT
              - DOMAIN-SUFFIX,netease.com,DIRECT
              - DOMAIN-SUFFIX,qq.com,DIRECT
              - DOMAIN-SUFFIX,qpic.cn,DIRECT
              - DOMAIN-SUFFIX,gtimg.com,DIRECT
              - DOMAIN-SUFFIX,kuwo.cn,DIRECT
              - DOMAIN-SUFFIX,kugou.com,DIRECT
              - DOMAIN-SUFFIX,migu.cn,DIRECT
              - DOMAIN-SUFFIX,baidu.com,DIRECT
              - DOMAIN-SUFFIX,bilibili.com,DIRECT
              - DOMAIN-SUFFIX,cn,DIRECT
              - DOMAIN-SUFFIX,jiosaavn.com,PROXY
              - DOMAIN-SUFFIX,saavn.com,PROXY
              - DOMAIN-SUFFIX,spotify.com,PROXY
              - DOMAIN-SUFFIX,scdn.co,PROXY
              - DOMAIN-SUFFIX,youtube.com,PROXY
              - DOMAIN-SUFFIX,youtubei.googleapis.com,PROXY
              - DOMAIN-SUFFIX,googlevideo.com,PROXY
              - DOMAIN-SUFFIX,deezer.com,PROXY
              - DOMAIN-SUFFIX,tidal.com,PROXY
              - DOMAIN-SUFFIX,qobuz.com,PROXY
              - GEOIP,CN,DIRECT
              - MATCH,PROXY
        """.trimIndent()
    }

    private fun stopCore() {
        if (runCatching { Clash.isLoaded() }.getOrDefault(false)) {
            // stopTun is idempotent and also shuts down the core listener if
            // VPN establishment failed after quickSetup completed.
            runCatching { Clash.stopTun() }
        }
        tunStarted = false
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun protect(fd: Int) {
        super.protect(fd)
    }

    override fun resolverProcess(protocol: Int, source: String, target: String, uid: Int): String = ""

    override fun onRevoke() {
        stopCore()
        stopSelf()
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopCore()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopCore()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BitChord 内置代理", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(status: String = "正在启动内置代理"): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("BitChord 内置代理")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "BitChordProxy"
        private const val CHANNEL_ID = "bitchord_embedded_proxy"
        private const val NOTIFICATION_ID = 7419
        const val ACTION_STOP = "com.music.bitchord.proxy.STOP"
        @Volatile var isRunning: Boolean = false
            private set
    }
}
