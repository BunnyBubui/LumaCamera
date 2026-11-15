#!/usr/bin/env python3
"""Deterministic synthetic tests for metrics/provenance, NOT perceptual acceptance."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PIL import Image
from benchmark_sr3 import run_manifest, sha256
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
        self.assertIsNone(report["cases"][0]["classic"]["SSIM_RGB"])
        self.assertIn("scale:2", report["summary"])
        self.assertEqual(report["summary"]["scale:2"]["independentHR"], 1)
        json.dumps(report, allow_nan=False)

    def test_valid_three_and_four_times_scales(self):
        for scale in (3, 4):
            Image.new("RGB", (5 * scale, 4 * scale), (70, 100, 130)).save(self.root / "classic.png")
            Image.new("RGB", (5 * scale, 4 * scale), (72, 102, 132)).save(self.root / "natural.png")
            Image.new("RGB", (5 * scale, 4 * scale), (71, 101, 131)).save(self.root / "reference.png")
            self.case["scale"] = scale
            report = run_manifest(self.manifest())
            self.assertEqual(report["cases"][0]["natural"]["width"], 5 * scale)
            self.assertIn(f"scale:{scale}", report["summary"])
            if scale==4:
                self.assertAlmostEqual(report["cases"][0]["classic"]["SSIM_RGB"],1.0,places=3)

    def test_reject_mismatched_export_size(self):
        self.case["scale"] = 3
        with self.assertRaisesRegex(ValueError, "expected both exports"):
            run_manifest(self.manifest())

    def test_reject_duplicate_ids(self):
        with self.assertRaisesRegex(ValueError, "unique"):
            run_manifest(self.manifest([self.case, dict(self.case)]))

    def test_perfect_reference_has_valid_json_and_explicit_exact_match(self):
        Image.new("RGB", (10, 8), (70, 100, 130)).save(self.root / "reference.png")
        report = run_manifest(self.manifest())
        self.assertIsNone(report["cases"][0]["classic"]["PSNR_dB"])
        self.assertTrue(report["cases"][0]["classic"]["referenceExactMatch"])
        json.dumps(report, allow_nan=False)

    def test_no_ground_truth_does_not_claim_psnr(self):
        self.case.pop("reference_hr")
        report = run_manifest(self.manifest())
        self.assertFalse(report["cases"][0]["hasIndependentHR"])
        self.assertNotIn("PSNR_dB", report["cases"][0]["natural"])
        self.assertNotIn("SSIM_RGB", report["cases"][0]["natural"])

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

    def reports(self):
        reports=[]
        for profile in ("CLASSIC","NATURAL"):
            value={"schema":"lumora-sr-render-v2","profile":profile,
                "sourceSha256":sha256(self.root/"source.png"),"scale":2,
                "outputWidth":10,"outputHeight":8,"strength":100,
                "modelSha256":"a"*64,"renderRevision":"test-v1","backend":"CPU"}
            reports.append(value)
            key="report_"+profile.lower()
            self.case[key]=profile+".json"
            (self.root/self.case[key]).write_text(json.dumps(value))
        return reports

    def test_export_reports_verify_both_profiles_and_source(self):
        self.reports()
        result=run_manifest(self.manifest())["cases"][0]
        self.assertTrue(result["exportProvenanceChecked"])
        self.assertEqual(result["exportReports"]["natural"]["backend"],"CPU")

    def test_legacy_exports_explicitly_lack_verified_reports(self):
        self.assertFalse(run_manifest(self.manifest())["cases"][0]["exportProvenanceChecked"])

    def test_rejects_stale_wrong_profile_and_wrong_strength_reports(self):
        for field,value in (("sourceSha256","b"*64),("profile","CLASSIC"),("strength",70),
                            ("scale",4),("modelSha256","not-a-hash")):
            reports=self.reports();reports[1][field]=value
            (self.root/"NATURAL.json").write_text(json.dumps(reports[1]))
            with self.subTest(field=field),self.assertRaises(ValueError):
                run_manifest(self.manifest())

    def test_rejects_only_one_sidecar(self):
        self.reports();self.case.pop("report_natural")
        with self.assertRaisesRegex(ValueError,"both export"):
            run_manifest(self.manifest())


if __name__ == "__main__":
    unittest.main()
