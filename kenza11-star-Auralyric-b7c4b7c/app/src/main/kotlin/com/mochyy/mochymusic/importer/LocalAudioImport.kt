// LocalAudioImport.kt
//
// Turning one audio file into a song in the library: tags, artwork, artist, lyrics and format row.
// Used by the local import (files picked or found on the phone, see LocalImportService) and by
// audio files shared into Mochy from other apps.

package com.mochyy.mochymusic.importer

import android.content.Context
import android.net.Uri
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.FormatEntity
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.ui.screens.extractAudioMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.UUID

/** adds one audio file to the library as a local song and returns its id, uri has to stay readable so a shared file is copied into the app first (importSharedAudio) */
suspend fun importLocalAudio(
    context: Context,
    database: MusicDatabase,
    uri: Uri,
): String {
    val metadata = withContext(Dispatchers.IO) {
        extractAudioMetadata(context, uri)
    }

    // the file's own lyrics if its tags have any, saved with it so they work offline from the first play
    val embeddedLyrics = withContext(Dispatchers.IO) {
        com.mochyy.mochymusic.lyrics.readEmbeddedLyrics(context, uri)
    }

    val songId = "LOCAL_${UUID.randomUUID()}"
    val thumbnailUrl = withContext(Dispatchers.IO) {
        metadata.embeddedArtwork?.let { LocalArtwork.save(context, songId, it) }
    }

    withContext(Dispatchers.IO) {
        database.transaction {
            insert(
                SongEntity(
                    id = songId,
                    title = metadata.title,
                    duration = metadata.durationSeconds,
                    thumbnailUrl = thumbnailUrl,
                    albumName = metadata.album,
                    isLocal = true,
                    inLibrary = LocalDateTime.now(),
                    localUri = uri.toString(),
                ),
            )

            val artistName = metadata.artist
            val artistId = artistByName(artistName)?.id
                ?: ArtistEntity.generateArtistId()
            insert(
                ArtistEntity(
                    id = artistId,
                    name = artistName,
                    isLocal = true,
                ),
            )
            insert(
                SongArtistMap(
                    songId = songId,
                    artistId = artistId,
                    position = 0,
                ),
            )

            embeddedLyrics?.let { lyrics ->
                upsert(
                    com.mochyy.mochymusic.db.entities.LyricsEntity(
                        id = songId,
                        lyrics = lyrics,
                        provider = com.mochyy.mochymusic.lyrics.EmbeddedLyrics.Provider,
                    ),
                )
            }

            val mimeType = metadata.mimeType ?: "audio/mpeg"
            val codecs = when {
                mimeType.contains("flac") -> "flac"
                mimeType.contains("opus") || mimeType.contains("ogg") -> "opus"
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "mp4a.40.2"
                mimeType.contains("wav") -> "pcm"
                else -> "mp3"
            }
            upsert(
                FormatEntity(
                    id = songId,
                    itag = -1,
                    mimeType = mimeType,
                    codecs = codecs,
                    bitrate = metadata.bitrate ?: 0,
                    sampleRate = metadata.sampleRate,
                    contentLength = 0L,
                    loudnessDb = null,
                    perceptualLoudnessDb = null,
                    playbackUrl = null,
                ),
            )
        }
    }
    return songId
}

/** copies a file another app shared into the app's own storage, then imports it */
suspend fun importSharedAudio(
    context: Context,
    database: MusicDatabase,
    shared: Uri,
) {
    val name = withContext(Dispatchers.IO) {
        context.contentResolver.query(shared, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "audio"
    val copy = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.filesDir, "shared_audio/${UUID.randomUUID()}").apply { mkdirs() }
        java.io.File(dir, name).also { file ->
            context.contentResolver.openInputStream(shared)!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }
    try {
        importLocalAudio(context, database, Uri.fromFile(copy))
    } catch (e: Exception) {
        copy.parentFile?.deleteRecursively()
        throw e
    }
}
