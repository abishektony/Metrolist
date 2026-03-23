package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.metrolist.music.pearconnect.PearConnectMethod
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType as KbType
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.pearconnect.PearConnectClient
import com.metrolist.music.pearconnect.PearConnectState

@Composable
fun PearConnectDialog(
    pearConnectClient: PearConnectClient?,
    pearConnectState: PearConnectState,
    showPearConnectDialog: Boolean,
    onDismiss: () -> Unit,
    pearConnectPin: String,
    onPinChange: (String) -> Unit,
    onNavigateToQrScanner: () -> Unit
) {
    LaunchedEffect(showPearConnectDialog, pearConnectState) {
        if (showPearConnectDialog) {
            when (pearConnectState) {
                PearConnectState.DISCONNECTED, PearConnectState.ERROR -> {
                    // Don't auto-start NSD — user must choose a connection method
                }
                PearConnectState.DEVICE_FOUND -> {
                    val device = pearConnectClient?.discoveredDevices?.value?.firstOrNull()
                    if (device != null) {
                        pearConnectClient.connectToDevice(device)
                    }
                }
                else -> {}
            }
        }
    }

    if (showPearConnectDialog) {
        val isConnecting = pearConnectState == PearConnectState.CONNECTING ||
                pearConnectState == PearConnectState.DISCOVERING ||
                pearConnectState == PearConnectState.DEVICE_FOUND
        val isConnected = pearConnectState == PearConnectState.CONNECTED
        val isPairing = pearConnectState == PearConnectState.PAIRING
        val isError = pearConnectState == PearConnectState.ERROR
        val isIdle = pearConnectState == PearConnectState.DISCONNECTED

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (isConnected) "Connected to Desktop"
                    else "Connect to Pear Desktop"
                )
            },
            text = {
                val currentMode by pearConnectClient?.connectionMode?.collectAsState(initial = PearConnectMethod.AUTO) ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(PearConnectMethod.AUTO) }

                Column {
                    // Mode Selection
                    Text(
                        "Connection Fidelity",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PearConnectMethod.entries.forEach { mode ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { pearConnectClient?.setConnectionMode(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                leadingIcon = if (currentMode == mode) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.check),
                                            contentDescription = null,
                                            modifier = Modifier.height(18.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp))

                    when {
                        isConnected -> {
                            val playbackState by pearConnectClient?.desktopPlaybackState?.collectAsState() ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }
                            val currentTarget = playbackState?.playbackTarget ?: "laptop"

                            Column {
                                Text(
                                    "You are connected to Pear Desktop.",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.height(16.dp))
                                
                                Text(
                                    "Playback Target",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    listOf("laptop" to "Desktop", "phone" to "Phone").forEach { (target, label) ->
                                        FilterChip(
                                            selected = currentTarget == target,
                                            onClick = { pearConnectClient?.setPlaybackTarget(target) },
                                            label = { Text(label) },
                                            leadingIcon = if (currentTarget == target) {
                                                {
                                                    Icon(
                                                        painter = painterResource(R.drawable.check),
                                                        contentDescription = null,
                                                        modifier = Modifier.height(18.dp)
                                                    )
                                                }
                                            } else null
                                        )
                                    }
                                }
                            }
                        }
                        isConnecting || isPairing -> {
                            Text(
                                if (isConnecting) "Connecting to Pear Desktop…" else "Authenticating with Pear Desktop…",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(16.dp))
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Or try another method:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            
                            ConnectionMethodButtons(
                                onScanQr = {
                                    pearConnectClient?.disconnect()
                                    onDismiss()
                                    onNavigateToQrScanner()
                                },
                                onDiscover = {
                                    pearConnectClient?.disconnect() // Stop previous attempt
                                    pearConnectClient?.startDiscovery()
                                },
                                pin = pearConnectPin,
                                onPinChange = onPinChange,
                                onPinConnect = {
                                    pearConnectClient?.connectWithPin(it)
                                }
                            )
                        }
                        isError -> {
                            Text(
                            "Connection failed. Make sure Pear Desktop is open and your firewall allows the connection.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(16.dp))
                            // Offer both options on error too
                            ConnectionMethodButtons(
                                onScanQr = {
                                    onDismiss()
                                    onNavigateToQrScanner()
                                },
                                onDiscover = {
                                    pearConnectClient?.startDiscovery()
                                },
                                pin = pearConnectPin,
                                onPinChange = onPinChange,
                                onPinConnect = {
                                    pearConnectClient?.connectWithPin(it)
                                }
                            )
                        }
                        isIdle -> {
                            Text(
                                "Choose how to connect:",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(16.dp))
                            ConnectionMethodButtons(
                                onScanQr = {
                                    onDismiss()
                                    onNavigateToQrScanner()
                                },
                                onDiscover = {
                                    pearConnectClient?.startDiscovery()
                                },
                                pin = pearConnectPin,
                                onPinChange = onPinChange,
                                onPinConnect = {
                                    pearConnectClient?.connectWithPin(it)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    isConnected -> {
                        TextButton(onClick = {
                            pearConnectClient?.disconnect()
                            onDismiss()
                        }) {
                            Text("Disconnect", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    isConnecting || isPairing -> {
                        TextButton(onClick = {
                            pearConnectClient?.disconnect()
                        }) {
                            Text("Stop", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (isConnected) "Close" else "Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConnectionMethodButtons(
    onScanQr: () -> Unit,
    onDiscover: () -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    onPinConnect: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Primary: Scan QR
        OutlinedButton(
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.qr_code_scanner),
                contentDescription = null,
                modifier = Modifier
                    .height(20.dp)
                    .align(Alignment.CenterVertically)
            )
            Spacer(Modifier.height(8.dp))
            Text("  Scan QR Code  (Recommended)")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        
        Text(
            "Or enter pairing code manually:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                modifier = Modifier.weight(1f),
                label = { Text("6-Digit Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            
            androidx.compose.material3.Button(
                onClick = { onPinConnect(pin) },
                enabled = pin.length >= 6
            ) {
                Text("Connect")
            }
        }

        // Tertiary: NSD discovery
        TextButton(
            onClick = onDiscover,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Search on network instead",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
