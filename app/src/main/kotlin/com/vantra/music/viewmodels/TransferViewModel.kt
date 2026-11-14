// TransferViewModel.kt

package com.vantra.music.viewmodels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantra.music.importer.transfer.TransferCounts
import com.vantra.music.importer.transfer.TransferExporter
import com.vantra.music.importer.transfer.TransferImporter
import com.vantra.music.importer.transfer.TransferManifest
import com.vantra.music.importer.transfer.TransferRestore
import com.vantra.music.utils.StreakManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** where an export or import stands, progress is null while there's no number to show yet */
sealed interface TransferStep {
    data object Idle : TransferStep
    data class Working(val progress: Float?) : TransferStep
    data object Done : TransferStep
    data class Failed(val message: String) : TransferStep
}

/** exporting everything to one zip and bringing a zip back in, for onboarding and the storage page */
@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: TransferExporter,
    private val importer: TransferImporter,
) : ViewModel() {
    private val _export = MutableStateFlow<TransferStep>(TransferStep.Idle)
    val export: StateFlow<TransferStep> = _export.asStateFlow()

    private val _import = MutableStateFlow<TransferStep>(TransferStep.Idle)
    val import: StateFlow<TransferStep> = _import.asStateFlow()

    private val _manifest = MutableStateFlow<TransferManifest?>(null)

    /** what the zip being imported holds, for the progress page to show */
    val manifest: StateFlow<TransferManifest?> = _manifest.asStateFlow()

    /** the name the export suggests, so it's easy to spot in downloads later */
    fun exportFileName(): String = "vantra-export-${LocalDate.now()}.zip"

    fun exportTo(target: Uri) {
        if (_export.value is TransferStep.Working) return
        _export.value = TransferStep.Working(0f)
        viewModelScope.launch {
            exporter.export(target) { _export.value = TransferStep.Working(it) }
                .onSuccess { _export.value = TransferStep.Done }
                .onFailure {
                    Timber.e(it, "Export failed")
                    _export.value = TransferStep.Failed(it.message ?: "Couldn't export. Try again.")
                }
        }
    }

    /** checks a picked zip before anything is unpacked, the failure message is ready to show */
    suspend fun check(source: Uri): Result<TransferManifest> =
        importer.peek(source).onSuccess { _manifest.value = it }

    /** what this phone holds right now, read off the main thread, for the confirmation to compare with the zip */
    suspend fun currentCounts(): TransferCounts = withContext(Dispatchers.IO) { exporter.currentCounts() }

    /** true when the zip has local songs and vantra can't see the phone's music yet, so it's worth asking first */
    fun wantsAudioPermission(manifest: TransferManifest): Boolean =
        manifest.localSongs > 0 && ContextCompat.checkSelfPermission(context, AudioPermission) != PackageManager.PERMISSION_GRANTED

    /** unpacks the zip, then restarts the app so the next start swaps it in */
    fun importFrom(source: Uri) {
        if (_import.value is TransferStep.Working) return
        _import.value = TransferStep.Working(0f)
        viewModelScope.launch {
            importer.stage(source) { _import.value = TransferStep.Working(it) }
                .onSuccess {
                    _import.value = TransferStep.Done
                    // a beat on the finished page, so the restart doesn't look like a crash
                    delay(1_200)
                    TransferRestore.restartToFinish(context)
                }
                .onFailure {
                    Timber.e(it, "Import failed")
                    _import.value = TransferStep.Failed(it.message ?: "Couldn't import that file. Try again.")
                }
        }
    }

    /** back to the start after a failed import, so the page can be tried again */
    fun clearImport() {
        if (_import.value !is TransferStep.Working) _import.value = TransferStep.Idle
    }

    companion object {
        /**
         * The confirmation text: what the zip holds, what this phone holds, and what the replace does.
         * Nothing is replaced until the user picks the action on this dialog.
         */
        fun replaceSummary(zip: TransferManifest, now: TransferCounts): String {
            val date = DateTimeFormatter.ofPattern("d MMM yyyy").format(
                java.time.Instant.ofEpochMilli(zip.createdAt).atZone(ZoneId.systemDefault()),
            )
            val streak = if (zip.streak.unlocked) {
                "streak on, ${zip.streak.recoveredDays} recovered days, " +
                    "${zip.streak.recoveryUsed} of ${StreakManager.RECOVERIES_PER_WEEK} recovery chances used"
            } else {
                "streak not started yet"
            }
            return "The zip is from $date: ${zip.songs} songs, ${zip.playlists} playlists, ${zip.plays} listening events, $streak.\n\n" +
                "This phone now: ${now.songs} songs, ${now.playlists} playlists, ${now.plays} listening events.\n\n" +
                "Everything here is replaced by the zip. Your logins stay as they are. If the import fails, your data " +
                "goes back exactly as it was. Vantra restarts once to finish."
        }

        /** the permission that lets vantra find local songs again, it changed name in android 13 */
        val AudioPermission: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
