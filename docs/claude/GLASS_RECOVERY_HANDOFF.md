# แบ่งงานแก้บัค Reno15 และ Glass UI

วันที่ 9 พฤศจิกายน 2569 · source UI `claude/glass-ui@683e8d3` · core ฐาน `v0.3-studio@4a65eb6`

## งานที่ทำคู่กันได้

| ผู้รับผิดชอบ | Branch / ไฟล์ | ผลงาน |
|---|---|---|
| Codex | `codex/video-session-recovery`: `VideoSessions.kt`, `VideoSessionPolicy.kt`, `VideoDiagnostics.kt`, core ใน `VideoActivity.kt`, `SpecialVideoActivity.kt`, `ClipInspector.kt`, camera bind/lifecycle ใน `CameraActivity.kt` | ตรวจ FPS เป็นคู่ quality/HDR/EIS, normal 60 แยก high-speed, sensor rotation, เก็บ settings, ลด bind ซ้ำ, diagnostics และ CI |
| Claude | แตก `claude/glass-recovery` จาก `claude/glass-ui@683e8d3`: `GlassKit.kt`, `GlassWidgets.kt`, `StudioUi.kt`, `StudioSettings.kt`, `GalleryActivity.kt`; layout/gesture เท่านั้นใน Camera/VideoActivity | แก้ UI ตามต้นแบบ, ซ้อนเมนู, ตัดขอบ, swipe/zoom, accessibility, render cost, UI test navigation |
| Codex รวมงาน | PR แยก UI และ core เข้า `v0.3-studio` | ตรวจ shared hunk ด้วยมือ; ไม่แทน Activity ทั้งไฟล์ด้วยเวอร์ชันเก่า; run build/lint/JVM/emulator |

ยังไม่ merge PR #9 ระหว่างแก้บัค ไม่ต้องรอ PR #9 เพื่อเริ่ม UI หรือ helper ของ core อย่า push core ลง `claude/glass-ui` ซ้ำ รอบนี้ให้ PR UI อ้างเอกสารนี้และระบุ shared methods ที่แตะ

ต้นแบบ: [docs/claude/design](https://github.com/BunnyBubui/luma-camera/tree/claude/glass-ui/docs/claude/design) มี Main/Settings/Gallery.dc.html; อ่านเป็น source ได้ แต่ขาด Claude Design runtime จึงไม่ถือว่าเปิด browser ตรง ๆ แล้วตรวจต้นแบบครบแล้ว

## งาน Claude ที่ขอให้ลงมือ

1. **P0 เมนูซ้อน**: ภาพ Reno15 แสดงแผงตัวเลือกกับเมนู aspect ซ้อนและโปร่งจนอ่านยาก ให้มี modal owner เดียว ปิด popup/dial เดิมก่อนเปิด sheet หรือ submenu; ฉากหลังลดความเด่นเท่าเดียว ไม่ animate เลเยอร์ที่ถูกปิดกลับมา; Back/outside tap/เลือกค่า/เปิดเร็วซ้ำต้องไม่เหลือ ghost view หรือตัวรับสัมผัส
2. **P0 ปัดขึ้นไม่ได้**: `installDockSwipe` ติด listener ที่ parent แต่ child รับ touch ไปก่อน; handle คืน false ตอน DOWN จึงอาจไม่ได้ MOVE/UP ใช้ dispatch/intercept ที่แยก vertical drag เมื่อเกิน touchSlop แต่ยังให้ปุ่มกดและ CenterStrip ปัดแนวนอนได้ มี accessibility action และปุ่มเปิดแผงที่กดได้
3. **P0 ลากซูมไม่ได้**: `zoomTouch` คืน false บน DOWN และติดที่ LinearLayout parent ขณะที่ปุ่มลูก consume event ให้เจ้าของ gesture รับทั้ง stream ตั้งแต่ DOWN, แยก tap/drag/cancel, ขอ parent ไม่ intercept หลังยืนยันแกน, คืนสถานะเมื่อ CANCEL/เปลี่ยนหน้า ตรวจทั้ง capsule, lens cell, wheel และ pinch กล้องจริง
4. **P1 ขอบ/ชิ้นส่วนขาด**: safe insets, rounded clip outline กับ background ใช้ radius เดียวกัน, nested ScrollView และ CenterStrip ต้องเลื่อน item สุดท้ายเข้ามาเห็น/กดได้เต็ม cell มี end padding; popup จำกัดขนาดตามพื้นที่จริง sheet scroll ได้ก่อนล้นขอบ gesture inset; ทดสอบแนวตั้ง/แนวนอนและ font 1.0/1.3/2.0
5. **P1 ความลื่น UI**: cache SVG Path ตาม icon/path/size แทน parse ทุก onDraw, ลด bitmap snapshot / blur pulse และ allocation ระหว่าง drag, cancel animator/runnable เมื่อ detach, เคารพ reduce_motion ห้าม cache Bitmap ขนาด preview หลายชุดโดยไม่คืน memory วัด frame timing ก่อน/หลัง ไม่อ้างตัวเลขที่ไม่ได้วัด
6. **P1 state/accessibility**: selected/isSelected/stateDescription บน CenterStrip/GlassMenu, targets >=48 dp, text wrap ตาม font scale, custom scroll semantics/actions สำหรับ TalkBack คง GlassShutter button role และ GlassSwitch checkable
7. อัปเดต navigation ของ CameraFlowTest/StudioFlowTest ให้ตาม UI ใหม่ **คง assertion ไฟล์ RAW/JPEG/AI, จำนวนเฟรม, dimension, video/audio/pause และ no-skip** ไม่ลด audit เพื่อให้ผ่าน
8. Night stop: `nightProgress` เปลี่ยน `nightRunning` หลัง shutter ถูก disable แต่ไม่ refresh ให้แก้ enabled/description ขณะเริ่ม night และมี test กดหยุด burst จริง

## ขอบเขตที่ต้องประสาน

- Claude แก้ `VideoActivity.buildScreen`, `recordingUi`, `openDial`, `applyChip` และ touch/layout ได้ แต่ไม่แก้ bind/start/finalize ของกล้องหรือสร้างชุด 60/120/240 เอง
- Codex จะส่ง `VideoSessions.rates(info, quality, dynamic, eis, slow)` คืน `Mode(range, source)`; `label(mode)` ระบุ fixed หรือสูงสุดสำหรับ variable range UI ใช้ผลนี้ทั้งเมนูและ dial อย่าอ่าน `supportedFrameRateRanges` ทั้งกล้องแทน
- `applyChip` ที่ debounce 450 ms ต้อง disable record ทันทีเมื่อชุดเปลี่ยน, block ชิป/slider ที่เปิดค้างขณะ recording, และผูก record กับ config ที่ bind สำเร็จเท่านั้น Codex จะรวม guard ตอน integrate UI; Claude คง callback/state hook ไว้
- UI ห้ามเรียก unbind/bind เพื่อเปลี่ยนไอคอนหรือ highlight ล้วน ๆ; ประสานการ reuse PreviewView และ generation token กับ core
- Rotation ของไฟล์ใช้ sensor แยกจากการหมุน layout: UI ย้าย layout ตาม config change ได้โดยไม่ reset FPS/quality และไม่หยุดคลิป
- Looks/brightness/warmth ยังเป็น preview-only ให้ระบุชัด; แกลเลอรีทั้งเครื่องตรวจ permission แบบ selected access บน Android 13/14+

## เกณฑ์ส่งกลับ

- แนบ PR, commit, build/lint/JVM และ emulator log พร้อม screenshot camera/settings/gallery แนวตั้ง+แนวนอน
- เพิ่ม gesture test: ปัดขึ้นจาก handle/dock, กดหลังปัด, ลากซูมจากปุ่มเลนส์, scroll สุดซ้าย/ขวาให้ cell สุดท้ายมองเห็น, เปิด/ปิดเมนูเร็ว 20 ครั้งไม่มี overlay ค้าง
- baseline Studio emulator 30/30; Glass เดิม 23/31 โดย 7 fail จาก selector/navigation เก่าและ 1 accessibility audit ไม่ใช้ผลนี้สรุปว่า fusion เสีย
- Reno15: normal video ทุกคู่ที่แสดงว่า 60 fps ถ่ายฉากสว่าง >=10s ตรวจ sample FPS; slow 120/240 ตรวจ playback duration และ sensor timing แยกกัน; หมุนก่อน/ระหว่างอัด; วัด mode-switch P50/P95 จากเครื่องเดียวกัน หากยังไม่วัดให้เขียน pending

## สถานะ

เอกสารนี้เป็นการมอบหมายที่เจ้าของโปรเจกต์นำให้ Claude อีก session ได้ ยังไม่ได้ส่งข้อความเข้า Claude โดยอัตโนมัติ Core fixes อยู่ PR แยกและยังต้องผ่าน CI/ทดสอบ Reno15 ห้ามถือว่าการเพิ่มตัวเลือกหมายถึง hardware บันทึกได้แล้ว
