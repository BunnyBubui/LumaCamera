#!/usr/bin/env python3
"""Same-size held-out real crops and tiled/full parity; not an OPPO quality claim."""
import gzip
import json
from pathlib import Path
import struct
import time
import numpy as np
from PIL import Image
import tensorflow as tf

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/"build/detail-comparison"
CORE,HALO=192,36
INPUT=CORE+2*HALO

def main():
    DEST.mkdir(parents=True,exist_ok=True)
    interpreter=tf.lite.Interpreter(model_path=str(ROOT/"app/src/main/assets/luma-detail-sr2.tflite"),num_threads=2)
    inp=interpreter.get_input_details()[0]["index"]
    interpreter.allocate_tensors()
    def run(image,left=0,top=0):
        xs=np.clip(np.arange(left-HALO,left-HALO+INPUT),0,image.shape[1]-1)
        ys=np.clip(np.arange(top-HALO,top-HALO+INPUT),0,image.shape[0]-1)
        tile=image[ys[:,None],xs[None,:]]
        interpreter.set_tensor(inp,tile[None].astype(np.float32)); interpreter.invoke()
        result=interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0]
        return result[HALO*4:(HALO+min(CORE,image.shape[0]-top))*4,
                      HALO*4:(HALO+min(CORE,image.shape[1]-left))*4]
    results={}
    for name in ["camera","rocket"]:
        with gzip.open(ROOT/f"app/src/test/resources/fixtures/{name}-low.gray.gz","rb") as stream:
            w,h=struct.unpack(">HH",stream.read(4))
            low=np.frombuffer(stream.read(),dtype=np.uint8).reshape(h,w)
        y,x=max(0,(h-48)//2),max(0,(w-80)//2)
        low=low[y:y+48,x:x+80]
        rgb=np.repeat(low[...,None],3,axis=2)/255.
        start=time.perf_counter()
        whole=run(rgb)
        seconds=time.perf_counter()-start
        image=Image.fromarray(np.clip(np.rint(whole*255),0,255).astype(np.uint8))
        source=Image.fromarray(np.repeat(low[...,None],3,axis=2))
        bicubic=source.resize(image.size,Image.Resampling.BICUBIC)
        source.save(DEST/f"{name}-source.png"); bicubic.save(DEST/f"{name}-bicubic-x4.png")
        image.save(DEST/f"{name}-detail-x4.png")
        pair=Image.new("RGB",(image.width*2,image.height))
        pair.paste(bicubic,(0,0)); pair.paste(image,(image.width,0))
        pair.save(DEST/f"{name}-left-bicubic-right-detail.png")
        results[name]={"input":list(low.shape[::-1]),"output":list(image.size),"desktopCpuSeconds":seconds,
            "meanDifferenceFromBicubic":float(np.abs(np.asarray(image,dtype=float)-np.asarray(bicubic,dtype=float)).mean())}
    # Shift the context window by half a tile. Matching overlapping interiors
    # verifies the 36-pixel halo, including both sides of the real x=CORE seam.
    source=np.random.default_rng(42).uniform(0.1,0.9,(24,CORE+9,3)).astype(np.float32)
    HALF=CORE//2
    middle=run(source,HALF)
    for left in [0,CORE]:
        tile=run(source,left)
        lo=max(left,HALF); hi=min(left+tile.shape[1]//4,HALF+middle.shape[1]//4)
        difference=np.abs(tile[:,(lo-left)*4:(hi-left)*4]-middle[:,(lo-HALF)*4:(hi-HALF)*4])
        results[f"tileAt{left}"]={"meanError":float(difference.mean()),"maxError":float(difference.max())}
        assert difference.max()<0.005,results
    (DEST/"comparison.json").write_text(json.dumps(results,indent=2)+"\n")
    print(json.dumps(results,indent=2))

if __name__=="__main__": main()
