package th.ogay.luma

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StudioFlowTest{
    @get:Rule val permissions=GrantPermissionRule.grant(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
    private val context:Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun until(ms:Long,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(250)};assertTrue("Timed out after $ms ms",condition())}

    @Test fun videoRecordsPausesResumesAndHasAudioTrack(){
        context.getSharedPreferences("camera",0).edit().putBoolean("video_mic",true).putBoolean("video_sound",false).commit()
        val old=MediaLedger.all(context).map{it.uri}.toSet()
        ActivityScenario.launch<VideoActivity>(Intent(context,VideoActivity::class.java)).use{
            until(40000){device.findObject(By.desc("เริ่มบันทึกวิดีโอ"))?.isEnabled==true}
            device.findObject(By.desc("เริ่มบันทึกวิดีโอ")).click()
            until(15000){device.findObject(By.desc("พักหรือต่อวิดีโอ"))?.isEnabled==true}
            SystemClock.sleep(2500);device.findObject(By.desc("พักหรือต่อวิดีโอ")).click()
            assertNotNull(device.wait(Until.findObject(By.text("ต่อ")),10000));SystemClock.sleep(500)
            device.findObject(By.desc("พักหรือต่อวิดีโอ")).click();assertNotNull(device.wait(Until.findObject(By.text("พัก")),10000))
            SystemClock.sleep(1500);device.findObject(By.desc("หยุดบันทึกวิดีโอ")).click()
            until(25000){MediaLedger.all(context).any{it.mime=="video/mp4"&&it.uri !in old}}
            val clip=MediaLedger.all(context).first{it.mime=="video/mp4"&&it.uri !in old};val report=ClipInspector.inspect(context,clip.uri)
            assertTrue(report.toString(),report.getLong("frames")>30);assertTrue(report.toString(),report.getBoolean("audio"));assertTrue(report.getDouble("measuredFps")>10)
        }
    }
    @Test fun documentRectifiesPageAndWritesPdf(){
        val b=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=38f};c.drawText("LUMA DOCUMENT",95f,250f,p);c.drawText("Page 1 - test",110f,340f,p)
        val file=File(context.filesDir,"test-document.jpg");PhotoEffects.write(b,file);b.recycle();val old=MediaLedger.all(context).map{it.uri}.toSet()
        ActivityScenario.launch<DocumentActivity>(Intent(context,DocumentActivity::class.java).setData(MediaLedger.privateUri(context,file))).use{
            until(15000){device.findObject(By.textContains("ลากจุดมุม"))!=null||device.findObject(By.textContains("ปรับจุดมุม"))!=null||device.findObject(By.textContains("ลากมุม"))!=null}
            SystemClock.sleep(1500);device.findObject(By.text("เก็บหน้านี้")).click()
            assertNotNull(device.wait(Until.findObject(By.textContains("เก็บ 1 หน้าแล้ว")),10000));device.findObject(By.text("สร้าง PDF")).click()
            until(15000){MediaLedger.all(context).any{it.mime=="application/pdf"&&it.uri !in old}}
            val item=MediaLedger.all(context).first{it.mime=="application/pdf"&&it.uri !in old};context.contentResolver.openInputStream(item.uri)!!.use{stream->val header=ByteArray(5);assertEquals(5,stream.read(header));assertEquals("%PDF-",String(header))}
        }
    }
    @Test fun rawLabFusesFourDngFramesWhenRawIsExposed(){
        assumeTrue(Capabilities.rawIds(context).isNotEmpty())
        val old=MediaLedger.all(context).map{it.uri}.toSet()
        ActivityScenario.launch<RawLabActivity>(Intent(context,RawLabActivity::class.java)).use{
            until(35000){device.findObject(By.desc("เริ่ม RAW video หรือ RAW AI"))?.isEnabled==true}
            device.findObject(By.text("ตัวเลือก")).click();device.wait(Until.findObject(By.textContains("โหมด RAW Video")),10000).click()
            device.wait(Until.findObject(By.text("RAW AI · 4 เฟรม")),10000).click()
            device.findObject(By.desc("เริ่ม RAW video หรือ RAW AI")).click()
            until(90000){MediaLedger.all(context).any{it.mime=="application/json"&&it.uri !in old&&it.note.startsWith("RAW ")}}
            val item=MediaLedger.all(context).first{it.mime=="application/json"&&it.uri !in old&&it.note.startsWith("RAW ")}
            val json=context.contentResolver.openInputStream(item.uri)!!.bufferedReader().use{JSONObject(it.readText())}
            assertEquals(json.toString(),4,json.getInt("frames"));assertEquals("complete",json.getString("state"))
        }
    }
    @Test fun sr1GpuExportsTwiceTheVideoResolution(){
        val file=syntheticVideo();val old=MediaLedger.all(context).map{it.uri}.toSet()
        ActivityScenario.launch<ClipEditorActivity>(Intent(context,ClipEditorActivity::class.java).setData(MediaLedger.privateUri(context,file))).use{
            val spinner=device.wait(Until.findObject(By.clazz(Spinner::class.java)),15000);assertNotNull(spinner);spinner.click()
            device.wait(Until.findObject(By.text("AI SR1 2× · SDR ≤1080p")),10000).click()
            device.wait(Until.findObject(By.desc("ส่งออกคลิป")),10000).click()
            until(120000){device.findObject(By.textContains("ส่งออกไม่ได้"))?.let{fail(it.text)};MediaLedger.all(context).any{it.mime=="video/mp4"&&it.uri !in old}}
            val item=MediaLedger.all(context).first{it.mime=="video/mp4"&&it.uri !in old};val report=ClipInspector.inspect(context,item.uri)
            assertEquals(report.toString(),640,report.getInt("width"));assertEquals(480,report.getInt("height"));assertTrue(report.toString(),report.getLong("frames")>=28)
        }
    }
    private fun syntheticVideo():File{
        val file=File(context.filesDir,"sr1-source-${System.nanoTime()}.mp4")
        val codec=MediaCodec.createEncoderByType("video/avc");val mux=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format=MediaFormat.createVideoFormat("video/avc",320,240).apply{setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);setInteger(MediaFormat.KEY_BIT_RATE,500000);setInteger(MediaFormat.KEY_FRAME_RATE,15);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)}
        var started=false;var track=-1;var frame=0;var inputEnded=false;var outputEnded=false;val info=MediaCodec.BufferInfo();val deadline=SystemClock.elapsedRealtime()+20000
        try{codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            while(!outputEnded&&SystemClock.elapsedRealtime()<deadline){
                if(!inputEnded){val index=codec.dequeueInputBuffer(10000);if(index>=0){if(frame==30){codec.queueInputBuffer(index,0,0,frame*1_000_000L/15,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}else{
                    val image=codec.getInputImage(index)!!
                    image.planes.forEachIndexed{ch,plane->val w=if(ch==0)320 else 160;val h=if(ch==0)240 else 120;for(y in 0 until h)for(x in 0 until w)plane.buffer.put(y*plane.rowStride+x*plane.pixelStride,(if(ch==0){if((x/20+y/20)%2==0)180 else 80}else 128).toByte())}
                    codec.queueInputBuffer(index,0,320*240*3/2,frame*1_000_000L/15,0);frame++
                }}}
                var index=codec.dequeueOutputBuffer(info,10000)
                while(index>=0){val buffer=codec.getOutputBuffer(index)!!;if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0&&info.size>0){buffer.position(info.offset);buffer.limit(info.offset+info.size);check(started);mux.writeSampleData(track,buffer,info)};outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(index,false);index=codec.dequeueOutputBuffer(info,0)}
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=mux.addTrack(codec.outputFormat);mux.start();started=true}
            };check(outputEnded){"Fixture encoder timed out"};return file
        }finally{runCatching{codec.stop()};codec.release();if(started)mux.stop();mux.release()}
    }
}
