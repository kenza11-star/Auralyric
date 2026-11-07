package com.mochyy.mochymusic.utils

import com.music.innertube.models.response.PlayerResponse

// one youtube video's pictures, each track the same picture at another size, with what the requests have to carry
data class VideoStreams(
    val videoId: String,
    // smallest first
    val tracks: List<VideoTrack>,
    val headers: Map<String, String>,
    val expiresAtMs: Long,
    val durationMs: Long,
) {
    // a minute early, so a video that is just starting doesn't run into a dead link halfway
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs - 60_000L

    // every size can be fetched in small pieces, so the player can switch between them as the connection changes
    val chunked: Boolean get() = tracks.isNotEmpty() && tracks.all { it.chunked }

    // the largest track whose short side fits in shortSide, or the smallest when none does
    fun trackFor(shortSide: Int): VideoTrack? =
        tracks.lastOrNull { it.shortSide <= shortSide } ?: tracks.firstOrNull()
}

data class VideoTrack(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val initRange: LongRange?,
    val indexRange: LongRange?,
    val contentLength: Long?,
) {
    // what "720p" means for a landscape and a portrait video alike
    val shortSide: Int get() = minOf(width, height)

    val chunked: Boolean get() = initRange != null && indexRange != null
}

private val CodecsPattern = Regex("codecs=\"([^\"]+)\"")

internal fun PlayerResponse.StreamingData.Format.containerType(): String = mimeType.substringBefore(';').trim()

internal fun PlayerResponse.StreamingData.Format.codecs(): String =
    CodecsPattern.find(mimeType)?.groupValues?.get(1).orEmpty()

internal fun PlayerResponse.StreamingData.Format.ByteRange.toLongRange(): LongRange? {
    val first = start.toLongOrNull() ?: return null
    val last = end.toLongOrNull() ?: return null
    return if (last >= first) first..last else null
}

// h264 has a hardware decoder on every phone, so it wins; vp9 and av1 only stand in for a video that has no h264
private val CodecFamilies = listOf("avc1", "vp9", "vp09", "av01")

// the picture-only formats of one codec, one per size, smallest first; 30fps beats 60 at the same size, it's half the decoding behind lyrics
internal fun pickVideoFamily(formats: List<PlayerResponse.StreamingData.Format>): List<PlayerResponse.StreamingData.Format> {
    val pictures = formats.filter { !it.isAudio && (it.width ?: 0) > 0 && (it.height ?: 0) > 0 && it.bitrate > 0 }
    for (family in CodecFamilies) {
        val inFamily = pictures.filter { it.codecs().startsWith(family) }
        if (inFamily.isEmpty()) continue
        return inFamily
            .groupBy { minOf(it.width!!, it.height!!) }
            .map { (_, sameSize) -> sameSize.minWith(compareBy({ it.fps ?: 30 }, { it.bitrate })) }
            .sortedBy { minOf(it.width!!, it.height!!) }
    }
    return emptyList()
}
