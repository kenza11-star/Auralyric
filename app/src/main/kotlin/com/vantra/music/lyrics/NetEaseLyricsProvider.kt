// NetEaseLyricsProvider.kt

package com.vantra.music.lyrics

import android.content.Context
import com.music.directlyrics.NetEase
import com.vantra.music.constants.EnableNetEaseKey
import com.vantra.music.utils.dataStore
import com.vantra.music.utils.get

object NetEaseLyricsProvider : LyricsProvider {
    override val name = "NetEase"
    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableNetEaseKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = NetEase.getLyrics(title, artist, duration)
}
