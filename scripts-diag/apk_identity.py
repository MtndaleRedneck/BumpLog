#!/usr/bin/env python3
"""Print sha256, size, package, version, targetSdk of each released APK, and measure alpha bounds of any
ic_stat* PNG found inside it (so the RELEASED asset is measured, not repo main)."""
import sys, glob, os, hashlib, subprocess, zipfile, io, re
from PIL import Image
import numpy as np
aapt=sorted(glob.glob(os.environ.get('ANDROID_HOME','/usr/local/lib/android/sdk')+'/build-tools/*/aapt2'))
for apk in sorted(glob.glob(os.path.join(sys.argv[1],'*.apk'))):
    print('==',os.path.basename(apk),os.path.getsize(apk),'bytes sha256',hashlib.sha256(open(apk,'rb').read()).hexdigest())
    if aapt:
        b=subprocess.run([aapt[-1],'dump','badging',apk],capture_output=True,text=True).stdout
        for k in ('package:','sdkVersion','targetSdkVersion'):
            for l in b.splitlines():
                if l.startswith(k): print('  ',l[:200])
    z=zipfile.ZipFile(apk)
    found=[n for n in z.namelist() if re.search(r'ic_stat|notif|small_icon',os.path.basename(n),re.I) and n.startswith('res/')]
    if not found: print('   no ic_stat/notif-named resource (names may be obfuscated by shrinking; see combined_icons.txt)')
    for n in found:
        if n.endswith('.png'):
            a=np.asarray(Image.open(io.BytesIO(z.read(n))).convert('RGBA'))[...,3]
            ys,xs=np.where(a>8); H,W=a.shape
            print(f'   {n} canvas {W}x{H} content x{xs.min()}-{xs.max()} y{ys.min()}-{ys.max()} margins L{xs.min()} R{W-1-xs.max()} T{ys.min()} B{H-1-ys.max()} opaque>200 {(a>200).sum()/(W*H):.2%}')
        else: print('  ',n,'(non-png, e.g. xml vector; inspect with apkanalyzer)')
