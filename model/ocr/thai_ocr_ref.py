"""Reference implementation of the Luma Thai OCR pipeline using cv2.dnn (OpenCV 4.12).

Every step mirrors ThaiOcrEngine.kt so results on the same fixture should match the
Android run up to float rounding in resize/normalisation.
"""
import json, math, sys, time, unicodedata
from pathlib import Path
import numpy as np, cv2

DET_MAX_SIDE = 1280
DET_MIN_SIDE = 64
DET_THRESH = 0.3
BOX_THRESH = 0.6
UNCLIP_RATIO = 1.5
MIN_BOX_SIDE = 3
REC_HEIGHT = 48
DROP_SCORE = 0.5


def load(det_path, rec_path, dict_path, rec_width):
    det = cv2.dnn.readNetFromONNX(det_path)
    rec = cv2.dnn.readNetFromONNX(rec_path)
    chars = [l.rstrip("\n") for l in open(dict_path, encoding="utf-8")]
    return det, rec, ["<blank>"] + chars + [" "], rec_width


def det_size(h, w):
    scale = min(1.0, DET_MAX_SIDE / max(h, w))
    if min(h, w) * scale < DET_MIN_SIDE:
        scale = DET_MIN_SIDE / min(h, w)
    rh = max(32, int(round(h * scale / 32)) * 32)
    rw = max(32, int(round(w * scale / 32)) * 32)
    return rh, rw


def detect(det, bgr):
    h, w = bgr.shape[:2]
    rh, rw = det_size(h, w)
    img = cv2.resize(bgr, (rw, rh), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0
    img = (img - np.array([0.485, 0.456, 0.406], np.float32)) / np.array([0.229, 0.224, 0.225], np.float32)
    det.setInput(img.transpose(2, 0, 1)[None])
    prob = det.forward()[0, 0]
    bitmap = (prob > DET_THRESH).astype(np.uint8) * 255
    contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    sx, sy = w / rw, h / rh
    for c in contours[:1000]:
        rect = cv2.minAreaRect(c)
        if min(rect[1]) < MIN_BOX_SIDE:
            continue
        x, y, bw, bh = cv2.boundingRect(c)
        mask = np.zeros((bh, bw), np.uint8)
        cv2.fillPoly(mask, [c - [x, y]], 1)
        score = cv2.mean(prob[y:y + bh, x:x + bw], mask)[0]
        if score < BOX_THRESH:
            continue
        (cx, cy), (rw_, rh_), ang = rect
        # Offsetting a rectangle by d grows both sides by 2d; d follows DBNet's area * ratio / perimeter.
        d = rw_ * rh_ * UNCLIP_RATIO / (2 * (rw_ + rh_))
        grown = ((cx, cy), (rw_ + 2 * d, rh_ + 2 * d), ang)
        if min(grown[1]) < MIN_BOX_SIDE + 2:
            continue
        pts = cv2.boxPoints(grown)
        pts[:, 0] = np.clip(pts[:, 0] * sx, 0, w - 1)
        pts[:, 1] = np.clip(pts[:, 1] * sy, 0, h - 1)
        boxes.append(order(pts))
    return boxes


def order(pts):
    pts = sorted(pts.tolist(), key=lambda p: p[0])
    left = sorted(pts[:2], key=lambda p: p[1]); right = sorted(pts[2:], key=lambda p: p[1])
    return np.float32([left[0], right[0], right[1], left[1]])  # tl, tr, br, bl


def crop(bgr, box):
    tl, tr, br, bl = box
    cw = int(round(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl))))
    ch = int(round(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr))))
    cw, ch = max(cw, 1), max(ch, 1)
    m = cv2.getPerspectiveTransform(box, np.float32([[0, 0], [cw, 0], [cw, ch], [0, ch]]))
    out = cv2.warpPerspective(bgr, m, (cw, ch), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    if ch / cw >= 1.5:
        out = cv2.rotate(out, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return out


def split_points(line, max_ratio):
    """Cut a too-long line at the emptiest column near each ideal cut position."""
    h, w = line.shape[:2]
    limit = int(h * max_ratio)
    if w <= limit:
        return [0, w]
    gray = cv2.cvtColor(line, cv2.COLOR_BGR2GRAY)
    ink = (255 - gray.astype(np.int32)).sum(axis=0) if gray.mean() > 127 else gray.astype(np.int32).sum(axis=0)
    cuts = [0]
    while w - cuts[-1] > limit:
        start = cuts[-1]
        lo, hi = start + int(limit * 0.6), start + limit
        cuts.append(lo + int(np.argmin(ink[lo:hi])))
    return cuts + [w]


def recognize_line(rec, table, rec_width, line):
    max_ratio = rec_width / REC_HEIGHT
    cuts = split_points(line, max_ratio)
    texts, scores = [], []
    for a, b in zip(cuts, cuts[1:]):
        piece = line[:, a:b]
        h, w = piece.shape[:2]
        tw = min(rec_width, max(1, int(math.ceil(REC_HEIGHT * w / h))))
        img = cv2.resize(piece, (tw, REC_HEIGHT), interpolation=cv2.INTER_LINEAR).astype(np.float32)
        img = (img / 255.0 - 0.5) / 0.5
        blob = np.zeros((1, 3, REC_HEIGHT, rec_width), np.float32)
        blob[0, :, :, :tw] = img.transpose(2, 0, 1)
        rec.setInput(blob)
        probs = rec.forward()[0]
        text, score = ctc_decode(probs, table)
        texts.append(text); scores.append(score)
    return "".join(texts), (float(np.mean(scores)) if scores else 0.0)


def ctc_decode(probs, table):
    idx = probs.argmax(axis=1); conf = probs.max(axis=1)
    out, keep, prev = [], [], -1
    for t, i in enumerate(idx):
        if i != prev and i != 0:
            out.append(table[i]); keep.append(conf[t])
        prev = i
    return "".join(out), (float(np.mean(keep)) if keep else 0.0)


def frame_angle(boxes):
    """Median direction of the top edges of elongated boxes, in radians."""
    angles = []
    for b in boxes:
        v = b[1] - b[0]
        if np.linalg.norm(v) >= 2 * np.linalg.norm(b[3] - b[0]):
            angles.append(math.atan2(v[1], v[0]))
    return float(np.median(angles)) if angles else 0.0


def extents(boxes, angle):
    """Axis-aligned (left, top, right, bottom) of each box after rotating the page by -angle."""
    c, s = math.cos(-angle), math.sin(-angle)
    out = []
    for b in boxes:
        x = b[:, 0] * c - b[:, 1] * s; y = b[:, 0] * s + b[:, 1] * c
        out.append((x.min(), y.min(), x.max(), y.max()))
    return out


def drop_fragments(boxes, angle):
    """Remove small boxes that sit on a much taller line, such as detached tone marks."""
    ext = extents(boxes, angle); keep = []
    for i, (l, t, r, b) in enumerate(ext):
        h = b - t; cx = (l + r) / 2; cy = (t + b) / 2; fragment = False
        for j, (l2, t2, r2, b2) in enumerate(ext):
            h2 = b2 - t2
            if j != i and h2 >= 2 * h and l2 <= cx <= r2 and abs(cy - (t2 + b2) / 2) < h2:
                fragment = True; break
        if not fragment: keep.append(boxes[i])
    return keep


def group_lines(items, angle):
    """items: (box, text). Same line when vertical overlap, in the page frame, exceeds half the smaller height."""
    ext = extents([b for b, _ in items], angle)
    order_ = sorted(range(len(items)), key=lambda i: ((ext[i][1] + ext[i][3]) / 2, ext[i][0]))
    lines = []
    for i in order_:
        l, top, r, bottom = ext[i]
        for line in lines:
            lt, lb = line["top"], line["bottom"]
            if min(bottom, lb) - max(top, lt) > 0.5 * min(bottom - top, lb - lt):
                line["items"].append((l, items[i][1])); line["top"] = min(lt, top); line["bottom"] = max(lb, bottom); break
        else:
            lines.append({"top": top, "bottom": bottom, "items": [(l, items[i][1])]})
    lines.sort(key=lambda ln: ln["top"])
    return "\n".join(" ".join(t for _, t in sorted(ln["items"])) for ln in lines)


def run(models, bgr):
    det, rec, table, rec_width = models
    boxes = detect(det, bgr)
    angle = frame_angle(boxes)
    items = []
    for box in drop_fragments(boxes, angle):
        text, score = recognize_line(rec, table, rec_width, crop(bgr, box))
        if score >= DROP_SCORE and text.strip():
            items.append((box, text.strip()))
    return group_lines(items, angle)


def normalize(s):
    s = unicodedata.normalize("NFC", s).replace("ํา", "ำ")
    for z in ("​", "‌", "‍", "﻿"):
        s = s.replace(z, "")
    return " ".join(s.split())


def edit(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def cer(truth, got):
    t = normalize(truth).replace(" ", ""); g = normalize(got).replace(" ", "")
    return edit(t, g) / max(1, len(t))


def wer(truth, got):
    t = normalize(truth).split(); g = normalize(got).split()
    return edit(t, g) / max(1, len(t))


if __name__ == "__main__":
    # Usage: python3 model/ocr/thai_ocr_ref.py app/src/main/assets/ocr app/src/androidTest/assets/ocr
    mdir, fdir = Path(sys.argv[1]), Path(sys.argv[2])
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 640
    models = load(str(mdir / "ppocrv5_mobile_det.onnx"), str(mdir / f"th_ppocrv5_mobile_rec_w{width}.onnx"), str(mdir / "ppocrv5_th_dict.txt"), width)
    truth = json.loads((fdir / "truth.json").read_text(encoding="utf-8"))
    total_e = total_n = 0
    for name, t in truth.items():
        path = next(p for p in fdir.iterdir() if p.stem == name and p.suffix in (".png", ".jpg"))
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        t0 = time.time(); got = run(models, img); dt = time.time() - t0
        c = cer(t, got); total_e += edit(normalize(t).replace(" ", ""), normalize(got).replace(" ", "")); total_n += len(normalize(t).replace(" ", ""))
        print(f"{name:18s} CER {c:.3f} WER {wer(t, got):.3f} {dt*1000:6.0f} ms | {got!r}")
    print(f"pooled CER {total_e/max(1,total_n):.4f}")
