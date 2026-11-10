#!/usr/bin/env python3
"""Convert official Real-ESRGAN general-x4v3 weights; never train random stand-ins.

Architecture: https://github.com/xinntao/Real-ESRGAN/blob/master/realesrgan/archs/srvgg_arch.py
The last convolution is reordered from PyTorch CRD to TensorFlow DCR pixel shuffle.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")
import numpy as np
import tensorflow as tf
import torch
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
CHECKPOINTS = ROOT / "build/detail-source"
INPUT = 200
NAMES = ["realesr-general-x4v3", "realesr-general-wdn-x4v3"]
URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/{}.pth"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    tf.config.threading.set_inter_op_parallelism_threads(2)
    tf.config.threading.set_intra_op_parallelism_threads(2)
    torch.set_num_threads(2)
    CHECKPOINTS.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    source_hashes = {}
    states = []
    lock_file = ROOT / "model/detail-source-lock.json"
    lock = json.loads(lock_file.read_text()) if lock_file.exists() else {}
    for name in NAMES:
        path = CHECKPOINTS / (name + ".pth")
        if not path.exists():
            request = urllib.request.Request(URL.format(name), headers={"User-Agent": "LumaCamera-model-build"})
            with urllib.request.urlopen(request, timeout=120) as response:
                path.write_bytes(response.read())
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        source_hashes[name] = digest
        if name in lock and lock[name] != digest:
            raise ValueError("Upstream checkpoint checksum mismatch: " + name)
        checkpoint = torch.load(path, map_location="cpu", weights_only=True)
        states.append(checkpoint.get("params_ema", checkpoint.get("params", checkpoint)))
    # Official DNI denoising weight interpolation. This builds one network, not two passes.
    state = {k: (states[0][k].float() + states[1][k].float()) * 0.5 for k in states[0]}
    assert len(state) == 101, (len(state), list(state))

    class Network(tf.Module):
        def __init__(self):
            super().__init__()
            self.layers = []
            for i in range(0, 67, 2):
                weight = state[f"body.{i}.weight"].numpy()
                bias = state[f"body.{i}.bias"].numpy()
                if i == 66:
                    order = np.arange(48).reshape(3, 4, 4).transpose(1, 2, 0).reshape(-1)
                    weight, bias = weight[order], bias[order]
                self.layers.append((tf.constant(weight.transpose(2, 3, 1, 0)), tf.constant(bias),
                    tf.constant(state[f"body.{i+1}.weight"].numpy()) if i < 66 else None))

        @tf.function
        def forward(self, image):
            x = image
            for weight, bias, alpha in self.layers:
                x = tf.nn.bias_add(tf.nn.conv2d(x, weight, strides=1, padding="SAME"), bias)
                if alpha is not None:
                    x = tf.maximum(x, 0.) + alpha * tf.minimum(x, 0.)
            x = tf.nn.depth_to_space(x, 4)
            base = tf.image.resize(image, tf.shape(image)[1:3] * 4, method="nearest")
            return {"enhanced": x + base}

    network = Network()
    concrete = network.forward.get_concrete_function(tf.TensorSpec([1, INPUT, INPUT, 3], tf.float32, name="image"))
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], network)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    model = converter.convert()
    destination = ASSETS / "luma-detail-sr2.tflite"
    destination.write_bytes(model)

    def reference(image):
        with torch.inference_mode():
            base = torch.from_numpy(image.transpose(0, 3, 1, 2).copy())
            x = base
            for i in range(0, 67, 2):
                x = F.conv2d(x, state[f"body.{i}.weight"], state[f"body.{i}.bias"], padding=1)
                if i < 66:
                    x = F.prelu(x, state[f"body.{i+1}.weight"])
            x = F.pixel_shuffle(x, 4) + F.interpolate(base, scale_factor=4, mode="nearest")
            return x.numpy().transpose(0, 2, 3, 1)

    validation = {}
    if args.verify:
        rng = np.random.default_rng(15)
        sample = rng.uniform(0, 1, (1, 17, 19, 3)).astype(np.float32)
        expected = reference(sample)
        actual = network.forward(tf.constant(sample))["enhanced"].numpy()
        validation["tensorflowVsTorchMaxError"] = float(np.abs(actual-expected).max())
        assert np.allclose(actual, expected, atol=0.0005, rtol=0.0005), validation
        interpreter = tf.lite.Interpreter(model_content=model, num_threads=2)
        inp = interpreter.get_input_details()[0]
        interpreter.resize_tensor_input(inp["index"], sample.shape)
        interpreter.allocate_tensors()
        interpreter.set_tensor(inp["index"], sample)
        interpreter.invoke()
        quantized = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
        validation["fp16VsTorchMeanError"] = float(np.abs(quantized-expected).mean())
        validation["fp16VsTorchMaxError"] = float(np.abs(quantized-expected).max())
        assert validation["fp16VsTorchMeanError"] < 0.005, validation
        assert validation["fp16VsTorchMaxError"] < 0.05, validation
        # A second input must not reuse stale accelerator buffers.
        second = np.zeros_like(sample) + [0.1, 0.4, 0.8]
        interpreter.set_tensor(inp["index"], second.astype(np.float32)); interpreter.invoke()
        result2 = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
        assert not np.allclose(result2, quantized)
        validation["secondInputChangesOutput"] = True

    metadata = {"model": "DetailSR2", "upstream": "Real-ESRGAN general-x4v3 v0.2.5.0",
        "sourceSha256": source_hashes, "denoiseDni": 0.5, "precision": "FP16 weights / float32 I/O",
        "nativeScale": 4, "input": [1, INPUT, INPUT, 3], "core": 128, "halo": 36,
        "sha256": hashlib.sha256(model).hexdigest(), "bytes": len(model), "validation": validation}
    (ASSETS / "luma-detail-sr2.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (CHECKPOINTS / "observed-source-lock.json").write_text(json.dumps(source_hashes, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
