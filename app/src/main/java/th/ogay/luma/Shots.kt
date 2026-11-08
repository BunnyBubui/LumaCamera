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

enum class Stage { ORIGINAL, QUEUED, RUNNING, DONE, FAILED }

data class Shot(
    val id: String, val time: Long, val uri: Uri, val size: Dimensions,
    val zoom: Float, val rear: Boolean, val lens: String?, val cropped: Long?,
    val reason: Decision, val stage: Stage, val ai: Uri?, val elapsed: Long, val error: String?,
    val softwareCrop: Boolean
) {
    val baseName get() = "LUMA_${time}_${id}"
    val aiName get() = "${baseName}_${MODEL_VERSION}_AI.jpg"
}

class Shots(context: Context) : SQLiteOpenHelper(context, "frames.db", null, 1) {
    val changes = MutableLiveData<Long>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shots(id TEXT PRIMARY KEY, time INTEGER NOT NULL, uri TEXT NOT NULL,
            w INTEGER NOT NULL, h INTEGER NOT NULL, zoom REAL NOT NULL, rear INTEGER NOT NULL,
            lens TEXT, cropped INTEGER, reason TEXT NOT NULL, stage TEXT NOT NULL, ai TEXT,
            elapsed INTEGER NOT NULL DEFAULT 0, error TEXT, software INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX shots_time ON shots(time DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unknown database version $oldVersion → $newVersion")
    }
    private fun Cursor.shot(): Shot {
        fun str(key: String): String? = getString(getColumnIndexOrThrow(key))
        fun num(key: String) = getLong(getColumnIndexOrThrow(key))
        return Shot(str("id")!!, num("time"), Uri.parse(str("uri")), Dimensions(num("w").toInt(), num("h").toInt()),
            getFloat(getColumnIndexOrThrow("zoom")), num("rear") == 1L, str("lens"),
            str("cropped")?.toLong(), Decision.valueOf(str("reason")!!), Stage.valueOf(str("stage")!!),
            str("ai")?.let { Uri.parse(it) }, num("elapsed"), str("error"), num("software") == 1L)
    }
    fun insert(s: Shot) {
        writableDatabase.insertOrThrow("shots", null, ContentValues().apply {
            put("id", s.id); put("time", s.time); put("uri", s.uri.toString())
            put("w", s.size.w); put("h", s.size.h); put("zoom", s.zoom); put("rear", s.rear)
            put("lens", s.lens); put("cropped", s.cropped); put("reason", s.reason.name)
            put("stage", s.stage.name); put("software", s.softwareCrop)
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
        fileIo.execute { shots.pendingIds().forEach { EnhanceWorker.enqueue(this, it) } }
    }
}
val Context.luma get() = applicationContext as LumaApp
