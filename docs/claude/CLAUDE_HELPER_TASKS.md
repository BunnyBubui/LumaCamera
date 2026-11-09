# งานแบ่งให้ Claude อีกบัญชี (session ผู้ช่วย)

ปรับปรุง 9 พฤศจิกายน 2569 · อ่านคู่กับ `docs/CLAUDE_NEXT_TASKS.md` และ `docs/CLAUDE_HANDOFF.md` ใน `v0.3-studio`

เอกสารนี้แบ่งงานรอบ A/D/E ระหว่าง Claude สอง session เพื่อไม่ให้แก้ไฟล์ชนกัน session หลัก (Claude บัญชีแรก) ทำงาน A และ D อยู่แล้ว **session ผู้ช่วยรับงาน E ทั้งหมด** และถ้าเหลือเวลาให้ทำงานเสริมท้ายเอกสาร

## อัปเดตล่าสุด

เจ้าของโปรเจกต์มอบให้ session หลักรับงาน build และรวมงานแทน Codex แล้ว และอนุญาตให้ merge เอง งาน A (PR #6) และ D (PR #7) รวมเข้า `v0.3-studio` แล้ว PR #8 เชื่อม OCR กับหน้าเอกสารและแก้ UI ตามรีวิว D ดังนั้นไฟล์ Activity และ UI กลางอาจเปลี่ยนอีก ให้ดึง `v0.3-studio` ล่าสุดก่อนเปิด PR งาน E และถ้าพบ bug ใน `PanoramaEngine` ให้รายงานใน PR ตามเดิม session หลักจะเป็นผู้แก้และ merge

## สถานะ ณ ตอนแบ่งงาน

| งาน | ผู้ทำ | Branch / PR | สถานะ |
|---|---|---|---|
| A — OCR ไทย + อังกฤษออฟไลน์ | session หลัก | `claude/thai-ocr` · PR #6 (draft) | โค้ด โมเดล เทสต์ และเอกสารเสร็จ JVM 12/12 ผ่านในเครื่อง รอบแรก CI ล้มที่ lint (ByteOrderMark) แก้แล้ว รอผล emulator |
| D — รีวิว UI 0.3 + accessibility | session หลัก | `claude/studio-ui-review` · PR #7 (draft) | มี `StudioAccessibilityTest` ที่วัด touch target, label, ข้อความถูกตัด ที่ฟอนต์ 1.0/2.0 และแนวนอน กำลังเก็บผลจาก CI ยังไม่ได้เขียน `UI_REVIEW_V03.md` |
| E — Panorama quality + failure cases | **session ผู้ช่วย** | `claude/panorama-quality` (ยังไม่สร้าง) | ยังไม่เริ่ม |
| Hardware acceptance บน Reno15 | Ogay | — | ยังไม่ได้วัด ใช้ `docs/claude/RENO15_TEST_PROTOCOL.md` |

## ไฟล์ที่ห้าม session ผู้ช่วยแก้

ไฟล์ของงาน A: `app/src/main/java/th/ogay/luma/ocr/**`, `app/src/main/assets/ocr/**`, `app/src/test/java/th/ogay/luma/ocr/**`, `app/src/androidTest/java/th/ogay/luma/ocr/**`, `app/src/androidTest/assets/ocr/**`, `model/ocr/**`, `docs/claude/THAI_OCR_INTEGRATION.md`

ไฟล์ของงาน D: `app/src/androidTest/java/th/ogay/luma/StudioAccessibilityTest.kt`, `docs/claude/UI_REVIEW_V03.md`

ไฟล์ของ Codex ตาม `CLAUDE_NEXT_TASKS.md`: Activity ทุกตัว, `PanoramaEngine.kt`, UI กลาง, Gradle, manifest, `.github/workflows/**` และ test file ที่มีอยู่แล้ว

## งาน E — Panorama quality และ failure cases

ทำตาม `docs/CLAUDE_NEXT_TASKS.md` หัวข้อ E ทุกข้อ สรุปสั้น ๆ ได้ดังนี้

- สร้าง branch `claude/panorama-quality` จาก `v0.3-studio` ล่าสุด push ครั้งแรกแล้ว **เปิด draft PR เข้า `v0.3-studio` ทันที** เพราะ CI ไม่รันจากการ push เข้า branch อย่างเดียว (ดูหัวข้อ "Build ในเครื่องไม่ได้") และ **ไม่ merge เอง**
- ไฟล์ที่เป็นเจ้าของ: `app/src/androidTest/java/th/ogay/luma/PanoramaQualityTest.kt`, `app/src/androidTest/assets/panorama/**` (หรือ generator ใน test) และ `docs/claude/PANORAMA_QUALITY.md`
- API ที่ทดสอบ: `PanoramaEngine.stitch(context: Context, files: List<File>): File` รับ JPEG 2–12 ภาพ ใช้ ORB + homography + feather blend ผ่าน OpenCV 4.12
- สิ่งที่ต้องรู้ก่อนตั้งเกณฑ์ (อ่านจากโค้ดใน `v0.3-studio`):
  - engine ย่อแต่ละภาพเหลือราว 1.2 MP ก่อนประมวลผล (`PhotoEffects.read(file, 1_200_000)`) และจำกัดภาพผลลัพธ์ไม่เกิน 6 MP ถ้าเทียบกับ ground truth ต้องปรับสเกลให้ตรงกันก่อน ขอบที่เกลี่ยต่อภาพ (feather) กว้าง 50 พิกเซลในภาพที่ย่อแล้ว ใช้เป็นเกณฑ์ตรวจรอยต่อได้
  - engine จับคู่เฉพาะภาพที่อยู่ติดกันตามลำดับ (ภาพที่ i กับ i-1) ภาพที่สลับลำดับจึงเป็นอีกกรณีที่ควรทดสอบ
  - จำนวนภาพผิดได้ `IllegalArgumentException` จาก `require` ส่วนภาพแบน ภาพซ้อนกันไม่พอ และไฟล์เสีย ได้ `IllegalStateException` จาก `check` พร้อมข้อความภาษาไทย ให้ assert ชนิด exception ด้วย ไม่ใช่แค่ว่ามี exception
- สร้างฉาก texture ด้วย seed คงที่ที่มีลายไม่สมมาตรและจุดอ้างอิงชัด ตัดเป็นภาพที่ซ้อนกัน 40–60% แล้วตรวจความกว้าง รูปทรง รอยต่อ และส่วนซ้ำ เทียบกับ ground truth ด้วย tolerance ที่อธิบายได้ อย่าตรวจแค่ว่ามีไฟล์ JPEG
- กรณีล้มเหลวที่ต้องมี: ภาพแบนไม่มี feature, overlap ไม่พอ, ไฟล์เสีย, จำนวนภาพผิด (1 และ 13), ภาพที่มีวัตถุเคลื่อนที่ ระบุว่ากรณีไหนควรคืนผลและกรณีไหนควรปฏิเสธ และตรวจว่าไฟล์อินพุตไม่ถูกลบเมื่อ stitching ล้มเหลว
- ข้อจำกัดของเทสต์ข้อสุดท้าย: `PanoramaEngine.stitch` ไม่ลบไฟล์เลย การลบอินพุตอยู่ใน `CameraActivity` (บรรทัดราว 690 ใน `v0.3-studio` เป็นไฟล์ของ Codex) ซึ่งลบเมื่อสำเร็จและเก็บไว้เมื่อล้มเหลว เทสต์ระดับ engine จึงยืนยันได้แค่ว่า engine ไม่ลบไฟล์ ให้เขียนข้อจำกัดนี้ใน `PANORAMA_QUALITY.md` และรายงานใน PR ว่าถ้า `publishSpecial` ล้มหลังต่อภาพสำเร็จ ไฟล์ `panorama-*.jpg` ใน cache จะไม่ถูกลบ เพราะ `result.delete()` อยู่ในเส้นทางที่สำเร็จเท่านั้น
- แยกเทสต์ performance/memory ของชุดใหญ่ (เช่น 12 ภาพ) ออกจาก smoke test ขนาดเล็ก
- ถ้าพบ bug ใน engine ห้ามแก้ `PanoramaEngine.kt` ให้เขียน fixture และวิธีทำซ้ำใน PR แล้ว Codex จะแก้ **ห้ามลบ assertion หรือใส่ skip เพื่อให้ CI เขียว**

## สิ่งที่ session หลักเรียนรู้แล้ว ใช้ได้เลย

**Build ในเครื่องไม่ได้:** เครื่องทำงานของ Claude ไม่มี Android SDK และเข้า Maven/Google ไม่ได้ วิธีที่ใช้ได้คือให้ GitHub Actions รัน (`Android APK` = build/lint/JVM, `Camera device test` = emulator API 30 x86_64 ใช้เวลาราว 20–30 นาที) ทั้งสอง workflow รันอัตโนมัติเมื่อมี pull request ส่วนการ push อย่างเดียวจะรันอัตโนมัติเฉพาะ `main` ดังนั้น push เข้า `claude/panorama-quality` โดยยังไม่มี PR จะไม่มี CI รัน (สั่งรันเองด้วย `workflow_dispatch` ได้ แต่ต้องมีสิทธิ์ actions และไม่ผูกกับ PR) ให้เปิด draft PR ตั้งแต่ push แรก หลังจากนั้นทุก push จะรัน CI ให้เอง emulator มี RAM 2 GB และ heap 384 MB ให้คำนึงถึงตอนออกแบบเทสต์ชุด 12 ภาพ

**อ่านผล CI:** `gh api repos/BunnyBubui/luma-camera/actions/runs?branch=<branch>` เพื่อหา run id แล้ว `gh api repos/BunnyBubui/luma-camera/actions/jobs/<job id>/logs` อ่าน log ได้ แต่ดาวน์โหลด artifact ไม่ได้ (blob storage ถูกบล็อก) logcat ของ test จึงอ่านไม่ได้ ถ้าต้องการตัวเลขจาก emulator ให้ใส่ตัวเลขไว้ในข้อความ assertion หรือทำ commit ชั่วคราวที่จงใจ fail พร้อมรายงาน แล้วลบออกใน commit ถัดไป และบอกใน PR ตรง ๆ

**ตรวจ API ของ OpenCV ก่อน push:** ดาวน์โหลด SDK จาก `https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip` (GitHub release ดาวน์โหลดได้) แล้วคอมไพล์ไฟล์ใน `sdk/java/src/org/opencv` (ยกเว้น `android/` และ `osgi/`) ด้วย javac พร้อม stub คลาส Android เล็ก ๆ จากนั้นคอมไพล์ Kotlin ด้วย kotlinc 2.1.20 จาก GitHub release ของ JetBrains วิธีนี้จับชื่อเมธอดผิดได้ก่อนเสียเวลารอ CI ส่วน Python `opencv-python-headless==4.12.0.88` จาก PyPI ใช้ลองอัลกอริทึมได้ เพราะเป็น OpenCV เวอร์ชันเดียวกัน

**Lint:** อย่าใส่อักขระที่มองไม่เห็นลงในซอร์ส (เช่น U+FEFF ใน regex) lint จะล้มด้วย `ByteOrderMark` ให้เขียนเป็น `\\uFEFF` ใน regex แทน

**เครือข่าย:** PyPI, npm, GitHub (raw และ release assets) ใช้ได้ ส่วน Hugging Face, CDN ของ Paddle และ Maven ใช้ไม่ได้

## งานเสริม ถ้างาน E เสร็จแล้ว

ทำได้เฉพาะเป็นเอกสารข้อเสนอ ไม่แก้ pipeline กลาง

1. ข้อเสนอ RAW lens shading / hot-pixel calibration ตามหัวข้อ "งานต่อยอด" ใน `CLAUDE_NEXT_TASKS.md` เขียนใน `docs/claude/RAW_CALIBRATION_PROPOSAL.md` พร้อมหลักฐานจาก metadata ที่ Camera2 เปิดให้ (`LENS_SHADING_MAP`, `SENSOR_INFO_*`) และระบุว่าต้องใช้ไฟล์ DNG จาก Reno15 แบบไหนก่อนเลือกวิธี
2. ห้ามเติมตัวเลขผลทดลองสมมติ ถ้าไม่มีไฟล์จริงให้เขียนว่า "ยังไม่ได้ทดสอบ"

## พรอมป์ต์สำหรับส่งให้ session ผู้ช่วย

> เปิด repo BunnyBubui/luma-camera ดึง branch v0.3-studio ล่าสุด อ่าน docs/CLAUDE_NEXT_TASKS.md, docs/CLAUDE_HANDOFF.md และ docs/claude/CLAUDE_HELPER_TASKS.md (อยู่ใน branch claude/coordination) คุณรับงาน E: Panorama quality และ failure cases ใน branch claude/panorama-quality ห้ามแก้ไฟล์ของงาน A/D และของ Codex ตามรายการในเอกสาร ส่ง PanoramaQualityTest.kt, fixtures/generator และ docs/claude/PANORAMA_QUALITY.md พร้อมผลที่รันจริงบน CI หลัง push ครั้งแรกให้เปิด draft PR เข้า v0.3-studio ทันที เพราะ CI รันเฉพาะเมื่อมี PR และห้าม merge เอง อ่านหัวข้อ "สิ่งที่ต้องรู้ก่อนตั้งเกณฑ์" และข้อจำกัดเรื่องการลบไฟล์อินพุตในเอกสารก่อนเขียนเทสต์ ถ้าพบ bug ใน PanoramaEngine ให้รายงานพร้อม fixture ไม่แก้ engine และไม่ใส่ skip ถ้าไม่มีผลจริงให้บอกตามจริง ไม่สร้างตัวเลขสมมติ
