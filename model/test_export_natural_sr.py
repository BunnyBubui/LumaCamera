"""Numeric mapping tests with procedural tensors, not trained photographic models."""
import unittest
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import torch
import tensorflow as tf
from train_natural_x2 import NaturalSR, fidelity_loss
from export_natural_sr import convert_network,verify_network

torch.set_num_threads(2)
tf.config.threading.set_inter_op_parallelism_threads(2)
tf.config.threading.set_intra_op_parallelism_threads(2)


class NaturalExportTests(unittest.TestCase):
    def test_both_native_scales_match_torch_with_nonzero_residual_and_fp16(self):
        for scale,fp16 in ((2,False),(4,False),(2,True),(4,True)):
            with self.subTest(scale=scale,fp16=fp16):
                torch.manual_seed(45)
                network=NaturalSR(width=4,blocks=1,scale=scale).eval()
                torch.nn.init.normal_(network.out.weight,mean=0.,std=0.02)
                torch.nn.init.normal_(network.out.bias,mean=0.,std=0.01)
                model=convert_network(network,8,fp16)
                report=verify_network(network,model,8,fp16)
                self.assertTrue(report["secondInputChangesOutput"])
                self.assertFalse(report["photographicQualityValidated"])
                self.assertFalse(report["androidGpuValidated"])

    def test_cli_exports_actual_synthetic_training_checkpoint_as_research_only(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            network=NaturalSR(width=4,blocks=1,scale=2)
            optimizer=torch.optim.Adam(network.parameters(),lr=0.001)
            loss=fidelity_loss(network(torch.rand(1,3,8,8)),torch.rand(1,3,16,16))
            loss.backward();optimizer.step()
            file=root/"synthetic-research.pth"
            checkpoint={"state_dict":network.state_dict(),"config":{"width":4,"blocks":1,"scale":2},
                        "trainedWeights":True,"epoch":1,"datasetAuditComplete":False}
            torch.save(checkpoint,file)
            command=[sys.executable,str(Path(__file__).with_name("export_natural_sr.py")),
                     "--checkpoint",str(file),"--out",str(root/"export"),"--input","32","--halo","8","--fp16"]
            result=subprocess.run(command,capture_output=True,text=True,timeout=90)
            self.assertEqual(result.returncode,0,result.stderr)
            manifest=json.loads((root/"export/natural-x2-research.json").read_text())
            self.assertEqual(manifest["input"],[1,32,32,3])
            self.assertEqual(manifest["core"],16)
            self.assertFalse(manifest["releaseApproved"])
            self.assertFalse(manifest["deployedToAndroid"])
            self.assertEqual(len(manifest["sha256"]),64)
            checkpoint["trainedWeights"]=False;torch.save(checkpoint,file)
            result=subprocess.run(command,capture_output=True,text=True,timeout=90)
            self.assertNotEqual(result.returncode,0)
            self.assertIn("trained research checkpoint",result.stderr)


if __name__=="__main__":
    unittest.main()
