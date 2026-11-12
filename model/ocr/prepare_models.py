"""Download the OCR models from pinned sources, convert the recogniser for OpenCV DNN and verify hashes.

Usage: python3 model/ocr/prepare_models.py
Needs: onnx 1.23, onnxsim 0.8.1, onnxruntime, opencv-python-headless 4.12.0.88, numpy.

The recogniser exported by paddle2onnx has a dynamic width; OpenCV 4.12 DNN cannot import its Shape
ops ("dynamic 'zero' shapes are not supported"). onnxsim fixes the input to 1x3x48x640 and folds those
ops. The script then checks that OpenCV and onnxruntime agree on random input.
"""
import hashlib, sys, tempfile, urllib.request
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/ocr"
WORK = Path(tempfile.gettempdir()) / "luma-ocr-models"
OAR = "https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/"  # tag commit 7a0ea9cbe5de7d9e4c8415b032f4854fb688245e
PADDLE = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/"
SOURCES = {
    "pp-ocrv5_mobile_det.onnx": (OAR + "pp-ocrv5_mobile_det.onnx", "1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039"),
    "th_pp-ocrv5_mobile_rec.onnx": (OAR + "th_pp-ocrv5_mobile_rec.onnx", "5f6ee21242691681261fee01bc39867da9cc8ff9b889f2f048b3cb7f74380217"),
    "ppocrv5_th_dict.txt": (PADDLE + "ppocr/utils/dict/ppocrv5_th_dict.txt", "57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a"),
}
WIDTH = 640


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(name):
    url, digest = SOURCES[name]
    path = WORK / name
    if not path.exists():
        WORK.mkdir(exist_ok=True)
        path.write_bytes(urllib.request.urlopen(url, timeout=120).read())
    if sha(path) != digest:
        sys.exit(f"{name}: SHA-256 {sha(path)} does not match {digest}")
    return path


def main():
    import onnx, onnxruntime, cv2
    from onnxsim import simplify
    det, rec, dictionary = (fetch(n) for n in SOURCES)
    model, ok = simplify(onnx.load(rec), overwrite_input_shapes={"x": [1, 3, 48, WIDTH]})
    assert ok, "onnxsim could not validate the simplified recogniser"
    fixed = WORK / f"th_ppocrv5_mobile_rec_w{WIDTH}.onnx"
    onnx.save(model, fixed)
    x = np.random.default_rng(1).standard_normal((1, 3, 48, WIDTH)).astype(np.float32)
    ref = onnxruntime.InferenceSession(str(rec), providers=["CPUExecutionProvider"]).run(None, {"x": x})[0]
    net = cv2.dnn.readNetFromONNX(str(fixed)); net.setInput(x); out = net.forward()
    print("OpenCV vs onnxruntime: max |diff|", float(np.abs(ref - out).max()), "argmax agreement", float((ref.argmax(-1) == out.argmax(-1)).mean()))
    ASSETS.mkdir(parents=True, exist_ok=True)
    for src, dst in [(det, "ppocrv5_mobile_det.onnx"), (fixed, fixed.name), (dictionary, "ppocrv5_th_dict.txt")]:
        (ASSETS / dst).write_bytes(src.read_bytes())
        print(dst, sha(ASSETS / dst))


if __name__ == "__main__":
    main()
