# แผนปรับปรุงวิดีโอและความเร็วการสลับโหมด

ปรับปรุง 9 พฤศจิกายน 2569 (Asia/Bangkok) · ตามปัญหาจาก Reno15 / CPH2825

ฐานที่ตรวจ source: `v0.3-studio` commit `8b81222e55f3b04a936fa74beb943ab6da5c59c3` ซึ่งรวม PR #8 เรื่อง OCR/UI แล้ว แผนนี้เป็นงานรอบใหม่ ไม่ใช่ผลยืนยันว่าแก้ปัญหาด้านล่างสำเร็จ

## แบ่งงานและลำดับ

| งาน | ผู้ทำ | ลำดับ | ผลที่ต้องได้ |
|---|---|---|---|
| V3 — Slow-motion ใช้งานไม่ได้ | Codex | P0 | เลือกชุดกล้อง/ขนาด/codec/high-speed session ที่เปิดได้จริง แล้วบันทึกและเล่นคลิปได้ |
| V2 — วิดีโอ 60 fps และความสามารถสูงสุด | Codex | P0 ทำร่วม V3 | รายการคู่ความละเอียด/FPS/HDR/EIS ตามความสามารถจริง ไม่มีการกลับเป็น 30 โดยไม่แจ้ง |
| V1 — แนวภาพตามการถือเครื่อง | Codex | P1 | ก่อนเริ่มคลิป หมุนเครื่องแล้วแนววิดีโอตามได้ ทั้งเปิด/ปิด Auto-rotate; ค่าที่เลือกไม่หายตอนหมุน |
| V4 — สลับโหมดหน่วง | Codex | P1 | ลดการสร้าง/ผูก session ซ้ำ วัดเวลาจนเห็นเฟรมแรกก่อนและหลังแก้ |
| U — UI ที่กำลังปรับ | Claude | ตามงานที่ Ogay มอบหมายอยู่ | ดูแลหน้าตาและ responsive UI ต่อ; เชื่อมค่าที่ core รายงานเมื่อ API พร้อม |
| V5 — ป้องกันงานส่งออกซ้อน | Codex | P2 หลัง P0 | เก็บ settings/sidecar แยกแต่ละงาน และล็อกช่วงบันทึกไฟล์จนเสร็จ งานนี้ยังค้างจากรอบก่อน |
| R — ทดสอบ Reno15 | Ogay ทดสอบเครื่อง; Codex วิเคราะห์/แก้ | หลังมี APK แต่ละชุด | ผลไฟล์/diagnostics จริง พร้อมระบุชุดที่ผ่านและชุดที่ใช้ไม่ได้ |

ลำดับลงมือ: ตรวจ capability/failure report ของ V3+V2 → แก้ Slow-motion → เปิดชุด 60 fps ที่รองรับ → V1 → V4 → V5 โดยแยก commit/PR ที่ตรวจได้ ไม่รวมทุกปัญหาเป็นการ rewrite UI ครั้งใหญ่

## ขอบเขตไฟล์และการทำพร้อม Claude

- Claude เป็นเจ้าของการจัดวาง สี ไอคอน animation และ responsive UI ที่กำลังทำ รวมส่วนสร้าง view/layout ใน Activity
- Codex ดูแล camera session, rotation ของไฟล์, capability selection, MediaRecorder/CameraX/Camera2, lifecycle, Gradle/manifest, diagnostics, tests, CI และ APK
- ถ้าต้องแตะ `VideoActivity.kt`, `SpecialVideoActivity.kt` หรือ `CameraActivity.kt` ให้ Codex แก้เฉพาะ logic/callback การผูกกล้อง การหมุน และการตั้งค่าบันทึก คง layout ของ Claude ไว้ แยก diff ที่เกี่ยวกับ core ให้ตรวจง่าย
- แยก logic ใหม่เป็น helper เมื่อเหมาะสม เช่น orientation, video profiles และ session switching ไม่ย้าย UI ไปมาเพื่อทำ performance optimization
- ทุกฝ่ายเริ่มจาก `v0.3-studio` ล่าสุด ใช้ branch ของงาน เปิด PR กลับมา ไม่ merge ข้ามงานโดยไม่ตรวจ diff/CI หลีกเลี่ยง force push และตรวจ head ก่อนอัปเดต ref

## สิ่งที่พบจาก source (ยังไม่ใช่ root cause ที่ยืนยันบนเครื่อง)

1. `VideoActivity` เริ่มด้วย `fps=30` และยังไม่บันทึก/คืนค่า quality/FPS/HDR เมื่อ Activity ถูกสร้างใหม่ การหมุนก่อนอัดอาจทำให้ค่ากลับเป็นค่าเริ่มต้น
2. แนววิดีโออิง `defaultDisplay.rotation`; จอยังถูกล็อกตอนอัด ไม่มี sensor listener สำหรับ target rotation แบบที่หน้าถ่ายภาพมี จึงต้องแยกแนวไฟล์ออกจากการตั้งค่า Auto-rotate ของจอ
3. เมนู FPS ใช้ `CameraInfo.supportedFrameRateRanges` แบบรวม ยังไม่ยืนยันคู่ resolution/FPS/dynamic range/stabilization ของ session ที่กำลังเลือก รายการที่เห็นแค่ 30 จึงยังสรุปไม่ได้ว่า hardware ทำ 60 ไม่ได้
4. Slow-motion ใช้ Camera2 high-speed + MediaRecorder และคัดตัวเลือกด้วย H.264 เพียงแบบเดียว ต้องตรวจ encoder profiles/codec candidates และ fixed high-speed ranges ต่อขนาดจริง แยกกรณีไม่มี capability ออกจากการตั้ง session ผิด
5. `CameraActivity.selectMode()` เรียกสร้างหน้าจอและ `bind()` ใหม่ ซึ่ง `unbindAll()` ทุกครั้ง มีจุดให้ลดการทำซ้ำ แต่โหมด OEM extension/รูปแบบเซนเซอร์บางคู่ยังจำเป็นต้องเปลี่ยน session

## V3 — แก้ Slow-motion

งานย่อย:

- อ่าน high-speed capability ต่อ camera ID, output size และ FPS range จาก `StreamConfigurationMap`; จัดรายการ 120/240 และค่าอื่นเฉพาะที่ API รายงาน
- ถ้าใช้ preview + recorder สอง surface ต้องใช้ fixed range `[fps,fps]` ที่รายงานสำหรับขนาดนั้น และขนาด surface ที่เข้าเงื่อนไข constrained high-speed session
- ตรวจ CamcorderProfile/EncoderProfiles และ H.264/HEVC ที่ใช้ได้จริง ไม่คัดทิ้งเพราะ hard-code codec เดียว และไม่ตั้ง bitrate/profile เกิน encoder
- ตรวจการส่งมอบ surface, ปิด session เดิม, จังหวะ prepare/start/stop, timeout และ callback ที่กลับมาหลัง Activity ออกจากหน้า
- มี error แยกขั้น: ไม่มี high-speed capability / ไม่พบ encoder / session configure ล้ม / recorder start-stop ล้ม พร้อม camera ID, size, requested FPS/range, codec และสาเหตุ
- เก็บข้อมูลเวลาถ่ายจริง จำนวนเฟรม และ sensor timestamps ที่มี เพื่อวัด capture FPS ไม่ใช้ playback FPS เป็นหลักฐานว่า sensor จับภาพได้ 120/240

เกณฑ์รับบน Reno15:

- บันทึกฉากสว่างอย่างน้อย 10 วินาทีที่ 120 และ 240 fps สำหรับทุกชุดที่แอปแสดงว่ารองรับ เปิดไฟล์ได้ ไม่มีไฟล์ว่างหรือ crash และเริ่ม/หยุดซ้ำอย่างน้อย 5 รอบ
- หากเล่น 30 fps: input 120/240 ควรได้ระยะเวลาประมาณ 4×/8× ตามเฟรมที่รับจริง ต้องรายงาน capture FPS และ playback FPS แยกกัน
- สลับออก/กลับ ล็อกจอ หมุนก่อนเริ่ม และพื้นที่ไม่พอ ไม่ทำให้กล้องค้าง; ส่วนที่ยังทดสอบไม่ได้คงสถานะ pending

## V2 — เลือกวิดีโอได้สูงสุดตามชุดที่รองรับ

- สร้างตารางกล้อง × resolution × FPS range × dynamic range × stabilization × codec/session type แยก requested/advertised/session-bound/recorded result
- ตรวจ API ที่มีใน CameraX เวอร์ชันจริงก่อนใช้ หากต้องใช้ Camera2 เพื่อเปิดชุด normal recording 60 fps ให้แยก path ชัด ไม่อัปเกรด dependencies เพียงเพื่อใช้ชื่อ API ที่ยังไม่มีในโปรเจกต์
- เก็บ FPS/quality/HDR/EIS ที่ผู้ใช้เลือก และคืนค่าเมื่อหมุน/กลับหน้า หากชุดนั้นใช้ไม่ได้ให้บอกเหตุผลพร้อมชุดที่ลองได้ การปรับ fallback ต้องปรากฏในสถานะและรายงานไฟล์
- ค่า “สูงสุด” มีทั้งสูงสุดด้านความละเอียดและสูงสุดด้าน FPS ให้แสดงคู่ที่ใช้ได้ เช่น 4K30 กับ 1080p60 เป็นคนละตัวเลือก ไม่อ้างว่าความสามารถแต่ละอย่างใช้รวมกันได้ทั้งหมด
- แยก normal video 30/60 ออกจาก high-speed 120/240 และตรวจ EIS/HDR ข้อจำกัดเป็นรายชุด

เกณฑ์รับ:

- 1080p60 และ 4K60 ต้องแสดงเมื่อ hardware/API+encoder+session ยืนยันรองรับ ไม่ใส่ชื่อโหมดเพื่อให้ดูเหมือนรองรับ
- คลิปฉากสว่าง 10 วินาทีขึ้นไปตรวจ sample timestamps/จำนวนเฟรมและ A/V sync; ตั้ง 60 แต่ไฟล์ได้ 30 ต้องถือว่าไม่ผ่านหรือเป็น fallback ที่แจ้งชัด
- ค่า FPS ไม่กลับเป็น 30 เพียงเพราะหมุนเครื่องก่อนอัด เปลี่ยน HDR หรือกลับจากตั้งค่า

## V1 — วิดีโอตามแนวเครื่อง

- ใช้ orientation sensor คำนวณ target rotation และ snapshot ณ เริ่มบันทึก รองรับแนวตั้ง แนวนอนซ้าย/ขวา และ reverse portrait ตามความสามารถ
- เปลี่ยนแนวก่อนเริ่มคลิปได้แม้ Auto-rotate ของระบบปิด; หลังเริ่มคงแนวของคลิปนั้นจนจบ การหมุนระหว่างอัดต้องไม่ทำให้ Activity ถูกสร้างใหม่จนคลิปหยุด
- ตรวจทั้งขนาดพิกเซลและ rotation metadata โดยคำนวณ display dimensions ไม่ตัดสินจาก width/height ของ stream เพียงอย่างเดียว
- กล้องหน้า/หลังและ orientation hint ของ MediaRecorder ใช้สูตรที่ตรง sensor/lens facing
- การจัดหน้า/หมุนไอคอนเป็นงาน UI ของ Claude; core ส่ง rotation/recording state ให้ UI ใช้

เกณฑ์รับ: ทดสอบ 0/90/180/270 องศา, Auto-rotate เปิด/ปิด, เปลี่ยนแนวก่อนเริ่ม/ระหว่างอัด เปิดไฟล์แล้วแนวถูกต้อง ไม่ยืดภาพ และคลิปไม่หยุดเมื่อหมุนระหว่างอัด

## V4 — ลดความหน่วงตอนสลับโหมด

- วัดเวลาจากคำสั่งเลือกโหมดจนถึงเฟรมแรกที่เห็นจริงอย่างน้อย 20 ครั้ง แยก cold/warm และคู่ Photo↔Pro, Photo↔Video, Photo↔Night/HDR
- cache capability/extension query และหลีกเลี่ยง bind ซ้ำจาก callback ที่ยังตามมาหลังสลับไปโหมดอื่น
- รวมคำสั่งปัดที่มาถี่ให้คำสั่งล่าสุดเป็นผู้ชนะ ใช้ generation token ป้องกัน callback เก่าผูกกล้องกลับโหมดเดิม
- reuse Preview/use cases/session เมื่อ configuration เข้ากันได้; เปลี่ยน session เมื่อ sensor format, extension หรือ stream combination ต้องเปลี่ยนจริง
- คง preview เดิมระหว่างเตรียม mode ถัดไปเมื่อทำได้ ประสาน Claude ถ้าต้องคง view/surface เดิม การลดความหน่วงไม่ควรทำให้โหมดที่แสดงกับ session ที่ถ่ายจริงไม่ตรงกัน

เกณฑ์รับ: รายงาน P50/P95 ก่อน–หลังจากเครื่องเดียวกันและชุดเดียวกัน ไม่มีเกณฑ์ความเร็วที่สร้างขึ้นล่วงหน้า ทดสอบปัดเร็ว 30 ครั้งไม่ crash/ค้าง/กลับโหมดผิด และไม่ทำให้ถ่าย RAW/HDR/วิดีโอผิดชนิด

## สิ่งที่ Claude ต้องทราบ (พร้อมส่งต่อ)

> ดูแล UI ตามงานที่กำลังทำต่อได้เลย รอบนี้ Codex รับ core วิดีโอทั้งหมด: rotation ของไฟล์, ตาราง resolution/FPS/HDR/EIS, Slow-motion session และลดการ bind กล้องซ้ำ กรุณาคงจุดเชื่อม listener/state ของกล้องไว้ ใช้ค่าความสามารถจาก core แสดงตัวเลือก ไม่ hard-code ว่าทุกเครื่องได้ 60/120/240 และไม่เปลี่ยน camera/session/recorder logic พร้อมกัน อ่าน docs/VIDEO_STABILITY_PLAN.md ก่อนแก้ไฟล์ Activity ที่ใช้ร่วมกัน เปิด PR แยก UI เข้า v0.3-studio เพื่อให้ Codex ตรวจ conflict และรัน CI

## หลักฐานและข้อจำกัดการตรวจรับ

- Build/lint/JVM/emulator เป็นด่านตรวจ regression ของ core แต่ไม่ยืนยันว่า Reno15 ถ่าย high-speed ได้จริง ต้องมีผลไฟล์และ timestamps จากเครื่อง
- รายงานอาการ Slow-motion ใช้ไม่ได้ของ Ogay ถือเป็น bug ที่ต้องสืบสวน ยังไม่ระบุว่าเกิดจาก OEM, CameraX หรือ encoder จนกว่าจะมี diagnostics
- หากกล้อง OPPO stock ใช้ชุดที่ไม่เปิดแก่ third-party API ต้องรายงานขอบเขตที่ตรวจพบตามจริง ไม่ใช้สเปกโฆษณาเป็นผลทดสอบของ Luma
- เอกสารนี้ยังไม่เปลี่ยน source กล้อง/หน้าตาแอป และยังไม่ได้ build APK แก้ปัญหารอบนี้

แหล่งอ้างอิงทางเทคนิค:

- [VideoCapture target rotation](https://developer.android.com/reference/androidx/camera/video/VideoCapture#setTargetRotation(int)) — orientation sensor และ rotation ของคลิปถัดไป
- [CameraConstrainedHighSpeedCaptureSession](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession) — ข้อจำกัด surface/fixed high-speed range
- [StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap) — ชุดขนาดและ FPS high-speed ที่กล้องรายงาน
- [Recorder video capability sources](https://developer.android.com/reference/androidx/camera/video/Recorder) — camera/encoder profile capability
