package com.metrolist.music.cast

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * A local HTTP server that serves cached/downloaded media files and proxies
 * live YouTube / Radio streams to Google Cast and DLNA TVs.
 */
class LocalMediaServer(
    private val context: Context,
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
    port: Int = 8080,
    var streamUrlResolver: (suspend (mediaId: String) -> String?)? = null,
    var lyricsResolver: (suspend (mediaId: String) -> String?)? = null
) : NanoHTTPD(port) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(com.metrolist.music.utils.YouTubeStreamInterceptor)
        .build()

    val serverPort: Int = port

    /**
     * Resolves the primary local Wi-Fi IPv4 address of the Android device.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error finding local IP address")
        }
        return null
    }

    /**
     * Generates a streaming URL accessible by smart TVs on the local Wi-Fi network.
     */
    fun getStreamUrl(mediaId: String): String? {
        val ip = getLocalIpAddress() ?: return null
        return "http://$ip:$serverPort/stream?mediaId=$mediaId"
    }

    /**
     * Generates a WebVTT subtitles URL for lyrics.
     */
    fun getSubtitleUrl(mediaId: String): String? {
        val ip = getLocalIpAddress() ?: return null
        return "http://$ip:$serverPort/subtitles?mediaId=$mediaId"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Timber.d("LocalMediaServer request: $uri with headers ${session.headers}")

        val params = session.parameters
        val mediaId = params["mediaId"]?.firstOrNull()

        if (mediaId.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing mediaId parameter")
        }

        return when {
            uri.startsWith("/stream") -> handleStreamRequest(session, mediaId)
            uri.startsWith("/subtitles") -> handleSubtitlesRequest(mediaId)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleStreamRequest(session: IHTTPSession, mediaId: String): Response {
        // 1. Check if the file is cached locally
        val cachedFile = findCachedFile(mediaId)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            Timber.d("Serving cached file directly for mediaId: $mediaId")
            return serveCachedFile(session, cachedFile)
        }

        // 2. Not cached locally: resolve remote stream URL and proxy it to the TV
        Timber.d("No local cache for $mediaId, resolving remote stream to proxy")
        val remoteUrl = runBlocking {
            try {
                streamUrlResolver?.invoke(mediaId)
            } catch (e: Exception) {
                Timber.e(e, "Failed resolving stream URL for $mediaId")
                null
            }
        }

        if (remoteUrl.isNullOrEmpty()) {
            Timber.w("Could not resolve remote stream URL for $mediaId")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
        }

        return proxyRemoteStream(session, remoteUrl)
    }

    private fun handleSubtitlesRequest(mediaId: String): Response {
        val rawLyrics = runBlocking {
            try {
                lyricsResolver?.invoke(mediaId)
            } catch (e: Exception) {
                null
            }
        }

        val vttContent = if (!rawLyrics.isNullOrEmpty()) {
            convertToWebVtt(rawLyrics)
        } else {
            "WEBVTT\n\n"
        }

        val res = newFixedLengthResponse(Response.Status.OK, "text/vtt; charset=utf-8", vttContent)
        res.addHeader("Access-Control-Allow-Origin", "*")
        return res
    }

    private fun proxyRemoteStream(session: IHTTPSession, remoteUrl: String): Response {
        return try {
            val requestBuilder = Request.Builder().url(remoteUrl)
            
            // Forward Range header if present
            val rangeHeader = session.headers["range"]
            if (!rangeHeader.isNullOrEmpty()) {
                requestBuilder.addHeader("Range", rangeHeader)
            }

            // Pretend to be a compatible client
            requestBuilder.addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/119.0 Firefox/119.0")

            val okResponse = httpClient.newCall(requestBuilder.build()).execute()
            val body = okResponse.body ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty response body")

            val contentType = okResponse.header("Content-Type") ?: "audio/mp4"
            val contentLength = body.contentLength()
            val isPartial = okResponse.code == 206
            val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK

            val res = newChunkedResponse(status, contentType, body.byteStream())
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Access-Control-Allow-Origin", "*")

            okResponse.header("Content-Range")?.let { res.addHeader("Content-Range", it) }
            if (contentLength > 0) {
                res.addHeader("Content-Length", contentLength.toString())
            }
            res
        } catch (e: Exception) {
            Timber.e(e, "Error proxying stream to TV for $remoteUrl")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
        }
    }

    private fun findCachedFile(mediaId: String): File? {
        val cleanMediaId = mediaId.removeSuffix("_audio").removeSuffix("_video")
        
        // Check download cache
        for (id in listOf(mediaId, "${cleanMediaId}_audio", cleanMediaId)) {
            val downloadSpans = downloadCache.getCachedSpans(id)
            if (downloadSpans.isNotEmpty()) {
                val span = downloadSpans.first()
                if (span.file != null && span.file!!.exists()) {
                    return span.file
                }
            }
        }

        // Check player cache
        for (id in listOf(mediaId, "${cleanMediaId}_audio", cleanMediaId)) {
            val playerSpans = playerCache.getCachedSpans(id)
            if (playerSpans.isNotEmpty()) {
                val span = playerSpans.first()
                if (span.file != null && span.file!!.exists()) {
                    return span.file
                }
            }
        }

        return null
    }

    private fun serveCachedFile(session: IHTTPSession, file: File): Response {
        return try {
            val mime = getMimeTypeForFile(session.uri) ?: "audio/mp4"
            val res = serveFileInternal(session.headers, file, mime)
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Access-Control-Allow-Origin", "*")
            res
        } catch (e: Exception) {
            Timber.e(e, "Error serving file: ${file.absolutePath}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error serving file")
        }
    }

    private fun serveFileInternal(header: Map<String, String>, file: File, mime: String): Response {
        var res: Response
        try {
            val fileLen = file.length()
            val rangeHeader = header["range"]

            var startFrom: Long = 0
            var endAt: Long = -1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.substring("bytes=".length)
                val minus = range.indexOf('-')
                try {
                    if (minus > 0) {
                        startFrom = range.substring(0, minus).toLong()
                        val endString = range.substring(minus + 1)
                        if (endString.isNotEmpty()) {
                            endAt = endString.toLong()
                        }
                    } else if (minus == 0) {
                        startFrom = fileLen - range.substring(1).toLong()
                    } else {
                        startFrom = range.toLong()
                    }
                } catch (ignored: NumberFormatException) {
                }
            }

            if (rangeHeader != null && startFrom >= 0) {
                if (startFrom >= fileLen) {
                    res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    res.addHeader("Content-Range", "bytes 0-0/$fileLen")
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1
                    }
                    val newLen = endAt - startFrom + 1
                    if (newLen < 0) {
                        res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                        res.addHeader("Content-Range", "bytes 0-0/$fileLen")
                    } else {
                        val fis = FileInputStream(file)
                        fis.skip(startFrom)
                        res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, newLen)
                        res.addHeader("Content-Length", "$newLen")
                        res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
                    }
                }
            } else {
                res = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), fileLen)
                res.addHeader("Content-Length", "$fileLen")
            }
        } catch (e: FileNotFoundException) {
            res = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
        return res
    }

    private fun convertToWebVtt(lrc: String): String {
        val lines = lrc.lines()
        val sb = StringBuilder("WEBVTT\n\n")
        var cueIndex = 1

        val timePattern = java.util.regex.Pattern.compile("\\[(\\d{2}):(\\d{2}\\.\\d{2,3})\\](.*)")
        val cueList = mutableListOf<Pair<String, String>>()

        for (line in lines) {
            val matcher = timePattern.matcher(line.trim())
            if (matcher.matches()) {
                val min = matcher.group(1)?.toIntOrNull() ?: 0
                val sec = matcher.group(2)?.toDoubleOrNull() ?: 0.0
                val text = matcher.group(3)?.trim() ?: ""
                val totalSeconds = min * 60 + sec
                val formatted = String.format("%02d:%02d:%06.3f", (totalSeconds / 3600).toInt(), ((totalSeconds % 3600) / 60).toInt(), totalSeconds % 60)
                cueList.add(Pair(formatted, text))
            }
        }

        for (i in 0 until cueList.size) {
            val (startTime, text) = cueList[i]
            val endTime = if (i + 1 < cueList.size) cueList[i + 1].first else startTime
            sb.append("$cueIndex\n")
            sb.append("$startTime --> $endTime\n")
            sb.append("$text\n\n")
            cueIndex++
        }

        return sb.toString()
    }
}
