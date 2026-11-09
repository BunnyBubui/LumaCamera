package th.ogay.luma

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.*
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.*
import org.json.JSONObject
import java.io.File

@UnstableApi
class ClipEditorActivity:ComponentActivity(){
    private lateinit var status:TextView;private lateinit var export:TextView;private lateinit var player:VideoView
    private var transformer:Transformer?=null;private var preset=0;private var codec="video/avc";private var speed=1f;private var crop=false
    private var lut:Array<Array<IntArray>>?=null;private var job:File?=null;private var output:File?=null
    private val handler=Handler(Looper.getMainLooper());private val progress=ProgressHolder()
    private val pickLut=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)luma.fileIo.execute{runCatching{contentResolver.openInputStream(uri)!!.bufferedReader().use{CubeLut.read(it)}}.onSuccess{cube->runOnUiThread{lut=cube;preset=4;status.text="นำเข้า LUT 3D ${cube.size} แล้ว"}}.onFailure{runOnUiThread{status.text="LUT ใช้ไม่ได้ · ${it.localizedMessage}"}}}}
    private val poll=object:Runnable{override fun run(){val t=transformer?:return;val state=t.getProgress(progress);status.text=if(state==Transformer.PROGRESS_STATE_AVAILABLE)"กำลังส่งออก ${progress.progress}% · เปิดหน้านี้ไว้"else "กำลังเตรียมส่งออก · เปิดหน้านี้ไว้";handler.postDelayed(this,500)}}
    override fun onCreate(state:Bundle?){super.onCreate(state)
        preset=state?.getInt("preset")?:0;speed=state?.getFloat("speed")?:1f;crop=state?.getBoolean("crop")?:false
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),0,dp(14),0)};installRoot(root)
        root.addView(pill("‹ กลับ"){cancel();finish()},-1,dp(48));root.addView(label("แต่งคลิป",26f),-1,dp(56))
        player=VideoView(this);root.addView(player,LinearLayout.LayoutParams(-1,0,1f));intent.data?.let{player.setVideoURI(it);player.setMediaController(MediaController(this).also{c->c.setAnchorView(player)});player.seekTo(1)}
        status=label("พรีวิวต้นฉบับ · ผลแต่งอยู่ในไฟล์ส่งออก\nSR1 รายเฟรมยังไม่มีการแก้ความสม่ำเสมอข้ามเฟรม",13f,MUTED);root.addView(status,-1,dp(80))
        val choices=Spinner(this);choices.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("ต้นฉบับ / เปลี่ยน codec","AI SR1 2× · SDR ≤1080p","Movie LUT","Luma Log · แปลงจาก SDR","LUT .cube ที่นำเข้า"));choices.setSelection(preset);choices.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){};override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,position:Int,id:Long){if(transformer==null)preset=position}};root.addView(choices,-1,dp(52))
        val options=LinearLayout(this)
        options.addView(pill("นำเข้า .cube"){if(transformer==null)pickLut.launch(arrayOf("*/*"))},LinearLayout.LayoutParams(0,dp(48),1f))
        options.addView(pill("H.264 / HEVC"){if(transformer==null){codec=if(codec=="video/avc")"video/hevc"else "video/avc";status.text="Codec ส่งออก $codec"}},LinearLayout.LayoutParams(0,dp(48),1f))
        options.addView(pill("ความเร็ว"){if(transformer==null)android.app.AlertDialog.Builder(this).setItems(arrayOf("0.25×","0.5×","1×","2×","4×")){_,i->speed=listOf(.25f,.5f,1f,2f,4f)[i];status.text="ความเร็ว $speed× · ปรับเสียงตามเวลา"}.show()},LinearLayout.LayoutParams(0,dp(48),1f));root.addView(options)
        root.addView(Switch(this).apply{text="ครอป Movie 2.39:1 ตอนส่งออก";setTextColor(WHITE);isChecked=crop;setOnCheckedChangeListener{_,v->if(transformer==null)crop=v}},-1,dp(48))
        export=pill("ส่งออกเป็นไฟล์ใหม่"){if(transformer==null)start()else cancel()};root.addView(export,-1,dp(60))
    }
    private fun start(){val uri=intent.data?:return;if(filesDir.usableSpace<128L*1024*1024){notice("พื้นที่ว่างไม่พอเริ่มส่งออก");return};if(preset==4&&lut==null){notice("นำเข้า .cube ก่อน");return}
        val id=System.currentTimeMillis();val dir=File(filesDir,"video-exports/$id").apply{mkdirs()};val file=File(dir,"LUMA_EDIT_$id.mp4");output=file;job=File(dir,"job.json")
        val metadata=JSONObject().put("source",uri.toString()).put("preset",preset).put("codec",codec).put("speed",speed).put("crop",crop).put("state","running")
        if(preset==3)metadata.put("transfer","LumaLog1: log10(1+9*linearRGB), derived from SDR; not sensor Log/RAW")
        job!!.writeText(metadata.toString(2))
        try{
            val effects=mutableListOf<Effect>()
            when(preset){1->effects+=LumaVideoEffect(true);2->{val n=17;effects+=SingleColorLut.createFromCube(Array(n){r->Array(n){g->IntArray(n){b->val red=((r/16f*1.04f+.015f).coerceIn(0f,1f)*255).toInt();val green=((g/16f*.98f).coerceIn(0f,1f)*255).toInt();val blue=((b/16f*.92f+.02f).coerceIn(0f,1f)*255).toInt();0xff000000.toInt()or(red shl 16)or(green shl 8)or blue}}})};3->effects+=LumaVideoEffect(false);4->effects+=SingleColorLut.createFromCube(lut!!)}
            if(crop)effects+=Presentation.createForAspectRatio(2.39f,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            val builder=EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(),effects))
            if(speed!=1f)builder.setSpeed(object:SpeedProvider{override fun getSpeed(timeUs:Long)=speed;override fun getNextSpeedChangeTimeUs(timeUs:Long)=C.TIME_UNSET})
            val listener=object:Transformer.Listener{
                override fun onCompleted(composition:Composition,result:ExportResult){transformer=null;handler.removeCallbacks(poll);export.text="ส่งออกอีกครั้ง";status.text="กำลังบันทึกไฟล์…"
                    luma.fileIo.execute{runCatching{val out=MediaLedger.publishVideo(this@ClipEditorActivity,file,file.name,when(preset){1->"SR1 2× รายเฟรม · ทดลอง";3->"Luma Log SDR · ดู sidecar";else->"แต่งคลิป"});metadata.put("state","complete").put("videoFrames",result.videoFrameCount).put("output",out.toString());job!!.writeText(metadata.toString(2));MediaLedger.rememberFile(this@ClipEditorActivity,job!!,"application/json","ข้อมูลการส่งออกและ transfer curve");ClipInspector.record(this@ClipEditorActivity,out,file.name);file.delete();out}.onSuccess{out->runOnUiThread{if(!isDestroyed){status.text="ส่งออกแล้ว · ${result.videoFrameCount} เฟรม";player.setVideoURI(out);player.seekTo(1)}}}.onFailure{e->runOnUiThread{status.text="ไฟล์อยู่ในแอป · ${e.localizedMessage}"};MediaLedger.rememberFile(this@ClipEditorActivity,file,"video/mp4")}}
                }
                override fun onError(composition:Composition,result:ExportResult,e:ExportException){transformer=null;handler.removeCallbacks(poll);file.delete();metadata.put("state","failed").put("error",e.toString());job!!.writeText(metadata.toString(2));status.text="ส่งออกไม่ได้ · ${e.localizedMessage}\nลองลดขนาดหรือเปลี่ยน codec";export.text="ลองส่งออกใหม่"}
                override fun onFallbackApplied(c:Composition,original:TransformationRequest,fallback:TransformationRequest){status.text="encoder ปรับรูปแบบตามที่เครื่องรองรับ · รายงานไฟล์จะแสดงค่าจริง"}
            }
            val t=Transformer.Builder(this).setVideoMimeType(codec).setPortraitEncodingEnabled(true).addListener(listener).build();transformer=t;t.start(builder.build(),file.path);export.text="ยกเลิกส่งออก";handler.post(poll)
        }catch(e:Exception){transformer?.cancel();transformer=null;file.delete();metadata.put("state","failed").put("error",e.toString());job!!.writeText(metadata.toString(2));status.text="เริ่มส่งออกไม่ได้ · ${e.localizedMessage}"}
    }
    private fun cancel(){if(transformer==null)return;transformer?.cancel();transformer=null;handler.removeCallbacks(poll);output?.delete();job?.let{runCatching{val m=JSONObject(it.readText());m.put("state","cancelled");it.writeText(m.toString(2))}};status.text="ยกเลิกแล้ว · ต้นฉบับยังอยู่";export.text="ลองส่งออกใหม่"}
    override fun onSaveInstanceState(out:Bundle){out.putInt("preset",preset);out.putFloat("speed",speed);out.putBoolean("crop",crop);super.onSaveInstanceState(out)}
    override fun onStop(){cancel();player.pause();super.onStop()}
    override fun onDestroy(){player.stopPlayback();super.onDestroy()}
}
