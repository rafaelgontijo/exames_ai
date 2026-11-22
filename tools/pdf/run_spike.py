#!/usr/bin/env python3
"""Opt-in synthetic PDF spike. Collects metrics, never document text or raw logcat."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

PACKAGE = 'br.com.exames.pdfspike.test'
RUNNER = PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'br.com.exames.pdfspike.PdfiumBufferSpikeTest'
ROOT = Path(__file__).resolve().parents[2]


def verify_native_apk(apk, build):
    if build['profile'] not in ('pdfium-jni-exames/1', 'pdfium-jni-exames/2'):
        raise ValueError('Unknown native profile')
    for key, file in [('patchSha256', 'validation/pdf/pdfium-2.0.1-jni-proposal.patch'),
                      ('sourceLockSha256', 'validation/pdf/native-sources.lock.json')]:
        if hashlib.sha256((ROOT / file).read_bytes()).hexdigest() != build[key]:
            raise ValueError('Native build is stale for current source/patch')
    if {item['abi'] for item in build['native']} != {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}:
        raise ValueError('Incomplete native ABI inventory')
    if build['profile'].endswith('/2'):
        lock = ROOT / 'validation/pdf/engine.lock.json'
        if hashlib.sha256(lock.read_bytes()).hexdigest() != build['engineLockSha256']:
            raise ValueError('Native build is stale for engine lock')
        engine = json.loads(lock.read_text())
        engine_by_abi = {item['abi']: item for item in engine['archives']}
        for item in build['native']:
            if item['engineSha256'] != engine_by_abi[item['abi']]['engineSha256']:
                raise ValueError('Engine binary differs from pinned distribution')
    with zipfile.ZipFile(apk) as archive:
        for item in build['native']:
            for name, key in [('libpdfiumandroid.so', 'jniSha256'), ('libpdfium.so', 'engineSha256')]:
                path = f'lib/{item["abi"]}/{name}'
                if archive.namelist().count(path) != 1 or hashlib.sha256(archive.read(path)).hexdigest() != item[key]:
                    raise ValueError('Native binary hash mismatch before installation')
        if build['profile'].endswith('/2'):
            for item in engine['archives']:
                for name, expected in item['files'].items():
                    path = f'assets/licenses/pdfium-engine-{engine["version"]}/{item["abi"]}/{name}'
                    if hashlib.sha256(archive.read(path)).hexdigest() != expected:
                        raise ValueError('Engine notices missing or modified in APK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, default=Path('pdf-spike/build/outputs/apk/androidTest/debug/pdf-spike-debug-androidTest.apk'))
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--native-build-report', type=Path,
                        help='Verify corrected JNI hashes in the APK before installing')
    args = parser.parse_args()
    if args.report.exists():
        parser.error('Report already exists; choose a new path')
    apk_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    native_build = None
    if args.native_build_report:
        native_build = json.loads(args.native_build_report.read_text())
        try:
            verify_native_apk(args.apk, native_build)
        except (ValueError, KeyError, zipfile.BadZipFile) as error:
            parser.error(str(error))
    prefix = [args.adb, '-s', args.serial]

    def adb(*arguments, timeout=60):
        return subprocess.run(prefix + list(arguments), check=True, capture_output=True,
                              text=True, timeout=timeout).stdout

    record = {'controllerVersion': 'pdf-spike/2', 'apkSha256': apk_hash, 'passed': False}
    native_profile = native_build['profile'] if native_build else 'upstream'
    if native_build:
        record['nativeBuild'] = native_build
    try:
        phase = 'install'
        adb('install', '-r', str(args.apk), timeout=90)
        phase = 'instrument'
        output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'pdfSpike', 'true',
                     '-e', 'pdfiumNativeProfile', native_profile,
                     '-e', 'class', CLASS, RUNNER, timeout=120)
        record['instrumentationPassed'] = 'OK (1 test)' in output and 'INSTRUMENTATION_CODE: -1' in output
        phase = 'metrics'
        raw = adb('shell', 'run-as', PACKAGE, 'cat', 'files/pdf-spike-report.json')
        if len(raw) > 65536:
            raise ValueError('Metrics report too large')
        record['metrics'] = json.loads(raw)
        pid = int(record['metrics']['processId'])
        # Inspect only this synthetic test process. Raw logs stay in memory and are discarded.
        phase = 'logs'
        logs = adb('logcat', '-d', '-v', 'brief', '--pid', str(pid))
        markers = ('SYNTHETIC-PDF-PRIVATE-MARKER', 'SYNTHETIC-INVALID-PRIVATE-TOKEN', 'SYNTHETIC RASTER')
        record['sourceMarkerFoundInProcessLogs'] = any(marker in logs for marker in markers)
        record['kernelPageSize'] = int(adb('shell', 'getconf', 'PAGE_SIZE').strip())
        record['passed'] = (record['instrumentationPassed'] and record['metrics'].get('passed') is True
                            and not record['sourceMarkerFoundInProcessLogs'])
        if native_build:
            record['passed'] = (record['passed'] and record['metrics'].get('nativeProfile') == native_profile
                                and record['metrics'].get('directBufferInvalidFourMiBRejected') == 8
                                and record['metrics'].get('directBufferValidOpenCloseCycles') == 16)
    except (subprocess.SubprocessError, ValueError, KeyError) as error:
        record['controllerFailureClass'] = type(error).__name__
        record['controllerFailurePhase'] = phase
    finally:
        # Stop only our dedicated synthetic harness; leave app/device settings untouched.
        try:
            adb('shell', 'am', 'force-stop', PACKAGE, timeout=15)
        except subprocess.SubprocessError:
            pass
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open('x') as stream:
            json.dump(record, stream, indent=2, ensure_ascii=False)
            stream.write('\n')
    print(json.dumps({'passed': record['passed'], 'report': str(args.report)}))
    return 0 if record['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
