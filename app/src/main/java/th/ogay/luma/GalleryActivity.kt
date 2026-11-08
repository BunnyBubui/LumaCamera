package th.ogay.luma

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.text.DateFormat
import java.util.*
import java.util.concurrent.Executors

class GalleryActivity : ComponentActivity() {
    private var selected:String?=null
    private var useAi=false
    private var showingId:String?=null
    private lateinit var root:LinearLayout
    private lateinit var inspect:InspectView
    private lateinit var toggle:LinearLayout
    private lateinit var status:TextView
    private var readVersion=0
    private val thumbs=Executors.newFixedThreadPool(2)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        selected=state?.getString("id");useAi=state?.getBoolean("ai") ?: false
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        installRoot(root)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {if(selected!=null) {selected=null;useAi=false;reload()} else finish()}
        })
        luma.shots.changes.observe(this) {reload()}
        if(state==null) {
            luma.fileIo.execute {
                val first=luma.shots.latest()?.id
                runOnUiThread {if(!isDestroyed){selected=first;reload()} }
            }
        } else reload()
    }
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putString("id",selected);out.putBoolean("ai",useAi)}
    override fun onDestroy(){thumbs.shutdownNow();super.onDestroy()}
    private fun reload() {
        val id=selected;val version=++readVersion
        luma.fileIo.execute {
            val shot=id?.let {luma.shots.get(it)}
            val list=if(shot==null) luma.shots.all() else emptyList()
            runOnUiThread {
                if(version==readVersion && !isDestroyed) {
                    if(shot!=null) showDetail(shot) else {selected=null;showGrid(list)}
                }
            }
        }
    }
    private fun header(title:String, back:()->Unit):LinearLayout {
        val bar=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(12),0)}
        bar.addView(Glyph(this,"back","ย้อนกลับ",back),dp(46),dp(46))
        bar.addView(label(title,21f).apply {setPadding(dp(8),0,0,0)})
        bar.space();root.addView(bar,-1,dp(60));return bar
    }
    private fun showGrid(rows:List<Shot>) {
        showingId=null;root.removeAllViews()
        header("รูปถ่าย") {finish()}
        if(rows.isEmpty()) {root.addView(label("รูปที่ถ่ายด้วย Luma จะปรากฏที่นี่",16f,MUTED).apply {gravity=Gravity.CENTER},-1,-1);return}
        val grid=GridView(this).apply {numColumns=3;horizontalSpacing=dp(3);verticalSpacing=dp(3)}
        root.addView(grid,-1,-1)
        grid.adapter=object:BaseAdapter(){
            override fun getCount()=rows.size
            override fun getItem(position:Int)=rows[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,previous:View?,parent:ViewGroup?):View {
                val image=(previous as? ImageView) ?: ImageView(this@GalleryActivity).apply {scaleType=ImageView.ScaleType.CENTER_CROP}
                val row=rows[position]
                image.tag=row.id;image.setImageDrawable(null);image.setBackgroundColor(PANEL)
                image.layoutParams=AbsListView.LayoutParams(-1,(grid.width/3).coerceAtLeast(dp(92)))
                image.contentDescription="เปิดรูป ${formatTime(row.time)}"
                thumbs.execute {
                    val bitmap=PhotoMedia.thumbnail(this@GalleryActivity,row.uri,320)
                    runOnUiThread {if(!isDestroyed && image.tag==row.id) image.setImageBitmap(bitmap)}
                }
                return image
            }
        }
        grid.setOnItemClickListener { _,_,p,_ -> selected=rows[p].id;useAi=false;reload() }
    }
    private fun formatTime(t:Long)=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(t))
    private fun showDetail(s:Shot) {
        if(showingId!=s.id) {
            showingId=s.id;root.removeAllViews()
            header("ภาพถ่าย") {selected=null;useAi=false;reload()}.addView(pill("รูปทั้งหมด"){selected=null;useAi=false;reload()})
            inspect=InspectView(this);root.addView(inspect,LinearLayout.LayoutParams(-1,0,1f))
            status=label("",14f,MUTED).apply {gravity=Gravity.CENTER;setPadding(dp(12),dp(5),dp(12),dp(5))}
            root.addView(status,-1,-2)
            toggle=LinearLayout(this).apply {gravity=Gravity.CENTER;setPadding(dp(6),0,dp(6),dp(8))}
            root.addView(toggle,-1,-2)
        }
        if(s.ai==null) useAi=false
        inspect.show(if(useAi) s.ai!! else s.uri)
        status.text=when(s.stage){
            Stage.QUEUED->"รอประมวลผล AI"
            Stage.RUNNING->"กำลังสร้างสำเนา AI"
            Stage.FAILED->"AI ยังไม่สำเร็จ · ${s.error}"
            Stage.DONE->if(useAi) "AI $MODEL_VERSION   ·   ${pixelsText(scaledSize(s.size,TARGET_PIXELS).pixels)}" else "ต้นฉบับ   ·   ${pixelsText(s.size.pixels)}"
            Stage.ORIGINAL->"${pixelsText(s.size.pixels)}   ·   ${s.reason.description}"
        }
        toggle.removeAllViews()
        fun chip(text:String,active:Boolean=false,click:()->Unit) {
            toggle.addView(pill(text,click).apply {if(active)setTextColor(LIME)},LinearLayout.LayoutParams(-2,dp(44)).apply{setMargins(dp(2),0,dp(2),0)})
        }
        if(s.ai!=null) {
            chip("ต้นฉบับ",!useAi){useAi=false;showDetail(s)}
            chip("ภาพ AI",useAi){useAi=true;showDetail(s)}
        } else if(s.stage!=Stage.RUNNING && s.stage!=Stage.QUEUED) {
            chip(if(s.stage==Stage.FAILED) "ลอง AI อีกครั้ง" else "ปรับด้วย AI") {
                luma.fileIo.execute {
                    val current=luma.shots.get(s.id)
                    if(current!=null && current.stage in setOf(Stage.ORIGINAL,Stage.FAILED)) {
                        luma.shots.update(s.id,Stage.QUEUED)
                        EnhanceWorker.enqueue(applicationContext,s.id,replace=true)
                    }
                }
            }
        }
        chip("ข้อมูล"){details(s)}
    }
    private fun details(s:Shot) {
        val ai=scaledSize(s.size,TARGET_PIXELS)
        val text=buildList {
            add(formatTime(s.time));add("ต้นฉบับ  ${s.size.w} × ${s.size.h} px")
            add("กล้อง${if(s.rear) "หลัง" else "หน้า"}  ·  ${zoomText(s.zoom)}")
            add(if(s.softwareCrop) "ซูมครอปจาก 1×" else "ซูมด้วยระบบกล้อง")
            add("Physical camera ID  ${s.lens ?: "เครื่องไม่รายงาน"}")
            add("พื้นที่ครอป  ${s.cropped?.let(::pixelsText) ?: "เครื่องไม่รายงาน"}")
            add(s.reason.description)
            if(s.ai!=null){add("AI $MODEL_VERSION  ${ai.w} × ${ai.h} px");add("เวลาประมวลผล  ${s.elapsed} ms")}
        }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("ข้อมูลภาพ").setMessage(text).setPositiveButton("ปิด",null).show()
    }
}
