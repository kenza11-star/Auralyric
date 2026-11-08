// PlayerMenuExtras.kt
//
// The chevron-menu actions that need more than a flag already in the player: keeping the screen
// on, translating the lyrics, downloading the song and adding it to a playlist. BottomSheetPlayer
// hands these to [PlayerMenuExtras] so its own list of actions stays a list.

package com.vantra.music.ui.player.studio

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.vantra.music.LocalDatabase
import com.vantra.music.LocalDownloadUtil
import com.vantra.music.constants.KeepScreenOn
import com.vantra.music.db.MusicDatabase
import com.vantra.music.lyrics.LyricsTranslationHelper
import com.vantra.music.models.MediaMetadata
import com.vantra.music.playback.ExoDownloadService
import com.vantra.music.playback.PlayerConnection
import com.vantra.music.ui.menu.AddToPlaylistDialog
import com.vantra.music.utils.rememberPreference
import kotlinx.coroutines.flow.flowOf

/** What the extra chevron actions do, and whether each one is on right now. */
class PlayerMenuExtras internal constructor(
    private val context: Context,
    private val database: MusicDatabase,
    private val song: MediaMetadata?,
    private val keepScreenOn: Boolean,
    private val setKeepScreenOn: (Boolean) -> Unit,
    private val translating: Boolean,
    private val downloadState: Int?,
    private val openLyrics: () -> Unit,
    private val showAddToPlaylist: () -> Unit,
) {
    fun isActive(action: PlayerMenuAction): Boolean = when (action) {
        PlayerMenuAction.KEEP_AWAKE -> keepScreenOn
        PlayerMenuAction.TRANSLATE_LYRICS -> translating
        PlayerMenuAction.DOWNLOAD -> downloadState == Download.STATE_COMPLETED || downloadState == Download.STATE_DOWNLOADING ||
            downloadState == Download.STATE_QUEUED
        else -> false
    }

    /** Runs [action] if it's one of these; false if the player handles it itself. */
    fun run(action: PlayerMenuAction): Boolean {
        when (action) {
            PlayerMenuAction.KEEP_AWAKE -> {
                setKeepScreenOn(!keepScreenOn)
                toast(if (keepScreenOn) "The screen can sleep again" else "The screen stays on while Vantra is open")
            }
            PlayerMenuAction.TRANSLATE_LYRICS -> {
                if (translating) {
                    LyricsTranslationHelper.triggerClearTranslations()
                } else {
                    LyricsTranslationHelper.triggerManualTranslation()
                    // the translation shows on the lyrics, so that's where it goes
                    openLyrics()
                }
            }
            PlayerMenuAction.DOWNLOAD -> toggleDownload()
            PlayerMenuAction.ADD_TO_PLAYLIST -> if (song != null) showAddToPlaylist()
            else -> return false
        }
        return true
    }

    private fun toggleDownload() {
        val song = song ?: return
        if (isActive(PlayerMenuAction.DOWNLOAD)) {
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, song.id, false)
            toast(if (downloadState == Download.STATE_COMPLETED) "Removed the download" else "Download stopped")
            return
        }
        // the song has to be in the database for its download to be listed and played from there
        database.transaction { insert(song) }
        val request = DownloadRequest.Builder(song.id, song.id.toUri())
            .setCustomCacheKey(song.id)
            .setData(song.title.toByteArray())
            .build()
        DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
        toast("Downloading \"${song.title}\"")
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** Also shows the add-to-playlist sheet when its action asks for it. */
@Composable
fun rememberPlayerMenuExtras(playerConnection: PlayerConnection, openLyrics: () -> Unit): PlayerMenuExtras {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val song by playerConnection.mediaMetadata.collectAsState()
    val download by remember(downloadUtil, song?.id) {
        song?.id?.let(downloadUtil::getDownload) ?: flowOf(null)
    }.collectAsState(initial = null)
    val (keepScreenOn, setKeepScreenOn) = rememberPreference(KeepScreenOn, defaultValue = false)
    val translating by LyricsTranslationHelper.translationOn.collectAsState()
    var addingToPlaylist by remember { mutableStateOf(false) }

    song?.let { current ->
        AddToPlaylistDialog(
            isVisible = addingToPlaylist,
            onGetSong = {
                database.transaction { insert(current) }
                listOf(current.id)
            },
            onDismiss = { addingToPlaylist = false },
            topSongId = current.id,
            topSongArtworkUrl = current.thumbnailUrl,
        )
    }

    return PlayerMenuExtras(
        context = context,
        database = database,
        song = song,
        keepScreenOn = keepScreenOn,
        setKeepScreenOn = setKeepScreenOn,
        translating = translating,
        downloadState = download?.state,
        openLyrics = openLyrics,
        showAddToPlaylist = { addingToPlaylist = true },
    )
}
