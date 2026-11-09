# Glass UI ในแอปจริง (branch `claude/glass-ui`)

นำ UI จากต้นแบบ (ดู `docs/claude/UI_MOTION_PROPOSAL.md` บน branch `claude/ui-motion-proposal`) มาเขียนเป็นโค้ด Kotlin แบบ View ล้วน ไม่มี XML
**ยังไม่ได้ build และยังไม่ได้ทดสอบบนเครื่องจริง** ตรวจได้เพียงว่าโค้ดคอมไพล์ผ่านกับ `android.jar` API 35 และ stub ของ CameraX/androidx ที่เขียนขึ้นเอง

## ไฟล์ที่เปลี่ยน

| ไฟล์ | สิ่งที่ทำ |
|---|---|
| `GlassKit.kt` (ใหม่) | สี, easing, spring, เบลอ (API 31+), ตัวแปลง SVG path, ชุดไอคอน, `IconView`, `GlassDrawable`, `glassTouch()`, `Liquid.show/hide` |
| `GlassWidgets.kt` (ใหม่) | `CenterStrip`, `GlassSlider`, `EdgeRail`, `ZoomWheel`, `ShutterButton`, `GlassSwitch`, `GlassMenu`, `GlassSheet` |
| `CameraActivity.kt` | เขียนส่วน UI ใหม่ทั้งหมด ส่วน bind และการถ่าย (burst, HDR, Ultra HDR, panorama, sequence) คงตรรกะเดิม |
| `ManualControls.kt` | เพิ่ม setter `setIso/setShutter/setWb/setFocus` และรายการค่า สำหรับแถบชิปโปร ส่วน UI เดิมยังอยู่ |
| `StudioSettings.kt` | หน้าตั้งค่าเต็มจอแบบ glass มีช่องค้นหา ใช้คีย์ prefs เดิมทุกตัว |
| `GalleryActivity.kt` | แท็บ Luma / ทั้งเครื่อง (MediaStore), แถบหมวด, ค้นหา, กดค้างดูตัวอย่าง, หน้ารายละเอียดเดิมพร้อมชิป glass |
| `VideoActivity.kt` | UI glass, แถบชิปคุณภาพวิดีโอ + สไลเดอร์, MOVIE แยกสองหมวด "ปรับแต่งวิดีโอ" กับ "คุณภาพวิดีโอ" ส่วนบันทึกคงเดิม |
| `StudioUi.kt` | `studioSheet` เปลี่ยนเป็นพื้น glass และเปิดแบบ liquid |
| `AndroidManifest.xml` | เพิ่ม `READ_MEDIA_IMAGES/VIDEO/VISUAL_USER_SELECTED` และ `READ_EXTERNAL_STORAGE` (maxSdk 32) สำหรับแท็บทั้งเครื่อง |

## prefs ใหม่
`mode_bar` (ค่าเริ่ม `NIGHT,PORTRAIT,PHOTO,VIDEO,PRO`), `night_toggle`, `night_frames` (16/24/32), `look`, `look_bright`, `look_warm`, `pro_chip`, `panel_hint_seen`, `gallery_device`

## ต้องทดสอบบนเครื่อง
1. แตะโฟกัส, กดค้างล็อก AE/AF, ปัดขึ้นลงปรับแสง (ซ้าย) และโฟกัสมือ (ขวา ถ้าเครื่องเปิด `LENS_INFO_MINIMUM_FOCUS_DISTANCE`)
2. ลากแถบซูมเป็นวงล้อ, ล็อกที่เลนส์, minimap เกิน 10×
3. ปุ่มกลางคืน: ถ่าย burst AI ตามจำนวนเฟรม แตะชัตเตอร์ระหว่างรวมแสงเพื่อหยุดและเก็บเฟรมที่ได้
4. มาโคร: ตั้งโฟกัสใกล้สุดผ่าน `ManualControls.setFocus`
5. แผงตัวเลือก (ปัดขึ้น), เมนู glass, ตัวแก้แถบโหมด (เรียง/เพิ่ม/ลบ/ใช้ชั่วคราว)
6. แผงโปร: ISO/ชัตเตอร์/WB/โฟกัส/EV ผ่าน Camera2 interop
7. ความลื่นของ spring และเบลอบน API 31+ และการทำงานเมื่อเปิด "ลดการเคลื่อนไหว"
8. แกลเลอรีทั้งเครื่องบน Android 13, 14 (เลือกบางรูป) และ 12 ลงไป

## ข้อจำกัดที่รู้แล้ว
- สไตล์ภาพ ความสว่าง และความอุ่น ใช้กับภาพพรีวิวเท่านั้น ยังไม่ใส่ลงไฟล์
- ชีตย่อยที่ยังใช้ `pill()` เดิม (ซูม A→B, ManualControls.show, CaptureSettings) อยู่บนพื้น glass แล้วแต่ปุ่มข้างในยังเป็นแบบเก่า
- `CameraActivity`, `StudioUi.kt`, `ManualControls.kt`, `VideoActivity.kt` เป็นไฟล์ที่ Codex ดูแลตาม `docs/CLAUDE_NEXT_TASKS.md` ต้องแจ้งก่อน merge
