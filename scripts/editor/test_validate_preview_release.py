import unittest

from validate_preview_release import REQUIRED_CASES, evaluate, p95


def full_report():
    return {
        "schema_version": 1,
        "build_sha": "a1b2c3d4",
        "device": {"kind": "physical", "model": "QA sample (synthetic fixture)", "api_level": 35},
        "cases": [{"id": c, "status": "pass", "evidence": "synthetic/unit-test-only"} for c in sorted(REQUIRED_CASES)],
        "metrics": {
            "preview_1080p30": {
                "render_source": "frame_presented_trace",
                "rendered_frames": 100, "on_time_frames": 95,
                "seam_gap_us": [10000, 20000, 30000, 40000, 60000],
                "black_join_frames": 0, "av_sync_offset_ms": [-50, 2, 45],
            },
            "soak": {"elapsed_ms": 600000, "crashes": 0},
        },
    }


class GateTests(unittest.TestCase):
    def test_nearest_rank_p95(self):
        self.assertEqual(9, p95([1, 9, 3, 8]))

    def test_exact_threshold_passes_synthetic_fixture(self):
        self.assertEqual("PASS", evaluate(full_report())[0])

    def test_missing_case_never_passes(self):
        x = full_report()
        x["cases"].pop()
        self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_not_run_case_never_passes(self):
        x = full_report()
        x["cases"][0]["status"] = "not_run"
        self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_ui_handler_ticks_are_not_rendered_frames(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["render_source"] = "handler_ticks"
        self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_one_frame_is_not_sufficient_coverage(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["rendered_frames"] = 1
        x["metrics"]["preview_1080p30"]["on_time_frames"] = 1
        self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_fail_lower_than_95_percent(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["on_time_frames"] = 94
        self.assertEqual("FAIL", evaluate(x)[0])

    def test_fail_black_join_frame(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["black_join_frames"] = 1
        self.assertEqual("FAIL", evaluate(x)[0])

    def test_single_join_is_not_p95_evidence(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["seam_gap_us"] = [1000]
        self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_fail_p95_seam_gap(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["seam_gap_us"] = [70000] * 10
        self.assertEqual("FAIL", evaluate(x)[0])

    def test_fail_av_sync(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["av_sync_offset_ms"] = [-51]
        self.assertEqual("FAIL", evaluate(x)[0])

    def test_missing_audio_measurement_incomplete(self):
        x = full_report()
        x["metrics"]["preview_1080p30"]["av_sync_offset_ms"] = []
        self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_fail_soak_short_or_crashed(self):
        x = full_report()
        x["metrics"]["soak"] = {"elapsed_ms": 599999, "crashes": 1}
        self.assertEqual("FAIL", evaluate(x)[0])

    def test_nan_inf_negative_and_bool_are_rejected(self):
        for bad in (float("nan"), float("inf"), -1, True):
            x = full_report()
            x["metrics"]["preview_1080p30"]["seam_gap_us"] = [bad]
            self.assertEqual("INCOMPLETE", evaluate(x)[0])

    def test_fail_dominates_incomplete(self):
        x = full_report()
        x["cases"].pop()
        x["metrics"]["preview_1080p30"]["black_join_frames"] = 2
        self.assertEqual("FAIL", evaluate(x)[0])


if __name__ == "__main__":
    unittest.main()
