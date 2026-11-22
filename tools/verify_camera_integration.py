#!/usr/bin/env python3
"""Fail closed on camera integration: pinned CameraX, on-demand CAMERA, optional camera feature, no other sensitive access.

Inputs are the merged debug manifest and the `:app:dependencies --configuration debugRuntimeClasspath` text.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = '{http://schemas.android.com/apk/res/android}'
CAMERAX_VERSION = '1.6.2'
# Everything the merged manifest may request. Anything else (location, microphone, storage, network) fails.
ALLOWED_PERMISSIONS = {
    'android.permission.CAMERA',
    'android.permission.WAKE_LOCK', 'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.FOREGROUND_SERVICE',  # WorkManager
}
ALLOWED_PERMISSION_SUFFIX = 'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'  # AndroidX receiver protection, app-scoped
FORBIDDEN_PERMISSION = re.compile(r'INTERNET|NETWORK_STATE|LOCATION|RECORD_AUDIO|EXTERNAL_STORAGE|READ_MEDIA|MEDIA_LOCATION|MANAGE_MEDIA')
FORBIDDEN_DEPENDENCY = re.compile(
    r'com\.squareup\.(?:retrofit|okhttp)|okhttp|retrofit|io\.ktor|firebase|play-services|com\.google\.android\.gms|opentelemetry|'
    r'datatransport|crashlytics|analytics|io\.grpc|volley|cronet|com\.google\.mlkit|camera-mlkit-vision', re.I)


def verify(manifest_text: str, dependencies_text: str) -> list[str]:
    problems: list[str] = []
    root = ET.fromstring(manifest_text)
    perms = {e.get(ANDROID + 'name') for e in root.findall('uses-permission')}
    if 'android.permission.CAMERA' not in perms:
        problems.append('CAMERA_PERMISSION_MISSING')
    for p in sorted(perms):
        if FORBIDDEN_PERMISSION.search(p or ''):
            problems.append(f'FORBIDDEN_PERMISSION {p}')
        elif p not in ALLOWED_PERMISSIONS and not (p or '').endswith(ALLOWED_PERMISSION_SUFFIX):
            problems.append(f'UNEXPECTED_PERMISSION {p}')
    features = {e.get(ANDROID + 'name'): e.get(ANDROID + 'required') for e in root.findall('uses-feature')}
    if features.get('android.hardware.camera') != 'false':
        problems.append('CAMERA_FEATURE_MUST_BE_OPTIONAL')
    if features.get('android.hardware.camera.autofocus', 'false') != 'false':
        problems.append('AUTOFOCUS_FEATURE_MUST_BE_OPTIONAL')
    sdk = root.find('uses-sdk')
    if sdk is None or sdk.get(ANDROID + 'minSdkVersion') != '24':
        problems.append('MIN_SDK_NOT_24')
    if 'debugRuntimeClasspath' not in dependencies_text:
        problems.append('DEPENDENCY_REPORT_NOT_RUNTIME_CLASSPATH')
    camera = set(re.findall(r'androidx\.camera:([\w-]+):([\w.\-]+)', dependencies_text))
    if not any(name == 'camera-core' for name, _ in camera):
        problems.append('CAMERAX_MISSING')
    for name, version in sorted(camera):
        if version != CAMERAX_VERSION:
            problems.append(f'CAMERAX_NOT_PINNED {name}:{version}')
    for line in dependencies_text.splitlines():
        if FORBIDDEN_DEPENDENCY.search(line):
            problems.append('FORBIDDEN_DEPENDENCY ' + line.strip())
    return problems


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--manifest', type=Path, required=True)
    ap.add_argument('--dependencies', type=Path, required=True)
    a = ap.parse_args()
    found = verify(a.manifest.read_text(), a.dependencies.read_text())
    if found:
        print('FAIL:\n  ' + '\n  '.join(found)); sys.exit(1)
    print(f'PASS: CameraX {CAMERAX_VERSION} pinned, CAMERA on demand, camera optional, no network/location/microphone/storage permissions, no network/telemetry clients')
