/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RadioStation(
    @SerialName("stationuuid") val stationuuid: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    @SerialName("favicon") val favicon: String = "",
    @SerialName("tags") val tags: String = "",
    @SerialName("country") val country: String = "",
    @SerialName("countrycode") val countrycode: String = "",
    @SerialName("state") val state: String = "",
    @SerialName("language") val language: String = "",
    @SerialName("codec") val codec: String = "",
    @SerialName("bitrate") val bitrate: Int = 0,
    @SerialName("votes") val votes: Int = 0,
    @SerialName("clickcount") val clickcount: Int = 0,
    @SerialName("clicktrend") val clicktrend: Int = 0,
)

fun RadioStation.toMediaMetadata(): MediaMetadata {
    val streamUrl = urlResolved.ifBlank { url }
    val location = listOfNotNull(
        state.takeIf { it.isNotBlank() },
        country.takeIf { it.isNotBlank() }
    ).joinToString(", ")
    val subtitleParts = listOfNotNull(
        location.takeIf { it.isNotBlank() },
        if (codec.isNotBlank() && bitrate > 0) "$codec ${bitrate}kbps" else null
    )
    val artistName = if (subtitleParts.isNotEmpty()) subtitleParts.joinToString(" · ") else "Live Radio"

    return MediaMetadata(
        id = "radio:$streamUrl",
        title = name.ifBlank { "Radio Station" },
        artists = listOf(MediaMetadata.Artist(id = null, name = artistName)),
        duration = -1,
        thumbnailUrl = favicon.takeIf { it.isNotBlank() },
        album = location.takeIf { it.isNotBlank() }?.let { MediaMetadata.Album(id = countrycode, title = it) }
    )
}

fun RadioStation.toMediaItem(): MediaItem {
    val metadata = toMediaMetadata()
    val streamUrl = urlResolved.ifBlank { url }
    return MediaItem.Builder()
        .setMediaId("radio:$streamUrl")
        .setUri(streamUrl.toUri())
        .setCustomCacheKey("radio:$streamUrl")
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist(metadata.artists.firstOrNull()?.name)
                .setDisplayTitle(metadata.title)
                .setArtworkUri(metadata.thumbnailUrl?.toUri())
                .setIsPlayable(true)
                .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        )
        .build()
}
