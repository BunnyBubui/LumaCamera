# ข้อสังเกตต่อแผนวิดีโอ (สำหรับ Codex)

เอกสารนี้เป็นความเห็นของ Claude ต่อ `docs/VIDEO_STABILITY_PLAN.md` อ้างอิง `v0.3-studio` commit `8503415` เจ้าของโปรเจกต์เลือกให้ทำตามแผน คือ Codex รับ core วิดีโอทั้งหมด (V1–V5) ส่วน Claude ทำ UI และตรวจโค้ด งานไอคอนให้ Claude อีก session ทำ **เอกสารนี้ไม่ได้แก้ไฟล์ core หรือ Activity ใด ๆ** ทุกข้อเป็นข้อสังเกตจากการอ่านซอร์สและตัวอย่างทางการ ยังไม่ได้ยืนยันบน Reno15

## สรุป

ผมเห็นด้วยกับลำดับงานและการแบ่งไฟล์ในแผน มีสามเรื่องที่อยากให้ Codex รู้ก่อนลงมือ

1. CameraX 1.6 ที่แอปใช้อยู่แล้ว (`1.6.2`) มี API สำหรับ slow-motion และสำหรับถามคู่ FPS/HDR/กันสั่นที่ใช้ร่วมกันได้จริง จึงไม่ต้องอัปเกรด dependency และอาจไม่ต้องเขียน Camera2 + MediaRecorder เอง
2. ในโค้ด Slow-motion ปัจจุบันมีเส้นทางหนึ่งที่เลือกชุด high-speed โดยไม่ผ่านการตรวจ encoder ซึ่งเป็นผู้ต้องสงสัยที่ตรวจได้ง่ายที่สุด
3. PR #8 ของผมเพิ่ม layout แนวนอนและล็อกการหมุนระหว่างอัดใน `VideoActivity`, `SpecialVideoActivity` และ `RawLabActivity` ส่วนนี้เกี่ยวกับ V1 และข้อค้นพบข้อ 1 ของแผน ดูหัวข้อสุดท้าย

## 1. API ของ CameraX 1.6 ที่ตรงกับ V2 และ V3

ผมตรวจตัวอย่างทางการใน `android/camera-samples` commit `154c954abc5c80dddf07d264319cf89a3e1ccda9` ซึ่งใช้ CameraX `1.6.1`

**Slow-motion (V3):** ตัวอย่าง `samples/camerax-slowmotion/.../CameraXSlowMotionController.kt` ทำงานตามลำดับนี้ ขั้นแรกเรียก `Recorder.getHighSpeedVideoCapabilities(cameraInfo)` ถ้าได้ `null` แปลว่ากล้องนี้ไม่มี high-speed ให้แอป ขั้นที่สองเลือก quality จาก `highSpeedCaps.getSupportedQualities(DynamicRange.SDR)` ขั้นที่สามสร้าง `HighSpeedVideoSessionConfig(capture, preview)` แล้วถาม `cameraInfo.getSupportedFrameRateRanges(probeConfig)` เพื่อได้ช่วง FPS ที่ session นี้ใช้ได้จริง ขั้นสุดท้าย bind ด้วย `HighSpeedVideoSessionConfig(capture, preview, frameRateRange = …, isSlowMotionEnabled = true)` ตัวอย่างนี้สร้าง `Preview` ใหม่ทุกครั้งที่ bind เพราะคอมเมนต์ในตัวอย่างบอกว่า config นี้ปฏิเสธ Preview ที่ใช้ซ้ำ

ข้อดีของเส้นทางนี้คือ CameraX เลือก encoder profile ให้ และแยกกรณี "ไม่มี capability" (ได้ `null` หรือรายการว่าง) ออกจาก "bind ล้ม" (exception) ได้ชัด ตรงกับเกณฑ์ error แยกขั้นใน V3

**60 fps และคู่ความสามารถ (V2):** ตัวอย่าง `samples/camerax-featurecombination/.../CameraXFeatureCombinationController.kt` สร้าง `SessionConfig.Builder(preview, videoCapture).setRequiredFeatureGroup(…)` ด้วย feature จาก `androidx.camera.core.featuregroup.GroupableFeature` (60 fps, HDR, preview stabilization) แล้วถาม `cameraInfo.isSessionConfigSupported(config)` ก่อน bind ตัวอย่างนี้ระบุว่าควรใส่เฉพาะ use case ที่ feature ต้องใช้ เพราะถ้าใส่ `ImageCapture` ที่ไม่ใช้ด้วย คำตอบจะกลายเป็นไม่รองรับทั้งที่เครื่องรองรับ ตัวอย่างนี้ใช้ artifact `androidx.camera.featurecombinationquery:featurecombinationquery` (และตัว play-services) เป็นแหล่งข้อมูลแบบออฟไลน์ประกอบ ซึ่งแอปยังไม่มี

วิธีนี้แก้ข้อค้นพบข้อ 3 ของแผนได้ตรงจุด เพราะตอนนี้ `VideoActivity.kt:140` รวม `supportedFrameRateRanges` ของกล้องทั้งตัว ไม่ได้ถามว่าคู่ quality/FPS/HDR ที่ผู้ใช้กำลังเลือกใช้ด้วยกันได้หรือไม่

ข้อควรระวัง: ผมยังไม่ได้รัน API เหล่านี้บน Reno15 ถ้า OPPO ไม่เปิดชุด 60 fps หรือ high-speed ให้ third-party API ทั้งเส้นทาง CameraX และ Camera2 จะได้ผลเหมือนกัน และรายงานตามจริงตามที่แผนกำหนด

## 2. ผู้ต้องสงสัยใน Slow-motion ปัจจุบัน

ผมเทียบ `SpecialVideoActivity.kt` กับตัวอย่าง Camera2 ทางการ `samples/camera2-slowmotion/.../Camera2SlowMotionController.kt` ใน commit เดียวกัน

**ส่วนที่เหมือนตัวอย่าง จึงไม่น่าใช่สาเหตุ:** การตั้ง MediaRecorder ใช้ `setVideoFrameRate(30)` คู่กับ `setCaptureRate(fps)` เหมือนตัวอย่าง (`setVideoFrameRate(PLAYBACK_FPS)` และ `setCaptureRate(fps.toDouble())`) ใช้ H.264 เหมือนกัน bitrate ใกล้กัน (24 กับ 20 Mbps) และใช้ `createHighSpeedRequestList` + `setRepeatingBurst` กับ `CONTROL_AE_TARGET_FPS_RANGE = [fps, fps]` เหมือนกัน `Capabilities.highSpeed` (`Capabilities.kt:21`) ก็คัดเฉพาะช่วงคงที่ `lower == upper` ซึ่งถูกต้องสำหรับ preview + recorder สอง surface

**ผู้ต้องสงสัยข้อ 1 — `open()` เลือกชุดโดยไม่ตรวจ encoder:** เมนูตัวเลือก (`SpecialVideoActivity.kt:49`) คัดชุด high-speed ด้วย `areSizeAndRateSupported` ของ encoder `video/avc` แต่ `open()` (`SpecialVideoActivity.kt:61`) ใช้ค่าเริ่มต้น 1280×720 ที่ 120 fps (`:30`) และถ้าชุดนั้นไม่อยู่ในรายการของกล้อง ก็เลือก `options.first()` ทันทีโดยไม่ผ่านการตรวจ encoder ผู้ใช้ที่กดอัดโดยไม่เปิดเมนูจึงอาจได้ชุดที่ encoder รับไม่ได้ ผลที่คาดคือ `onConfigureFailed` ขึ้นข้อความรวม "กล้องและ encoder ไม่รองรับชุดที่เลือก" (`:85`) หรือ recorder เริ่มไม่ได้ ข้อนี้ตรวจได้ง่ายจาก diagnostics ว่าชุดที่ใช้จริงตอนล้มคืออะไร

**ผู้ต้องสงสัยข้อ 2 — การตรวจ encoder อาจเข้มเกินไป:** `areSizeAndRateSupported(w, h, fps)` ถามว่า encoder รับ 240 fps ได้หรือไม่ บางเครื่องรายงานค่านี้ต่ำกว่าความสามารถจริง เพราะ MediaRecorder ในโหมด slow-motion อาจเข้ารหัสด้วยเส้นทางของผู้ผลิต ถ้าเป็นแบบนั้น เมนูจะขึ้น "Android ไม่เปิดชุด high-speed ที่ encoder รองรับบนเครื่องนี้" ทั้งที่อัดได้ ตัวอย่างทางการไม่ได้ตรวจ encoder ก่อนเลย ข้อเสนอคือเก็บผลตรวจ encoder ลง diagnostics เป็นข้อมูลประกอบ แต่ไม่ใช้ตัดชุดทิ้ง จนกว่าจะมีผลจากเครื่องจริง

**ความต่างที่ไม่น่าเป็นสาเหตุแต่ควรรู้:** ตัวอย่างทางการเปิดพรีวิวใน high-speed session ตั้งแต่ก่อนอัด ส่วน Luma เปิดพรีวิวใน session ปกติ (`:68`) แล้วเปลี่ยนเป็น high-speed ตอนเริ่มอัด และตัวอย่างใช้ `SessionConfiguration(SESSION_HIGH_SPEED, …)` บน API 28 ขึ้นไป ส่วน Luma ใช้ `createConstrainedHighSpeedCaptureSession` ที่ deprecated (`:86`) ทั้งสองแบบใช้ได้ตามเอกสาร แต่การเปลี่ยนชนิด session ตอนเริ่มอัดทำให้มีขั้นที่ล้มได้เพิ่มหนึ่งขั้น

**diagnostics ที่ควรมีก่อนแก้:** ทุกครั้งที่ล้ม ให้บันทึก camera ID, ขนาด, ช่วง FPS ที่ขอ, รายการช่วงที่ `getHighSpeedVideoFpsRangesFor(size)` คืน, ผลตรวจ encoder ทั้ง AVC และ HEVC, ชนิดของ exception และขั้นที่ล้ม (เปิดกล้อง / prepare / configure / start / stop) ตอนนี้โค้ดส่งต่อแค่ `localizedMessage` ข้อมูลจึงไม่พอบอกสาเหตุ

## 3. จุดที่เกี่ยวกับ PR #8 ของผม

PR #8 ทำสองอย่างในไฟล์วิดีโอ อย่างแรก `VideoActivity` สร้าง layout ตามแนวจอตอน `onCreate` (แนวนอนมีพรีวิวซ้ายและแผงเลื่อนได้ขวา) อย่างที่สอง ทั้งสามหน้าเรียก `requestedOrientation = SCREEN_ORIENTATION_LOCKED` ตอนเริ่มอัด และปลดล็อกเมื่อจบหรือล้ม

ข้อค้นพบข้อ 1 ของแผน (ค่า FPS/quality/HDR กลับเป็นค่าเริ่มต้นเมื่อหมุน) มีอยู่ก่อน PR #8 แล้ว เพราะ manifest ไม่ประกาศ `configChanges` ให้ `VideoActivity` ตั้งแต่ต้น ทุกการหมุนจึงสร้าง Activity ใหม่ แต่ layout แนวนอนของผมทำให้ผู้ใช้หมุนจอบ่อยขึ้น ปัญหาจึงเห็นชัดขึ้น การเก็บและคืนค่าเป็นงาน V2 ของ Codex ผมจะไม่แก้

ถ้า V1 เปลี่ยนไปใช้ orientation sensor และประกาศ `configChanges` เพื่อไม่ให้ Activity ถูกสร้างใหม่ layout แนวนอนของผมจะไม่ถูกสร้างใหม่ตามไปด้วย เพราะโค้ดเลือก layout ใน `onCreate` เท่านั้น กรณีนั้นบอกผมได้ ผมจะย้ายโค้ดสร้าง layout ไปไว้ในฟังก์ชันที่เรียกซ้ำได้จาก `onConfigurationChanged` ใน PR UI แยก โดยไม่แตะ logic กล้อง ส่วนการล็อกการหมุนระหว่างอัด Codex เก็บไว้หรือแทนด้วยวิธีของ V1 ได้ตามที่เห็นเหมาะ

`StudioAccessibilityTest` เปิดหน้าวิดีโอในแนวตั้งและแนวนอน ถ้า V1 เปลี่ยนพฤติกรรมการหมุน เทสต์นี้จะช่วยจับปุ่มที่ล้นจอหรือข้อความที่ถูกตัด
