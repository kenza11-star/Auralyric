// TransferRestore.kt

package com.vantra.music.importer.transfer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.vantra.music.MainActivity
import com.vantra.music.db.InternalDatabase
import com.vantra.music.markOnboardedOnThisInstall
import com.vantra.music.playback.MusicService
import com.vantra.music.utils.dataStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * finishes an import on the first start after it was unpacked, before room, media3 or the settings open anything.
 *
 * Every path the import replaces is first moved into a rollback folder, and each of those moves is written to a
 * journal. If the import fails, or the app is killed halfway, the journal puts the originals back. Nothing is
 * deleted until the whole import has succeeded and been marked committed.
 */
object TransferRestore {
    private const val Tag = "TransferRestore"
    private const val StagingName = "transfer_staging"
    private const val RollbackName = "transfer_rollback"
    private const val NoteName = "transfer_restored.json"
    private const val CommittedMarker = "committed"
    private const val SettingsStore = "datastore/settings.preferences_pb"
    internal const val ReadyMarker = "ready.json"

    /** where an unpacked import waits for the next start, next to the data so moving it in is a rename */
    fun stagingDir(context: Context): File = File(context.dataDir, StagingName)

    /** the originals of an import in progress, kept until it is committed */
    private fun rollbackDir(context: Context): File = File(context.dataDir, RollbackName)

    /** called first thing in App.onCreate, swaps the unpacked import in if one is waiting */
    fun applyPending(app: Application) {
        // the crash screen runs in its own process and must never touch an import
        if (!isMainProcess(app)) return

        val rollback = rollbackDir(app)
        if (rollback.exists()) recoverInterrupted(app, rollback)

        val staging = stagingDir(app)
        if (!staging.exists()) return
        val ready = File(staging, ReadyMarker)
        if (!ready.exists()) {
            // the import was closed halfway through unpacking, so there's nothing whole to use
            staging.deleteRecursively()
            return
        }

        val journal = Journal(app.dataDir, rollback)
        val swapped = runCatching { swapIn(app, staging, JSONObject(ready.readText()), journal) }
        if (swapped.isSuccess) {
            // committed first, so a crash while cleaning up can't roll back a finished import
            rollback.mkdirs()
            File(rollback, CommittedMarker).createNewFile()
            staging.deleteRecursively()
            rollback.deleteRecursively()
            return
        }

        val error = swapped.exceptionOrNull()
        Log.e(Tag, "Couldn't finish the import, putting the old data back", error)
        val undone = runCatching { journal.undo() }
        staging.deleteRecursively()
        File(app.noBackupFilesDir, NoteName).writeText(
            JSONObject().put(
                "error",
                if (undone.isSuccess) {
                    "The import couldn't finish, so your data is exactly as it was. Import the zip again to retry."
                } else {
                    "The import couldn't finish and Vantra couldn't put everything back yet. It will try again on the next start."
                },
            ).toString(),
        )
        // settings and preferences are already loaded in memory, so the restart is what makes the rollback visible
        restartToFinish(app)
    }

    /** stops playback and starts the app again, so the next start can swap the import in */
    fun restartToFinish(context: Context) {
        context.stopService(Intent(context, MusicService::class.java))
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        exitProcess(0)
    }

    /** the welcome line for the first screen after an import, handed out once */
    fun takeRestoredNote(context: Context): String? {
        val file = File(context.noBackupFilesDir, NoteName)
        if (!file.exists()) return null
        val note = runCatching { JSONObject(file.readText()) }.getOrNull()
        file.delete()
        note ?: return null
        note.optString("error").takeIf { it.isNotEmpty() }?.let { return it }
        val missing = note.optInt("missingLocal")
        return if (missing > 0) {
            "Welcome back! Your library is here, but $missing local ${if (missing == 1) "song wasn't" else "songs weren't"} found on this phone."
        } else {
            "Welcome back! Your whole library is here."
        }
    }

    /** an import that was running when the app stopped: put back whatever it had replaced */
    private fun recoverInterrupted(context: Context, rollback: File) {
        if (File(rollback, CommittedMarker).exists()) {
            // the import had finished, only the cleanup was cut short
            rollback.deleteRecursively()
            return
        }
        Log.w(Tag, "An import was interrupted, putting the old data back")
        val undone = runCatching { Journal(context.dataDir, rollback).undo() }
        stagingDir(context).deleteRecursively()
        if (undone.isSuccess) {
            File(context.noBackupFilesDir, NoteName).writeText(
                JSONObject().put("error", "Vantra stopped during an import, so your data was put back as it was.").toString(),
            )
        }
    }

    // journaled, so every step can be undone. Files first and the library last, so a stop halfway can always be rolled back.
    private fun swapIn(context: Context, staging: File, ready: JSONObject, journal: Journal) {
        moveFiles(context, File(staging, TransferArchive.FilesPrefix), journal)
        File(staging, TransferArchive.MediaEntry).takeIf { it.exists() }?.let { media ->
            // the streaming cache's index lived in the old media database, so the cache starts over with the new one
            journal.moveAside(File(context.filesDir, "exoplayer"))
            replaceDatabase(context.getDatabasePath(TransferArchive.MediaDatabaseName), media, journal)
        }
        restorePrefs(context, File(staging, TransferArchive.PrefsPrefix), journal)
        File(staging, TransferArchive.SettingsEntry).takeIf { it.exists() }?.let { settings ->
            val json = JSONObject(settings.readText())
            journal.keepCopy(File(context.filesDir, SettingsStore))
            runBlocking { context.dataStore.edit { TransferArchive.jsonIntoPreferences(json, it) } }
        }
        replaceDatabase(context.getDatabasePath(InternalDatabase.DB_NAME), File(staging, TransferArchive.LibraryEntry), journal)
        markOnboardedOnThisInstall(context)
        File(context.noBackupFilesDir, NoteName).writeText(ready.toString())
    }

    private fun moveFiles(context: Context, from: File, journal: Journal) {
        for (item in from.listFiles().orEmpty()) {
            val target = File(context.filesDir, item.name)
            journal.moveAside(target)
            if (!item.renameTo(target)) {
                item.copyRecursively(target, overwrite = true)
            }
        }
    }

    private fun replaceDatabase(target: File, staged: File, journal: Journal) {
        if (!staged.exists()) return
        target.parentFile?.mkdirs()
        // a leftover wal from the empty database would be replayed onto the imported one and break it, so those move aside too
        listOf("", "-wal", "-shm", "-journal").forEach { journal.moveAside(File(target.path + it)) }
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
        }
    }

    // merged into what's there, so anything set during onboarding that the old app never had stays
    private fun restorePrefs(context: Context, folder: File, journal: Journal) {
        for (file in folder.listFiles().orEmpty()) {
            val fileName = TransferArchive.PrefsToCarry[file.name.removeSuffix(".json")] ?: continue
            journal.keepCopy(File(context.dataDir, "shared_prefs/$fileName.xml"))
            val editor = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit()
            TransferArchive.jsonIntoSharedPreferences(JSONObject(file.readText()), editor)
            // a failed commit must fail the import, otherwise the rollback below never runs
            check(editor.commit()) { "Couldn't save $fileName" }
        }
    }

    private fun isMainProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }.getOrNull()
        }
        return name.isNullOrEmpty() || name == context.packageName
    }
}

/**
 * Records every path an import touches, with whether it existed before, and keeps its original.
 * Originals are moved aside with rename (atomic, same filesystem) and never deleted until commit.
 * Files edited in place are copied through a temporary name, so a half-written copy never counts as a backup.
 */
internal class Journal(private val dataDir: File, val dir: File) {
    private val list = File(dir, "touched.txt")

    /** the path is about to be replaced: its current copy is renamed into the rollback folder */
    fun moveAside(target: File) {
        val rel = relative(target)
        record(rel, existed = target.exists())
        if (!target.exists()) return
        val saved = File(dir, rel).also { it.parentFile?.mkdirs() }
        // a failed rename throws before the original is touched, so the original is never half gone
        check(target.renameTo(saved)) { "Couldn't set aside ${target.name}" }
    }

    /** the path is about to be edited in place: its current copy is kept, written atomically */
    fun keepCopy(target: File) {
        val rel = relative(target)
        record(rel, existed = target.exists())
        if (!target.exists()) return
        val saved = File(dir, rel).also { it.parentFile?.mkdirs() }
        val partial = File(saved.path + ".part")
        target.copyTo(partial, overwrite = true)
        check(partial.renameTo(saved)) { "Couldn't keep a copy of ${target.name}" }
    }

    /** puts every touched path back: a new file is removed, an original is moved back. Safe to run twice. */
    fun undo() {
        val entries = list.takeIf { it.exists() }?.readLines().orEmpty().filter { it.contains('\t') }
        for (line in entries.asReversed()) {
            val flag = line.substringBefore('\t')
            val rel = line.substringAfter('\t')
            val target = File(dataDir, rel)
            val saved = File(dir, rel)
            when {
                saved.exists() -> {
                    target.deleteRecursively()
                    target.parentFile?.mkdirs()
                    if (!saved.renameTo(target)) saved.copyRecursively(target, overwrite = true)
                }
                // the import created it, so it goes
                flag == NEW -> target.deleteRecursively()
                // it existed and no copy was made, so it was never touched and stays as it is
            }
        }
        dir.deleteRecursively()
    }

    private fun record(rel: String, existed: Boolean) {
        dir.mkdirs()
        list.appendText((if (existed) EXISTED else NEW) + "\t" + rel + "\n")
    }

    private fun relative(target: File): String = target.relativeTo(dataDir).path

    private companion object {
        const val EXISTED = "E"
        const val NEW = "N"
    }
}
