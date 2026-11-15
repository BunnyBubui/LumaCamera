#!/usr/bin/env python3
"""Research-only native x2/x4 natural-detail trainer. Requires licensed, disjoint HR datasets.
No network downloads and no generative/GAN loss. Not yet deployed in Android.
Run: python model/train_natural_x2.py --manifest /private/dataset.json --scale 2
"""
import argparse
import hashlib
import io
import json
import math
import random
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter, ImageOps
import torch
from torch import nn
from torch.nn import functional as F
from torch.utils.data import Dataset, DataLoader
from sr3_dataset import validate_manifest, image_identity

IMAGE_EXT = {".png",".jpg",".jpeg",".webp",".tif",".tiff"}

class ResidualBlock(nn.Module):
    def __init__(self, width):
        super().__init__()
        self.net = nn.Sequential(nn.Conv2d(width,width,3,padding=1),
            nn.LeakyReLU(0.1,True),nn.Conv2d(width,width,3,padding=1))
    def forward(self,x):
        return x + 0.2*self.net(x)

class NaturalSR(nn.Module):
    """Research x2/x4 residual CNN initialized to exact bicubic interpolation."""
    def __init__(self,width=32,blocks=6,scale=2):
        super().__init__()
        if scale not in (2,4) or width < 1 or blocks < 1:
            raise ValueError("scale must be 2/4 and width/blocks must be positive")
        self.scale=scale
        self.entry=nn.Conv2d(3,width,3,padding=1)
        self.body=nn.Sequential(*(ResidualBlock(width) for _ in range(blocks)))
        self.out=nn.Conv2d(width,3*scale*scale,3,padding=1)
        nn.init.zeros_(self.out.weight)
        nn.init.zeros_(self.out.bias)
    def forward(self,lr):
        correction=F.pixel_shuffle(self.out(self.body(self.entry(lr))),self.scale)
        base=F.interpolate(lr,scale_factor=self.scale,mode="bicubic",align_corners=False)
        return base+correction


class NaturalX2(NaturalSR):
    """Backwards-compatible constructor for earlier x2 experiments."""
    def __init__(self,width=32,blocks=6):
        super().__init__(width,blocks,scale=2)

def rgb_tensor(im):
    data=np.asarray(im.convert("RGB"),dtype=np.float32)/255.
    return torch.from_numpy(data.transpose(2,0,1).copy())

class NaturalPairs(Dataset):
    def __init__(self,root=None,patch=64,repeat=12,seed=6118,training=True,scale=2,files=None):
        self.files=list(files) if files is not None else sorted(p for p in root.rglob("*") if p.suffix.lower() in IMAGE_EXT)
        if not self.files: raise ValueError(f"No HR image files: {root}")
        if scale not in (2,4) or patch < 2 or repeat < 1:
            raise ValueError("Invalid scale, patch or repeat")
        self.scale,self.epoch=scale,0
        self.patch,self.repeat,self.seed,self.training=patch,repeat if training else 1,seed,training
    def set_epoch(self,epoch):
        self.epoch=epoch if self.training else 0
    def __len__(self): return len(self.files)*self.repeat
    def __getitem__(self,index):
        file=self.files[index%len(self.files)]
        sample_seed=self.seed+index*1009+self.epoch*1000003
        rng=random.Random(sample_seed)
        with Image.open(file) as image:
            image=ImageOps.exif_transpose(image).convert("RGB")
            size=self.patch*self.scale
            if min(image.size)<size: raise ValueError(f"Image smaller than {size}: {file}")
            x=rng.randrange(image.width-size+1) if self.training else (image.width-size)//2
            y=rng.randrange(image.height-size+1) if self.training else (image.height-size)//2
            hr=image.crop((x,y,x+size,y+size))
        if self.training:
            if rng.choice((True,False)): hr=hr.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            if rng.choice((True,False)): hr=hr.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
            hr=hr.rotate(rng.randrange(4)*90)
        blur=rng.uniform(0,0.65) if self.training else 0.3
        if blur: lr=hr.filter(ImageFilter.GaussianBlur(blur))
        else: lr=hr
        lr=lr.resize((self.patch,self.patch),Image.Resampling.BICUBIC)
        pixels=np.asarray(lr,dtype=np.float32)
        sigma=rng.uniform(0,3) if self.training else 0.8
        rng_np=np.random.default_rng(sample_seed)
        pixels=np.clip(pixels+rng_np.normal(0,sigma,pixels.shape),0,255).astype(np.uint8)
        lr=Image.fromarray(pixels)
        # Real JPEG artifacts vary with epochs too; validation degradation is fixed.
        if self.training and rng.random()<0.7:
            encoded=io.BytesIO()
            lr.save(encoded,format="JPEG",quality=rng.randint(70,98),subsampling=2)
            encoded.seek(0)
            with Image.open(encoded) as decoded:
                lr=decoded.convert("RGB")
        return rgb_tensor(lr),rgb_tensor(hr)

def fidelity_loss(pred,truth):
    pixel=F.smooth_l1_loss(pred,truth,beta=0.02)
    dx=F.l1_loss(pred[:,:,:,1:]-pred[:,:,:,:-1],
                 truth[:,:,:,1:]-truth[:,:,:,:-1])
    dy=F.l1_loss(pred[:,:,1:,:]-pred[:,:,:-1,:],
                 truth[:,:,1:,:]-truth[:,:,:-1,:])
    return pixel+0.055*(dx+dy)

def psnr(pred,truth):
    mse=F.mse_loss(pred.clamp(0,1),truth).item()
    return float("inf") if mse==0 else -10*np.log10(mse)

def validate(network,loader,device):
    network.eval()
    values=[]
    with torch.no_grad():
        for lr,hr in loader:
            lr,hr=lr.to(device),hr.to(device)
            out=network(lr)
            baseline=F.interpolate(lr,scale_factor=network.scale,mode="bicubic",align_corners=False)
            values.append((psnr(baseline,hr),psnr(out,hr)))
    return np.mean(np.asarray(values),axis=0).tolist()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",type=Path,help="Private, rights-reviewed dataset manifest (preferred)")
    parser.add_argument("--train-dir",type=Path)
    parser.add_argument("--val-dir",type=Path)
    parser.add_argument("--out",type=Path,default=Path("build/natural-x2"))
    parser.add_argument("--epochs",type=int,default=20)
    parser.add_argument("--patch",type=int,default=64)
    parser.add_argument("--batch-size",type=int,default=8)
    parser.add_argument("--lr",type=float,default=2e-4)
    parser.add_argument("--scale",type=int,choices=(2,4),default=2)
    parser.add_argument("--seed",type=int,default=6118)
    parser.add_argument("--repeat",type=int,default=12)
    parser.add_argument("--width",type=int,default=32)
    parser.add_argument("--blocks",type=int,default=6)
    parser.add_argument("--threads",type=int,default=2)
    parser.add_argument("--resume",type=Path,help="Resume a checkpoint from this trainer with the same config/data")
    parser.add_argument("--device",default="cuda" if torch.cuda.is_available() else "cpu")
    args=parser.parse_args()
    if args.epochs<1 or args.patch<16 or args.batch_size<1 or args.repeat<1 or args.threads<1 or args.lr<=0:
        parser.error("epochs, batch and patch sizes must be positive")
    if args.width<1 or args.blocks<1:
        parser.error("width and blocks must be positive")
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed);np.random.seed(args.seed);random.seed(args.seed)
    torch.use_deterministic_algorithms(True)
    torch.backends.cudnn.benchmark=False
    dataset_digest=None
    if args.manifest:
        if args.train_dir or args.val_dir:
            parser.error("Use manifest or directory mode, not both")
        try:
            _,manifest_digest,records=validate_manifest(args.manifest,args.patch*args.scale)
            dataset_digest=hashlib.sha256((manifest_digest+"".join(r["sha256"] for r in records)).encode()).hexdigest()
        except (ValueError,OSError) as exc:
            parser.error(str(exc))
        # The final test split is validated for leakage but never used for training/model selection.
        train_files=[r["file"] for r in records if r["split"]=="train"]
        val_files=[r["file"] for r in records if r["split"]=="val"]
    else:
        if not args.train_dir or not args.val_dir:
            parser.error("Provide --manifest or both --train-dir and --val-dir")
        if args.train_dir.resolve()==args.val_dir.resolve():
            parser.error("Training and validation images must be disjoint")
        train_files=sorted(p for p in args.train_dir.rglob("*") if p.suffix.lower() in IMAGE_EXT)
        val_files=sorted(p for p in args.val_dir.rglob("*") if p.suffix.lower() in IMAGE_EXT)
        # Filenames miss renamed duplicates and falsely reject unrelated IMG_0001 files.
        hashes=[image_identity(p)[0] for p in train_files+val_files]
        if len(set(hashes))!=len(hashes):
            parser.error("Duplicate decoded images in training/validation")
        dataset_digest=hashlib.sha256("".join(hashes).encode()).hexdigest()
        print("WARNING: directory mode has no reviewed rights/scene/person/session manifest; research only")
    training=NaturalPairs(patch=args.patch,repeat=args.repeat,seed=args.seed,training=True,scale=args.scale,files=train_files)
    testing=NaturalPairs(patch=args.patch,seed=args.seed,training=False,scale=args.scale,files=val_files)
    if len(training.files)<20 or len(testing.files)<5:
        parser.error("Need at least 20 HR training photos and 5 independent validation photos")
    device=torch.device(args.device)
    network=NaturalSR(args.width,args.blocks,args.scale).to(device)
    optimizer=torch.optim.AdamW(network.parameters(),lr=args.lr,weight_decay=1e-5)
    val_loader=DataLoader(testing,batch_size=1,shuffle=False)
    args.out.mkdir(parents=True,exist_ok=True)
    config={k:getattr(args,k) for k in ("scale","patch","batch_size","lr","seed","repeat","width","blocks")}
    best=-float("inf");start_epoch=1
    if args.resume:
        prior=torch.load(args.resume,map_location=device,weights_only=True)
        if prior.get("config")!=config or prior.get("datasetDigest")!=dataset_digest:
            parser.error("Resume requires identical model, training config and dataset")
        network.load_state_dict(prior["state_dict"])
        optimizer.load_state_dict(prior["optimizer_state_dict"])
        best=prior["bestValPSNR"];start_epoch=prior["epoch"]+1
        if start_epoch>args.epochs:
            parser.error("epochs must exceed the resumed epoch")
    history=[]
    for epoch in range(start_epoch,args.epochs+1):
        training.set_epoch(epoch)
        generator=torch.Generator().manual_seed(args.seed+epoch)
        train_loader=DataLoader(training,batch_size=args.batch_size,shuffle=True,generator=generator)
        network.train();total=0.
        for lr,hr in train_loader:
            lr,hr=lr.to(device),hr.to(device)
            optimizer.zero_grad(set_to_none=True)
            loss=fidelity_loss(network(lr),hr)
            if not torch.isfinite(loss).item():
                raise RuntimeError("Non-finite training loss; no checkpoint will be promoted")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(network.parameters(),1.)
            optimizer.step()
            total+=loss.item()
        baseline,learned=validate(network,val_loader,device)
        if math.isnan(baseline) or math.isnan(learned):
            raise RuntimeError("Non-finite validation result")
        print(f"epoch={epoch} loss={total/len(train_loader):.5f} bicubic_psnr={baseline:.2f} model_psnr={learned:.2f}")
        improved=learned>best
        best=max(best,learned)
        checkpoint={"state_dict":network.state_dict(),"optimizer_state_dict":optimizer.state_dict(),
            "architecture":f"NaturalSR-x{args.scale}-width{args.width}-blocks{args.blocks}",
            "epoch":epoch,"scale":args.scale,"config":config,"datasetDigest":dataset_digest,
            "datasetAuditComplete":args.manifest is not None,
            "bestValPSNR":float(best),"valPSNR":float(learned),"baselinePSNR":float(baseline),
            "trainedWeights":True,"deviceAcceptanceComplete":False,"releaseApproved":False,
            "torchVersion":str(torch.__version__),"trainerSha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        torch.save(checkpoint,args.out/"last-research.pth")
        if improved:
            torch.save(checkpoint,args.out/f"natural-x{args.scale}-research.pth")
        history.append({"epoch":epoch,"loss":total/len(train_loader),
                        "bicubicPSNR":float(baseline) if math.isfinite(baseline) else None,
                        "modelPSNR":float(learned) if math.isfinite(learned) else None,
                        "modelReferenceExactMatch":learned==float("inf")})
        (args.out/f"history-from-{start_epoch}.json").write_text(json.dumps(history,indent=2,allow_nan=False)+"\n")
    print(json.dumps({"bestValPSNR":float(best) if math.isfinite(best) else None,
        "bestReferenceExactMatch":best==float("inf"),"trainingPhotos":len(training.files),
        "validationPhotos":len(testing.files),"deployedToAndroid":False},indent=2,allow_nan=False))

if __name__=="__main__":
    main()
