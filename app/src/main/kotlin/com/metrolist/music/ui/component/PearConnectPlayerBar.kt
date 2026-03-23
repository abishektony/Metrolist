/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * PearConnectPlayerBar — Shows the desktop's current playback when connected.
 * Also provides seamless handoff: "Continue on Phone" loads the desktop's
 * current track locally in Metrolist at the exact playback position.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.pearconnect.PearConnectClient
import com.metrolist.music.pearconnect.PearConnectState
import com.metrolist.music.pearconnect.PlaybackStatePayload
import com.metrolist.music.pearconnect.QueueItemPayload
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.metrolist.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map

/**
 * Compact mini-player bar shown above the app's own player bar when
 * PearConnect is connected and the desktop is playing something.
 */
@Composable
fun PearConnectPlayerBar(
    pearConnectClient: PearConnectClient?,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {}
) {
    val connectionState by pearConnectClient?.state?.collectAsState()
        ?: remember { mutableStateOf(PearConnectState.DISCONNECTED) }
    val playbackState by pearConnectClient?.desktopPlaybackState?.collectAsState()
        ?: remember { mutableStateOf<PlaybackStatePayload?>(null) }

    val currentState = playbackState // Capture for smart casting
    val isConnected = connectionState == PearConnectState.CONNECTED
    val trackInfo = currentState?.trackInfo

    AnimatedVisibility(
        visible = isConnected && trackInfo != null && (playbackState?.playbackTarget ?: "laptop") == "laptop",
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (trackInfo != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpand() },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Column {
                    // Progress bar line at top
                    // Interpolated progress logic
                    var interpolatedTime by remember(currentState) { 
                        mutableDoubleStateOf(currentState?.currentTime ?: 0.0) 
                    }
                    
                    LaunchedEffect(currentState?.isPlaying, currentState?.timestamp) {
                        if (currentState != null && currentState.isPlaying && currentState.timestamp > 0) {
                            val baseTime = currentState.currentTime
                            val desktopTimestamp = currentState.timestamp
                            val duration = currentState.duration
                            while (true) {
                                val latencyAdjustedElapsed = (System.currentTimeMillis() - desktopTimestamp) / 1000.0
                                interpolatedTime = (baseTime + latencyAdjustedElapsed).coerceAtMost(duration)
                                delay(250)
                            }
                        }
                    }

                    val progress = if (currentState != null && currentState.duration > 0)
                        (interpolatedTime / currentState.duration).toFloat().coerceIn(0f, 1f)
                    else 0f

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // 🍐 Desktop indicator
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00FF88))
                        )

                        Spacer(Modifier.width(8.dp))

                        // Thumbnail
                        AsyncImage(
                            model = trackInfo.thumbnail,
                            contentDescription = trackInfo.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )

                        Spacer(Modifier.width(10.dp))

                        // Track info
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = trackInfo.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${trackInfo.artist} • Pear Desktop",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        // Simple play/pause control
                        IconButton(
                            onClick = {
                                if (currentState?.isPlaying == true) {
                                    pearConnectClient?.pause()
                                } else {
                                    pearConnectClient?.play()
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (currentState?.isPlaying == true) R.drawable.pause
                                    else R.drawable.play
                                ),
                                contentDescription = if (currentState?.isPlaying == true) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = { pearConnectClient?.next() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.skip_next),
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen expanded view of the desktop playback — shown when user taps the mini bar.
 * Includes album art, controls, progress, volume, and queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PearConnectExpandedPlayer(
    pearConnectClient: PearConnectClient?,
    onDismiss: () -> Unit
) {
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val playbackState by pearConnectClient?.desktopPlaybackState?.collectAsState()
        ?: remember { mutableStateOf<PlaybackStatePayload?>(null) }
    val queue by pearConnectClient?.desktopQueue?.collectAsState()
        ?: remember { mutableStateOf<List<QueueItemPayload>>(emptyList()) }

    val currentState = playbackState // Capture for smart casting
    val trackInfo = currentState?.trackInfo

    var interpolatedTime by remember(currentState) { 
        mutableDoubleStateOf(currentState?.currentTime ?: 0.0) 
    }
    
    LaunchedEffect(currentState?.isPlaying, currentState?.timestamp) {
        if (currentState != null && currentState.isPlaying && currentState.timestamp > 0) {
            val baseTime = currentState.currentTime
            val desktopTimestamp = currentState.timestamp
            val duration = currentState.duration
            while (true) {
                val latencyAdjustedElapsed = (System.currentTimeMillis() - desktopTimestamp) / 1000.0
                interpolatedTime = (baseTime + latencyAdjustedElapsed).coerceAtMost(duration)
                delay(250)
            }
        }
    }

    val progress = if (currentState != null && currentState.duration > 0)
        (interpolatedTime / currentState.duration).toFloat().coerceIn(0f, 1f)
    else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Playing from Desktop",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Pear Connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = "Close"
                        )
                    }
                },
                actions = {
                    // Connection indicator
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FF88))
                    )
                }
            )
        }
    ) { innerPadding ->
        if (trackInfo == null) {
            // No track playing
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "🍐",
                        fontSize = 48.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Connected to Desktop",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Play something on Pear Desktop to see it here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Album art
            item {
                Spacer(Modifier.height(16.dp))
                AsyncImage(
                    model = trackInfo.thumbnail,
                    contentDescription = trackInfo.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
            }

            // Track info
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = trackInfo.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = trackInfo.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (trackInfo.album != null) {
                    Text(
                        text = trackInfo.album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Progress
            item {
                Spacer(Modifier.height(24.dp))
                Slider(
                    value = progress,
                    onValueChange = { newVal ->
                        val seekPosition = (newVal * currentState!!.duration).toLong()
                        pearConnectClient?.seekTo(seekPosition)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDuration(interpolatedTime.toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatDuration(currentState?.duration?.toLong() ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Playback controls
            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = { pearConnectClient?.previous() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = "Previous",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (currentState?.isPlaying == true) {
                                pearConnectClient?.pause()
                            } else {
                                pearConnectClient?.play()
                            }
                        },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            painter = painterResource(
                                if (currentState?.isPlaying == true) R.drawable.pause
                                else R.drawable.play
                            ),
                            contentDescription = if (currentState?.isPlaying == true) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = { pearConnectClient?.next() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = "Next",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Volume
            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = "Volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Slider(
                        value = ((currentState?.volume ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f),
                        onValueChange = { pearConnectClient?.setVolume(it) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            activeTrackColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        "${((currentState?.volume ?: 100.0)).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Play On Selection ──────────────────────────────────────
            item {
                Spacer(Modifier.height(32.dp))
                Text(
                    "Play On",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                )
                Spacer(Modifier.height(12.dp))

                val context = androidx.compose.ui.platform.LocalContext.current
                val currentModeStr by context.dataStore.data
                    .map { it[com.metrolist.music.constants.PearConnectModeKey] ?: com.metrolist.music.constants.PearConnectMode.LAPTOP_ONLY.name }
                    .collectAsState(initial = com.metrolist.music.constants.PearConnectMode.LAPTOP_ONLY.name)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.metrolist.music.constants.PearConnectMode.entries.forEach { mode ->
                        val isSelected = currentModeStr == mode.name
                        val localScope = rememberCoroutineScope()
                        OutlinedButton(
                            onClick = {
                                localScope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[com.metrolist.music.constants.PearConnectModeKey] = mode.name
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            ),
                            border = if (isSelected) null else ButtonDefaults.outlinedButtonBorder,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(
                                        when (mode) {
                                            com.metrolist.music.constants.PearConnectMode.PHONE_ONLY -> R.drawable.phone_android
                                            com.metrolist.music.constants.PearConnectMode.LAPTOP_ONLY -> R.drawable.cast
                                            com.metrolist.music.constants.PearConnectMode.BOTH -> R.drawable.sync
                                        }
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = when (mode) {
                                        com.metrolist.music.constants.PearConnectMode.PHONE_ONLY -> "Phone"
                                        com.metrolist.music.constants.PearConnectMode.LAPTOP_ONLY -> "Laptop"
                                        com.metrolist.music.constants.PearConnectMode.BOTH -> "Both"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            // ── Handoff / Disconnect ──────────────────────────────────────
            item {
                Spacer(Modifier.height(32.dp))
                HorizontalDivider(modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
                Spacer(Modifier.height(24.dp))

                val videoId = trackInfo?.videoId?.takeIf { it.isNotBlank() }
                val seekMs = (interpolatedTime * 1000).toLong()

                OutlinedButton(
                    onClick = {
                        if (videoId != null && playerConnection != null) {
                            playerConnection.playQueue(
                                YouTubeQueue(WatchEndpoint(videoId = videoId))
                            )
                            coroutineScope.launch {
                                delay(800)
                                playerConnection.seekTo(seekMs)
                            }
                            pearConnectClient?.disconnect()
                            onDismiss()
                        }
                    },
                    enabled = videoId != null && playerConnection != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phone_android),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Complete Handoff & Disconnect")
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Switch fully to mobile and stop controlling desktop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }

            // Queue section
            if (queue.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Up Next",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${queue.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                itemsIndexed(queue) { index, item ->
                    QueueItemRow(
                        item = item,
                        onClick = {
                            // Send PLAY_QUEUE_ITEM to desktop
                            pearConnectClient?.sendMessage(
                                "PLAY_QUEUE_ITEM",
                                kotlinx.serialization.json.JsonPrimitive(index)
                            )
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: QueueItemPayload,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (item.isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        // Thumbnail
        AsyncImage(
            model = item.thumbnail,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Normal,
                color = if (item.isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = formatDuration(item.duration.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}

@Composable
fun PearExpandedControls(
    pearConnectClient: PearConnectClient?,
    desktopPlaybackState: PlaybackStatePayload,
    isPearConnected: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    TextBackgroundColor: Color,
    sideButtonContainerColor: Color,
    sideButtonContentColor: Color,
    useNewPlayerDesign: Boolean
) {
    if (!isPearConnected) return

    val track = desktopPlaybackState.trackInfo ?: return

    Column(modifier = Modifier.fillMaxWidth()) {
        // Track Info
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            color = TextBackgroundColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.titleMedium,
            color = TextBackgroundColor.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(Modifier.height(24.dp))

        // Interpolated time indicators
        var interpolatedTime by remember(desktopPlaybackState) { 
            mutableDoubleStateOf(desktopPlaybackState.currentTime) 
        }
        
        LaunchedEffect(desktopPlaybackState.isPlaying, desktopPlaybackState.timestamp) {
            if (desktopPlaybackState.isPlaying && desktopPlaybackState.timestamp > 0) {
                val baseTime = desktopPlaybackState.currentTime
                val desktopTimestamp = desktopPlaybackState.timestamp
                val duration = desktopPlaybackState.duration
                while (true) {
                    val latencyAdjustedElapsed = (System.currentTimeMillis() - desktopTimestamp) / 1000.0
                    interpolatedTime = (baseTime + latencyAdjustedElapsed).coerceAtMost(duration)
                    delay(250)
                }
            }
        }

        val progressPercent = if (desktopPlaybackState.duration > 0)
            (interpolatedTime.toFloat() / desktopPlaybackState.duration.toFloat()).coerceIn(0f, 1f)
        else 0f

        Slider(
            value = progressPercent,
            onValueChange = { percent ->
                if (desktopPlaybackState.duration > 0) {
                    val positionSecs = (percent * desktopPlaybackState.duration).toLong()
                    pearConnectClient?.sendMessage("SEEK", kotlinx.serialization.json.JsonPrimitive(positionSecs))
                }
            },
            modifier = Modifier.padding(horizontal = 24.dp),
            colors = SliderDefaults.colors(
                thumbColor = textButtonColor,
                activeTrackColor = textButtonColor,
                inactiveTrackColor = textButtonColor.copy(alpha = 0.3f)
            )
        )

        // Time indicators
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Text(
                text = formatDuration(interpolatedTime.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = TextBackgroundColor
            )
            Text(
                text = formatDuration(desktopPlaybackState.duration.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = TextBackgroundColor
            )
        }

        Spacer(Modifier.height(24.dp))

        // Controls
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            // Previous
            FilledIconButton(
                onClick = { pearConnectClient?.previous() },
                shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = sideButtonContainerColor,
                    contentColor = sideButtonContentColor,
                ),
                modifier = Modifier
                    .height(68.dp)
                    .weight(0.45f)
            ) {
                Icon(
                    painter = painterResource(R.drawable.skip_previous),
                    contentDescription = "Previous",
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Play/Pause
            FilledIconButton(
                onClick = {
                    if (desktopPlaybackState.isPlaying) pearConnectClient?.pause()
                    else pearConnectClient?.play()
                },
                shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = textButtonColor,
                    contentColor = iconButtonColor,
                ),
                modifier = Modifier
                    .height(68.dp)
                    .weight(1.3f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (desktopPlaybackState.isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Next
            FilledIconButton(
                onClick = { pearConnectClient?.next() },
                shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = sideButtonContainerColor,
                    contentColor = sideButtonContentColor,
                ),
                modifier = Modifier
                    .height(68.dp)
                    .weight(0.45f)
            ) {
                Icon(
                    painter = painterResource(R.drawable.skip_next),
                    contentDescription = "Next",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
