#!/usr/bin/env python3
"""Clean offline model acceptance in a disposable app ID. Never saves generated/source text."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'br.com.exames.offline.validation'
TEST_PACKAGE = PACKAGE + '.test'


def sha_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_apk(apk):
    lock = json.loads((ROOT / 'validation/models/gemma4-e2b/model.lock.json').read_text())
    with zipfile.ZipFile(apk) as archive:
        info = archive.getinfo('assets/models/gemma4.litertlm')
        if info.compress_type != zipfile.ZIP_STORED or info.file_size != lock['sizeBytes']:
            raise ValueError('BUNDLED_MODEL_SIZE_OR_COMPRESSION')
        digest = hashlib.sha256()
        with archive.open(info) as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != lock['sha256']:
            raise ValueError('BUNDLED_MODEL_HASH')
        if hashlib.sha256(archive.read('assets/models/LICENSE')).hexdigest() != lock['license']['sha256']:
            raise ValueError('BUNDLED_LICENSE_HASH')
        manifest = json.loads(archive.read('assets/models/manifest.json'))
        if manifest['artifact'] != lock:
            raise ValueError('BUNDLED_MANIFEST_MISMATCH')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    if args.report.exists():
        parser.error('REPORT_EXISTS')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    test_apk = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    verify_apk(apk)
    prefix = [args.adb, '-s', args.serial]
    aapt = Path(args.adb).resolve().parent.parent / 'build-tools/36.0.0/aapt'
    for package, artifact in ((PACKAGE, apk), (TEST_PACKAGE, test_apk)):
        metadata = subprocess.run([str(aapt), 'dump', 'badging', str(artifact)], capture_output=True,
                                  text=True, check=True, timeout=30).stdout.splitlines()[0]
        if not metadata.startswith("package: name='" + package + "'"):
            parser.error('UNEXPECTED_APK_PACKAGE: refusing to replace a different app')

    def adb(*parts, timeout=120):
        return subprocess.run(prefix + list(parts), capture_output=True, text=True, check=True, timeout=timeout).stdout.strip()

    # Never replace/clear a package that was already installed before our experiment.
    for package in (PACKAGE, TEST_PACKAGE):
        lookup = subprocess.run(prefix + ['shell', 'pm', 'path', package], capture_output=True,
                                text=True, timeout=30)
        if lookup.returncode not in (0, 1) or lookup.stderr.strip():
            parser.error('PACKAGE_LOOKUP_FAILED')
        if lookup.stdout.strip():
            parser.error('DEDICATED_PACKAGE_ALREADY_EXISTS: refusing to alter existing data')
    if adb('shell', 'settings', 'get', 'global', 'airplane_mode_on') != '1':
        parser.error('AIRPLANE_MODE_REQUIRED')
    # AOSP WifiSettingsStore: 0=disabled, 3=disabled by airplane mode; 1/2 are enabled.
    wifi_state = adb('shell', 'settings', 'get', 'global', 'wifi_on')
    if wifi_state not in ('0', '3'):
        parser.error('WIFI_OFF_REQUIRED_FOR_OFFLINE_ACCEPTANCE')
    record = dict(schemaVersion='bundled-model-acceptance/1', passed=False, package=PACKAGE,
                  cleanInstall=True, airplaneMode=True, wifiPersistedState=wifi_state, appApkSha256=sha_file(apk),
                  testApkSha256=sha_file(test_apk), bundledModelVerified=True, events=[],
                  clinicalValidation=False, thoughtPersisted=False)
    installed = []
    try:
        record['device'] = {key: adb('shell', 'getprop', prop) for key, prop in
            [('model', 'ro.product.model'), ('android', 'ro.build.version.release'), ('api', 'ro.build.version.sdk')]}
        print('Verified bundled model/license; installing disposable offline package.', flush=True)
        for package, artifact in ((PACKAGE, apk), (TEST_PACKAGE, test_apk)):
            adb('install', '-t', str(artifact), timeout=180); installed.append(package)
        output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'bundledModelTest', 'true',
            '-e', 'class', 'br.com.exames.app.BundledInferenceTest',
            TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner', timeout=180)
        for line in output.splitlines():
            if line.startswith('INSTRUMENTATION_STATUS: bundled_model='):
                event = json.loads(line.split('bundled_model=', 1)[1]); record['events'].append(event)
                print(json.dumps(event), flush=True)
            if line.startswith('INSTRUMENTATION_STATUS: stack='):
                record['testFailureType'] = line.split('stack=', 1)[1].split(':', 1)[0]
        record['passed'] = ('OK (1 test)' in output and 'INSTRUMENTATION_CODE: -1' in output and
                            any(event.get('passed') is True for event in record['events']))
    except (subprocess.SubprocessError, ValueError) as failure:
        record['controllerFailureType'] = type(failure).__name__
    finally:
        # Remove only the two packages that this invocation installed into empty package IDs.
        record['cleanupSucceeded'] = True
        for package in reversed(installed):
            try:
                adb('shell', 'am', 'force-stop', package, timeout=20)
                adb('uninstall', package, timeout=60)
            except subprocess.SubprocessError:
                record['cleanupSucceeded'] = False
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open('x') as stream:
            json.dump(record, stream, indent=2); stream.write('\n')
    print('PASS' if record['passed'] else 'FAIL (aggregate report saved)', flush=True)
    return 0 if record['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
