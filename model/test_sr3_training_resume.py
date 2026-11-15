"""Exercise actual training/resume on procedural fixtures; no image-quality claim."""
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

import numpy as np
from PIL import Image
import torch


class TrainingResumeTest(unittest.TestCase):
    def test_resumed_training_matches_uninterrupted_weights_and_rejects_changed_data(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);records=[]
            for i in range(25):
                file=root/f"{i}.png"
                Image.fromarray(np.random.default_rng(i).integers(0,256,(40,44,3),dtype=np.uint8)).save(file)
                records.append({"id":str(i),"path":file.name,"split":"train" if i<20 else "val",
                    "scene_id":str(i),"category":"procedural-fixture","license":"owner-consent",
                    "attribution":"generated unit test fixture","rights_confirmed":True})
            manifest=root/"dataset.json"
            manifest.write_text(json.dumps({"dataset_version":"synthetic-test","images":records}))
            script=Path(__file__).with_name("train_natural_x2.py")
            command=[sys.executable,str(script),"--manifest",str(manifest),"--patch","16",
                     "--width","4","--blocks","1","--repeat","1","--batch-size","5","--threads","1"]
            def run(out,epochs,*extra):
                return subprocess.run(command+["--out",str(out),"--epochs",str(epochs),*extra],
                    capture_output=True,text=True,timeout=90)
            full=root/"full";resumed=root/"resumed"
            result=run(full,2);self.assertEqual(result.returncode,0,result.stderr)
            result=run(resumed,1);self.assertEqual(result.returncode,0,result.stderr)
            result=run(resumed,2,"--resume",str(resumed/"last-research.pth"))
            self.assertEqual(result.returncode,0,result.stderr)
            a=torch.load(full/"last-research.pth",weights_only=True)
            b=torch.load(resumed/"last-research.pth",weights_only=True)
            self.assertTrue(all(torch.equal(a["state_dict"][k],b["state_dict"][k]) for k in a["state_dict"]))
            self.assertFalse(b["releaseApproved"])
            self.assertFalse(b["deviceAcceptanceComplete"])
            # A manifest unchanged on disk cannot conceal edited source-image bytes on resume.
            Image.new("RGB",(40,44),(12,24,36)).save(root/"0.png")
            result=run(resumed,3,"--resume",str(resumed/"last-research.pth"))
            self.assertNotEqual(result.returncode,0)
            self.assertIn("identical model, training config and dataset",result.stderr)


if __name__=="__main__":
    unittest.main()
