"""Render synthetic OCR fixtures with known ground truth.

Usage: python3 model/ocr/gen_fixtures.py <font dir> app/src/androidTest/assets/ocr
Fonts: Sarabun-Regular/Bold, NotoSansThai, NotoSerifThai, Kanit-Regular, ChakraPetch-Regular from
github.com/google/fonts (ofl/). Variable Noto files are saved without the axis suffix in the name.

Text is original and written for this test set. Fonts are SIL OFL 1.1 (Google Fonts).
Rendering uses Pillow with libraqm so Thai marks are shaped correctly.
"""
import json, math, sys
from pathlib import Path
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

FONTS = Path(sys.argv[1]); OUT = Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(20261109)

def font(name, size, wght=None):
    f = ImageFont.truetype(str(FONTS / name), size, layout_engine=ImageFont.Layout.RAQM)
    if wght is not None:
        try: f.set_variation_by_axes([100, wght])
        except Exception: pass
    return f

def render(lines, f, fg=(20, 20, 20), bg=(250, 250, 248), pad=40, spacing=0.55, width=None):
    asc, desc = f.getmetrics(); lh = int((asc + desc) * (1 + spacing))
    w = width or max(int(f.getlength(l)) for l in lines) + 2 * pad
    h = lh * len(lines) + 2 * pad
    img = Image.new("RGB", (w, h), bg); d = ImageDraw.Draw(img)
    for i, l in enumerate(lines): d.text((pad, pad + i * lh), l, font=f, fill=fg)
    return np.array(img)[:, :, ::-1].copy()  # BGR

def photo(bgr, angle, persp, blur, noise, jpeg, light):
    h, w = bgr.shape[:2]; m = 60
    canvas = cv2.copyMakeBorder(bgr, m, m, m, m, cv2.BORDER_CONSTANT, value=(250, 250, 248))
    H, W = canvas.shape[:2]
    rot = cv2.getRotationMatrix2D((W / 2, H / 2), angle, 1.0)
    canvas = cv2.warpAffine(canvas, rot, (W, H), borderValue=(250, 250, 248))
    s = persp * W
    src = np.float32([[0, 0], [W, 0], [W, H], [0, H]]); dst = np.float32([[s, 0], [W - s * 0.3, s * 0.5], [W, H], [0, H - s * 0.4]])
    canvas = cv2.warpPerspective(canvas, cv2.getPerspectiveTransform(src, dst), (W, H), borderValue=(235, 235, 230))
    if blur: canvas = cv2.GaussianBlur(canvas, (0, 0), blur)
    yy, xx = np.mgrid[0:H, 0:W]; shade = 1 - light * (xx / W) * (yy / H)
    canvas = np.clip(canvas * shade[..., None] + rng.normal(0, noise, canvas.shape), 0, 255).astype(np.uint8)
    ok, enc = cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, jpeg]); return cv2.imdecode(enc, cv2.IMREAD_COLOR)

DOC = ["แอปกล้องนี้อ่านข้อความจากภาพได้โดยไม่ต้องใช้อินเทอร์เน็ต",
       "ภาพทุกภาพประมวลผลบนเครื่อง ไม่มีการส่งไปยังเซิร์ฟเวอร์",
       "ผู้ใช้ควรถือกล้องให้นิ่งและให้แสงสว่างเพียงพอ",
       "ตัวอักษรที่เล็กเกินไปอาจอ่านผิดหรืออ่านไม่ได้",
       "กรุณาตรวจสอบผลลัพธ์ทุกครั้งก่อนนำไปใช้งานจริง"]
RECEIPT = ["ใบเสร็จรับเงิน เลขที่ INV-2026/0117",
           "วันที่ 9 พ.ย. 2569 เวลา 14:35 น.",
           "กาแฟเย็น 2 แก้ว 120.00 บาท",
           "Total: 1,250.75 THB (VAT 7%)",
           "ติดต่อ 081-234-5678 หรือ info@example.com"]
cases = {
    "sign_th": (["ทางออกฉุกเฉิน", "ห้ามจอดรถขวางประตู"], lambda l: render(l, font("Sarabun-Bold.ttf", 64))),
    "document_th": (DOC, lambda l: render(l, font("NotoSansThai.ttf", 30))),
    "receipt_mixed": (RECEIPT, lambda l: render(l, font("Sarabun-Regular.ttf", 34))),
    "english_ui": (["Battery 87% - Storage 12.4 GB free", "Open Settings > Camera > Grid"], lambda l: render(l, font("ChakraPetch-Regular.ttf", 36))),
    "thai_digits": (["ราคา ๑,๒๕๐ บาท ลดเหลือ ๙๙๙ บาท", "โทร ๐๒-๑๒๓-๔๕๖๗"], lambda l: render(l, font("Kanit-Regular.ttf", 40))),
    "tone_marks_serif": (["น้ำใจไม่ต้องซื้อหา", "ผู้ใหญ่ใจดีพาเด็ก ๆ ไปเที่ยวทะเล", "ปู่ย่าตายายเล่าเรื่องเก่าก่อนนอน"], lambda l: render(l, font("NotoSerifThai.ttf", 38))),
    "small_text": (DOC[:3], lambda l: render(l, font("NotoSansThai.ttf", 16), pad=20)),
    "low_contrast": (DOC[2:4], lambda l: render(l, font("Sarabun-Regular.ttf", 32), fg=(120, 120, 120), bg=(190, 190, 186))),
    "photo_skewed": (DOC[:4], lambda l: photo(render(l, font("Sarabun-Regular.ttf", 32)), 6, 0.04, 1.0, 6, 60, 0.35)),
    "tilted_15deg": (RECEIPT[:3], lambda l: photo(render(l, font("Sarabun-Regular.ttf", 34)), 15, 0.0, 0.6, 3, 85, 0.1)),
}
truth = {}
for name, (lines, make) in cases.items():
    img = make(lines)
    photo_like = name.startswith(("photo_", "tilted_"))
    cv2.imwrite(str(OUT / (name + (".jpg" if photo_like else ".png"))), img, [cv2.IMWRITE_JPEG_QUALITY, 92] if photo_like else [])
    truth[name] = "\n".join(lines)
# A page with paper texture and no text.
paper = np.clip(np.full((600, 800, 3), 236, np.float32) + cv2.GaussianBlur(rng.normal(0, 18, (600, 800, 1)).astype(np.float32), (0, 0), 3)[..., None].repeat(3, 2) + rng.normal(0, 3, (600, 800, 3)), 0, 255).astype(np.uint8)
cv2.imwrite(str(OUT / "blank_paper.jpg"), paper, [cv2.IMWRITE_JPEG_QUALITY, 90]); truth["blank_paper"] = ""
(OUT / "truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
print({k: v.count("\n") + 1 if v else 0 for k, v in truth.items()})
