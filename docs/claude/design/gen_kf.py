import math
def spring(t, zeta, w):
    if t<=0: return 0.0
    wd=w*math.sqrt(1-zeta*zeta)
    return 1-math.exp(-zeta*w*t)*(math.cos(wd*t)+zeta*w/wd*math.sin(wd*t))
def frames(dur, s0x, s0y, r0, r1, zeta=0.62, w=15.5, lag=0.035, steps=24, op0=0.9, extra=''):
    out=[]
    for i in range(steps+1):
        f=i/steps; t=f*dur
        px=spring(t,zeta,w); py=spring(max(0,t-lag),zeta,w)
        sx=s0x+(1-s0x)*px; sy=s0y+(1-s0y)*py
        pr=min(1,spring(t,0.9,w*0.9))
        if r1 is None: rr=f'border-radius:{r0}px;' if (r0 is not None and i==0) else ''
        else: rr=f'border-radius:{r0+(r1-r0)*pr:.0f}px;' if i<steps else ''
        op=min(1,op0+(1-op0)*min(1,f*4))
        out.append(f'{f*100:.1f}%{{scale:{sx:.4f} {sy:.4f};{rr}opacity:{op:.2f}}}')
    return ''.join(out)
def outframes(dur, s1x, s1y, r0, r1, steps=14, dy=False):
    out=[]
    for i in range(steps+1):
        f=i/steps
        e=f*f*(3-2*f) if f<0.5 else 1-(1-f)**3*0  # placeholder
        e=1-(1-f)**2.2 if False else f**1.7  # ease-in
        sx=1+(s1x-1)*e; sy=1+(s1y-1)*min(1,e*1.08)
        op=1 if f<0.6 else 1-(f-0.6)/0.4
        r=f'border-radius:{r0+(r1-r0)*e:.0f}px;' if r0 is not None else (f'border-radius:999px;' if i==steps else '')
        tr=f'transform:translateY(calc(var(--dy,0px) * {1-e:.3f}));' if dy else ''
        out.append(f'{f*100:.1f}%{{{tr}scale:{sx:.4f} {sy:.4f};{r}opacity:{op:.2f}}}')
    return ''.join(out)
liquidIn=frames(0.72,0.14,0.066,999,34,zeta=0.6,w=13.5,lag=0.04,steps=30)
popIn=frames(0.52,0.24,0.16,999,None,zeta=0.62,w=17,lag=0.03,steps=22,op0=0.0)
viewIn=frames(0.6,0.3,0.2,60,0,zeta=0.7,w=15,lag=0.03,steps=24,op0=0.0)
css=(f"@keyframes liquidInA{{{liquidIn}}}\n@keyframes liquidInB{{{liquidIn}}}\n"
     f"@keyframes liquidOut{{{outframes(0.42,0.14,0.066,34,999,dy=True)}}}\n"
     f"@keyframes popInA{{{popIn}}}\n@keyframes popInB{{{popIn}}}\n"
     f"@keyframes popOut{{{outframes(0.28,0.24,0.16,None,None)}}}\n"
     f"@keyframes viewInA{{{viewIn}}}\n@keyframes viewInB{{{viewIn}}}\n"
     f"@keyframes viewOut{{{outframes(0.36,0.3,0.2,0,60)}}}\n"
     "@keyframes contentIn{0%,30%{opacity:0;translate:0 8px}100%{opacity:1;translate:0 0}}\n"
     "@keyframes contentOut{0%{opacity:1}100%{opacity:0}}\n")
open('kf.css','w').write(css)
print(len(css)); print(liquidIn[:400])
