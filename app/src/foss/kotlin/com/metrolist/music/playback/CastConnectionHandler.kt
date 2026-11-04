package com.metrolist.music.playback

import android.content.Context
import com.metrolist.music.cast.dlna.DlnaController
import com.metrolist.music.cast.dlna.DlnaDevice
import com.metrolist.music.cast.dlna.SsdpDiscovery
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CastConnectionHandler for FOSS builds.
 * Implements DLNA / UPnP Smart TV casting (Samsung Tizen, LG webOS, Roku, Sony, Windows PC).
 */
class CastConnectionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicService: MusicService
) {
    val ssdpDiscovery = SsdpDiscovery(context, scope)
    val dlnaController = DlnaController(
        scope = scope,
        onPlaybackEnded = {
            val player = musicService.player
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            }
        }
    )

    val isCasting: StateFlow<Boolean> = dlnaController.isConnected
    val isConnecting: StateFlow<Boolean> = dlnaController.isBuffering

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()

    val castPosition: StateFlow<Long> = dlnaController.positionMs
    val castDuration: StateFlow<Long> = dlnaController.durationMs
    val castIsPlaying: StateFlow<Boolean> = dlnaController.isPlaying
    val castIsBuffering: StateFlow<Boolean> = dlnaController.isBuffering
    val castVolume: StateFlow<Float> = dlnaController.volume

    val dlnaDevices: StateFlow<List<DlnaDevice>> = ssdpDiscovery.devices

    var isSyncingFromCast: Boolean = false
        private set

    fun initialize(): Boolean {
        Timber.d("Initializing DLNA TV discovery")
        return true
    }

    fun startDlnaDiscovery() {
        ssdpDiscovery.startDiscovery()
    }

    fun stopDlnaDiscovery() {
        ssdpDiscovery.stopDiscovery()
    }

    fun connectToDlnaDevice(device: DlnaDevice) {
        Timber.d("Connecting to DLNA TV: ${device.name}")
        dlnaController.connect(device)
        _castDeviceName.value = device.name

        // Pause local player when routing to TV
        if (musicService.player.isPlaying) {
            musicService.player.pause()
        }

        // Load current song if playing
        loadCurrentMedia()
    }

    fun disconnect() {
        Timber.d("Disconnecting from DLNA TV")
        val resumePos = dlnaController.positionMs.value
        dlnaController.disconnect()
        _castDeviceName.value = null

        // Resume local playback on phone at same position
        if (resumePos > 0) {
            musicService.player.seekTo(resumePos)
        }
        musicService.player.play()
    }

    fun loadCurrentMedia() {
        val currentMedia = musicService.currentMediaMetadata.value
            ?: musicService.player.currentMediaItem?.metadata
            ?: return
        loadMedia(currentMedia)
    }

    fun loadMedia(metadata: MediaMetadata) {
        if (!isCasting.value) return
        scope.launch(Dispatchers.IO) {
            try {
                val streamUrl = musicService.localMediaServer?.getStreamUrl(metadata.id)
                    ?: musicService.getStreamUrl(metadata.id)

                if (streamUrl != null) {
                    val initialPos = musicService.player.currentPosition
                    dlnaController.playMedia(
                        mediaUrl = streamUrl,
                        metadata = metadata,
                        initialPositionMs = initialPos
                    )
                } else {
                    Timber.w("Could not get stream URL to cast media ${metadata.id}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading media to DLNA TV")
            }
        }
    }

    fun play() {
        dlnaController.play()
    }

    fun pause() {
        dlnaController.pause()
    }

    fun seekTo(position: Long) {
        dlnaController.seekTo(position)
    }

    fun setVolume(volume: Float) {
        dlnaController.setVolume(volume)
    }

    fun skipToNext() {
        val player = musicService.player
        if (player.hasNextMediaItem()) {
            player.pause()
            player.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        val player = musicService.player
        if (player.hasPreviousMediaItem()) {
            player.pause()
            player.seekToPreviousMediaItem()
        }
    }

    fun navigateToMediaIfInQueue(mediaId: String): Boolean {
        return false
    }

    fun release() {
        stopDlnaDiscovery()
        dlnaController.disconnect()
    }
}
