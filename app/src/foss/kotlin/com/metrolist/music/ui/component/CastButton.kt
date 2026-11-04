package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.EnableGoogleCastKey
import com.metrolist.music.utils.rememberPreference

/**
 * CastButton for FOSS builds supporting DLNA Smart TVs (Samsung, LG, Roku, Sony, Windows).
 */
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current

    val (enableCast) = rememberPreference(
        key = EnableGoogleCastKey,
        defaultValue = true
    )

    if (!enableCast) return

    val castHandler = playerConnection?.service?.castConnectionHandler
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
    val isConnecting by castHandler?.isConnecting?.collectAsState() ?: remember { mutableStateOf(false) }
    val castDeviceName by castHandler?.castDeviceName?.collectAsState() ?: remember { mutableStateOf(null) }
    val currentMetadata by playerConnection?.mediaMetadata?.collectAsState() ?: remember { mutableStateOf(null) }
    val dlnaDevices by castHandler?.dlnaDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) }

    Box(modifier = modifier) {
        // Shadow background
        Box(
            modifier = Modifier
                .size(56.dp)
                .align(Alignment.Center)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Cast button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    if (currentMetadata == null && !isCasting) {
                        Toast.makeText(context, "Play a song first to cast", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }

                    castHandler?.startDlnaDiscovery()

                    menuState.show {
                        DlnaPickerSheet(
                            devices = dlnaDevices,
                            isConnecting = isConnecting,
                            connectedDevice = if (isCasting) castDeviceName else null,
                            onDeviceSelected = { device ->
                                castHandler?.connectToDlnaDevice(device)
                                menuState.dismiss()
                            },
                            onDisconnect = {
                                castHandler?.disconnect()
                                menuState.dismiss()
                            },
                            onRefresh = {
                                castHandler?.startDlnaDiscovery()
                            }
                        )
                    }
                }
        ) {
            Image(
                painter = painterResource(
                    if (isCasting) R.drawable.cast_connected else R.drawable.cast
                ),
                contentDescription = if (isCasting) "Stop casting" else "Cast",
                colorFilter = ColorFilter.tint(
                    if (isCasting) MaterialTheme.colorScheme.primary else tintColor
                ),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
