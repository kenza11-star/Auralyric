// QQMusicLyricsProvider.kt

package com.vantra.music.lyrics

import android.content.Context
import com.music.directlyrics.QQMusic
import com.vantra.music.constants.EnableQQMusicKey
import com.vantra.music.utils.dataStore
import com.vantra.music.utils.get

object QQMusicLyricsProvider : LyricsProvider {
    override val name = "QQMusic"
    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableQQMusicKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = QQMusic.getLyrics(title, artist, duration)
}
