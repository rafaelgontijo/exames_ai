#!/usr/bin/env python3
"""Select synthetic cases per format/layout without changing their independent truth."""
import argparse
import json
from pathlib import Path
import zipfile
from validate import validate_package

def stage(package, output):
    checked=validate_package(package)
    manifest=checked['manifest']
    if any(c['origin']!='SYNTHETIC' for c in manifest['cases']):
        raise ValueError('SYNTHETIC_ONLY')
    selected={}
    for case in manifest['cases']:
        if case['partition']!='DEVELOPMENT':
            continue
        selected.setdefault((case['format'],case['layoutId']),case)
    if len(selected)!=8:
        raise ValueError('EIGHT_DEVELOPMENT_FORMAT_LAYOUT_CONTROLS_REQUIRED')
    manifest=dict(manifest,cases=list(selected.values()))
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(package) as source, zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as target:
        target.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,separators=(',',':')).encode())
        for case in manifest['cases']:
            for key in ('documentPath','groundTruthPath'):
                target.writestr(case[key],source.read(case[key]))
    result=validate_package(output)
    print(json.dumps({'cases':len(manifest['cases']),'reviewed':result['reviewedCases'],'synthetic':True}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--package',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    a=parser.parse_args();stage(a.package,a.output)
