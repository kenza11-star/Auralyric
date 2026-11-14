package probe

import java.io.File

var failures = 0
fun check(name: String, cond: Boolean) {
    println((if (cond) "PASS " else "FAIL ") + name)
    if (!cond) failures++
}

fun fresh(): Pair<File, File> {
    val root = File(System.getProperty("java.io.tmpdir"), "jt-" + System.nanoTime())
    val data = File(root, "data").apply { mkdirs() }
    val rollback = File(data, "transfer_rollback")
    return data to rollback
}

fun main() {
    // 1. happy path: replace an existing file and create a new one, then commit
    run {
        val (data, rb) = fresh()
        val db = File(data, "databases/music.db").apply { parentFile.mkdirs(); writeText("OLD-LIBRARY") }
        val j = Journal(data, rb)
        j.moveAside(db)
        db.writeText("NEW-LIBRARY")
        val created = File(data, "files/new.jpg").apply { parentFile.mkdirs(); writeText("new") }
        j.moveAside(created)
        created.writeText("replaced")
        check("1a replaced file has new content", db.readText() == "NEW-LIBRARY")
        // commit: caller writes marker then deletes rollback
        File(rb, "committed").createNewFile(); rb.deleteRecursively()
        check("1b committed import keeps new data", db.readText() == "NEW-LIBRARY" && created.readText() == "replaced")
        check("1c rollback folder gone after commit", !rb.exists())
    }

    // 2. failure midway: undo must restore the old library and remove what the import created
    run {
        val (data, rb) = fresh()
        val db = File(data, "databases/music.db").apply { parentFile.mkdirs(); writeText("OLD-LIBRARY") }
        val wal = File(data, "databases/music.db-wal").apply { writeText("OLD-WAL") }
        val media = File(data, "databases/exoplayer_internal.db").apply { writeText("OLD-MEDIA") }
        val pref = File(data, "shared_prefs/audio_lab.xml").apply { parentFile.mkdirs(); writeText("OLD-PREF") }
        val ds = File(data, "datastore/settings.preferences_pb").apply { parentFile.mkdirs(); writeText("OLD-STREAK") }
        val j = Journal(data, rb)
        j.moveAside(media)
        media.writeText("NEW-MEDIA")
        j.moveAside(File(data, "databases/music.db")); j.moveAside(wal)
        db.writeText("NEW-LIBRARY")
        j.keepCopy(pref); pref.writeText("NEW-PREF")
        j.keepCopy(ds); ds.writeText("NEW-STREAK")
        val createdFromNothing = File(data, "files/brand_new.bin")
        j.moveAside(createdFromNothing); createdFromNothing.parentFile.mkdirs(); createdFromNothing.writeText("x")
        // now the library write blows up: simulate the process failing and undo being run
        j.undo()
        check("2a library restored", db.readText() == "OLD-LIBRARY")
        check("2b wal restored", wal.readText() == "OLD-WAL")
        check("2c media restored", media.readText() == "OLD-MEDIA")
        check("2d shared pref restored", pref.readText() == "OLD-PREF")
        check("2e datastore restored", ds.readText() == "OLD-STREAK")
        check("2f imported-only file removed", !createdFromNothing.exists())
        check("2g rollback folder cleared", !rb.exists())
    }

    // 3. undo twice (a second attempt after a partial first attempt) must not lose data
    run {
        val (data, rb) = fresh()
        val db = File(data, "databases/music.db").apply { parentFile.mkdirs(); writeText("OLD") }
        val j = Journal(data, rb)
        j.moveAside(db); db.writeText("NEW")
        j.undo()
        j.undo()
        check("3a double undo keeps original", db.readText() == "OLD")
    }

    // 4. process killed after moves but before undo: a fresh Journal instance recovers it
    run {
        val (data, rb) = fresh()
        val files = File(data, "files/download").apply { mkdirs(); File(this, "song.mp3").writeText("AUDIO") }
        val j = Journal(data, rb)
        j.moveAside(files)
        File(files, "song.mp3").let { } // folder replaced, original moved aside
        File(files, "x").mkdirs()
        // "crash": a brand-new instance, as on the next app start
        Journal(data, rb).undo()
        check("4a interrupted folder restored with its content", File(files, "song.mp3").readText() == "AUDIO")
        check("4b imported junk gone", !File(files, "x").exists())
    }

    // 5. in-place edit without a prior copy is not lost when copy is made first
    run {
        val (data, rb) = fresh()
        val ds = File(data, "datastore/settings.preferences_pb").apply { parentFile.mkdirs(); writeText("ORIG") }
        val j = Journal(data, rb)
        j.keepCopy(ds)
        ds.writeText("CHANGED")
        j.undo()
        check("5a in-place edit rolled back", ds.readText() == "ORIG")
    }

    // 6. keepCopy when the file did not exist before: undo removes what the import wrote
    run {
        val (data, rb) = fresh()
        val ds = File(data, "datastore/settings.preferences_pb")
        val j = Journal(data, rb)
        j.keepCopy(ds)
        ds.parentFile.mkdirs(); ds.writeText("WRITTEN")
        j.undo()
        check("6a file created by the import is removed", !ds.exists())
    }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
