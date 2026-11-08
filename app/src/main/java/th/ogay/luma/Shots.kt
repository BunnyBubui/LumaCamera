package th.ogay.luma

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.work.Configuration
import java.util.concurrent.Executors

enum class Stage { CAPTURING, ORIGINAL, QUEUED, RUNNING, DONE, FAILED }

data class Shot(
    val id: String, val time: Long, val uri: Uri, val size: Dimensions,
    val zoom: Float, val rear: Boolean, val lens: String?, val cropped: Long?,
    val reason: Decision, val stage: Stage, val ai: Uri?, val elapsed: Long, val error: String?,
    val softwareCrop: Boolean,
    val raw: Uri? = null,
    val output: CaptureOutput = CaptureOutput.JPEG,
    val frames: Int = 1,
    val requestedFrames: Int = 1,
    val fusedFrames: Int = 0,
    val captureNote: String? = null
) {
    val baseName get() = "LUMA_${time}_${id}"
    val aiVersion get() = if (output.enhance) "MF1_$MODEL_VERSION" else MODEL_VERSION
    val aiName get() = "${baseName}_${aiVersion}_AI.jpg"
}

class Shots(context: Context) : SQLiteOpenHelper(context, "frames.db", null, 2) {
    val changes = MutableLiveData<Long>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shots(id TEXT PRIMARY KEY, time INTEGER NOT NULL, uri TEXT NOT NULL,
            w INTEGER NOT NULL, h INTEGER NOT NULL, zoom REAL NOT NULL, rear INTEGER NOT NULL,
            lens TEXT, cropped INTEGER, reason TEXT NOT NULL, stage TEXT NOT NULL, ai TEXT,
            elapsed INTEGER NOT NULL DEFAULT 0, error TEXT, software INTEGER NOT NULL,
            raw TEXT, output TEXT NOT NULL DEFAULT 'JPEG', frames INTEGER NOT NULL DEFAULT 1,
            requested_frames INTEGER NOT NULL DEFAULT 1, fused_frames INTEGER NOT NULL DEFAULT 0, capture_note TEXT)""")
        db.execSQL("CREATE INDEX shots_time ON shots(time DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE shots ADD COLUMN raw TEXT")
            db.execSQL("ALTER TABLE shots ADD COLUMN output TEXT NOT NULL DEFAULT 'JPEG'")
            db.execSQL("ALTER TABLE shots ADD COLUMN frames INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE shots ADD COLUMN requested_frames INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE shots ADD COLUMN fused_frames INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE shots ADD COLUMN capture_note TEXT")
        }
    }
    private fun Cursor.shot(): Shot {
        fun str(key: String): String? = getString(getColumnIndexOrThrow(key))
        fun num(key: String) = getLong(getColumnIndexOrThrow(key))
        return Shot(str("id")!!, num("time"), Uri.parse(str("uri")), Dimensions(num("w").toInt(), num("h").toInt()),
            getFloat(getColumnIndexOrThrow("zoom")), num("rear") == 1L, str("lens"),
            str("cropped")?.toLong(), Decision.valueOf(str("reason")!!), Stage.valueOf(str("stage")!!),
            str("ai")?.let { Uri.parse(it) }, num("elapsed"), str("error"), num("software") == 1L,
            str("raw")?.let(Uri::parse), CaptureOutput.valueOf(str("output")!!),
            num("frames").toInt(), num("requested_frames").toInt(), num("fused_frames").toInt(), str("capture_note"))
    }
    fun insert(s: Shot) {
        writableDatabase.insertOrThrow("shots", null, ContentValues().apply {
            put("id", s.id); put("time", s.time); put("uri", s.uri.toString())
            put("w", s.size.w); put("h", s.size.h); put("zoom", s.zoom); put("rear", s.rear)
            put("lens", s.lens); put("cropped", s.cropped); put("reason", s.reason.name)
            put("stage", s.stage.name); put("software", s.softwareCrop)
            put("raw", s.raw?.toString()); put("output", s.output.name); put("frames", s.frames)
            put("requested_frames", s.requestedFrames); put("fused_frames", s.fusedFrames)
            put("capture_note", s.captureNote)
        })
        changes.postValue(System.nanoTime())
    }
    fun get(id: String): Shot? = readableDatabase.rawQuery("SELECT * FROM shots WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.shot() else null
    }
    fun all(): List<Shot> = readableDatabase.rawQuery("SELECT * FROM shots ORDER BY time DESC", null).use {
        buildList { while (it.moveToNext()) add(it.shot()) }
    }
    fun latest(): Shot? = readableDatabase.rawQuery("SELECT * FROM shots ORDER BY time DESC LIMIT 1", null).use {
        if (it.moveToFirst()) it.shot() else null
    }
    fun captureProgress(id: String, count: Int, note: String? = null) {
        writableDatabase.update("shots", ContentValues().apply {
            put("frames", count)
            put("capture_note", note)
        }, "id=?", arrayOf(id))
        changes.postValue(System.nanoTime())
    }
    fun fusionProgress(id: String, used: Int) {
        writableDatabase.update("shots", ContentValues().apply { put("fused_frames", used) }, "id=?", arrayOf(id))
    }
    fun unfinishedCaptures(): List<Shot> = all().filter { it.stage == Stage.CAPTURING }

    fun pendingIds(): List<String> = readableDatabase.rawQuery("SELECT id FROM shots WHERE stage IN ('QUEUED','RUNNING')", null).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }
    fun update(id: String, stage: Stage, ai: Uri? = null, elapsed: Long = 0, error: String? = null) {
        writableDatabase.update("shots", ContentValues().apply {
            put("stage", stage.name); put("ai", ai?.toString()); put("elapsed", elapsed); put("error", error)
        }, "id=?", arrayOf(id))
        changes.postValue(System.nanoTime())
    }
}

class LumaApp : Application(), Configuration.Provider {
    val shots by lazy { Shots(this) }
    val fileIo = Executors.newSingleThreadExecutor()
    private val aiExecutor = Executors.newSingleThreadExecutor()
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setExecutor(aiExecutor).build()
    override fun onCreate() {
        super.onCreate()
        fileIo.execute {
            // An interrupted burst retains its first public image and all completed input frames.
            shots.unfinishedCaptures().forEach { shot ->
                shots.update(shot.id, if (shot.output.enhance) Stage.QUEUED else Stage.ORIGINAL)
            }
            shots.pendingIds().forEach { EnhanceWorker.enqueue(this, it) }
        }
    }
}
val Context.luma get() = applicationContext as LumaApp
