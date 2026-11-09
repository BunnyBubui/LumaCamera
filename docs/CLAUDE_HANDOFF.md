# Luma Camera — งานที่เหลือและการส่งต่อให้ Claude

ปรับปรุง 9 พฤศจิกายน 2569 · เจ้าของโปรเจกต์ Ogay · เครื่องเป้าหมาย Reno15 / CPH2825

## สถานะที่ยืนยันแล้ว

- `main` รุ่น 0.2 ถ่าย JPEG / RAW / AI / RAW + AI ได้ รวม JPEG 4/8/16/32 เฟรม มี SR1 ออฟไลน์ เสียงชัตเตอร์ กริด ซูม และคิวกู้คืนงาน
- รุ่น 0.2 ผ่าน 17 unit tests และ Android emulator 7 กรณี รวม RAW + AI ภาพ 4/32 เฟรม และการย้ายฐานข้อมูล
- โค้ดรุ่น 0.3 กำลังพัฒนาใน `v0.3-studio` และคอมไพล์ผ่านแล้ว กำลังตรวจ lint/tests/emulator สถานะ “เขียนแล้ว” ยังไม่ใช่ผลตรวจรับคุณภาพบนเครื่องจริง
- ยังไม่มีผลทดสอบคุณภาพ/ความร้อน/เลนส์จาก Reno15 จริง ห้ามใช้สเปก OPPO แทนความสามารถที่ Android เปิดให้แอป

## ผลตรวจล่าสุด

ดูผลที่เป็นปัจจุบันจาก Checks ใน [PR #3](https://github.com/BunnyBubui/luma-camera/pull/3) และ [PR #4](https://github.com/BunnyBubui/luma-camera/pull/4) โค้ดที่เชื่อม HDR ล่าสุดผ่าน 41 JVM tests, lint และสร้าง app/test APK ในเครื่องแล้ว รอบ Android ที่เชื่อม HDR ผ่าน 12/13 กรณี รวม HDR/สถานะปุ่ม/RAW Lab/เสียงวิดีโอ/PDF ส่วน SR1 ส่งออก 640×480 ได้แต่มี 26/30 เฟรม จึงเพิ่ม decoder backpressure และตรวจจำนวนเฟรม/เวลาให้ตรงต้นฉบับในรอบถัดไป ต้องดูผล CI รอบแก้นี้ก่อนถือว่าผ่าน ยังไม่มีผลตรวจรับ Reno15 จริง

## ความคืบหน้าหลังรับงาน Claude B/C

- **B ส่งแล้วใน PR #4**: ทบทวนโมดูล HDR และเชื่อม `HdrCapture.kt` แล้ว ปรับ histogram ก่อน alignment, รายงานจำนวนเฟรมจริง, เพิ่มการยกเลิกและตรวจงบ scratch ขั้นต่ำ ผลลัพธ์เป็น JPEG SDR exposure fusion ไม่ใช่ Ultra HDR/gain map ตัวเลข benchmark ของ Claude เป็นผล desktop เท่านั้น
- **C ส่งแล้วใน PR #5**: มีแผนทดสอบเครื่องจริงและรีวิว UI รุ่น 0.2 ทุกผลเครื่องจริงยังเป็น “ยังไม่ได้ทดสอบ” เก็บรีวิวเดิมไว้พร้อมบันทึกการแก้ใน 0.3
- **A ยังไม่ได้ทำ**: ให้ Claude เริ่ม OCR ไทยตาม API ด้านล่างได้ทันที โดยดึง `v0.3-studio` ล่าสุดก่อนทำงาน
- Codex แก้ปุ่มเลือกให้มีพื้นหลัง/ตัวหนาและสถานะ accessibility, เป้าสัมผัสอย่างน้อย 48 dp, flash AUTO, ตัวเลือก RAW ตามความสามารถกล้อง และพื้นที่ก่อนถ่าย ยังต้องตรวจ TalkBack/ฟอนต์ใหญ่/ประสิทธิภาพบนเครื่องจริง

## แบ่งงานเพื่อทำพร้อมกัน

Codex เป็นผู้รวมงาน ดูแล Activity, UI, camera session, Gradle, manifest, GitHub Actions และการส่ง APK ส่วน Claude ทำโมดูลแยกพร้อม tests ใน branch ของตนเอง เปิด PR มาที่ `v0.3-studio` และไม่ merge เอง

| งาน | สถานะตอนส่งต่อ | ผู้รับผิดชอบ / ขอบเขต |
|---|---|---|
| UI สีดำ/ขาว/เหลือง แถบปัดโหมด More แผงตั้งค่าด่วน ไม้บรรทัดซูม | Build/lint ผ่าน เพิ่ม selected accessibility แล้ว รอตรวจเครื่องจริง | Codex: `CameraActivity.kt`, `StudioUi.kt`, `Ui.kt`, `CompositionOverlay.kt` |
| ตั้งเวลา อัตราส่วน ปุ่มเสียง แสงเซลฟี่ ลายน้ำ GPS โฟลเดอร์ | เขียนบางส่วน รอเชื่อมและทดสอบทุกทางถ่าย | Codex: `StudioSettings.kt`, `PhotoEffects.kt`, `PhotoMedia.kt`, `BurstCapture.kt` |
| Video เสียง/ปิดไมค์ พัก/ต่อ HDR กันสั่น ซูม A→B/Dual-view/Movie | เขียนแล้ว รอ build และทดสอบไฟล์จริง | Codex: `VideoActivity.kt`, `ClipInspector.kt`, `MediaLedger.kt` |
| Slow-motion / Time-lapse | เขียน Camera2/MediaRecorder แล้ว ยังไม่ทดสอบเครื่องจริง | Codex: `SpecialVideoActivity.kt` |
| PRO ภาพ/วิดีโอ ISO/shutter/WB/focus/EV | เขียนตัวควบคุมแล้ว ต้องตรวจค่าที่กล้องใช้จริงและการคืน AUTO | Codex: `ManualControls.kt` |
| HDR / Night / Portrait / Ultra HDR / HI-RES | มี OEM gating และ fallback เริ่มต้น ยังต้องปรับคุณภาพ | Codex ดูแล capture/session; Claude รับโมดูล HDR แยกด้านล่าง |
| Document แก้ perspective หลายหน้า PDF / OCR Latin | เขียนแล้ว รอทดสอบ; OCR ไทยยังไม่ได้ทำ | Codex ดูแล `DocumentActivity.kt`; Claude รับ Thai OCR แยก |
| Panorama ORB/homography/feather | เขียนแล้ว ต้องทดสอบรอยต่อ ภาพไม่ซ้อนพอ และหน่วยความจำ | Codex: `PanoramaEngine.kt` |
| RAW video DNG sequence + WAV + timestamps + recovery | เขียนและ build แล้ว ยังต้องวัด RAW video throughput/A/V sync บนเครื่องจริง | Codex: `RawLabActivity.kt`, `PcmAudio.kt` |
| RAW linear fusion / Bayer / WB / SR1 | เชื่อมแล้ว Android flow RAW AI 4 DNG ผ่านในรอบก่อนรวม; ยังไม่ตรวจสี/คุณภาพจริง | Codex: `RawBayer.kt`, `LinearRawFusion.java` |
| แต่งคลิป AI SR1 / Luma Log / LUT / codec ส่งออก | Build/lint ผ่าน กำลังตรวจการส่งออก SR1 บน emulator; ไม่ใช่ Log จากเซนเซอร์ | Codex: `ClipEditorActivity.kt`, `LumaVideoEffect.kt` |
| Build/lint/tests/emulator/APK และการรวม branch | Build/lint/JVM 41 ผ่าน กำลังตรวจ emulator รอบรวม | Codex |
| คุณภาพจริง Reno15/หลายยี่ห้อ, 60/120 Hz, HDR สี, fps, เสียง, ความร้อน | ยังไม่ได้วัด | ต้องมีเครื่อง/ไฟล์ตัวอย่างจริง; Claude เตรียมวิธีวัดได้ |

## งาน Claude A — OCR ไทย + อังกฤษแบบออฟไลน์

ลำดับแรกที่ช่วยได้โดยไม่ชนไฟล์ Codex

- สร้าง `app/src/main/java/th/ogay/luma/ocr/ThaiOcrEngine.kt` และ tests/resources ที่เกี่ยวข้อง
- สัญญาที่ต้องการ: package `th.ogay.luma.ocr`, class `ThaiOcrEngine`, `fun recognize(context: Context, bitmap: Bitmap): String` ทำงานแบบ blocking บน worker thread, คืนข้อความ UTF-8, โยน exception ที่อธิบายได้เมื่อโมเดลไม่พร้อม
- เลือก runtime/โมเดลที่ตรวจ license, SHA-256, ABI และ 16 KB page-size compatibility ได้ โมเดลต้องมาพร้อม APK หรือดาวน์โหลดโดยผู้ใช้กดเลือกพร้อมเห็นขนาดและสถานะ การอ่านภาพต้องไม่อัปโหลดขึ้นบริการภายนอก
- ทดสอบข้อความไทย/อังกฤษ ตัวเลข เครื่องหมาย บรรทัดเอียง และภาพที่ไม่มีข้อความ อธิบาย CER/WER และชุดข้อมูลที่ใช้ ไม่สร้างผล benchmark สมมติ
- ไม่แก้ `DocumentActivity.kt`, `app/build.gradle.kts`, manifest หรือ workflow ให้ใส่ dependency/model-install instructions ใน `docs/claude/THAI_OCR_INTEGRATION.md` แล้ว Codex จะเชื่อม UI และ build
- เกณฑ์ผ่าน: offline หลังติดตั้งโมเดล, ยกเลิก/timeout ได้, แจ้งโมเดลหายและพื้นที่ไม่พอ, มีภาพ fixture ที่ใช้สิทธิ์ได้และผลทดสอบที่รันจริง

## งาน Claude B — HDR fusion ที่รับมือการเคลื่อนไหว

- สร้าง `app/src/main/java/th/ogay/luma/hdr/HdrFusionQuality.java` ซึ่งเป็น pure Java ไม่มี Android dependencies
- สัญญา `public static int[] merge(int[][] frames, int width, int height, int[] dx, int[] dy, float[] exposureEv)`
- `frames[i]` คือ ARGB8888 แบบ row-major ขนาด `width * height` เท่ากัน; `exposureEv[i]` เป็นค่าจริงจาก bracket; `dx/dy` ใช้ mapping reference `(x,y)` → candidate `(x+dx[i],y+dy[i])`; frame 0 เป็น reference
- คืน ARGB8888 SDR ขนาดเดิม รักษาขอบและบริเวณที่ไม่มี overlap มี motion rejection อิงภาพอ้างอิง ป้องกัน clipping/halo และกำหนดเพดาน memory
- เพิ่ม JVM tests ที่มีคุณค่า: highlight/shadow recovery, moving subject, translated edges, flat scenes, clipping และ invalid inputs พร้อมอธิบายข้อจำกัด JPEG exposure fusion
- ไม่แก้ `HdrCapture.kt`, `FrameFusionEngine.java`, Gradle หรือ UI ให้เขียน `docs/claude/HDR_INTEGRATION.md` วิธีแทนที่ขั้น merge เท่านั้น
- ห้ามอ้างว่าส่งออก Ultra HDR/gain map หรือคุณภาพเทียบ OPPO หากไม่มี pipeline/ผลวัดรองรับ

## งาน Claude C — แผนทดสอบเครื่องจริงและตรวจทานแบบ read-only

- เขียน `docs/claude/RENO15_TEST_PROTOCOL.md` และ `docs/claude/UI_REVIEW.md`
- ครอบคลุมเปลี่ยนกล้อง/โหมด, แตะเร็ว, ลด motion, TalkBack, ฟอนต์ใหญ่, หมุนจอ, ปฏิเสธสิทธิ์, กลับจากล็อกจอ, AI พร้อมถ่าย, พื้นที่เต็ม และ process death
- วัด fps จาก sample timestamps, A/V sync จากเสียง/ภาพอ้างอิง, raw dropped frames / timestamp source / write MB/s / ความร้อน และคู่ resolution+fps+HDR+stabilization ที่ใช้ได้จริง
- UI ให้อิงความคุ้นเคยจากกล้อง OPPO แต่รักษาชื่อ Luma; ไม่เสนอปุ่มที่กดแล้วทำงานไม่ได้ และไม่ใช้เลขซูมแทนชื่อเลนส์โดยไม่มี physical camera ID
- หากไม่มี Reno15 ให้ส่ง protocol/ข้อค้นพบจาก source พร้อมสถานะ “ยังไม่ได้ทดสอบ” ไม่ใส่ตัวเลขผลทดลองสมมติ

## กติกา branch และการส่งงาน

1. อ่าน `main` และเอกสารนี้ก่อน เริ่มจาก `v0.3-studio` ล่าสุด หากโค้ดส่วนนั้นยังไม่เข้า branch ให้ทำเฉพาะ module/API ที่กำหนด
2. ใช้ branch `claude/thai-ocr`, `claude/hdr-fusion` หรือ `claude/device-qa` ไม่ push ทับ `main` หรือ branch ของ Codex
3. ถ้าต้องเปลี่ยนสัญญา API ให้เสนอใน PR ก่อนแก้ไฟล์ร่วม ส่ง dependency/asset integration เป็นเอกสาร ไม่แตะ Gradle พร้อม Codex
4. PR ระบุสิ่งที่ทำ ผล tests ที่รันจริง รายการที่ยังไม่ได้ทดสอบ และความเสี่ยงด้าน memory/สี/ความเข้ากันได้
5. Codex จะ review เชื่อมโมดูล รัน build/lint/JVM/emulator และรวมงาน หลังจากนั้นจึงทดสอบคุณภาพบนเครื่องจริง

## คำสั่งตรวจพื้นฐาน

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

ใช้ JDK 17, compile SDK 36, AGP 8.9.2, Kotlin 2.1.20, Gradle 8.11.1, CameraX 1.6.2 งาน RAW video, HDR/สี, high-speed และ dual-camera ต้องมีเกณฑ์ทดสอบบน hardware เพิ่มจาก emulator

## ข้อจำกัดที่ต้องคงไว้ในการสื่อสาร

- RAW+AI รุ่น 0.2 เก็บ DNG แรกแยก และ fusion จาก JPEG; RAW linear fusion รุ่นใหม่อยู่ในโหมดทดลองแยก
- การมี DNG ภาพเดียวไม่ได้ยืนยันว่า RAW video ต่อเนื่องได้ตาม fps ที่ร้องขอ
- Ultra HDR, HDR video, Log, RAW video และ JPEG SDR เป็นคนละรูปแบบ ต้องแสดงชื่อและ metadata ตามจริง
- เป้าหมายรุ่น 0.3 คือมีเส้นทางทำงานจริงและหลักฐานทดสอบของแต่ละโหมด งานที่ยังทดสอบไม่ได้ต้องระบุให้ชัดก่อนส่งมอบ
