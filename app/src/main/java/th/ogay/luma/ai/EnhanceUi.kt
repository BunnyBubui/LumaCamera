package th.ogay.luma.ai

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import th.ogay.luma.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only the AI workflow: the existing camera/gallery layout stays owned by Glass UI. */
object EnhanceUi {
    private val previews = Executors.newSingleThreadExecutor()
    fun show(activity: ComponentActivity, shot: Shot) {
        val prefs = activity.getSharedPreferences("enhance",0)
        var scale = prefs.getInt("scale",2).coerceIn(2,4)
        var strength = prefs.getInt("strength",70).coerceIn(0,100)
        var profile = runCatching { EnhanceProfile.valueOf(prefs.getString("profile","NATURAL") ?: "NATURAL") }
            .getOrDefault(EnhanceProfile.NATURAL)
        fun spec() = EnhanceSpec(scale,strength,profile)
        val column = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        fun label(value: String) = TextView(activity).apply { text=value; textSize=15f; setPadding(0,12,0,12) }
        column.addView(label("เพิ่มรายละเอียดอย่างเป็นธรรมชาติ · ประมวลผลออฟไลน์\nAI ไม่สามารถยืนยันตัวหนังสือหรือรายละเอียดที่หายไปจากต้นฉบับได้"))
        val profiles = RadioGroup(activity).apply { orientation=RadioGroup.HORIZONTAL }
        for ((id,name) in listOf(1 to "Natural · แนะนำ",2 to "Classic SR2")) {
            profiles.addView(RadioButton(activity).apply {
                this.id=id; text=name; minimumHeight=activity.dp(48)
                isChecked=(id==1 && profile==EnhanceProfile.NATURAL) || (id==2 && profile==EnhanceProfile.CLASSIC)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        profiles.setOnCheckedChangeListener { _,id -> profile=if(id==1) EnhanceProfile.NATURAL else EnhanceProfile.CLASSIC }
        column.addView(profiles)
        val size = label(""); column.addView(size)
        fun updateSize() {
            size.text = runCatching { spec().output(shot.size).let {
                "×$scale · ${it.w} × ${it.h} px · ${pixelsText(it.pixels)}\nขยายทั้งความกว้างและความสูง" }
            }.getOrElse { it.message ?: "ขนาดเกินขีดจำกัด" }
        }
        val choices = RadioGroup(activity).apply { orientation=RadioGroup.HORIZONTAL }
        for (s in 2..4) choices.addView(RadioButton(activity).apply {
            id=s; text="×$s"; minimumHeight=activity.dp(48); isChecked=s==scale
        },LinearLayout.LayoutParams(0,-2,1f))
        choices.setOnCheckedChangeListener { _, id -> scale=id; updateSize() }
        column.addView(choices)
        val amount = label("ความแรงรายละเอียด $strength%"); column.addView(amount)
        column.addView(SeekBar(activity).apply {
            max=100; progress=strength; contentDescription="ความแรงรายละเอียด"; minimumHeight=activity.dp(48)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { strength=value; amount.text="ความแรงรายละเอียด $value%" }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        column.addView(label("×3 และ ×4 ใช้โมเดล ×4 แล้วปรับขนาดผลลัพธ์ จึงอาจใช้เวลาใกล้กัน\nตัวอย่างใช้ส่วนกลางภาพ ภาพเต็มจะประมวลผลเบื้องหลัง"))
        updateSize()
        val dialog = AlertDialog.Builder(activity).setTitle("เพิ่มรายละเอียดด้วย AI")
            .setView(ScrollView(activity).apply { addView(column) })
            .setNegativeButton("ปิด",null).setNeutralButton("ดูตัวอย่าง",null).setPositiveButton("สร้างภาพ",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { preview(activity,shot,spec()) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { spec().output(shot.size) }
                catch (e: IllegalArgumentException) { activity.notice(e.message ?: "ขนาดภาพเกินขีดจำกัด"); return@setOnClickListener }
                prefs.edit().putInt("scale",scale).putInt("strength",strength)
                    .putString("profile",profile.name).apply()
                val requested = spec()
                activity.luma.fileIo.execute {
                    if (activity.luma.shots.queueEnhancement(shot.id,requested)) EnhanceWorker.enqueue(activity.applicationContext,shot.id,true)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun preview(activity: ComponentActivity, shot: Shot, spec: EnhanceSpec) {
        val stopped = AtomicBoolean(false)
        val image = ImageView(activity).apply { adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="ตัวอย่างรายละเอียดกลางภาพ" }
        val dialog = AlertDialog.Builder(activity).setTitle("กำลังสร้างตัวอย่าง…")
            .setView(image).setNegativeButton("ปิด",null).setPositiveButton("ก่อน / หลัง",null).create()
        var original: Bitmap? = null; var enhanced: Bitmap? = null; var after=true
        dialog.setOnDismissListener { stopped.set(true); image.setImageDrawable(null); original?.recycle(); enhanced?.recycle() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            after=!after; image.setImageBitmap(if(after) enhanced else original)
            dialog.setTitle(if(after) "หลัง AI ×${spec.scale}" else "ต้นฉบับขยาย ×${spec.scale}")
        } }
        dialog.show()
        previews.execute {
            var a: Bitmap? = null; var b: Bitmap? = null
            try {
                DetailComputeGate.run({ stopped.get() }) {
                    val rotation = activity.contentResolver.openInputStream(shot.uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
                    activity.contentResolver.openInputStream(shot.uri)?.use { stream ->
                        val decoder = BitmapRegionDecoder.newInstance(stream,false) ?: error("อ่านภาพไม่ได้")
                        try {
                            val w=minOf(EnhanceSpec.CORE,decoder.width); val h=minOf(EnhanceSpec.CORE,decoder.height)
                            val input = DetailSuperResolution.readInput(decoder,((decoder.width-w)/2),((decoder.height-h)/2))
                            val before = IntArray(w*h) { i -> input[(i/w+EnhanceSpec.HALO)*EnhanceSpec.INPUT+i%w+EnhanceSpec.HALO] }
                            val raw = Bitmap.createBitmap(before,w,h,Bitmap.Config.ARGB_8888)
                            a=Bitmap.createScaledBitmap(raw,w*spec.scale,h*spec.scale,true)
                            if(a!==raw) raw.recycle()
                            DetailRuntime(activity.applicationContext).use { runtime ->
                                val result = runtime.run(input)
                                val output=IntArray(w*spec.scale*h*spec.scale)
                                DetailResampler.copyCore(result,input,spec.scale,spec.strength,w,h,output,w*spec.scale,0,spec.profile)
                                b=Bitmap.createBitmap(output,w*spec.scale,h*spec.scale,Bitmap.Config.ARGB_8888)
                            }
                            if(rotation!=0) {
                                fun rotate(bitmap: Bitmap): Bitmap = Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,
                                    Matrix().apply { setRotate(rotation.toFloat()) },true).also { if(it!==bitmap) bitmap.recycle() }
                                a=rotate(a!!); b=rotate(b!!)
                            }
                        } finally { decoder.recycle() }
                    } ?: error("ไม่พบภาพต้นฉบับ")
                }
                val first=a; val second=b
                activity.runOnUiThread {
                    if(stopped.get() || activity.isDestroyed) { first?.recycle(); second?.recycle() }
                    else { original=first; enhanced=second; image.setImageBitmap(second); dialog.setTitle("หลัง AI ×${spec.scale}") }
                }
            } catch(e: Exception) {
                a?.recycle(); b?.recycle()
                activity.runOnUiThread { if(!stopped.get() && !activity.isDestroyed) { dialog.dismiss(); activity.notice(e.message ?: "สร้างตัวอย่างไม่ได้") } }
            } catch(e: LinkageError) {
                a?.recycle(); b?.recycle()
                activity.runOnUiThread { if(!stopped.get() && !activity.isDestroyed) { dialog.dismiss(); activity.notice("โหลดระบบ AI ไม่สำเร็จ") } }
            }
        }
    }
}
