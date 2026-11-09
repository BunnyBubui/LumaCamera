# OCR ไทย + อังกฤษแบบออฟไลน์ — วิธีเชื่อมเข้าแอป

เอกสารนี้อธิบายงาน Claude A ใน `docs/CLAUDE_NEXT_TASKS.md` ได้แก่ โมดูล `th.ogay.luma.ocr` ซึ่งอ่านข้อความไทย อังกฤษ ตัวเลข และเครื่องหมายจากภาพบนเครื่อง เอกสารบอกว่าโมดูลทำงานอย่างไร ผลทดสอบที่รันจริงเป็นอย่างไร และ Codex ต้องทำอะไรเพื่อเชื่อมโมดูลเข้ากับ `DocumentActivity` งานนี้ไม่ได้แก้ `DocumentActivity.kt`, Gradle, manifest, workflow หรือ UI กลาง

## สรุปการตัดสินใจ

ML Kit Text Recognition v2 ที่แอปใช้อยู่ รองรับเฉพาะอักษรละติน จีน เทวนาครี ญี่ปุ่น และเกาหลี ไม่รองรับภาษาไทย โมดูลนี้จึงใช้โมเดล PaddleOCR PP-OCRv5 สองตัว ได้แก่ ตัวหากรอบข้อความ (`PP-OCRv5_mobile_det`) และตัวอ่านภาษาไทย (`th_PP-OCRv5_mobile_rec`) ซึ่ง PaddleOCR ระบุว่าอ่านภาษาไทย อังกฤษ และตัวเลขได้

โมดูลรันโมเดลด้วยโมดูล DNN ของ OpenCV 4.12 ที่แอปมีอยู่แล้ว (`org.opencv:opencv:4.12.0`) **จึงไม่ต้องเพิ่ม dependency หรือ native library ใหม่** และไม่ต้องแก้ Gradle โมเดลมากับ APK ใน `app/src/main/assets/ocr/` จึงใช้งานได้ทันทีโดยไม่ต้องดาวน์โหลด และไม่มีการส่งภาพออกจากเครื่อง

ผมเทียบกับ Tesseract 5.3.4 (`tha+eng` จาก tessdata_fast) บนชุดภาพทดสอบเดียวกันด้วย Tesseract ได้ pooled CER 0.123 ขณะที่ PP-OCRv5 ได้ 0.009 และ Tesseract อ่านภาพกระดาษเปล่าออกมาเป็นตัวอักษรขยะ 131 ตัว ส่วน PP-OCRv5 คืนข้อความว่าง นอกจากนี้ Tesseract ต้องเพิ่ม native library ใหม่ ซึ่งต้องแก้ Gradle

## API

```kotlin
package th.ogay.luma.ocr

class ThaiOcrEngine {
    fun recognize(context: Context, bitmap: Bitmap): String                       // API ตามสัญญา
    fun recognize(context: Context, bitmap: Bitmap, timeoutMs: Long,
                  isCancelled: () -> Boolean): String                               // overload เพิ่ม
    fun prepare(context: Context)                                                   // โหลดโมเดลล่วงหน้า
    fun close()                                                                     // คืนหน่วยความจำ
    companion object { const val NO_TIMEOUT: Long }
}
```

`recognize` คืนข้อความ UTF-8 ที่คั่นบรรทัดด้วย `\n` และคั่นกล่องข้อความในบรรทัดเดียวกันด้วยช่องว่างหนึ่งตัว ถ้าภาพไม่มีข้อความ ฟังก์ชันคืน `""` ฟังก์ชันทำงานแบบ blocking และจะโยน `IllegalStateException` ถ้าถูกเรียกบน main thread

overload ที่สองไม่ได้เปลี่ยน API ตามสัญญา แต่เพิ่มการยกเลิกและเวลาจำกัด โมดูลตรวจทั้งสองอย่างก่อนหากรอบข้อความ และก่อนอ่านข้อความทุกชิ้น ดังนั้นการหยุดจะช้าไม่เกินการรันโมเดลหนึ่งครั้ง (บนคอมพิวเตอร์ประมาณ 0.05–0.15 วินาทีต่อครั้ง บนมือถือยังไม่ได้วัด) การ interrupt thread ก็นับเป็นการยกเลิกเช่นกัน

ข้อผิดพลาดทุกแบบเป็นคลาสลูกของ `ThaiOcrException` และมีข้อความภาษาไทยที่แสดงให้ผู้ใช้ได้ทันที

| Exception | เกิดเมื่อ | ข้อความ |
|---|---|---|
| `ThaiOcrModelException` | ไม่พบไฟล์โมเดล, SHA-256 ไม่ตรง (ไฟล์เสีย), OpenCV เปิดไม่ได้หรือโหลดโมเดลไม่ได้, จำนวนคลาสไม่ตรงพจนานุกรม | เช่น "ไฟล์โมเดล OCR เสียหาย (…) ติดตั้งแอปใหม่เพื่อกู้คืน" |
| `ThaiOcrCancelledException` | `isCancelled()` คืน true หรือ thread ถูก interrupt | "ยกเลิกการอ่านข้อความแล้ว" |
| `ThaiOcrTimeoutException` | ใช้เวลาเกิน `timeoutMs` | "อ่านข้อความนานเกิน N วินาที ลองครอปให้เหลือเฉพาะส่วนที่มีข้อความ" |
| `ThaiOcrException` | หน่วยความจำไม่พอ (`OutOfMemoryError`) | "หน่วยความจำไม่พอสำหรับภาพนี้ ลองครอปหรือย่อภาพก่อนอ่านข้อความ" |

เรื่องพื้นที่เก็บข้อมูลไม่พอไม่เกิดกับโมดูลนี้ เพราะโมดูลโหลดโมเดลจาก asset เข้าหน่วยความจำโดยตรง ไม่ได้แตกไฟล์ลงดิสก์

**Thread safety:** ใช้ instance เดียวร่วมกันได้ทั้งแอป แต่การเรียกบน instance เดียวกันจะทำทีละครั้ง เพราะ `cv::dnn::Net` ไม่ปลอดภัยเมื่อหลาย thread ใช้พร้อมกัน ถ้าต้องการอ่านหลายภาพพร้อมกันจริง ให้สร้างหลาย instance ซึ่งแต่ละ instance ใช้หน่วยความจำของตัวเอง

**หน่วยความจำ:** ไฟล์โมเดลรวม 12.7 MB โมเดลถูกโหลดครั้งแรกที่เรียกใช้ (หรือเมื่อเรียก `prepare`) แล้วค้างอยู่จนกว่าจะเรียก `close()` ระหว่างอ่านภาพ 12 MP โมดูลแปลงภาพเป็น `Mat` แบบ RGBA (48 MB) แล้วเป็น BGR (36 MB) ชั่วคราว ภาพที่ส่งเข้าหาข้อความถูกย่อให้ด้านยาวไม่เกิน 1280 พิกเซล

## ขั้นตอนการทำงาน

1. แปลง `Bitmap` เป็น BGR (รองรับ `ARGB_8888`, `RGB_565` และ `HARDWARE` ซึ่งถูกคัดลอกก่อน)
2. ย่อภาพให้ด้านยาวไม่เกิน 1280 พิกเซล ด้านสั้นไม่ต่ำกว่า 64 และเป็นพหุคูณของ 32 แล้วรันตัวหากรอบข้อความ
3. แปลงแผนที่ความน่าจะเป็นเป็นกรอบแบบ DBNet (threshold 0.3, box score 0.6, unclip 1.5) ตามค่าใน `OCR.yaml` ของ PaddleX
4. หามุมเอียงของหน้า แล้วตัดกรอบเล็กที่อยู่ติดบรรทัดที่สูงกว่าอย่างน้อยสองเท่าทิ้ง กรอบแบบนี้มักเป็นวรรณยุกต์หรือสระที่โมเดลแยกกรอบออกมา ซึ่งถ้าไม่ตัดจะถูกอ่านเป็นตัว "ญ"
5. ตัดแต่ละกรอบด้วย perspective transform ถ้าบรรทัดกว้างเกิน 640/48 เท่าของความสูง โมดูลจะตัดบรรทัดเป็นชิ้นที่คอลัมน์ที่มีหมึกน้อยที่สุด
6. รันตัวอ่านข้อความที่ความกว้างคงที่ 640 พิกเซล แล้วถอดรหัส CTC แบบ greedy และทิ้งชิ้นที่ความมั่นใจเฉลี่ยต่ำกว่า 0.5
7. เรียงบรรทัดในกรอบอ้างอิงที่หมุนตามมุมเอียงของหน้า ภาพที่เอียง 15° จึงยังแยกบรรทัดได้ถูก

ไฟล์ `OcrLayout.kt` ไม่ใช้ Android หรือ OpenCV จึงทดสอบบน JVM ได้ ส่วน `model/ocr/thai_ocr_ref.py` คือขั้นตอนเดียวกันที่เขียนด้วย Python บน `cv2.dnn` เพื่อใช้เทียบผล

## โมเดล license และ native library

| ไฟล์ | ขนาด | ที่มา | License |
|---|---|---|---|
| `ppocrv5_mobile_det.onnx` | 4.8 MB | PP-OCRv5_mobile_det จาก release v0.3.0 ของ GreatV/oar-ocr (ไม่แก้ไข) | Apache-2.0 |
| `th_ppocrv5_mobile_rec_w640.onnx` | 7.8 MB | th_PP-OCRv5_mobile_rec จาก release เดียวกัน แล้วกำหนด input เป็น 1×3×48×640 ด้วย onnxsim 0.8.1 | Apache-2.0 |
| `ppocrv5_th_dict.txt` | 1.8 KB | PaddleOCR commit `dab3fe3` | Apache-2.0 |

SHA-256 เต็มอยู่ใน `model/ocr/SHA256SUMS` และอยู่ในโค้ด (`ThaiOcrEngine.DETECTOR/RECOGNIZER/DICTIONARY`) โมดูลตรวจค่านี้ทุกครั้งที่โหลด เทสต์ JVM `bundledModelsMatchTheirChecksums` ก็ตรวจว่าไฟล์ใน repo ตรงกับค่าในโค้ด `model/ocr/prepare_models.py` สร้างไฟล์ทั้งสามซ้ำได้ ผมรันซ้ำแล้วได้ไบต์เดิม

ต้องแปลงตัวอ่านข้อความเพราะ OpenCV 4.12 นำเข้าไฟล์ต้นฉบับไม่ได้ (error: "dynamic 'zero' shapes are not supported") หลังแปลง ผลของ OpenCV ตรงกับ onnxruntime ต่างกันไม่เกิน 2.8×10⁻⁶ และตัวอักษรที่ทายตรงกัน 100% บน input สุ่ม

ข้อจำกัดเรื่องที่มา: ไฟล์ ONNX ทั้งสองถูก export โดยโปรเจกต์ oar-ocr จาก weights ทางการของ PaddlePaddle ผมยังไม่ได้เทียบกับไฟล์ที่ export จาก PaddlePaddle โดยตรง เพราะเครื่องที่ผมใช้เข้า CDN ของ Paddle และ Hugging Face ไม่ได้ ถ้าต้องการยืนยัน ให้ export ด้วย `paddlex --paddle2onnx` บนเครื่องที่เข้าได้ แล้วรัน `model/ocr/thai_ocr_ref.py` เทียบผลกับไฟล์ปัจจุบัน

Apache-2.0 กำหนดให้แสดง license และ notice เมื่อแจกจ่าย Codex ควรเพิ่ม PaddleOCR (Apache-2.0) ลงในหน้า license หรือหน้าเกี่ยวกับแอป

**ABI และ 16 KB pages:** โมดูลนี้ไม่มี native code ของตัวเอง native code ที่ใช้คือ `libopencv_java4.so` ของ OpenCV ที่แอปมีอยู่แล้ว ผมตรวจ SDK 4.12.0 ทางการจาก GitHub release แล้ว พบว่ามี arm64-v8a, armeabi-v7a, x86 และ x86_64 และ segment `LOAD` ของ arm64-v8a จัดแนวที่ `0x4000` (16 KB) ตามที่ Android กำหนด ผมยังไม่ได้ตรวจไฟล์จาก Maven artifact ที่ Gradle ดึงจริง ให้ Codex ตรวจ APK ที่ build แล้วด้วยคำสั่ง

```bash
unzip -o app/build/outputs/apk/debug/app-debug.apk 'lib/arm64-v8a/*' -d /tmp/apk
readelf -lW /tmp/apk/lib/arm64-v8a/libopencv_java4.so | grep LOAD    # คอลัมน์สุดท้ายต้องเป็น 0x4000
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

**ขนาด APK:** asset เพิ่มขึ้น 12.7 MB ถ้าบีบอัดด้วย deflate จะเหลือประมาณ 11.8 MB

## วิธีเชื่อมกับ DocumentActivity (สำหรับ Codex)

ปุ่ม "OCR Latin" ปัจจุบันเรียก ML Kit ใน `DocumentActivity.ocr()` ผมเสนอให้เพิ่มปุ่ม "อ่านข้อความไทย" ที่เรียกโมดูลนี้บน worker thread ด้วยภาพที่ผ่าน `rectify()` แล้ว เพราะภาพที่แก้มุมและครอปแล้วทำให้ตัวอักษรใหญ่ขึ้นเมื่อเทียบกับขนาดที่ตัวหากรอบรับ ตัวอย่างโค้ด:

```kotlin
private val thaiOcr = ThaiOcrEngine()            // เก็บไว้ทั้ง Activity หรือทั้งแอป
@Volatile private var ocrCancelled = false

private fun ocrThai() {
    if (busy) return
    val bitmap = runCatching { rectify() }.getOrElse { status.text = it.localizedMessage; return }
    busy = true; ocrCancelled = false; status.text = "กำลังอ่านข้อความไทย/อังกฤษบนเครื่อง…"
    Thread {
        val result = runCatching { thaiOcr.recognize(this, bitmap, 60_000) { ocrCancelled || isDestroyed } }
        bitmap.recycle()
        runOnUiThread {
            busy = false
            result.onSuccess { text -> /* แสดงและส่งออกแบบเดียวกับ OCR Latin */ }
                .onFailure { e -> status.text = (e as? ThaiOcrException)?.message ?: "อ่านข้อความไม่สำเร็จ" }
        }
    }.start()
}
override fun onDestroy() { ocrCancelled = true; super.onDestroy() }
```

ข้อความ UI ที่เสนอ: ปุ่ม "อ่านข้อความไทย", สถานะระหว่างทำงาน "กำลังอ่านข้อความไทย/อังกฤษบนเครื่อง…", เมื่อไม่พบข้อความ "ไม่พบข้อความในภาพ ลองถ่ายให้ใกล้ขึ้นหรือเพิ่มแสง", และใต้ผลลัพธ์ "อ่านบนเครื่อง ไม่ส่งภาพออกไป · ตรวจทานข้อความก่อนใช้"

ถ้าต้องการให้เปิดหน้าเร็ว ให้เรียก `thaiOcr.prepare(context)` บน worker thread ตอนเปิด `DocumentActivity` การโหลดครั้งแรกรวมการตรวจ SHA-256 ของไฟล์ 12.7 MB

## ผลทดสอบ

### JVM (รันจริงบนเครื่องที่ผมใช้ทำงาน)

ผมคอมไพล์ไฟล์ Kotlin ทั้งหมดใน `th.ogay.luma.ocr` ด้วย kotlinc 2.1.20 (`-Werror`) กับคลาส Java ของ OpenCV 4.12.0 จาก SDK ทางการ และ stub ของคลาส Android แล้วรัน `ThaiOcrLayoutTest` ผ่าน 12/12 การคอมไพล์นี้ยืนยันว่าทุกเมธอด OpenCV ที่โค้ดเรียกมีอยู่จริงใน 4.12.0 แต่ไม่ได้รัน native code ของ OpenCV

### Desktop reference (Python + `cv2.dnn` 4.12.0)

คำสั่ง: `python3 model/ocr/thai_ocr_ref.py app/src/main/assets/ocr app/src/androidTest/assets/ocr`

| ภาพ | สิ่งที่ทดสอบ | CER | WER |
|---|---|---|---|
| sign_th | ป้าย ตัวหนา 64 px | 0.000 | 0.000 |
| document_th | ย่อหน้า 5 บรรทัด | 0.000 | 0.000 |
| receipt_mixed | ไทย/อังกฤษ/ตัวเลข/เครื่องหมาย/อีเมล | 0.007 | 0.042 |
| english_ui | อังกฤษล้วน | 0.000 | 0.000 |
| thai_digits | เลขไทย | 0.026 | 0.125 |
| tone_marks_serif | วรรณยุกต์ซ้อน ฟอนต์มีหัว | 0.013 | 0.200 |
| small_text | ตัวอักษรสูงประมาณ 16 px | 0.013 | 0.500 |
| low_contrast | ตัวเทาบนพื้นเทา | 0.056 | 1.500 |
| photo_skewed | เอียง 6° + perspective + blur + noise + JPEG 60 | 0.000 | 0.400 |
| tilted_15deg | เอียง 15° | 0.000 | 0.000 |
| blank_paper | ไม่มีข้อความ | คืน "" | — |
| **รวม** | 1,113 ตัวอักษร | **0.009** | — |

CER นับหลัง normalize (NFC, รวม ํ+า เป็น ำ, ตัด zero-width) และไม่นับช่องว่าง เพราะภาษาไทยไม่เว้นวรรคระหว่างคำ WER แยกคำด้วยช่องว่าง จึงมีความหมายกับภาษาอังกฤษ แต่สำหรับภาษาไทยเป็นระดับวลีเท่านั้น WER ของ low_contrast สูงเพราะมีบรรทัดขยะหนึ่งบรรทัดที่เกิดจากเศษวรรณยุกต์

### Android emulator

`ThaiOcrDeviceTest` รันโมเดลจริงผ่าน OpenCV บน Android กับภาพชุดเดียวกัน และตรวจการยกเลิก, timeout, การปฏิเสธ main thread และ bitmap แบบ `RGB_565` / `HARDWARE` เกณฑ์ CER ในเทสต์ตั้งจากผล desktop บวกค่าเผื่อ เพื่อจับการถดถอย ไม่ได้เป็นตัวชี้คุณภาพบนภาพจริง ผลจาก CI อยู่ในหัวข้อถัดไป

<!-- CI_RESULTS -->

### สิ่งที่ยังไม่ได้ทดสอบ

ภาพทุกภาพในชุดทดสอบเป็นภาพสังเคราะห์ที่เรนเดอร์จากข้อความที่ผมเขียนเองด้วยฟอนต์ SIL OFL **ยังไม่มีผลจากภาพถ่ายจริง** และยังไม่ได้วัดเวลาหรือหน่วยความจำบน Reno15 PaddleOCR รายงานความแม่นยำระดับบรรทัด (ทุกตัวอักษรในบรรทัดต้องถูก) ของ th_PP-OCRv5_mobile_rec ไว้ที่ 82.68% บนชุดข้อมูลภาษาไทยของเขา ตัวเลขนี้ใกล้กับสิ่งที่ควรคาดบนภาพจริงมากกว่าผลภาพสังเคราะห์ด้านบน

ให้ทดสอบเพิ่มบนเครื่องจริงด้วยภาพเหล่านี้: ป้ายร้านค้า ใบเสร็จความร้อนที่ซีด เอกสารราชการฟอนต์ TH Sarabun เมนูที่มีพื้นลาย และหน้าหนังสือทั้งหน้า ให้เก็บภาพกับข้อความที่ถูกต้องไว้ แล้วรัน `thai_ocr_ref.py` หรือ `ThaiOcrDeviceTest` เพื่อวัด CER

## ข้อจำกัดที่รู้แล้ว

- ภาพเต็มหน้าที่ถ่ายจากระยะไกลถูกย่อเหลือด้านยาว 1280 พิกเซลก่อนหาข้อความ ตัวอักษรเล็กจึงอาจหายไป ควรครอปหรือแก้มุมก่อนเรียกใช้
- ตัวอักษรตัดกันต่ำทำให้เกิดบรรทัดขยะสั้น ๆ ได้ (ดู low_contrast)
- ไม่รองรับข้อความแนวตั้งที่อ่านจากบนลงล่าง และยังไม่ได้ทดสอบลายมือ
- การตัดบรรทัดยาวเลือกคอลัมน์ที่มีหมึกน้อยที่สุด ถ้าบรรทัดนั้นไม่มีช่องว่างระหว่างตัวอักษรเลย ตำแหน่งตัดอาจผ่ากลางตัวอักษร
- ตัวกรองเศษวรรณยุกต์อาจตัดข้อความเล็กที่อยู่ติดบรรทัดตัวใหญ่ภายในระยะหนึ่งความสูงบรรทัด
- ตัวอ่านข้อความสับสน I กับ เ ได้ในบางฟอนต์ (เช่น "INV" อ่านเป็น "เNV" ในภาพ receipt_mixed)
