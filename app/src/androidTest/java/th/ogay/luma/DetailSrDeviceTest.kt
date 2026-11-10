package th.ogay.luma

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import th.ogay.luma.ai.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs

class DetailSrDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(w: Int=37,h: Int=29): File {
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(w*h) { i -> val x=i%w; val y=i/w
            0xff000000.toInt() or (((x*7+y*3)%256) shl 16) or (((x*11+y*13)%256) shl 8) or ((x*17+y*5)%256) }
        bitmap.setPixels(pixels,0,w,0,0,w,h)
        return File(context.cacheDir,"detail-source-${System.nanoTime()}.jpg").also { f ->
            f.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,97,it)) }; bitmap.recycle()
        }
    }
    private fun shot(file: File) = Shot("detail-test",1,Uri.fromFile(file),PhotoMedia.dimensions(file),1f,true,null,null,
        Decision.OFF,Stage.ORIGINAL,null,0,null,false)

    @Test fun bundledNetworkIsNotResizeAndChangesWithSecondInput() {
        val n=EnhanceSpec.INPUT
        DetailRuntime(context,forceCpu=true).use { runtime ->
            val a=IntArray(n*n) { i -> if((i%n/4+i/n/5)%2==0) 0xff28486c.toInt() else 0xffd8b678.toInt() }
            val first=runtime.run(a)
            val second=runtime.run(IntArray(n*n) { 0xff2080d0.toInt() })
            assertEquals(n*n*16*3,first.size)
            assertTrue(first.all { it.isFinite() })
            var residual=0.0; var difference=0.0; var count=0
            val edge=n*4
            for(y in 160 until 240 step 3) for(x in 160 until 240 step 3) for(c in 0..2) {
                val i=(y*edge+x)*3+c
                val base=((a[(y/4)*n+x/4] ushr (16-c*8)) and 255)/255f
                residual+=abs(first[i]-base); difference+=abs(first[i]-second[i]); count++
            }
            assertTrue("Network must reconstruct, not just resize",residual/count>0.001)
            assertTrue("Second input must refresh buffers",difference/count>0.01)
        }
    }
    @Test fun streamJpegRoundTripsScanlinesAndRejectsIncompleteFinish() {
        val f=File(context.cacheDir,"strip-${System.nanoTime()}.jpg")
        try {
            StripJpeg(f,31,25,100).use { jpeg ->
                jpeg.write(IntArray(31*10) { 0xff335577.toInt() },10)
                assertThrows(java.io.IOException::class.java) { jpeg.finish() }
                jpeg.write(IntArray(31*15) { 0xff99bbdd.toInt() },15)
                jpeg.finish()
            }
            val b=BitmapFactory.decodeFile(f.path)
            assertEquals(31,b.width); assertEquals(25,b.height)
            val top=b.getPixel(10,3); val bottom=b.getPixel(10,20)
            assertTrue(abs(((top ushr 16) and 255)-0x33)<=3)
            assertTrue(abs((bottom and 255)-0xdd)<=3); b.recycle()
        } finally { f.delete() }
    }
    @Test fun allScalesExportExactDimensionsAndRecordActualBackend() {
        val source=fixture(); val output=File(context.cacheDir,"detail-export-${System.nanoTime()}.jpg")
        try {
            for(scale in 2..4) {
                val progress=mutableListOf<Int>()
                val report=DetailSuperResolution(context).process(shot(source),output,EnhanceSpec(scale),{false}) { progress+=it }
                assertEquals(Dimensions(37*scale,29*scale),PhotoMedia.dimensions(output))
                assertEquals(report.size,PhotoMedia.dimensions(output))
                assertEquals(100,progress.last())
                assertTrue(JSONObject(report.json).getString("backend") in setOf("GPU FP16","CPU"))
            }
        } finally { source.delete(); output.delete() }
    }
    @Test fun cancellationDoesNotProduceACompletedExport() {
        val source=fixture(); val output=File(context.cacheDir,"detail-cancel-${System.nanoTime()}.jpg")
        try {
            assertThrows(CancellationException::class.java) {
                DetailSuperResolution(context).process(shot(source),output,EnhanceSpec(4),{true})
            }
            assertFalse(output.exists())
        } finally { source.delete(); output.delete() }
    }
    @Test fun cancelledWorkerCannotCompleteANewerRequestAndOldFilesAreNamedSeparately() {
        val directory=File(context.cacheDir,"detail-db-${System.nanoTime()}").apply { mkdirs() }
        val isolated=object: ContextWrapper(context) {
            override fun getDatabasePath(name: String)=File(directory,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        val source=fixture()
        try { Shots(isolated).use { db ->
            db.insert(shot(source))
            assertTrue(db.queueEnhancement("detail-test",EnhanceSpec(2)))
            val first=db.get("detail-test")!!
            db.cancelEnhancement(first.id)
            assertTrue(db.queueEnhancement(first.id,EnhanceSpec(4)))
            val second=db.get(first.id)!!
            assertNotEquals(first.aiName,second.aiName)
            assertFalse(db.aiState(first.id,first.aiToken,Stage.DONE,100,Uri.parse("content://stale")))
            assertEquals(Stage.QUEUED,db.get(first.id)!!.stage)
            assertTrue(db.aiState(second.id,second.aiToken,Stage.DONE,100,Uri.parse("content://current"),size=Dimensions(148,116)))
            assertEquals(Dimensions(148,116),db.get(first.id)!!.aiSize)
        } } finally { directory.deleteRecursively(); source.delete() }
    }
}
