package th.ogay.luma

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.*

fun Activity.showStudioSettings(rawAvailable:Boolean, changed:()->Unit) {
    val prefs=getSharedPreferences("camera",0)
    studioSheet("ตั้งค่ากล้อง"){box,dialog->
        fun heading(text:String){box.addView(label(text,16f,LIME),-1,dp(44))}
        fun toggle(title:String,key:String,default:Boolean=false){
            box.addView(Switch(this).apply{text=title;setTextColor(WHITE);textSize=15f;minHeight=dp(52);isChecked=prefs.getBoolean(key,default)
                if(key=="sound"&&androidx.camera.core.CameraInfo.mustPlayShutterSound()){isChecked=true;isEnabled=false;text="เสียงชัตเตอร์ · ระบบกำหนดให้เปิด"}
                setOnCheckedChangeListener{_,v->prefs.edit().putBoolean(key,v).apply();changed()}
            },-1,-2)
        }
        fun choose(title:String,key:String,labels:Array<String>,values:List<String>,default:String){
            box.addView(pill("$title  ›"){AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,values.indexOf(prefs.getString(key,default))){d,i->prefs.edit().putString(key,values[i]).apply();changed();d.dismiss()}.setNegativeButton("ปิด",null).show()},-1,-2)
        }
        heading("ภาพถ่าย")
        choose("อัตราส่วนภาพ","aspect",arrayOf("4:3","1:1","16:9"),listOf("4:3","1:1","16:9"),"4:3")
        choose("ตั้งเวลาถ่าย","timer",arrayOf("ปิด","3 วินาที","10 วินาที"),listOf("0","3","10"),"0")
        choose("คุณภาพ JPEG","jpeg_quality",arrayOf("ประหยัด · 85%","มาตรฐาน · 95%","สูงสุด · 100%"),listOf("85","95","100"),"95")
        box.addView(pill("จำนวนเฟรม AI · ${prefs.getInt("frames",8)} เฟรม"){showCaptureSettings(rawAvailable,changed)},-1,-2)
        toggle("AI อัตโนมัติใน JPEG","auto",true)
        heading("การควบคุมและจัดองค์ประกอบ")
        choose("ปุ่มเสียง","volume_action",arrayOf("ถ่ายภาพ / เริ่มหยุดวิดีโอ","ซูม","ปรับเสียงระบบ"),listOf("shutter","zoom","system"),"shutter")
        choose("ความเร็วซูม","zoom_speed",arrayOf("ช้า","ปกติ","เร็ว"),listOf("slow","normal","fast"),"normal")
        toggle("แตะภาพสดเพื่อถ่าย","tap_shutter")
        toggle("เส้นกริด","grid");toggle("ระดับน้ำ","level")
        toggle("Histogram พรีวิว","histogram");toggle("Focus peaking พรีวิว","peaking");toggle("Zebra พรีวิว","zebra")
        heading("เสียงและการตอบสนอง")
        toggle("เสียงชัตเตอร์","sound",true);toggle("เสียงเริ่ม / จบวิดีโอ","video_sound",true)
        toggle("เสียงนับถอยหลัง","countdown_sound",true);toggle("เสียงโฟกัส","focus_sound")
        toggle("แรงสั่นเมื่อแตะ","haptic",true);toggle("ลดการเคลื่อนไหว","reduce_motion")
        heading("เซลฟี่และไฟล์")
        toggle("กลับภาพเซลฟี่ซ้ายขวา","mirror_selfie");toggle("เติมแสงหน้าจอเมื่อเซลฟี่","screen_flash")
        toggle("ลายน้ำชื่อและวันที่","watermark")
        box.addView(pill("ชื่อลายน้ำ"){val input=EditText(this).apply{setText(prefs.getString("watermark_name","Luma"));setSingleLine()};AlertDialog.Builder(this).setTitle("ชื่อลายน้ำ").setView(input).setPositiveButton("บันทึก"){_,_->prefs.edit().putString("watermark_name",input.text.toString().take(60)).apply()}.setNegativeButton("ยกเลิก",null).show()},-1,-2)
        box.addView(pill("โฟลเดอร์รูปภาพ"){val input=EditText(this).apply{setText(prefs.getString("folder","Luma"));setSingleLine()};AlertDialog.Builder(this).setTitle("โฟลเดอร์ภายใต้ Pictures").setView(input).setPositiveButton("บันทึก"){_,_->val name=input.text.toString().replace(Regex("[^\\p{L}\\p{N} _-]"),"").take(40).ifBlank{"Luma"};prefs.edit().putString("folder",name).apply()}.setNegativeButton("ยกเลิก",null).show()},-1,-2)
        toggle("บันทึก GPS เมื่ออนุญาตตำแหน่ง","gps")
        box.addView(label("GPS ใช้ตำแหน่งล่าสุดที่ระบบมี เมื่อถ่ายภาพและได้รับสิทธิ์เท่านั้น",12f,MUTED),-1,-2)
        box.addView(pill("ความสามารถกล้อง / ส่งออกรายงาน"){Capabilities.show(this)},-1,-2)
        box.addView(pill("คืนค่าตั้งค่ากล้อง"){AlertDialog.Builder(this).setTitle("คืนค่าการตั้งค่า?").setMessage("รูปและวิดีโอที่บันทึกไว้ยังอยู่").setPositiveButton("คืนค่า"){_,_->prefs.edit().clear().apply();changed();dialog.dismiss()}.setNegativeButton("ยกเลิก",null).show()},-1,-2)
        box.addView(pill("ปิด"){dialog.dismiss()},-1,-2)
    }
}
