"""Synthetic manifest/leakage tests. No fixture is a photographic training dataset."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np
from PIL import Image, PngImagePlugin
from sr3_dataset import validate_manifest, summary


class DatasetTests(unittest.TestCase):
    def setUp(self):
        self.tmp=TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.records=[]
        for i,split in enumerate(("train","val","test")):
            Image.fromarray(np.random.default_rng(i).integers(0,256,(64,72,3),dtype=np.uint8)).save(self.root/f"{i}.png")
            self.records.append({"id":str(i),"path":f"{i}.png","split":split,
                "scene_id":f"scene-{i}","group_ids":[f"session-{i}"],"category":"fixture",
                "license":"owner-consent","attribution":"synthetic test fixture","rights_confirmed":True})

    def manifest(self):
        file=self.root/"dataset.json"
        file.write_text(json.dumps({"dataset_version":"test-v1","images":self.records}))
        return file

    def test_summary_is_repeatable_and_never_a_release_pass(self):
        file=self.manifest()
        self.assertEqual(summary(file,32),summary(file,32))
        result=summary(file,32)
        self.assertEqual(result["splits"],{"train":1,"val":1,"test":1})
        self.assertFalse(result["minimumTrainingCountsMet"])
        self.assertFalse(result["testTarget80Met"])
        self.assertNotIn("qualityPass",result)

    def test_rejects_renamed_duplicate_bytes(self):
        (self.root/"1.png").write_bytes((self.root/"0.png").read_bytes())
        with self.assertRaisesRegex(ValueError,"duplicate"):
            validate_manifest(self.manifest(),32)

    def test_rejects_same_pixels_with_different_file_metadata(self):
        info=PngImagePlugin.PngInfo();info.add_text("Comment","new file, same pixels")
        with Image.open(self.root/"0.png") as image:
            image.save(self.root/"1.png",pnginfo=info)
        self.assertNotEqual((self.root/"0.png").read_bytes(),(self.root/"1.png").read_bytes())
        with self.assertRaisesRegex(ValueError,"duplicate"):
            validate_manifest(self.manifest(),32)

    def test_scene_and_person_session_groups_cannot_cross_splits(self):
        for field,value in (("scene_id",self.records[0]["scene_id"]),("group_ids",self.records[0]["group_ids"])):
            old=self.records[1][field];self.records[1][field]=value
            with self.assertRaisesRegex(ValueError,"crosses"):
                validate_manifest(self.manifest(),32)
            self.records[1][field]=old

    def test_rejects_unconfirmed_or_unsupported_rights(self):
        self.records[0]["rights_confirmed"]=False
        with self.assertRaisesRegex(ValueError,"rights"):
            validate_manifest(self.manifest(),32)
        self.records[0]["rights_confirmed"]=True
        self.records[0]["license"]="unknown mirror license"
        with self.assertRaisesRegex(ValueError,"rights"):
            validate_manifest(self.manifest(),32)

    def test_checks_minimum_resolution(self):
        with self.assertRaisesRegex(ValueError,"smaller"):
            validate_manifest(self.manifest(),128)

    def test_duplicate_id_and_missing_scene_rejected(self):
        self.records[1]["id"]=self.records[0]["id"]
        with self.assertRaisesRegex(ValueError,"Duplicate image id"):
            validate_manifest(self.manifest(),32)
        self.records[1]["id"]="1";self.records[1].pop("scene_id")
        with self.assertRaisesRegex(ValueError,"scene_id"):
            validate_manifest(self.manifest(),32)


if __name__=="__main__":
    unittest.main()
