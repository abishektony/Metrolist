/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.pearconnect

import kotlinx.serialization.Serializable

enum class PearConnectMethod {
    LOCAL,
    SUPABASE,
    AUTO
}

@Serializable
data class PearConnectMessage(
    val type: String,
    val payload: kotlinx.serialization.json.JsonElement? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class AuthRequestPayload(
    val deviceName: String,
    val deviceType: String
)

@Serializable
data class AuthResponsePayload(
    val pairingCode: String,
    val expiresAt: Long = 0,
    val expiresIn: Long = 0
)

@Serializable
data class AuthSuccessPayload(
    val token: String,
    val serviceName: String = "Pear Desktop"
)

/**
 * Matches the desktop's PlaybackState type exactly.
 * Desktop sends: { isPlaying, currentTime, duration, volume, trackInfo, queuePosition, queueLength }
 */
@Serializable
data class PlaybackStatePayload(
    val isPlaying: Boolean = false,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Double = 100.0,
    val trackInfo: TrackInfo? = null,
    val queuePosition: Int = 0,
    val queueLength: Int = 0,
    val playbackTarget: String? = null,
    val timestamp: Long = 0,
    // Legacy compat field (some older versions may send this)
    val currentPosition: Long = 0
)

/**
 * Matches the desktop's TrackInfo type.
 * Desktop sends: { title, artist, album?, thumbnail?, videoId, duration }
 */
@Serializable
data class TrackInfo(
    val title: String = "Unknown",
    val artist: String = "Unknown",
    val album: String? = null,
    val thumbnail: String? = null,
    val videoId: String = "",
    val duration: Double = 0.0
)

/**
 * Matches the desktop's QueueItem type.
 * Desktop sends: { videoId, title, artist, thumbnail?, duration, addedBy?, isPlaying? }
 */
@Serializable
data class QueueItemPayload(
    val videoId: String = "",
    val title: String = "Unknown",
    val artist: String = "Unknown",
    val thumbnail: String? = null,
    val duration: Double = 0.0,
    val addedBy: String? = null,
    val isPlaying: Boolean = false
)
