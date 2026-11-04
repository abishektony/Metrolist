package com.metrolist.music.cast.dlna

import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DlnaController(
    private val scope: CoroutineScope,
    var onPlaybackEnded: (() -> Unit)? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _connectedDevice = MutableStateFlow<DlnaDevice?>(null)
    val connectedDevice: StateFlow<DlnaDevice?> = _connectedDevice.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var pollJob: Job? = null
    private var lastObservedDuration = 0L

    fun connect(device: DlnaDevice) {
        _connectedDevice.value = device
        _isConnected.value = true
        Timber.d("Connected to DLNA device: ${device.name}")
        startPolling()
    }

    fun disconnect() {
        val device = _connectedDevice.value
        stopPolling()
        if (device != null) {
            scope.launch(Dispatchers.IO) {
                stop(device)
            }
        }
        _connectedDevice.value = null
        _isConnected.value = false
        _isPlaying.value = false
        _isBuffering.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    fun playMedia(
        mediaUrl: String,
        metadata: MediaMetadata,
        initialPositionMs: Long = 0L
    ) {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            _isBuffering.value = true
            try {
                val didl = buildDidlMetadata(mediaUrl, metadata)
                val setUriSuccess = setAvTransportUri(device, mediaUrl, didl)
                if (setUriSuccess) {
                    val playSuccess = play(device)
                    if (playSuccess) {
                        _isPlaying.value = true
                        _isBuffering.value = false
                        if (initialPositionMs > 1000) {
                            delay(500)
                            seekTo(initialPositionMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error playing media on DLNA TV")
                _isBuffering.value = false
            }
        }
    }

    fun play() {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            if (play(device)) {
                _isPlaying.value = true
            }
        }
    }

    fun pause() {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            if (pause(device)) {
                _isPlaying.value = false
            }
        }
    }

    fun stop() {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            if (stop(device)) {
                _isPlaying.value = false
                _positionMs.value = 0L
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val device = _connectedDevice.value ?: return
        scope.launch(Dispatchers.IO) {
            val timeString = formatTime(positionMs)
            val body = """
                <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                    <Unit>REL_TIME</Unit>
                    <Target>$timeString</Target>
                </u:Seek>
            """.trimIndent()
            sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#Seek", body)
            _positionMs.value = positionMs
        }
    }

    fun setVolume(volumeFraction: Float) {
        val device = _connectedDevice.value ?: return
        val renderingUrl = device.renderingControlUrl ?: return
        val percent = (volumeFraction.coerceIn(0f, 1f) * 100).toInt()
        scope.launch(Dispatchers.IO) {
            val body = """
                <u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                    <DesiredVolume>$percent</DesiredVolume>
                </u:SetVolume>
            """.trimIndent()
            val res = sendSoapAction(renderingUrl, "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume", body)
            if (res != null) {
                _volume.value = volumeFraction
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val device = _connectedDevice.value ?: break
                try {
                    pollPositionAndState(device)
                } catch (e: Exception) {
                    Timber.d("DLNA poll error: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollPositionAndState(device: DlnaDevice) {
        // Query Position Info
        val posBody = """
            <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:GetPositionInfo>
        """.trimIndent()
        val posResponse = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo", posBody)
        if (posResponse != null) {
            val relTime = extractTag(posResponse, "RelTime")
            val trackDuration = extractTag(posResponse, "TrackDuration")
            if (relTime != null) {
                val ms = parseTime(relTime)
                if (ms >= 0) _positionMs.value = ms
            }
            if (trackDuration != null) {
                val durMs = parseTime(trackDuration)
                if (durMs > 0) {
                    _durationMs.value = durMs
                    lastObservedDuration = durMs
                }
            }
        }

        // Query Transport Info
        val transportBody = """
            <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:GetTransportInfo>
        """.trimIndent()
        val transportResponse = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo", transportBody)
        if (transportResponse != null) {
            val state = extractTag(transportResponse, "CurrentTransportState")?.uppercase()
            when (state) {
                "PLAYING" -> {
                    _isPlaying.value = true
                    _isBuffering.value = false
                }
                "PAUSED_PLAYBACK" -> {
                    _isPlaying.value = false
                    _isBuffering.value = false
                }
                "TRANSITIONING" -> {
                    _isBuffering.value = true
                }
                "STOPPED" -> {
                    if (_isPlaying.value && _positionMs.value > 5000 && lastObservedDuration > 0 &&
                        _positionMs.value >= lastObservedDuration - 3000) {
                        // Song naturally ended on TV
                        _isPlaying.value = false
                        Timber.d("DLNA playback naturally completed, triggering next song")
                        withContext(Dispatchers.Main) {
                            onPlaybackEnded?.invoke()
                        }
                    } else {
                        _isPlaying.value = false
                    }
                    _isBuffering.value = false
                }
            }
        }
    }

    private suspend fun setAvTransportUri(device: DlnaDevice, uri: String, didlMetadata: String): Boolean {
        val escapedDidl = escapeXml(didlMetadata)
        val body = """
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <CurrentURI>$uri</CurrentURI>
                <CurrentURIMetaData>$escapedDidl</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()
        val res = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", body)
        return res != null
    }

    private suspend fun play(device: DlnaDevice): Boolean {
        val body = """
            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent()
        val res = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", body)
        return res != null
    }

    private suspend fun pause(device: DlnaDevice): Boolean {
        val body = """
            <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:Pause>
        """.trimIndent()
        val res = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#Pause", body)
        return res != null
    }

    private suspend fun stop(device: DlnaDevice): Boolean {
        val body = """
            <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:Stop>
        """.trimIndent()
        val res = sendSoapAction(device.avTransportUrl, "urn:schemas-upnp-org:service:AVTransport:1#Stop", body)
        return res != null
    }

    private suspend fun sendSoapAction(url: String, soapAction: String, innerBody: String): String? = withContext(Dispatchers.IO) {
        try {
            val soapXml = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        $innerBody
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            val request = Request.Builder()
                .url(url)
                .addHeader("SOAPAction", "\"$soapAction\"")
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .post(soapXml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Timber.w("DLNA SOAP action $soapAction failed with code ${response.code}: ${response.body?.string()}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error sending SOAP action $soapAction to $url")
            null
        }
    }

    private fun buildDidlMetadata(mediaUrl: String, metadata: MediaMetadata): String {
        val title = escapeXml(metadata.title)
        val artist = escapeXml(metadata.artists.joinToString(", ") { it.name })
        val album = escapeXml(metadata.album?.title ?: "")
        val artUri = escapeXml(metadata.thumbnailUrl ?: "")

        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="1" parentID="0" restricted="1">
                    <dc:title>$title</dc:title>
                    <dc:creator>$artist</dc:creator>
                    <upnp:artist>$artist</upnp:artist>
                    <upnp:album>$album</upnp:album>
                    <upnp:albumArtURI>$artUri</upnp:albumArtURI>
                    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                    <res protocolInfo="http-get:*:audio/mp4:*">$mediaUrl</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hrs = totalSec / 3600
        return String.format("%02d:%02d:%02d", hrs, min, sec)
    }

    private fun parseTime(timeStr: String): Long {
        val parts = timeStr.trim().split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble().toLong()
                    (h * 3600 + m * 60 + s) * 1000
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble().toLong()
                    (m * 60 + s) * 1000
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun extractTag(xml: String, tagName: String): String? {
        val pattern = Pattern.compile("<$tagName(?:\\s[^>]*)?>([^<]*)</$tagName>", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(xml)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }
}
