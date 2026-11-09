# สถานะ branch `integration/glass-video-recovery` (PR #13)

ปรับปรุง 9 พฤศจิกายน 2569 · รวม `claude/glass-recovery` (Glass UI) กับ `codex/video-session-recovery` (core วิดีโอ)
งานแกลเลอรีแยกให้ Claude อีกตัวแล้ว ดู `docs/claude/CLAUDE_B_GALLERY_TASK.md`

## สิ่งที่ CI ยืนยันแล้ว

- "Android APK" ผ่านทุกรอบ ทั้ง unit test, lint และ assemble จึงได้ APK debug จาก artifact ของ workflow นี้
- "Camera device test" บน emulator API 30: รอบ `1816f93` ผ่าน 36 จาก 37 test ตัวที่ตกคือการอัดวิดีโอ ซึ่งแก้ใน `614148b`

## บัคที่พบจาก CI และแก้แล้ว

| อาการ | สาเหตุ | แก้ |
|---|---|---|
| วิดีโอกดอัดไม่ได้ทุกโหมด | ใน `ShutterButton(...).apply{}` ชื่อ `recording` อ้างถึง Boolean ของปุ่ม ไม่ใช่ `Recording?` ของ Activity จึงสั่งหยุดทุกครั้งที่แตะ | ใช้ `this@VideoActivity.recording` |
| เมนูซ้อนบนแผงอ่านยาก และแตะตัวเลือกไม่ติด เช่น เลือก RAW หรือ AI ไม่ได้ | ViewGroup เรียงการวาดและการแตะตาม elevation การ์ดเมนู (12 dp) จึงอยู่ใต้แผง (16 dp) | ให้ shade และการ์ดเมนูอยู่ที่ 20/24 dp |
| เลื่อนแผงไม่ได้เมื่อนิ้วเริ่มบน slider | `GlassSlider` ขอ parent หยุดดักตั้งแต่ DOWN | รับ gesture เฉพาะเมื่อลากแนวนอนเกิน slop แตะเฉย ๆ ยังตั้งค่าได้ |
| test เลือกรูปแบบไฟล์ไปกดผิดปุ่ม | ไอคอนสถานะด้านหลังมีคำอธิบายเดียวกับไทล์ | `GlassNav.inPanel` ค้นเฉพาะในแผง |

## สิ่งที่เพิ่มตามคำขอผู้ใช้

- ค่าโปรละเอียดขึ้น: ISO และชัตเตอร์ทีละ 1/3 สต็อปตามช่วงของเซนเซอร์ WB เป็น Kelvin 2000–10000 K (ใช้เมื่อกล้องเปิด manual post-processing และมี forward matrix ถ้าไม่มีก็ใช้ preset เพิ่มขึ้น) โฟกัสมือ 48 ขั้นพร้อมระยะ EV ครบช่วง และปุ่ม − / + ทีละขั้น
- ลื่นขึ้น: เลิกใช้ RenderEffect บน TextureView ตอนแผงเปิด ใช้ภาพนิ่งเบลอขนาดเล็กแทน และเอา blur pulse ตอนเปลี่ยนโหมดออก
- วิดีโอ: FPS มาจาก `VideoSessions.rates()` ซึ่งถาม session จริงทั้งจาก camcorder profile และ codec capability จึงแสดง 60 ได้เมื่อชุดนั้นรองรับ เพิ่ม "โปรไฟล์ · Log แบน" เมื่อกล้องรองรับ tonemap contrast curve
- **ข้อจำกัดที่ต้องบอกผู้ใช้:** Log แบนไม่ใช่ C-Log ของ Canon หรือโปรไฟล์ของผู้ผลิตรายใด ถ้าต้องการ 10 บิตจริงให้ใช้ HLG10 และแอปทั่วไปขอสิทธิ์กล้องระดับระบบของ OPPO ไม่ได้

## ยังค้าง

- Reno15: 60 fps ต้องตรวจจากไฟล์จริง, slow-motion 120/240, Kelvin WB และ Log บนเครื่องจริง, ความลื่นของแผง
- แกลเลอรี: รอ PR จาก Claude อีกตัว
- PR #11 และ #12 ถูกรวมไว้ในนี้แล้ว ปิดได้เมื่อ #13 merge
