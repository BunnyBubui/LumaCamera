#!/usr/bin/env python3
"""Unit tests for the evaluator only (NOT model-quality measurements)."""
import unittest

try:
    from .evaluate_highlights import evaluate, enforce_gate, event_metrics, streak_metrics
except ImportError:
    from evaluate_highlights import evaluate, enforce_gate, event_metrics, streak_metrics


class HighlightEvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.truth = [
            {"asset_id": "g1", "start_us": 1_000_000, "end_us": 1_200_000, "type": "KILL", "streak_id": "multi"},
            {"asset_id": "g1", "start_us": 2_000_000, "end_us": 2_200_000, "type": "KILL", "streak_id": "multi"},
        ]
        self.pred = [
            {"asset_id": "g1", "at_us": 1_100_000, "type": "CONFIRMED_KILL"},
            {"asset_id": "g1", "at_us": 2_100_000, "type": "CONFIRMED_KILL"},
        ]
        self.clips = [{"asset_id": "g1", "start_us": 800_000, "end_us": 2_500_000}]

    def test_perfect_detection_and_streak_retention(self):
        report = evaluate({
            "schema": 1,
            "dataset_id": "unit-only",
            "ground_truth_events": self.truth,
            "predicted_events": self.pred,
            "selected_clips": self.clips,
        })
        self.assertEqual(1.0, report["kill_detection"]["precision"])
        self.assertEqual(1.0, report["kill_detection"]["recall"])
        self.assertEqual(1.0, report["multi_kill"]["retention"])
        self.assertEqual((True, []), enforce_gate(report))

    def test_duplicate_prediction_is_not_double_matched(self):
        p = self.pred + [dict(self.pred[0])]
        r = event_metrics(self.truth, p)
        self.assertEqual(2, r["true_positive"])
        self.assertEqual(1, r["false_positive"])
        self.assertAlmostEqual(2 / 3, r["precision"])

    def test_cross_asset_predictions_cannot_match(self):
        wrong = [dict(self.pred[0], asset_id="different")]
        r = event_metrics(self.truth, wrong)
        self.assertEqual(0, r["true_positive"])

    def test_high_volume_alone_cannot_be_counted_as_confirmed(self):
        peak = [{"asset_id": "g1", "at_us": 1_100_000, "type": "HIGH_ACTIVITY"}]
        r = event_metrics(self.truth, peak)
        self.assertEqual(0, r["predicted_confirmed_kills"])
        self.assertIsNone(r["precision"])

    def test_split_streak_fails_retention(self):
        split = [
            {"asset_id": "g1", "start_us": 800_000, "end_us": 1_400_000},
            {"asset_id": "g1", "start_us": 1_700_000, "end_us": 2_500_000},
        ]
        r = streak_metrics(self.truth, split)
        self.assertEqual(0, r["fully_retained_streaks"])
        self.assertEqual(["g1/multi"], r["broken_streaks"])

    def test_undefined_ground_truth_fails_enforced_gate(self):
        report = evaluate({
            "schema": 1,
            "dataset_id": "empty",
            "ground_truth_events": [],
            "predicted_events": [],
            "selected_clips": [],
        })
        passed, why = enforce_gate(report)
        self.assertFalse(passed)
        self.assertTrue(any("undefined" in text for text in why))

    def test_requires_valid_annotation_schema(self):
        with self.assertRaises(ValueError):
            evaluate({
                "schema": 2,
                "dataset_id": "wrong",
                "ground_truth_events": [],
                "predicted_events": [],
                "selected_clips": [],
            })


if __name__ == "__main__":
    unittest.main()
