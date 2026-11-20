package com.music.bitchord

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.music.bitchord.proxy.EmbeddedProxyVpnService
import com.music.bitchord.proxy.ProxySecretStore

class EmbeddedProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var url by remember { mutableStateOf(ProxySecretStore.getSubscription(this)) }
            var message by remember { mutableStateOf("订阅地址只会加密保存在本机，不会上传到 GitHub。") }
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) {
                if (it.resultCode == RESULT_OK) startProxy()
                else message = "未获得 VPN 授权，代理没有启动。"
            }
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("BitChord 内置代理", style = MaterialTheme.typography.headlineSmall)
                    Text("填入与 FlClash 相同的订阅链接。以后打开 BitChord 会自动启动，不需要再单独打开 FlClash。")
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("订阅链接") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            runCatching { ProxySecretStore.saveSubscription(this@EmbeddedProxySettingsActivity, url.trim()) }
                                .onSuccess {
                                    ProxySecretStore.setEnabled(this@EmbeddedProxySettingsActivity, true)
                                    message = "订阅已加密保存。正在请求系统 VPN 授权…"
                                    val request = VpnService.prepare(this@EmbeddedProxySettingsActivity)
                                    if (request == null) startProxy() else permission.launch(request)
                                }
                                .onFailure { message = it.message ?: "保存失败" }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存并连接") }
                    TextButton(
                        onClick = {
                            ProxySecretStore.setEnabled(this@EmbeddedProxySettingsActivity, false)
                            startService(Intent(this@EmbeddedProxySettingsActivity, EmbeddedProxyVpnService::class.java).setAction(EmbeddedProxyVpnService.ACTION_STOP))
                            message = "已发送断开请求。"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("断开内置代理") }
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Text("首次连接时，如果 FlClash 仍在运行，BitChord 会先尝试通过它的 7890 混合端口读取订阅；随后由 Android 切换到 BitChord 内置 VPN。连接后不需要再打开 FlClash。")
                }
            }
        }
    }

    private fun startProxy() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, EmbeddedProxyVpnService::class.java),
        )
    }
}
