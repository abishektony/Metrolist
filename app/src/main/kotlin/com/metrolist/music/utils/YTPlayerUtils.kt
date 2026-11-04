/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_NO_SDK
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val VIDEO_CLIENTS: Array<YouTubeClient> = arrayOf(
        IOS,
        MOBILE,
        ANDROID_NO_SDK,
        ANDROID_VR_1_43_32,
        TVHTML5,
        WEB
    )

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        IOS,
        MOBILE,
        ANDROID_NO_SDK,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        TVHTML5,
        WEB,
        ANDROID_VR_1_43_32,
        ANDROID_VR_NO_AUTH,
        ANDROID_CREATOR,
        IPADOS,
        WEB_CREATOR
    )

    private var cachedSignatureTimestamp: SignatureTimestampResult? = null
    private var cachedSignatureTimestampTime: Long = 0L
    private const val SIGNATURE_TIMESTAMP_CACHE_DURATION = 12 * 60 * 60 * 1000L // 12 hours
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferVideo: Boolean = false,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        // Debug: Log ALL playback attempts
        println("[PLAYBACK_DEBUG] playerResponseForPlayback called: videoId=$videoId, playlistId=$playlistId")
        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        // Get signature timestamp (same as before for normal content)
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")

        // Generate PoToken (only for main client audio if needed)
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (!preferVideo && MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        // Try WEB_REMIX with signature timestamp and poToken (same as before)
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        val mainResult = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken)
        var mainPlayerResponse = mainResult.getOrNull()
        if (mainPlayerResponse == null) {
            val err = mainResult.exceptionOrNull()
            Timber.tag(logTag).e(err, "YouTube.player FAILED for MAIN_CLIENT (${MAIN_CLIENT.clientName}): ${err?.javaClass?.simpleName}: ${err?.message}")
        }

        // Debug uploaded track response
        if (isUploadedTrack || playlistId?.contains("MLPT") == true) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse?.playabilityStatus?.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse?.playabilityStatus?.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainPlayerResponse?.videoDetails?.title}, videoId=${mainPlayerResponse?.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainPlayerResponse?.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainPlayerResponse?.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainPlayerResponse?.playabilityStatus?.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse) {
            if (isLoggedIn) {
                // Age-restricted: use WEB_CREATOR directly if logged in
                Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
                Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
                val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
                if (creatorResponse?.playabilityStatus?.status == "OK") {
                    Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                    mainPlayerResponse = creatorResponse
                    usedAgeRestrictedClient = WEB_CREATOR
                }
            }
            if (usedAgeRestrictedClient == null) {
                // Try TVHTML5_SIMPLY_EMBEDDED_PLAYER to bypass age-restriction without login
                Timber.tag(logTag).d("Age-restricted detected, trying TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                val tvEmbeddedResponse = YouTube.player(videoId, playlistId, TVHTML5_SIMPLY_EMBEDDED_PLAYER, signatureTimestamp.timestamp, null).getOrNull()
                if (tvEmbeddedResponse?.playabilityStatus?.status == "OK") {
                    Timber.tag(logTag).d("TVHTML5_SIMPLY_EMBEDDED_PLAYER bypassed age-restriction")
                    mainPlayerResponse = tvEmbeddedResponse
                    usedAgeRestrictedClient = TVHTML5_SIMPLY_EMBEDDED_PLAYER
                }
            }
        }

        val audioConfig = mainPlayerResponse?.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse?.videoDetails
        val playbackTracking = mainPlayerResponse?.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse?.playabilityStatus?.status
        var isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Timber.tag(TAG)
                .i("Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        val activeFallbackClients = if (preferVideo) VIDEO_CLIENTS else STREAM_FALLBACK_CLIENTS

        // For private tracks: use TVHTML5 (index 1) with PoToken + n-transform
        // For age-restricted: skip main client, start with fallbacks
        // For video: skip WEB_REMIX main client (no video), start directly with VIDEO_CLIENTS
        // For main client failure: start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            preferVideo -> 0
            isPrivateTrack -> 1
            isAgeRestricted -> 0
            mainPlayerResponse == null -> 0
            else -> -1
        }
        
        Timber.tag(TAG).d("Starting playback resolution: videoId=$videoId, preferVideo=$preferVideo, startIndex=$startIndex")

        for (clientIndex in (startIndex until activeFallbackClients.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = activeFallbackClients[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${activeFallbackClients.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Use signature timestamp for clients that require it (e.g. TVHTML5_SIMPLY_EMBEDDED_PLAYER)
                val clientSigTimestamp = if (wasOriginallyAgeRestricted && !client.useSignatureTimestamp) null else signatureTimestamp.timestamp
                val fallbackResult =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken)
                streamPlayerResponse = fallbackResult.getOrNull()
                if (streamPlayerResponse == null) {
                    val err = fallbackResult.exceptionOrNull()
                    Timber.tag(logTag).e(err, "YouTube.player FAILED for fallback client (${client.clientName}): ${err?.javaClass?.simpleName}: ${err?.message}")
                }
            }

            // If we prefer video and this client is WEB_REMIX (main), check if it has any muxed formats.
            val hasMuxed = streamPlayerResponse?.streamingData?.formats?.any { it.width != null } == true
            if (preferVideo && clientIndex == -1 && !hasMuxed) {
                Timber.tag(logTag).d("MAIN_CLIENT has no muxed formats, skipping to fallback clients for video")
                continue
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else activeFallbackClients[clientIndex].clientName}")

                // Skip NewPipe for age-restricted content (NewPipe doesn't use our auth)
                val responseToUse = if (wasOriginallyAgeRestricted) {
                    Timber.tag(logTag).d("Skipping NewPipe for age-restricted content")
                    streamPlayerResponse
                } else {
                    // Try to get streams using newPipePlayer method
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                val candidateFormats =
                    findCandidateFormats(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                        preferVideo,
                    )

                if (candidateFormats.isEmpty()) {
                    Timber.tag(logTag).d("No suitable formats found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else activeFallbackClients[clientIndex].clientName}")
                    continue
                }

                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    activeFallbackClients[clientIndex]
                }

                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val isLastClient = clientIndex == activeFallbackClients.size - 1

                for (candidate in candidateFormats) {
                    if (preferVideo && candidate.width == null && !isLastClient) {
                        continue
                    }

                    val nonNullUrl = findUrlOrNull(candidate, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                    if (nonNullUrl == null) {
                        Timber.tag(logTag).d("Stream URL not found for format itag ${candidate.itag}")
                        continue
                    }

                    var resolvedUrl: String = nonNullUrl

                    // Apply n-transform and PoToken for web clients OR for private tracks (including TVHTML5)
                    val needsNTransform = currentClient.useWebPoTokens ||
                        currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                        isPrivatelyOwnedTrack

                    if (needsNTransform) {
                        try {
                            Timber.tag(logTag).d("Applying n-transform to stream URL for ${currentClient.clientName}")
                            kotlinx.coroutines.withTimeoutOrNull(2000L) {
                                EjsNTransformSolver.transformNParamInUrl(resolvedUrl)
                            }?.let { resolvedUrl = it }

                            // Append pot= parameter with streaming data poToken
                            if ((currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null) {
                                Timber.tag(logTag).d("Appending pot= parameter to stream URL")
                                val separator = if ("?" in resolvedUrl) "&" else "?"
                                resolvedUrl = "${resolvedUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                            }
                        } catch (e: Exception) {
                            Timber.tag(logTag).e(e, "N-transform or pot append failed: ${e.message}")
                            // Continue with original URL
                        }
                    }

                    if (isLastClient || isPrivatelyOwnedTrack) {
                        format = candidate
                        streamUrl = resolvedUrl
                        Timber.tag(TAG)
                            .i("Playback: client=${currentClient.clientName}, itag=${format.itag}, videoId=$videoId, private=$isPrivatelyOwnedTrack (bypass validation)")
                        break
                    }

                    if (validateStatus(resolvedUrl, currentClient)) {
                        format = candidate
                        streamUrl = resolvedUrl
                        Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}, itag=${format.itag}")
                        Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, itag=${format.itag}, videoId=$videoId")
                        break
                    } else {
                        Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}, itag=${candidate.itag}")
                    }
                }

                if (format != null && streamUrl != null) {
                    streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                        ?: streamUrl?.let { url ->
                            runCatching {
                                val expireParam = Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()
                                if (expireParam != null) {
                                    val nowSec = System.currentTimeMillis() / 1000L
                                    (expireParam - nowSec).coerceAtLeast(3600L).toInt()
                                } else null
                            }.getOrNull()
                        }
                        ?: 21600 // Default 6 hours

                    Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")
                    break
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: All clients failed for uploaded track videoId=$videoId")
            }
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: Playability not OK for uploaded track - status=${streamPlayerResponse.playabilityStatus.status}, reason=$errorReason")
            }
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        val resolvedExpires = streamExpiresInSeconds ?: 21600

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl?.take(100)}...")
        }
        PlaybackData(
            audioConfig ?: streamPlayerResponse.playerConfig?.audioConfig,
            videoDetails ?: streamPlayerResponse.videoDetails,
            playbackTracking ?: streamPlayerResponse.playbackTracking,
            format,
            streamUrl,
            resolvedExpires,
        )
    }.onFailure { e ->
        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findCandidateFormats(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferVideo: Boolean = false,
    ): List<PlayerResponse.StreamingData.Format> {
        val result = mutableListOf<PlayerResponse.StreamingData.Format>()

        if (preferVideo) {
            // First priority: Muxed formats with video and audio (usually itag 18 or 22)
            val muxedFormats = (playerResponse.streamingData?.formats ?: emptyList())
                .filter { it.width != null && (it.audioSampleRate != null || it.audioQuality != null || it.itag == 18 || it.itag == 22) }
                .sortedByDescending { it.width ?: 0 }
            result.addAll(muxedFormats)

            val adaptiveMuxed = (playerResponse.streamingData?.adaptiveFormats ?: emptyList())
                .filter { it.width != null && (it.audioSampleRate != null || it.audioQuality != null || it.itag == 18 || it.itag == 22) }
                .sortedByDescending { it.width ?: 0 }
            result.addAll(adaptiveMuxed)

            // Fallback to pure audio formats if no muxed format found
            if (result.isEmpty()) {
                val adaptiveAudio = (playerResponse.streamingData?.adaptiveFormats ?: emptyList())
                    .filter { it.isAudio && it.isOriginal }
                    .sortedByDescending {
                        it.bitrate * when (audioQuality) {
                            AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                            AudioQuality.HIGH -> 1
                            AudioQuality.LOW -> -1
                        } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                    }
                result.addAll(adaptiveAudio)
            }
        } else {
            // Pure audio preferred:
            // 1. Audio formats from adaptiveFormats sorted by quality
            val adaptiveAudio = (playerResponse.streamingData?.adaptiveFormats ?: emptyList())
                .filter { it.isAudio && it.isOriginal }
                .sortedByDescending {
                    it.bitrate * when (audioQuality) {
                        AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                        AudioQuality.HIGH -> 1
                        AudioQuality.LOW -> -1
                    } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                }
            result.addAll(adaptiveAudio)

            // 2. Fallback: Muxed formats from streamingData.formats (e.g. itag 18 or 22) which contain playable audio
            val muxedFallback = (playerResponse.streamingData?.formats ?: emptyList())
                .filter { it.audioSampleRate != null || it.audioQuality != null || it.itag == 18 || it.itag == 22 }
                .sortedByDescending { it.bitrate }
            result.addAll(muxedFallback)
        }

        return result
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferVideo: Boolean = false,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}, preferVideo: $preferVideo")
        val formats = findCandidateFormats(playerResponse, audioQuality, connectivityManager, preferVideo)
        val format = formats.firstOrNull()

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}, width: ${format.width}, height: ${format.height}")
            if (preferVideo && format.width == null) {
                Timber.tag(logTag).w("Prefer video was true but selected format has no video!")
            }
        } else {
            Timber.tag(logTag).d("No suitable format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * Uses a non-zero range (bytes=1024-2048) to ensure Google Video CDN allows range requests.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, client: YouTubeClient? = null): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val userAgent = client?.userAgent ?: YouTubeClient.USER_AGENT_WEB
            val requestBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=1024-2048")

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val code = response.code
            val isSuccessful = response.isSuccessful || code == 200 || code == 206
            response.close()
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} ($code)")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        val cached = cachedSignatureTimestamp
        if (cached != null && System.currentTimeMillis() - cachedSignatureTimestampTime < SIGNATURE_TIMESTAMP_CACHE_DURATION) {
            return cached
        }
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        val res = result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
        if (res.timestamp != null) {
            cachedSignatureTimestamp = res
            cachedSignatureTimestampTime = System.currentTimeMillis()
        }
        return res
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Skip NewPipe for age-restricted content
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1500L) {
                YouTube.getNewPipeStreamUrls(videoId)
            }
        }.getOrNull() ?: emptyList()
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
