package th.ogay.luma

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StudioMedia(val uri:Uri,val title:String,val mime:String,val note:String,val time:Long)

object MediaLedger {
    @Synchronized fun all(c:Context):List<StudioMedia> = runCatching {
        val a=JSONArray(c.getSharedPreferences("studio_media",0).getString("items","[]"))
        (0 until a.length()).map{a.getJSONObject(it)}.map{StudioMedia(Uri.parse(it.getString("uri")),it.getString("title"),it.getString("mime"),it.optString("note"),it.getLong("time"))}.sortedByDescending{it.time}
    }.getOrDefault(emptyList())
    @Synchronized fun add(c:Context,uri:Uri,title:String,mime:String,note:String="") {
        val list=listOf(StudioMedia(uri,title,mime,note,System.currentTimeMillis()))+all(c).filter{it.uri!=uri}
        val a=JSONArray();list.take(500).forEach{a.put(JSONObject().put("uri",it.uri.toString()).put("title",it.title).put("mime",it.mime).put("note",it.note).put("time",it.time))}
        c.getSharedPreferences("studio_media",0).edit().putString("items",a.toString()).commit()
    }
    fun privateUri(c:Context,file:File):Uri=FileProvider.getUriForFile(c,"${c.packageName}.files",file)
    fun share(c:Context,uri:Uri,mime:String) { c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"ส่งออกไฟล์")) }
    fun view(c:Context,uri:Uri,mime:String) { runCatching{c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{c.notice("ยังไม่มีแอปเปิดไฟล์ประเภทนี้ ใช้ปุ่มส่งออกเพื่อบันทึกไฟล์")}}
    fun publishVideo(c:Context,file:File,title:String,note:String=""):Uri {
        val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,title);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.DATE_TAKEN,System.currentTimeMillis())}
        if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Luma/");values.put(MediaStore.Video.Media.IS_PENDING,1)}
        else {
            @Suppress("DEPRECATION") val target=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"Luma/$title")
            target.parentFile?.mkdirs();file.copyTo(target,true)
            @Suppress("DEPRECATION") values.put(MediaStore.Video.Media.DATA,target.path)
        }
        val uri=c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:error("สร้างไฟล์วิดีโอไม่ได้")
        try{
            if(Build.VERSION.SDK_INT>=29){ c.contentResolver.openOutputStream(uri,"wt")!!.use{out->file.inputStream().use{it.copyTo(out)}};c.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null) }
            add(c,uri,title,"video/mp4",note);return uri
        }catch(e:Exception){c.contentResolver.delete(uri,null,null);throw e}
    }
    fun rememberFile(c:Context,file:File,mime:String,note:String=""):Uri {
        val uri=privateUri(c,file);add(c,uri,file.name,mime,note);return uri
    }
}

class StudioLibraryActivity:ComponentActivity(){
    override fun onCreate(state:android.os.Bundle?){super.onCreate(state);render()}
    override fun onResume(){super.onResume();render()}
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun render(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),0,dp(18),0)};installRoot(root)
        root.addView(pill("‹  กลับหน้ากล้อง"){finish()},-1,dp(52))
        root.addView(label("คลัง Luma",28f),-1,dp(64))
        root.addView(pill("ภาพถ่ายและเปรียบเทียบ AI"){startActivity(Intent(this,GalleryActivity::class.java))},-1,dp(52))
        val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(list);root.addView(scroll,-1,0);(scroll.layoutParams as LinearLayout.LayoutParams).weight=1f
        val media=MediaLedger.all(this)
        if(media.isEmpty())list.addView(label("วิดีโอ เอกสาร และไฟล์ทดลองที่บันทึกจะแสดงที่นี่",15f,MUTED),-1,dp(100))
        media.forEach{item->
            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(16),dp(12),dp(12));background=shape(PANEL,dp(18).toFloat())}
            box.addView(label(item.title,15f),-1,-2)
            if(item.note.isNotBlank())box.addView(label(item.note,13f,MUTED),-1,-2)
            val row=LinearLayout(this)
            row.addView(pill("เปิด"){MediaLedger.view(this,item.uri,item.mime)},0,dp(48));(row.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight=1f
            row.addView(pill("ส่งออก"){MediaLedger.share(this,item.uri,item.mime)},0,dp(48));(row.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight=1f
            if(item.mime=="video/mp4")row.addView(pill("แต่ง / AI"){startActivity(Intent(this,ClipEditorActivity::class.java).setData(item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))},LinearLayout.LayoutParams(0,dp(48),1f))
            box.addView(row);list.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        }
    }
}
