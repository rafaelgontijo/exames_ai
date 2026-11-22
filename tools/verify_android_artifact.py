#!/usr/bin/env python3
"""Fail closed on final APK permissions/backup and bundled artifact identity."""
import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import hashlib
import json
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]

def verify(apk,manifest,aapt,dependencies):
    spec=importlib.util.spec_from_file_location('bundled',ROOT/'tools/litert/run_bundled.py')
    bundled=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundled);bundled.verify_apk(apk)
    ocr=json.loads((ROOT/'validation/ocr/model.lock.json').read_text())
    with zipfile.ZipFile(apk) as z:
        model=z.getinfo('assets/ocr/por.traineddata')
        assert model.file_size==ocr['modelBytes'] and model.compress_type==zipfile.ZIP_STORED
        assert hashlib.sha256(z.read(model)).hexdigest()==ocr['modelSha256']
        assert json.loads(z.read('assets/ocr/manifest.json'))==ocr
        for license in ocr['licenses']:
            assert hashlib.sha256(z.read('assets/ocr/licenses/'+Path(license['file']).name)).hexdigest()==license['sha256']
    android='{http://schemas.android.com/apk/res/android}'
    root=ET.parse(manifest).getroot();app=root.find('application')
    assert app is not None
    assert app.get(android+'allowBackup')=='false' and app.get(android+'fullBackupContent')=='false'
    assert app.get(android+'usesCleartextTraffic')=='false'
    assert root.find('uses-sdk').get(android+'minSdkVersion')=='24'
    permissions={e.get(android+'name') for e in root.findall('uses-permission')}
    assert 'android.permission.INTERNET' not in permissions
    tree=subprocess.run([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],check=True,capture_output=True,text=True).stdout
    assert 'android.permission.INTERNET' not in tree
    assert re.search(r'android:allowBackup[^\n]*\(type 0x12\)0x0',tree)
    assert re.search(r'android:fullBackupContent[^\n]*\(type 0x12\)0x0',tree)
    text=dependencies.read_text()
    assert 'BUILD SUCCESSFUL' in text and 'debugRuntimeClasspath' in text
    assert not re.search(r'com\.squareup\.(?:retrofit|okhttp)|io\.ktor|firebase-analytics|firebase-crashlytics|opentelemetry|com\.google\.android\.datatransport|com\.google\.mlkit',text,re.I)
    print('PASS: APK model/hash/license, minSdk24, no INTERNET, backup/cleartext disabled, no application network/analytics clients')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--aapt',type=Path,required=True);p.add_argument('--dependencies',type=Path,required=True)
    a=p.parse_args();verify(a.apk,a.manifest,a.aapt,a.dependencies)
