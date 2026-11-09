# Luma OCR models (Thai + English)

`app/src/main/assets/ocr/` holds three files used by `th.ogay.luma.ocr.ThaiOcrEngine`. They run on the
OpenCV DNN module that the app already ships (`org.opencv:opencv:4.12.0`); no other runtime is added.

| Asset | Source | SHA-256 | License |
|---|---|---|---|
| `ppocrv5_mobile_det.onnx` | PaddleOCR PP-OCRv5_mobile_det, ONNX export from GreatV/oar-ocr release v0.3.0 (`pp-ocrv5_mobile_det.onnx`), unchanged | `1eb7b4f7…a4039` | Apache-2.0 |
| `th_ppocrv5_mobile_rec_w640.onnx` | PaddleOCR th_PP-OCRv5_mobile_rec, ONNX export from GreatV/oar-ocr release v0.3.0 (`th_pp-ocrv5_mobile_rec.onnx`, SHA-256 `5f6ee212…80217`), input fixed to 1×3×48×640 with onnxsim 0.8.1 | `94675f51…a2eb7` | Apache-2.0 |
| `ppocrv5_th_dict.txt` | PaddleOCR `ppocr/utils/dict/ppocrv5_th_dict.txt` at commit `dab3fe3` | `57f5406f…6b7a` | Apache-2.0 |

Full hashes are in `SHA256SUMS`; check them with `sha256sum -c model/ocr/SHA256SUMS`.

`prepare_models.py` downloads the sources, verifies their hashes, converts the recogniser and checks that
OpenCV and onnxruntime agree. The conversion is needed because OpenCV 4.12 cannot import the dynamic
Shape operations of the original export. Running it again reproduces the same asset bytes.

`gen_fixtures.py` renders the synthetic test images in `app/src/androidTest/assets/ocr/` from original
text and SIL OFL fonts. `thai_ocr_ref.py` is a Python reference of the Kotlin pipeline on `cv2.dnn`;
running it on those fixtures prints per-image CER/WER.

The ONNX files were exported by a third party (oar-ocr) from the official PaddlePaddle weights. They
have not been compared with an export made directly from PaddlePaddle; the Paddle CDN and Hugging Face
were not reachable from the environment that prepared them.
