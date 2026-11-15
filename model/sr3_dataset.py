#!/usr/bin/env python3
"""Validate private SR3 datasets before training. Does not download or publish images.

Manifest: {"dataset_version":"v1", "images":[{"id":"a", "path":"hr/a.jpg",
"split":"train", "scene_id":"scene-a", "group_ids":["session-a"],
"category":"foliage", "license":"owner-consent", "attribution":"owner",
"rights_confirmed":true}]}
Use group_ids for recurring people, sessions and source collections. Human curation
is still required: hashes cannot detect different crops or nearly identical photos.
"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from PIL import Image, ImageOps

SPLITS = {"train", "val", "test"}
# Unrecognized terms need review rather than silently becoming a training license.
LICENSES = {"owner-consent", "CC0-1.0", "CC-BY-4.0", "authorized"}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024*1024), b""):
            digest.update(block)
    return digest.hexdigest()


def image_identity(path):
    with Image.open(path) as source:
        image = ImageOps.exif_transpose(source).convert("RGB")
        digest = hashlib.sha256()
        digest.update(f"RGB:{image.width}x{image.height}:".encode())
        digest.update(image.tobytes())
        return digest.hexdigest(), image.size


def validate_manifest(path, min_edge=128):
    path = Path(path).resolve()
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("dataset_version"), str) or not data["dataset_version"].strip():
        raise ValueError("A nonempty dataset_version is required")
    records = data.get("images")
    if not isinstance(records, list) or not records:
        raise ValueError("A nonempty images list is required")
    ids, byte_hashes, pixel_hashes, groups = set(), {}, {}, {}
    checked = []
    for record in records:
        required = ("id", "path", "split", "scene_id", "category", "license", "attribution")
        if not isinstance(record, dict) or any(not isinstance(record.get(k), str) or not record[k].strip() for k in required):
            raise ValueError("Each image needs nonempty id/path/split/scene_id/category/license/attribution")
        ident = record["id"]
        if ident in ids:
            raise ValueError(f"Duplicate image id: {ident}")
        ids.add(ident)
        if record["split"] not in SPLITS:
            raise ValueError(f"{ident}: invalid split")
        if record["license"] not in LICENSES or record.get("rights_confirmed") is not True:
            raise ValueError(f"{ident}: confirmed training/distribution rights are required")
        extra_groups = record.get("group_ids", [])
        if not isinstance(extra_groups, list) or any(not isinstance(g, str) or not g.strip() for g in extra_groups):
            raise ValueError(f"{ident}: group_ids must be nonempty strings")
        for group in ["scene:"+record["scene_id"], *("group:"+g for g in extra_groups)]:
            if group in groups and groups[group] != record["split"]:
                raise ValueError(f"{ident}: scene/person/session group crosses dataset splits")
            groups[group] = record["split"]
        file = (path.parent / record["path"]).resolve()
        if not file.is_file():
            raise ValueError(f"{ident}: missing image")
        byte_hash = sha256(file)
        pixels, size = image_identity(file)
        if min(size) < min_edge:
            raise ValueError(f"{ident}: image smaller than {min_edge}px")
        # Reject duplicates even within one split: repeating entries must not inflate counts.
        if byte_hash in byte_hashes or pixels in pixel_hashes:
            raise ValueError(f"{ident}: duplicate bytes or decoded pixels")
        byte_hashes[byte_hash] = ident; pixel_hashes[pixels] = ident
        checked.append({**record, "file": file, "sha256": byte_hash,
                        "pixel_sha256": pixels, "width": size[0], "height": size[1]})
    return data["dataset_version"], sha256(path), checked


def summary(path, min_edge=128):
    version, digest, records = validate_manifest(path, min_edge)
    counts = Counter(r["split"] for r in records)
    # Counts are preparation targets; never a declaration of photographic quality.
    return {"schema":"lumora-sr3-dataset-v1", "datasetVersion":version,
            "manifestSha256":digest, "imageCount":len(records),
            "splits":{s:counts[s] for s in sorted(SPLITS)},
            "categories":dict(sorted(Counter(r["category"] for r in records).items())),
            "minimumTrainingCountsMet":counts["train"]>=20 and counts["val"]>=5,
            "testTarget80Met":counts["test"]>=80,
            "limitations":["Rights and identity/session groups are human declarations.",
                            "Exact hashes do not detect alternate crops or near duplicates.",
                            "No photographic quality, phone performance or release approval is measured."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--min-edge", type=int, default=128)
    args = parser.parse_args()
    if args.min_edge < 1:
        parser.error("min-edge must be positive")
    try:
        result = summary(args.manifest, args.min_edge)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    content = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)+"\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
    print(content, end="")


if __name__ == "__main__":
    main()
