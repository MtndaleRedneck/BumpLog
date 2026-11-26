#!/usr/bin/env python3
"""Measure notification icons in the status bar from raw emulator PNGs. Usage: measure.py OUTDIR
Validity rules (an INVALID condition prints a reason and NO numbers):
  * trigger_log.txt must show posted>0 for the app and a matching package; NOT_RUN/NOT_ON_IMAGE are printed as such.
  * baseline (no app notification) cluster count per theme is measured from 5 captures; solo capture must have exactly
    ONE cluster beyond the baseline icons in every repeat, else INVALID.
  * clock position must match the baseline clock within CLOCK_TOL px or the capture is flagged.
  * combined: every non-baseline cluster is labelled by signature match against the VALID solo captures
    (w,h within 2 px, ink within 10%); anything else prints UNMATCHED (no gap is attributed to it).
Ink bounds are not system slot bounds (no slot bounds are obtained unless the hierarchy exposes them; not attempted here).
Cluster 0 = clock (leftmost). Threshold: |luma - median(bar luma)| > DELTA. Bar crop: top BAR_H px, left LEFT_FRAC of width."""
import sys, glob, os, re, json
import numpy as np
from PIL import Image, ImageDraw
BAR_H=120; LEFT_FRAC=0.45; DELTA=40; MERGE=9; CLOCK_TOL=2
def density(d):
    t=open(os.path.join(d,'config.txt')).read(); m=re.findall(r'(?:Override|Physical) density: (\d+)',t); return int(m[-1]) if m else 480
def clusters(path):
    im=Image.open(path).convert('RGB'); W,H=im.size
    bar=np.asarray(im.crop((0,0,int(W*LEFT_FRAC),BAR_H))).astype(float)
    luma=0.299*bar[...,0]+0.587*bar[...,1]+0.114*bar[...,2]
    ink=np.abs(luma-np.median(luma))>DELTA
    if ink[-2:].any(): print('  WARNING ink touches the bottom of the bar crop (BAR_H too small?)',os.path.basename(path))
    cols=np.where(ink.any(axis=0))[0]
    if not len(cols): return [],im
    g=[[cols[0],cols[0]]]
    for c in cols[1:]:
        if c-g[-1][1]<=MERGE: g[-1][1]=c
        else: g.append([c,c])
    out=[]
    for a,b in g:
        sub=ink[:,a:b+1]; r=np.where(sub.any(axis=1))[0]
        out.append(dict(x0=int(a),x1=int(b),w=int(b-a+1),y0=int(r[0]),y1=int(r[-1]),h=int(r[-1]-r[0]+1),ink=int(sub.sum())))
    return out,im
def same(a,b): return abs(a['w']-b['w'])<=2 and abs(a['h']-b['h'])<=2 and abs(a['ink']-b['ink'])<=0.10*max(a['ink'],b['ink'])
def reps(d,name): return sorted(glob.glob(os.path.join(d,f'{name}_r[1-9].png')))
def main(d):
    dpi=density(d); k=160.0/dpi
    log={}
    for l in open(os.path.join(d,'trigger_log.txt')):
        p=l.strip().split('|')
        if len(p)>=5: log[(p[0],p[1])]=dict(pkg=p[2],posted=int(re.sub(r'\D','',p[3]) or 0),note=p[4])
    print(f'density={dpi} dpi; dp = px*{k:.3f}; INK bounds only; emulator, not the user phone\n')
    for th in ('dark','light'):
        print(f'##### THEME {th}')
        base=[clusters(p)[0] for p in reps(d,f'base_{th}')]
        if not base: print('no baseline captures: ALL CONDITIONS INVALID'); continue
        nb=sorted(len(b) for b in base)[len(base)//2]
        if len(set(len(b) for b in base))>1 or any(len(b)==0 for b in base): print('ALL CONDITIONS INVALID this theme: baseline cluster count varies or empty',[len(b) for b in base]); continue
        bclock=base[0][0] if base[0] else None; bicons=base[0][1:] if base[0] else []
        print(f'baseline: {nb} clusters (clock + {nb-1} system icons) {[ (c["w"],c["h"]) for c in bicons]}')
        sig={}
        for app in ('BumpLog','ListenLater','Messages','ShellPost'):
            e=log.get((th,app))
            if not e: print(f'{app}: NOT RUN (not in log)'); continue
            if not e['note'].startswith(('ok','pkgcheck=ok')): print(f'{app}: NOT RUN/INVALID ({e["note"]})'); continue
            if e['posted']<1: print(f'{app}: INVALID (no notification posted; trigger failed)'); continue
            ea=log.get((th,app+'_after'))
            if ea and ea['posted']<1: print(f'{app}: INVALID (notification gone before/during captures: after-count 0)'); continue
            rows=[]; bad=None
            for p in reps(d,f'solo_{app}_{th}'):
                cl,_=clusters(p)
                if not cl: bad=f'{os.path.basename(p)} no ink in bar'; break
                if abs(cl[0]['x1']-bclock['x1'])>CLOCK_TOL: bad=f'clock moved vs baseline in {os.path.basename(p)}'
                new=[c for c in cl[1:] if not any(same(c,b) for b in bicons)]
                if len(new)!=1: bad=f'{os.path.basename(p)} has {len(new)} new clusters, need exactly 1'; break
                n=new[0]; rows.append((os.path.basename(p),cl[0]['x1'],n,n['x0']-cl[0]['x1']-1,cl.index(n)))
            if bad or not rows: print(f'{app}: INVALID ({bad or "no captures"})'); continue
            sig[app]=rows[0][2]
            g=[r[3] for r in rows]
            print(f'{app}: VALID n={len(rows)} clock_right->icon_left px {g} (min {min(g)} max {max(g)}, spread {max(g)-min(g)}) = {min(g)*k:.2f}..{max(g)*k:.2f} dp; icon w,h px {rows[0][2]["w"]},{rows[0][2]["h"]} = {rows[0][2]["w"]*k:.1f}x{rows[0][2]["h"]*k:.1f} dp; ink px {rows[0][2]["ink"]}; position index {rows[0][4]}')
            print(f'   (gap is to the clock only when index==1; otherwise a system icon sits between: index {rows[0][4]})')
        for app in ('BingeMote','HomeRoom'):
            e=log.get((th,app)); print(f'{app}: NOT RUN ({e["note"] if e else "no log"})')
        for name in (f'combined_{th}',f'nodemo_combined_{th}'):
            print(f'-- {name}' + ('  (non-demo: real clock/system icons, 3 captures)' if 'nodemo' in name else ''))
            if len(sig)<2: print('   INVALID: need >=2 valid solo signatures to label icons'); continue
            allg=[]
            for p in reps(d,name):
                cl,im=clusters(p)
                if not cl: print(f'   {os.path.basename(p)}: INVALID no ink'); continue
                icons=cl[1:]; labels=[]
                for c in icons:
                    lab='system(baseline)' if any(same(c,b) for b in bicons) else next((a for a,s in sig.items() if same(c,s)),'UNMATCHED')
                    labels.append(lab)
                seq=[(lab,c['x0'],c['x1']) for lab,c in zip(labels,icons)]
                gaps=[(f'{seq[i][0]}->{seq[i+1][0]}',seq[i+1][1]-seq[i][2]-1) for i in range(len(seq)-1)]
                print(f'   {os.path.basename(p)}: clock_x1={cl[0]["x1"]} order={[s[0] for s in seq]} clock->first={icons[0]["x0"]-cl[0]["x1"]-1 if icons else None} gaps_px={gaps}')
                dr=ImageDraw.Draw(im)
                for i,c in enumerate(cl): dr.rectangle([c['x0'],c['y0'],c['x1'],c['y1']],outline=(255,0,0) if i==0 else (0,255,0))
                if p.endswith('_r1.png'): im.crop((0,0,int(im.size[0]*LEFT_FRAC),BAR_H)).resize((int(im.size[0]*LEFT_FRAC)*2,BAR_H*2)).save(p.replace('.png','_annot.png'))
if __name__=='__main__': main(sys.argv[1])
