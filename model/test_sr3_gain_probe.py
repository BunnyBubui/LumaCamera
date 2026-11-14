import unittest

import numpy as np

import sr3_gain_probe as p


def textured(h, w, seed=1):
    rng = np.random.default_rng(seed)
    base = np.kron(rng.uniform(60, 190, (h // 8, w // 8, 3)), np.ones((8, 8, 1)))
    fine = rng.normal(0, 9, (h, w, 1))
    return np.clip(base + fine, 0, 255).astype(np.float32)


def shrink(img, s):
    h, w = img.shape[:2]
    return img.reshape(h // s, s, w // s, s, 3).mean(axis=(1, 3))


class GainProbe(unittest.TestCase):
    def setUp(self):
        self.scale = 2
        self.hr = textured(96, 96)
        self.lr = shrink(self.hr, self.scale)
        # An "oracle" network: it recovers the true detail exactly.
        self.neural = self.hr.copy()

    def test_current_blend_keeps_little_in_flat_bands(self):
        rep = p.run(self.lr, self.neural, self.scale, 70)
        self.assertLess(rep["natural_current"]["overall_kept_pct"], 60.0)

    def test_consistent_blend_keeps_more_of_true_detail(self):
        rep = p.run(self.lr, self.neural, self.scale, 70)
        self.assertGreater(rep["consistent_proposal"]["overall_kept_pct"], rep["natural_current"]["overall_kept_pct"])

    def test_consistent_blend_is_closer_to_reference_than_bilinear(self):
        rep = p.run(self.lr, self.neural, self.scale, 70, self.hr)
        self.assertGreater(rep["consistent_proposal"]["vs_reference"]["psnr_db"], rep["bilinear_only"]["vs_reference"]["psnr_db"])

    def test_back_projection_removes_detail_the_source_contradicts(self):
        # A network that adds a uniform brightness offset the source never had must lose it.
        src_y = p.luma(self.lr)
        neural_y = p.luma(self.hr) + 25.0
        fixed = p.back_project(neural_y, src_y, self.scale)
        down = fixed.reshape(src_y.shape[0], self.scale, src_y.shape[1], self.scale).mean(axis=(1, 3))
        self.assertLess(float(np.abs(down - src_y).mean()), 3.0)

    def test_wrong_size_is_rejected(self):
        with self.assertRaises(ValueError):
            p.run(self.lr, self.hr[:-2], self.scale, 70)

    def test_zero_strength_changes_nothing(self):
        rep = p.run(self.lr, self.neural, self.scale, 0)
        self.assertEqual(rep["natural_current"]["overall_kept_pct"], 0.0)


if __name__ == "__main__":
    unittest.main()
