package th.ogay.luma

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShotMigrationTest {
    @Test fun openingVersionOnePreservesOriginalsAndAiLinks() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "migration-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("frames.db"), null).use { db ->
                db.execSQL("""CREATE TABLE shots(id TEXT PRIMARY KEY, time INTEGER NOT NULL, uri TEXT NOT NULL,
                    w INTEGER NOT NULL, h INTEGER NOT NULL, zoom REAL NOT NULL, rear INTEGER NOT NULL,
                    lens TEXT, cropped INTEGER, reason TEXT NOT NULL, stage TEXT NOT NULL, ai TEXT,
                    elapsed INTEGER NOT NULL DEFAULT 0, error TEXT, software INTEGER NOT NULL)""")
                db.execSQL("INSERT INTO shots(id,time,uri,w,h,zoom,rear,reason,stage,ai,software) VALUES('old',1,'content://original',4000,3000,2,1,'SMALL','DONE','content://enhanced',0)")
                db.version = 1
            }
            Shots(context).use { db ->
                val old = db.get("old")!!
                assertEquals("content://original", old.uri.toString())
                assertEquals("content://enhanced", old.ai.toString())
                assertEquals(CaptureOutput.JPEG, old.output)
                assertEquals(1, old.frames)
                assertEquals(Stage.DONE, old.stage)
                assertTrue(old.aiName.endsWith("_SR1_AI.jpg"))
                assertNull(old.raw)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
