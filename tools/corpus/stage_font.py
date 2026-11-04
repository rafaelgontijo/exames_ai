#!/usr/bin/env python3
"""Extract only the pinned corpus font from a verified, externally downloaded Debian package."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
ROOT=Path(__file__).resolve().parents[2]
def stage(package,output):
    source=json.loads((ROOT/'tools/corpus/font-source.lock.json').read_text())
    data=package.read_bytes()
    if hashlib.sha256(data).hexdigest()!=source['packageSha256'] or not data.startswith(b'!<arch>\n'): raise ValueError('FONT_PACKAGE_HASH')
    at=8
    while at<len(data):
        header=data[at:at+60];name=header[:16].decode().strip().rstrip('/');size=int(header[48:58]);body=data[at+60:at+60+size];at+=60+size+(size%2)
        if name.startswith('data.tar'):
            with tarfile.open(fileobj=io.BytesIO(body)) as tar: font=tar.extractfile('./usr/share/fonts/truetype/dejavu/DejaVuSans.ttf').read()
            expected=json.loads((ROOT/'tools/corpus/toolchain.lock.json').read_text())['fontSha256']
            if hashlib.sha256(font).hexdigest()!=expected: raise ValueError('FONT_HASH')
            output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(font);return
    raise ValueError('FONT_MISSING')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--package',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();stage(a.package,a.output)
