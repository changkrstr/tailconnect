/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.backends.tailscale.TailscaleLinkProvider
import org.kde.kdeconnect.backends.tailscale.TailscaleTunnelManager
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.KdeTopAppBar
import org.kde.kdeconnect_tp.R

class TailscaleSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KdeTheme(this) {
                TailscaleSettingsScreen(
                    onNavigateUp = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleSettingsScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isEnabled by remember { mutableStateOf(TailscaleTunnelManager.isEnabled(context)) }
    var authKey by remember { mutableStateOf(TailscaleTunnelManager.getAuthKey(context)) }
    var controlUrl by remember { mutableStateOf(TailscaleTunnelManager.getControlUrl(context)) }
    var hostname by remember { mutableStateOf(TailscaleTunnelManager.getHostname(context)) }

    var tunnelState by remember { mutableStateOf(TailscaleTunnelManager.state) }
    var localIp by remember { mutableStateOf(TailscaleTunnelManager.localIp) }
    var lastError by remember { mutableStateOf(TailscaleTunnelManager.lastError) }

    DisposableEffect(Unit) {
        val listener = TailscaleTunnelManager.StateListener { state, ip, err ->
            tunnelState = state
            localIp = ip
            lastError = err
        }
        TailscaleTunnelManager.addStateListener(listener)
        onDispose {
            TailscaleTunnelManager.removeStateListener(listener)
        }
    }

    Scaffold(
        topBar = {
            KdeTopAppBar(
                title = stringResource(R.string.tailscale_settings_title),
                navIconOnClick = onNavigateUp
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Enable / Disable switch card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.tailscale_enable),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.tailscale_settings_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                isEnabled = checked
                                TailscaleTunnelManager.setEnabled(context, checked)
                                if (checked) {
                                    TailscaleTunnelManager.start(context)
                                } else {
                                    TailscaleTunnelManager.stop()
                                }
                                BackgroundService.ForceRefreshConnections(context)
                            }
                        )
                    }
                }
            }

            // Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (tunnelState) {
                            TailscaleTunnelManager.State.ONLINE -> Color(0xFF1B5E20).copy(alpha = 0.12f)
                            TailscaleTunnelManager.State.CONNECTING -> Color(0xFFE65100).copy(alpha = 0.12f)
                            TailscaleTunnelManager.State.ERROR -> Color(0xFFB71C1C).copy(alpha = 0.12f)
                            TailscaleTunnelManager.State.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            color = when (tunnelState) {
                                                TailscaleTunnelManager.State.ONLINE -> Color(0xFF4CAF50)
                                                TailscaleTunnelManager.State.CONNECTING -> Color(0xFFFF9800)
                                                TailscaleTunnelManager.State.ERROR -> Color(0xFFF44336)
                                                TailscaleTunnelManager.State.STOPPED -> Color.Gray
                                            },
                                            shape = CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (tunnelState) {
                                        TailscaleTunnelManager.State.ONLINE -> stringResource(R.string.tailscale_status_online)
                                        TailscaleTunnelManager.State.CONNECTING -> stringResource(R.string.tailscale_status_connecting)
                                        TailscaleTunnelManager.State.ERROR -> stringResource(R.string.tailscale_status_error)
                                        TailscaleTunnelManager.State.STOPPED -> stringResource(R.string.tailscale_status_stopped)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (isEnabled && tunnelState != TailscaleTunnelManager.State.CONNECTING) {
                                IconButton(
                                    onClick = {
                                        TailscaleTunnelManager.stop()
                                        TailscaleTunnelManager.start(context)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reconnect"
                                    )
                                }
                            }
                        }

                        if (!localIp.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.tailscale_local_ip, localIp ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (!lastError.isNullOrBlank() && tunnelState == TailscaleTunnelManager.State.ERROR) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = lastError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Tailscale Auth & Server Config Section
            item {
                Text(
                    text = "Tailscale 配置",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it },
                    label = { Text(stringResource(R.string.tailscale_authkey_title)) },
                    placeholder = { Text(stringResource(R.string.tailscale_authkey_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = controlUrl,
                    onValueChange = { controlUrl = it },
                    label = { Text(stringResource(R.string.tailscale_control_url_title)) },
                    placeholder = { Text(stringResource(R.string.tailscale_control_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text(stringResource(R.string.tailscale_hostname_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Button(
                    onClick = {
                        TailscaleTunnelManager.setAuthKey(context, authKey)
                        TailscaleTunnelManager.setControlUrl(context, controlUrl)
                        TailscaleTunnelManager.setHostname(context, hostname)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("配置已保存")
                        }
                        isEnabled = true
                        TailscaleTunnelManager.setEnabled(context, true)
                        TailscaleTunnelManager.stop()
                        TailscaleTunnelManager.start(context)
                        BackgroundService.ForceRefreshConnections(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.tailscale_save_settings))
                }
            }

            // Device Discovery Guidance
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "如何发现与连接 Tailnet 设备",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tailscale 隧道已与系统的「通过 IP 添加设备」深度打通。\n在上方启用并连上 Tailscale 后，只需在「通过 IP 添加设备」中输入同一 Tailnet 下其他设备的 IP (例如你的电脑 100.x.y.z)，KDE Connect 就会自动完成内网探测、握手并显示设备名。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(android.content.Intent(context, CustomDevicesActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("打开「通过 IP 添加设备」")
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
