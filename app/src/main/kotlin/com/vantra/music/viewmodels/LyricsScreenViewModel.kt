// LyricsScreenViewModel.kt

package com.vantra.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantra.music.db.MusicDatabase
import com.vantra.music.db.entities.LyricsEntity
import com.vantra.music.lyrics.LyricsHelper
import com.vantra.music.models.MediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsScreenViewModel
@Inject
constructor(
    private val lyricsHelper: LyricsHelper,
    // Exposed so the lyrics screen can hand it to LyricsTranslationHelper, which persists a
    // finished translation against the song row.
    val database: MusicDatabase,
) : ViewModel() {
    private val requestedIds = mutableSetOf<String>()

    fun ensureLyricsLoaded(mediaMetadata: MediaMetadata) {
        val id = mediaMetadata.id
        if (!requestedIds.add(id)) return

        viewModelScope.launch(Dispatchers.IO) {
            val existing = database.lyrics(id).first()
            // YouLyPlus and Paxsenix used to join syllables with spaces ("wa nt yo u"). Their
            // converters are fixed, but lyrics cached before that keep the split text forever, so
            // a cached copy from either that still reads as syllable-split is fetched again once.
            val staleSyllableSplit = existing != null &&
                existing.provider in SyllableFixedProviders &&
                com.vantra.music.lyrics.LyricsUtils.isSyllableSplit(existing.lyrics)
            if (existing != null && !staleSyllableSplit) return@launch

            val lyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
            // nothing was asked: not a "not found" to keep
            if (lyricsWithProvider.provider == com.vantra.music.lyrics.LyricsHelper.NoConnectionProvider) return@launch
            database.query {
                upsert(
                    LyricsEntity(
                        id = id,
                        lyrics = lyricsWithProvider.lyrics,
                        provider = lyricsWithProvider.provider,
                    ),
                )
            }
        }
    }
}

private val SyllableFixedProviders = setOf("YouLyPlus", "Paxsenix")
