#!/usr/bin/env python3
"""Smoke tests for the research-only Natural x2 model and its fidelity loss."""
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
import numpy as np
from PIL import Image
import torch
from train_natural_x2 import NaturalX2, NaturalSR, NaturalPairs, fidelity_loss

torch.set_num_threads(2)

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

    def test_native_x4_starts_at_bicubic_and_has_correct_dimensions(self):
        model=NaturalSR(width=8,blocks=1,scale=4)
        low=torch.rand(1,3,9,13)
        with torch.no_grad():
            actual=model(low)
            expected=torch.nn.functional.interpolate(low,scale_factor=4,mode="bicubic",align_corners=False)
        self.assertEqual(tuple(actual.shape),(1,3,36,52))
        self.assertTrue(torch.equal(actual,expected))

    def test_training_changes_per_epoch_but_validation_is_fixed_and_repeatable(self):
        with TemporaryDirectory() as tmp:
            file=Path(tmp)/"fixture.png"
            Image.fromarray(np.random.default_rng(8).integers(0,256,(96,104,3),dtype=np.uint8)).save(file)
            train=NaturalPairs(patch=16,files=[file],seed=4)
            first=train[0]
            self.assertTrue(torch.equal(first[0],train[0][0]))
            train.set_epoch(1)
            second=train[0]
            self.assertFalse(torch.equal(first[0],second[0]))
            train.set_epoch(0)
            self.assertTrue(torch.equal(first[0],train[0][0]))
            val=NaturalPairs(patch=16,files=[file],seed=4,training=False,scale=4)
            before=val[0];val.set_epoch(22)
            self.assertTrue(torch.equal(before[0],val[0][0]))
            self.assertEqual(tuple(before[1].shape),(3,64,64))

if __name__ == "__main__":
    unittest.main()
