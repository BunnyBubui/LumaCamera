# Luma Camera — งานรอบถัดไปสำหรับ Claude

> **แผนรอบปัจจุบัน:** [VIDEO_STABILITY_PLAN.md](VIDEO_STABILITY_PLAN.md) — Ogay ให้ Claude ดูแล UI ที่กำลังทำต่อ ส่วน Codex ดูแล rotation/FPS/Slow-motion/session performance กติกา D แบบ read-only ด้านล่างเป็นของรอบก่อน ไม่จำกัดงาน UI ที่ผู้ใช้มอบหมายล่าสุด
>
> เนื้อหาด้านล่างเป็น snapshot ก่อนรวม PR #8 (OCR/UI integration) สถานะ A/D เดิมไม่ใช่รายการค้างปัจจุบัน ให้ตรวจ source ของ `v0.3-studio` ล่าสุดก่อนทำซ้ำ

ปรับปรุง 9 พฤศจิกายน 2569 · เริ่มจาก `v0.3-studio` ล่าสุด · เครื่องเป้าหมาย Reno15 / CPH2825

## จุดเริ่มต้นที่ยืนยันแล้ว

- งาน B (HDR) ใน PR #4 และงาน C (protocol/รีวิว UI รุ่นเก่า) ใน PR #5 รวมเข้า `v0.3-studio` แล้ว ไม่ต้องทำสองงานนี้ซ้ำ
- โค้ดที่ตรวจ CI คือ `c0895d04c1af31373dd9ee33c7d20bea6364eb72` และรวมเข้า branch ที่ `6f69845924eec9faab25d9a6d132dbf69e88a690`
- [Build/lint/JVM](https://github.com/BunnyBubui/luma-camera/actions/runs/42554314508) ผ่าน 41 tests รวม HDR 16 tests
- [Android API 30 emulator](https://github.com/BunnyBubui/luma-camera/actions/runs/42554314489) ผ่าน 13/13 ไม่มี skip: JPEG/RAW/AI 4–32 เฟรม, recovery, PDF, HDR, selected semantics, RAW Lab, วิดีโอมีเสียง และ GPU SR1 ส่งออก 640×480 ครบ 30 เฟรมพร้อมเวลาตรงต้นฉบับ
- ยังไม่มีผลคุณภาพ/ความเร็ว/ความร้อนจาก Reno15 จริง และ emulator ใช้ software decoder สำหรับ export เพื่อเลี่ยง Goldfish surface EOS loss

## แบ่งเจ้าของไฟล์

| ชุด | ผู้รับผิดชอบ | งานที่ยังไม่เสร็จ | ส่งมอบ |
|---|---|---|---|
| A — ทำก่อน | Claude | OCR ไทย + อังกฤษออฟไลน์ | โมดูล OCR, fixtures/tests, เอกสารติดตั้ง dependency/model |
| D — หลัง A หรือแยก session | Claude | ตรวจ UI รุ่น 0.3 ที่มีโค้ดแล้ว โดยเฉพาะฟอนต์ใหญ่/การเข้าถึง | รีวิวใหม่พร้อม commit/หลักฐาน และ test file แยกถ้ารันได้ |
| E — หลัง A หรือแยก session | Claude | ตรวจ panorama ด้วยภาพที่รู้คำตอบ และกรณีต่อภาพไม่ได้ | Android tests/fixtures ที่สร้างเอง + ผลรันหรือระบุว่ายังไม่รัน |
| Core/integration | Codex | Activity/UI, camera session, Media3, Gradle/manifest/CI, รวมโมดูล, แก้ปัญหาจาก tests, APK | โค้ดแอปและ build ที่ตรวจได้ |
| Hardware acceptance | Ogay มี Reno15; Claude ช่วยวิเคราะห์ไฟล์ได้ | เลนส์ที่ Android เปิดให้, HDR/RAW สี, fps, A/V sync, ความร้อน, 60/120 Hz | ผลจริงตาม `docs/claude/RENO15_TEST_PROTOCOL.md` |

ถ้า Claude ทำคนเดียว ให้ทำ A → D → E ทีละ PR ถ้าแยกหลาย session ให้แต่ละ session รับเพียงหนึ่งชุดและไม่แก้ไฟล์ของอีกชุด

## A — OCR ไทย + อังกฤษออฟไลน์

Branch: `claude/thai-ocr` · รายละเอียดเดิมยังอยู่ใน `docs/CLAUDE_HANDOFF.md`

ไฟล์ที่เป็นเจ้าของ:

- `app/src/main/java/th/ogay/luma/ocr/ThaiOcrEngine.kt` และ helper ใน package `th.ogay.luma.ocr`
- tests/resources ของ OCR ในชื่อเฉพาะ เช่น `ThaiOcrEngineTest` / `ThaiOcrDeviceTest`
- `docs/claude/THAI_OCR_INTEGRATION.md`

API ที่ Codex จะเชื่อม:

```kotlin
package th.ogay.luma.ocr
class ThaiOcrEngine {
    fun recognize(context: Context, bitmap: Bitmap): String
}
```

ทำงาน blocking บน worker thread คืนข้อความ UTF-8 และรายงานข้อผิดพลาดโมเดลไม่พร้อมให้เข้าใจได้ อธิบายวิธียกเลิก/timeout และขอบเขต thread safety ให้ชัด ถ้าต้องมี overload เพิ่ม ให้คง API ข้างบนไว้และอธิบายใน PR

เกณฑ์รับ:

1. อ่านไทย/อังกฤษ/ตัวเลข/เครื่องหมาย/หลายบรรทัด และจัดการภาพไม่มีข้อความ ทดสอบภาพเอียงและตัวอักษรเล็กด้วย
2. ออฟไลน์หลังติดตั้งโมเดล ไม่มีการส่งภาพออกจากเครื่อง โมเดลมากับ APK หรือให้ผู้ใช้กดดาวน์โหลดโดยเห็นขนาด/สถานะก่อน
3. ระบุแหล่งโมเดล/เวอร์ชัน/license/SHA-256, dependency versions, ABI และหลักฐานรองรับ native 16 KB pages หากมี native runtime
4. มี fixtures ที่ใช้สิทธิ์ได้และ ground truth อธิบาย normalization/CER/WER พร้อมคำสั่งรันและผลที่รันจริง แยกผล synthetic/ภาพจริง
5. ระบุโมเดลหาย/เสียหาย พื้นที่ไม่พอ ยกเลิก และ timeout; ห้ามแสดงว่าอ่านไทยได้เพียงเพราะ OCR Latin build ผ่าน

ไม่แก้ `DocumentActivity.kt`, Gradle, manifest, workflow หรือ UI กลาง ให้เขียนขั้นตอน dependency/asset integration พร้อมข้อเสนอข้อความ UI แล้ว Codex จะเชื่อม ถ้าไม่มี SDK ให้ระบุส่วนที่ยังไม่ได้รัน ไม่สร้างตัวเลขผลทดสอบสมมติ

## D — ตรวจ UI 0.3 และ accessibility รอบใหม่

Branch: `claude/studio-ui-review`

- อ่านโค้ดปัจจุบัน ไม่ใช้เลขบรรทัด/สีของ `9ba0844` แทนรุ่นใหม่ และไม่แก้ทับ `docs/claude/UI_REVIEW.md` ซึ่งเก็บ snapshot เดิม
- อ่าน `CameraActivity`, `StudioUi`, `StudioSettings`, `ManualControls`, `VideoActivity`, `SpecialVideoActivity`, `RawLabActivity`, `DocumentActivity`, `ClipEditorActivity` และ Gallery/Library
- เน้นฟอนต์ 200%, จอแคบ/แนวนอน, bottom sheet เลื่อนครบ, พื้นที่แตะ, ปุ่ม disabled, focus order, selected/stateDescription, ข้อความผลถ่าย และการใช้ zoom ด้วย accessibility actions
- เพิ่ม `app/src/androidTest/java/th/ogay/luma/StudioAccessibilityTest.kt` ได้ โดยไม่แก้ test file/workflow ที่ Codex ดูแล และคืนค่า font scale/rotation ที่เปลี่ยนทุกครั้ง
- ส่ง `docs/claude/UI_REVIEW_V03.md` ระบุ commit, ตำแหน่งไฟล์, วิธีทำซ้ำ, ระดับผลกระทบ, expected/actual และหลักฐาน การมี accessibility node ไม่ใช่ผลฟัง TalkBack จริง การดู source ไม่ใช่ผลวัดความลื่น 60/120 Hz
- ส่ง patch proposal ในเอกสารได้ Codex เป็นผู้แก้ไฟล์ UI กลาง ห้ามประกาศว่าผ่าน Reno15 หากไม่มีเครื่อง/หลักฐาน

## E — Panorama quality และ failure cases

Branch: `claude/panorama-quality`

API ปัจจุบัน: `PanoramaEngine.stitch(context: Context, files: List<File>): File` รับ JPEG 2–12 ภาพ ใช้ OpenCV/ORB/homography และ feather blend

ไฟล์ที่เป็นเจ้าของ:

- `app/src/androidTest/java/th/ogay/luma/PanoramaQualityTest.kt`
- fixtures เฉพาะงานใน `app/src/androidTest/assets/panorama/` หรือ generator ภายใน test
- `docs/claude/PANORAMA_QUALITY.md`

ใช้ภาพ texture ที่สร้างเองด้วย seed คงที่ มีลายไม่สมมาตรและตำแหน่งอ้างอิงชัด ตัดภาพจากฉากเดียวให้ overlap 40–60% แล้วตรวจความกว้าง/รูปทรง ขอบ/รอยต่อ และส่วนซ้ำ เทียบ ground truth ด้วย tolerance ที่อธิบายได้ อย่าตรวจเพียงว่ามีไฟล์ JPEG

เพิ่มกรณีภาพแบน/no features, overlap ไม่พอ, อินพุตเสีย, จำนวนภาพผิด และภาพที่มีการเคลื่อนไหว ระบุว่าอะไรควรคืนผล อะไรควรปฏิเสธ ตรวจว่าไฟล์อินพุตไม่ถูกลบเมื่อ stitching ล้มเหลว แยก performance/memory ของชุดใหญ่จาก smoke tests ขนาดเล็ก

ไม่แก้ `PanoramaEngine.kt`, Activity, Gradle หรือ workflow ถ้าพบปัญหาให้เปิดข้อค้นพบพร้อม fixture/วิธีทำซ้ำใน PR Codex จะแก้ engine และรันรวม ไม่ยกเลิก assertion หรือใส่ skip เพื่อให้ CI เขียวโดยซ่อน bug

## กติกาทุกชุด

- ดึง `v0.3-studio` ล่าสุดก่อนเริ่ม ใช้ branch ของชุดนั้น เปิด PR กลับ `v0.3-studio` และ **ไม่ merge เอง**
- PR ระบุสิ่งที่ทำ/คำสั่งที่รัน/ผลจริง/ส่วนที่ยังไม่ทดสอบ/ข้อจำกัด ไม่แก้ไฟล์ร่วมและไม่เปลี่ยน API ที่ตกลงไว้เงียบ ๆ
- ใช้ JDK 17, compile SDK 36, CameraX 1.6.2, Media3 1.9.0 ตาม Gradle ปัจจุบัน Codex ดูแล build environment และ CI
- ห้ามอ้างคุณภาพเท่ากล้อง OPPO, sensor Log, Ultra HDR gain map หรือ RAW video fps จากชื่อโหมด/จำนวนพิกเซลเพียงอย่างเดียว

## งานต่อยอดที่ยังไม่ได้จัดเป็นงาน implementation

RAW lens shading/hot-pixel calibration, temporal consistency ของ AI video, ปรับโทน/สีจากภาพจริง และปรับ throughput/ความร้อน ต้องมี fixtures/metadata หรือผลเครื่องจริงก่อนเลือกวิธีทำ หาก Claude จะศึกษาก่อน ให้ส่งเป็นข้อเสนอพร้อมหลักฐาน ไม่เปลี่ยน pipeline กลางพร้อม Codex

## พรอมป์ต์ส่งให้ Claude ได้ทันที

> เปิด repo BunnyBubui/luma-camera ดึง branch v0.3-studio ล่าสุด อ่าน docs/CLAUDE_NEXT_TASKS.md และ docs/CLAUDE_HANDOFF.md งาน B/C รวมแล้ว ให้เริ่มงาน A: Thai OCR ออฟไลน์ใน branch claude/thai-ocr ตาม API และเกณฑ์รับ ไม่แก้ Activity/Gradle/manifest/workflow ที่ Codex ดูแล ส่ง integration instructions พร้อม tests/fixtures และผลที่รันจริง เปิด PR เข้า v0.3-studio โดยไม่ merge เอง หลัง A ค่อยรับ D แล้ว E แยก PR หากไม่มี SDK หรือเครื่องจริงให้บอกตามจริง ไม่สร้างผล benchmark สมมติ
