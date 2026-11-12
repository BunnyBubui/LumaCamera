#!/usr/bin/env python3
"""Research-only native x2 natural-detail trainer. Requires licensed, disjoint HR datasets.
No network downloads and no generative/GAN loss. Not yet deployed in Android.
Run: python model/train_natural_x2.py --train-dir datasets/train --val-dir datasets/val
"""
import argparse
import json
import random
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter
import torch
from torch import nn
from torch.nn import functional as F
from torch.utils.data import Dataset, DataLoader

IMAGE_EXT = {".png",".jpg",".jpeg",".webp",".tif",".tiff"}

class ResidualBlock(nn.Module):
    def __init__(self, width):
        super().__init__()
        self.net = nn.Sequential(nn.Conv2d(width,width,3,padding=1),
            nn.LeakyReLU(0.1,True),nn.Conv2d(width,width,3,padding=1))
    def forward(self,x):
        return x + 0.2*self.net(x)

class NaturalX2(nn.Module):
    """x2 lightweight residual CNN initialized to exact bicubic interpolation."""
    def __init__(self,width=32,blocks=6):
        super().__init__()
        self.entry=nn.Conv2d(3,width,3,padding=1)
        self.body=nn.Sequential(*(ResidualBlock(width) for _ in range(blocks)))
        self.out=nn.Conv2d(width,12,3,padding=1)
        nn.init.zeros_(self.out.weight)
        nn.init.zeros_(self.out.bias)
    def forward(self,lr):
        correction=F.pixel_shuffle(self.out(self.body(self.entry(lr))),2)
        base=F.interpolate(lr,scale_factor=2,mode="bicubic",align_corners=False)
        return base+correction

def rgb_tensor(im):
    data=np.asarray(im.convert("RGB"),dtype=np.float32)/255.
    return torch.from_numpy(data.transpose(2,0,1).copy())

class NaturalPairs(Dataset):
    def __init__(self,root,patch=64,repeat=12,seed=6118,training=True):
        self.files=sorted(p for p in root.rglob("*") if p.suffix.lower() in IMAGE_EXT)
        if not self.files: raise ValueError(f"No HR image files: {root}")
        self.patch,self.repeat,self.seed,self.training=patch,repeat if training else 1,seed,training
    def __len__(self): return len(self.files)*self.repeat
    def __getitem__(self,index):
        file=self.files[index%len(self.files)]
        rng=random.Random(self.seed+index*1009)
        with Image.open(file) as image:
            image=image.convert("RGB")
            size=self.patch*2
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
        sigma=rng.uniform(0,2) if self.training else 0.8
        rng_np=np.random.default_rng(self.seed+index)
        pixels=np.clip(pixels+rng_np.normal(0,sigma,pixels.shape),0,255).astype(np.uint8)
        return rgb_tensor(Image.fromarray(pixels,"RGB")),rgb_tensor(hr)

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
            baseline=F.interpolate(lr,scale_factor=2,mode="bicubic",align_corners=False)
            values.append((psnr(baseline,hr),psnr(out,hr)))
    return np.mean(np.asarray(values),axis=0).tolist()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--train-dir",type=Path,required=True)
    parser.add_argument("--val-dir",type=Path,required=True)
    parser.add_argument("--out",type=Path,default=Path("build/natural-x2"))
    parser.add_argument("--epochs",type=int,default=20)
    parser.add_argument("--patch",type=int,default=64)
    parser.add_argument("--batch-size",type=int,default=8)
    parser.add_argument("--lr",type=float,default=2e-4)
    parser.add_argument("--device",default="cuda" if torch.cuda.is_available() else "cpu")
    args=parser.parse_args()
    if args.epochs<1 or args.patch<16 or args.batch_size<1:
        parser.error("epochs, batch and patch sizes must be positive")
    if args.train_dir.resolve()==args.val_dir.resolve():
        parser.error("Training and validation images must be disjoint")
    torch.manual_seed(6118);np.random.seed(6118);random.seed(6118)
    training=NaturalPairs(args.train_dir,args.patch,training=True)
    testing=NaturalPairs(args.val_dir,args.patch,training=False)
    overlap={p.name for p in training.files}&{p.name for p in testing.files}
    if overlap: parser.error(f"Train/validation filenames overlap: {sorted(overlap)[:8]}")
    if len(training.files)<20 or len(testing.files)<5:
        parser.error("Need at least 20 HR training photos and 5 independent validation photos")
    device=torch.device(args.device)
    network=NaturalX2().to(device)
    optimizer=torch.optim.AdamW(network.parameters(),lr=args.lr,weight_decay=1e-5)
    train_loader=DataLoader(training,batch_size=args.batch_size,shuffle=True)
    val_loader=DataLoader(testing,batch_size=1,shuffle=False)
    args.out.mkdir(parents=True,exist_ok=True)
    best=-float("inf")
    for epoch in range(1,args.epochs+1):
        network.train();total=0.
        for lr,hr in train_loader:
            lr,hr=lr.to(device),hr.to(device)
            optimizer.zero_grad(set_to_none=True)
            loss=fidelity_loss(network(lr),hr)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(network.parameters(),1.)
            optimizer.step()
            total+=loss.item()
        baseline,learned=validate(network,val_loader,device)
        print(f"epoch={epoch} loss={total/len(train_loader):.5f} bicubic_psnr={baseline:.2f} model_psnr={learned:.2f}")
        if learned>best:
            best=learned
            torch.save({"state_dict":network.state_dict(),
                "architecture":"NaturalX2-width32-blocks6","epoch":epoch,"scale":2,
                "valPSNR":learned,"baselinePSNR":baseline,"trainedWeights":True,
                "deviceAcceptanceComplete":False},args.out/"natural-x2-research.pth")
    print(json.dumps({"bestValPSNR":best,"trainingPhotos":len(training.files),
        "validationPhotos":len(testing.files),"deployedToAndroid":False},indent=2))

if __name__=="__main__":
    main()
