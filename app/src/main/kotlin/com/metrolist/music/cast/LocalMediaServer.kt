package com.metrolist.music.cast

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import fi.iki.elonen.NanoHTTPD
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * A local HTTP server that serves cached/downloaded audio files to Google Cast devices.
 * Google Cast devices (Chromecasts) cannot read local files from the Android file system,
 * so we must serve them over HTTP.
 */
class LocalMediaServer(
    private val context: Context,
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Timber.d("LocalMediaServer received request: $uri")

        // Example URL format: /stream?mediaId=xyz
        val params = session.parameters
        val mediaId = params["mediaId"]?.firstOrNull()

        if (mediaId.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing mediaId parameter")
        }

        // Try to find the file in the download cache or player cache
        val cachedFile = findCachedFile(mediaId)

        return if (cachedFile != null && cachedFile.exists()) {
            serveFile(session, cachedFile)
        } else {
            Timber.w("LocalMediaServer: File not found for mediaId $mediaId")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
    }

    private fun findCachedFile(mediaId: String): File? {
        // Look in download cache first
        val downloadSpans = downloadCache.getCachedSpans(mediaId)
        if (downloadSpans.isNotEmpty()) {
            val span = downloadSpans.first()
            if (span.file != null) {
                return span.file
            }
        }

        // Look in player cache next
        val playerSpans = playerCache.getCachedSpans(mediaId)
        if (playerSpans.isNotEmpty()) {
            val span = playerSpans.first()
            if (span.file != null) {
                return span.file
            }
        }

        return null
    }

    private fun serveFile(session: IHTTPSession, file: File): Response {
        return try {
            val mime = getMimeTypeForFile(session.uri) ?: "audio/mpeg"
            val res = serveFile(session.headers, file, mime)
            res.addHeader("Accept-Ranges", "bytes")
            res
        } catch (e: Exception) {
            Timber.e(e, "Error serving file: ${file.absolutePath}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error serving file")
        }
    }

    private fun serveFile(header: Map<String, String>, file: File, mime: String): Response {
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
                        // suffix-byte-range-spec
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
}
