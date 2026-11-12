#!/usr/bin/env python3
"""Deterministic synthetic tests for metrics/provenance, NOT perceptual acceptance."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PIL import Image
from benchmark_sr3 import run_manifest
from evaluate_natural import text_cer


class BenchmarkSR3Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        Image.new("RGB", (5, 4), (70, 100, 130)).save(self.root / "source.png")
        Image.new("RGB", (10, 8), (70, 100, 130)).save(self.root / "classic.png")
        Image.new("RGB", (10, 8), (72, 102, 132)).save(self.root / "natural.png")
        Image.new("RGB", (10, 8), (71, 101, 131)).save(self.root / "reference.png")
        self.case = {"id": "01", "category": "portrait", "scale": 2,
                     "original": "source.png", "classic": "classic.png",
                     "natural": "natural.png", "reference_hr": "reference.png"}

    def manifest(self, cases=None):
        path = self.root / "manifest.json"
        path.write_text(json.dumps({"dataset_version": "test-v1",
                                    "cases": [self.case] if cases is None else cases}), encoding="utf-8")
        return path

    def test_equal_scale_metrics_provenance_and_aggregate(self):
        report = run_manifest(self.manifest())
        self.assertEqual(report["caseCount"], 1)
        self.assertTrue(report["cases"][0]["hasIndependentHR"])
        self.assertEqual(len(report["cases"][0]["inputSha256"]["original"]), 64)
        self.assertIn("PSNR_dB", report["cases"][0]["classic"])
        self.assertIn("scale:2", report["summary"])
        self.assertEqual(report["summary"]["scale:2"]["independentHR"], 1)
        json.dumps(report, allow_nan=False)

    def test_reject_mismatched_export_size(self):
        self.case["scale"] = 3
        with self.assertRaisesRegex(ValueError, "expected both exports"):
            run_manifest(self.manifest())

    def test_reject_duplicate_ids(self):
        with self.assertRaisesRegex(ValueError, "unique"):
            run_manifest(self.manifest([self.case, dict(self.case)]))

    def test_no_ground_truth_does_not_claim_psnr(self):
        self.case.pop("reference_hr")
        report = run_manifest(self.manifest())
        self.assertFalse(report["cases"][0]["hasIndependentHR"])
        self.assertNotIn("PSNR_dB", report["cases"][0]["natural"])

    def test_text_cer_treats_identical_thai_as_zero(self):
        self.assertEqual(text_cer("ข้อความ ๑๒๓", "ข้อความ ๑๒๓"), 0)
        self.assertGreater(text_cer("ข้อความ", "ข้อควาน"), 0)

    def test_partial_text_files_rejected(self):
        self.case["text_gt"] = "missing.txt"
        with self.assertRaisesRegex(ValueError, "all three"):
            run_manifest(self.manifest())

    def test_missing_inputs_rejected(self):
        self.case["natural"] = "absent.png"
        with self.assertRaisesRegex(ValueError, "Missing input"):
            run_manifest(self.manifest())

    def test_no_duplicate_or_false_quality_pass(self):
        report = run_manifest(self.manifest())
        self.assertNotIn("qualityPass", report)
        self.assertNotIn("trainedWeights", report)


if __name__ == "__main__":
    unittest.main()
