#!/usr/bin/env python3
"""Smoke tests for the research-only Natural x2 model and its fidelity loss."""
import unittest
import torch
from train_natural_x2 import NaturalX2, fidelity_loss

class NaturalX2SmokeTest(unittest.TestCase):
    def test_native_scale_and_source_residual(self):
        model = NaturalX2()
        low = torch.rand(1, 3, 16, 21)
        with torch.no_grad():
            actual = model(low)
            expected = torch.nn.functional.interpolate(
                low, scale_factor=2, mode="bicubic", align_corners=False)
        self.assertEqual(tuple(actual.shape), (1, 3, 32, 42))
        self.assertTrue(torch.allclose(actual, expected, atol=1e-6))

    def test_loss_is_finite_and_training_step_changes_weights(self):
        model = NaturalX2()
        low = torch.rand(1, 3, 16, 16)
        truth = torch.rand(1, 3, 32, 32)
        opt = torch.optim.Adam(model.parameters(), lr=0.001)
        before = model.out.weight.detach().clone()
        loss = fidelity_loss(model(low), truth)
        self.assertTrue(torch.isfinite(loss).item())
        loss.backward()
        opt.step()
        self.assertFalse(torch.equal(before, model.out.weight.detach()))

if __name__ == "__main__":
    unittest.main()
