// CastConnectionHandler.kt

package com.mochyy.mochymusic.playback

import android.content.Context
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable
class CastConnectionHandler(
    context: Context,
    scope: CoroutineScope,
    musicService: MusicService
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName

    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition

    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying

    private val _castIsBuffering = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = _castIsBuffering

    private val _castVolume = MutableStateFlow(1.0f)
    val castVolume: StateFlow<Float> = _castVolume

    var isSyncingFromCast: Boolean = false
        private set

    /** a cast receiver on the network; the foss build has no cast, so there are never any */
    data class CastTarget(val id: String, val name: String, val detail: String?)

    val targets: StateFlow<List<CastTarget>> = MutableStateFlow(emptyList())

    fun initialize(): Boolean = false
    fun startDiscovery() {}
    fun stopDiscovery() {}
    fun connectTo(id: String) {}
    fun disconnect() {}
    fun loadCurrentMedia() {}
    fun loadMedia(metadata: com.mochyy.mochymusic.models.MediaMetadata) {}
    fun play() {}
    fun pause() {}
    fun seekTo(position: Long) {}
    fun setVolume(volume: Float) {}
    fun skipToNext() {}
    fun skipToPrevious() {}
    fun navigateToMediaIfInQueue(mediaId: String): Boolean = false
    fun release() {}
}
