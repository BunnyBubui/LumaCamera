#!/usr/bin/env python3
"""Export a RESEARCH NaturalSR checkpoint to TFLite and verify numeric parity.

Does not install weights into Android, change SR2 or certify photographic quality.
Dependencies: torch, tensorflow-cpu==2.19.0, numpy. Fixed NHWC float32 I/O;
convolutions and pixel shuffle mirror train_natural_x2.py exactly.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL","2")
os.environ.setdefault("OMP_NUM_THREADS","2")
import numpy as np
import torch
import tensorflow as tf
from train_natural_x2 import NaturalSR


def cubic_samples(edge,scale):
    """PyTorch align_corners=False bicubic (a=-0.75), with replicated borders."""
    coordinates=(np.arange(edge*scale,dtype=np.float32)+0.5)/scale-0.5
    starts=np.floor(coordinates).astype(np.int32)
    indices=starts[:,None]+np.arange(-1,3,dtype=np.int32)[None,:]
    distance=np.abs(coordinates[:,None]-indices).astype(np.float32)
    a=-0.75
    weights=np.where(distance<=1,((a+2)*distance-(a+3))*distance*distance+1,
                     ((a*distance-5*a)*distance+8*a)*distance-4*a)
    weights=np.where(distance<2,weights,0).astype(np.float32)
    return np.clip(indices,0,edge-1),weights


class TensorFlowNatural(tf.Module):
    def __init__(self,network,edge):
        super().__init__()
        self.scale=network.scale
        state=network.state_dict()
        self.layers={}
        for key in state:
            if key.endswith(".weight"):
                name=key[:-7]
                kernel=state[key].detach().cpu().numpy().transpose(2,3,1,0)
                bias=state[name+".bias"].detach().cpu().numpy()
                if name=="out":
                    # PyTorch PixelShuffle is CRD; TF depth_to_space is DCR.
                    order=np.arange(3*self.scale*self.scale).reshape(3,self.scale,self.scale).transpose(1,2,0).reshape(-1)
                    kernel=kernel[:,:,:,order];bias=bias[order]
                self.layers[name]=(tf.constant(kernel),tf.constant(bias))
        self.blocks=len(network.body)
        indices,weights=cubic_samples(edge,self.scale)
        self.indices=tf.constant(indices)
        self.weights=tf.constant(weights)

    def conv(self,value,name):
        kernel,bias=self.layers[name]
        return tf.nn.bias_add(tf.nn.conv2d(value,kernel,strides=1,padding="SAME"),bias)

    @tf.function
    def __call__(self,source):
        value=self.conv(source,"entry")
        for block in range(self.blocks):
            residual=tf.nn.leaky_relu(self.conv(value,f"body.{block}.net.0"),alpha=0.1)
            value=value+0.2*self.conv(residual,f"body.{block}.net.2")
        residual=tf.nn.depth_to_space(self.conv(value,"out"),self.scale)
        vertical=tf.reduce_sum(tf.gather(source,self.indices,axis=1)*self.weights[None,:,:,None,None],axis=2)
        baseline=tf.reduce_sum(tf.gather(vertical,self.indices,axis=2)*self.weights[None,None,:,:,None],axis=3)
        return baseline+residual


def convert_network(network,edge,fp16=False):
    module=TensorFlowNatural(network,edge)
    concrete=module.__call__.get_concrete_function(tf.TensorSpec([1,edge,edge,3],tf.float32))
    converter=tf.lite.TFLiteConverter.from_concrete_functions([concrete],module)
    if fp16:
        converter.optimizations=[tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types=[tf.float16]
    return converter.convert()


def verify_network(network,model,edge,fp16=False):
    network=network.cpu().eval()
    interpreter=tf.lite.Interpreter(model_content=model,num_threads=2)
    interpreter.allocate_tensors()
    input_info=interpreter.get_input_details()[0];output_info=interpreter.get_output_details()[0]
    expected_shape=(1,edge*network.scale,edge*network.scale,3)
    if tuple(output_info["shape"])!=expected_shape:
        raise ValueError("Exported model has the wrong output shape")
    rng=np.random.default_rng(6118)
    fixtures=[rng.random((1,edge,edge,3),dtype=np.float32),
              np.full((1,edge,edge,3),0.17,dtype=np.float32)]
    outputs=[];maximum=0.;mean=0.
    for source in fixtures:
        with torch.no_grad():
            expected=network(torch.from_numpy(source.transpose(0,3,1,2).copy())).numpy().transpose(0,2,3,1)
        interpreter.set_tensor(input_info["index"],source);interpreter.invoke()
        actual=interpreter.get_tensor(output_info["index"])
        if not np.isfinite(actual).all():
            raise ValueError("Non-finite TFLite prediction")
        error=np.abs(actual-expected)
        maximum=max(maximum,float(error.max()));mean=max(mean,float(error.mean()))
        outputs.append(actual.copy())
    if maximum>(0.015 if fp16 else 3e-5) or mean>(0.001 if fp16 else 3e-6):
        raise ValueError(f"TFLite numeric parity failed: max={maximum}, mean={mean}")
    if np.array_equal(outputs[0],outputs[1]):
        raise ValueError("Different source images produced an identical output")
    return {"maxError":maximum,"meanError":mean,"secondInputChangesOutput":True,
            "photographicQualityValidated":False,"androidGpuValidated":False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint",type=Path,required=True)
    parser.add_argument("--out",type=Path,required=True,help="Research output directory")
    parser.add_argument("--input",type=int,default=264)
    parser.add_argument("--halo",type=int,default=36)
    parser.add_argument("--fp16",action="store_true")
    args=parser.parse_args()
    torch.set_num_threads(2)
    tf.config.threading.set_inter_op_parallelism_threads(2)
    tf.config.threading.set_intra_op_parallelism_threads(2)
    checkpoint=torch.load(args.checkpoint,map_location="cpu",weights_only=True)
    config=checkpoint.get("config",{})
    if checkpoint.get("trainedWeights") is not True or checkpoint.get("epoch",0)<1:
        parser.error("A trained research checkpoint is required")
    if any(k not in config for k in ("width","blocks","scale")):
        parser.error("Missing architecture configuration")
    if args.halo<2*config["blocks"]+4 or args.input<=2*args.halo:
        parser.error("Input/halo must cover the receptive field and a positive core")
    network=NaturalSR(config["width"],config["blocks"],config["scale"]).eval()
    network.load_state_dict(checkpoint["state_dict"])
    model=convert_network(network,args.input,args.fp16)
    parity=verify_network(network,model,args.input,args.fp16)
    args.out.mkdir(parents=True,exist_ok=True)
    file=args.out/f"natural-x{network.scale}-research.tflite"
    file.write_bytes(model)
    manifest={"schema":"lumora-natural-research-v1","model":file.name,
        "sha256":hashlib.sha256(model).hexdigest(),
        "checkpointSha256":hashlib.sha256(args.checkpoint.read_bytes()).hexdigest(),
        "nativeScale":network.scale,"input":[1,args.input,args.input,3],
        "core":args.input-2*args.halo,"halo":args.halo,
        "precision":"FP16 weights" if args.fp16 else "FP32 weights",
        "datasetDigest":checkpoint.get("datasetDigest"),"datasetAuditComplete":checkpoint.get("datasetAuditComplete",False),
        "validation":parity,"releaseApproved":False,"deployedToAndroid":False}
    file.with_suffix(".json").write_text(json.dumps(manifest,indent=2,allow_nan=False)+"\n")
    print(json.dumps(manifest,indent=2,allow_nan=False))


if __name__=="__main__":
    main()
