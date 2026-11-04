package com.metrolist.music.cast.dlna

/**
 * Represents a DLNA / UPnP MediaRenderer device discovered on the local network
 * (e.g. Samsung Tizen TV, LG webOS TV, Roku, Sony, Windows Media Player).
 */
data class DlnaDevice(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val locationUrl: String,
    val avTransportUrl: String,
    val renderingControlUrl: String? = null,
    val iconUrl: String? = null
)
