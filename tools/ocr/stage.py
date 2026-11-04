#!/usr/bin/env python3
"""Pinned native OCR and static Portuguese weights; no clinical input."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import xml.etree.ElementTree as ET
import zipfile
ROOT=Path(__file__).resolve().parents[2]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def stage(aar,model,output):
    lock=json.loads((ROOT/'validation/ocr/model.lock.json').read_text())
    if sha(aar)!=lock['aarSha256'] or model.stat().st_size!=lock['modelBytes'] or sha(model)!=lock['modelSha256']:
        raise ValueError('OCR_ARTIFACT_IDENTITY')
    with zipfile.ZipFile(aar) as z:
        root=ET.fromstring(z.read('AndroidManifest.xml'))
        if int(root.find('uses-sdk').get('{http://schemas.android.com/apk/res/android}minSdkVersion'))>24: raise ValueError('OCR_MIN_SDK')
        checked=0
        for n in z.namelist():
            if n.startswith(('jni/arm64-v8a/','jni/x86_64/')) and n.endswith('.so'):
                b=z.read(n);off=struct.unpack_from('<Q',b,32)[0];size,count=struct.unpack_from('<HH',b,54)
                for i in range(count):
                    if struct.unpack_from('<I',b,off+i*size)[0]==1 and struct.unpack_from('<Q',b,off+i*size+48)[0]<16384:
                        raise ValueError('OCR_ALIGNMENT')
                checked+=1
        if checked!=8: raise ValueError('OCR_ABI')
    for notice in lock['licenses']:
        if sha(ROOT/'validation/ocr'/notice['file'])!=notice['sha256']: raise ValueError('OCR_LICENSE')
    output.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(model,output/'por.traineddata')
    notices=output/'licenses';notices.mkdir(exist_ok=True)
    for notice in lock['licenses']: shutil.copyfile(ROOT/'validation/ocr'/notice['file'],notices/Path(notice['file']).name)
    (output/'manifest.json').write_text(json.dumps(lock,indent=2)+'\n')
    print('Verified pinned OCR AAR/model/licenses, minSdk and 64-bit 16 KiB alignment')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--aar',type=Path,required=True);p.add_argument('--model',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();stage(a.aar,a.model,a.output)
