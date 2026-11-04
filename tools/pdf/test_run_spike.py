import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import run_spike


class NativeArtifactVerificationTest(unittest.TestCase):
    def fixture(self, root):
        apk = root / 'native.apk'
        build = {'profile': 'pdfium-jni-exames/1', 'native': []}
        for key, name in [('patchSha256', 'validation/pdf/pdfium-2.0.1-jni-proposal.patch'),
                          ('sourceLockSha256', 'validation/pdf/native-sources.lock.json')]:
            build[key] = hashlib.sha256((run_spike.ROOT / name).read_bytes()).hexdigest()
        with zipfile.ZipFile(apk, 'w') as archive:
            for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
                item = {'abi': abi}
                for name, key in [('libpdfiumandroid.so', 'jniSha256'), ('libpdfium.so', 'engineSha256')]:
                    data = (abi + name).encode()
                    archive.writestr(f'lib/{abi}/{name}', data)
                    item[key] = hashlib.sha256(data).hexdigest()
                build['native'].append(item)
        return apk, build

    def test_rejects_native_replacement_before_install(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, build = self.fixture(Path(directory))
            run_spike.verify_native_apk(apk, build)
            changed = copy.deepcopy(build)
            changed['native'][0]['jniSha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                run_spike.verify_native_apk(apk, changed)

    def test_rejects_stale_patch_or_incomplete_abis(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, build = self.fixture(Path(directory))
            changed = copy.deepcopy(build)
            changed['patchSha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'stale'):
                run_spike.verify_native_apk(apk, changed)
            build['native'].pop()
            with self.assertRaisesRegex(ValueError, 'ABI'):
                run_spike.verify_native_apk(apk, build)


class ReportAcceptanceTest(unittest.TestCase):
    def exercise(self, instrumentation='OK (1 test)\nINSTRUMENTATION_CODE: -1', logs='', timeout=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'synthetic.apk'
            apk.write_bytes(b'synthetic apk')
            report = root / 'report.json'

            def command(args, **kwargs):
                tail = args[3:]
                if tail[0] == 'install':
                    output = 'Success'
                elif tail[:3] == ['shell', 'am', 'instrument']:
                    if timeout:
                        raise subprocess.TimeoutExpired('synthetic', 120)
                    output = instrumentation
                elif tail[:2] == ['shell', 'run-as']:
                    output = json.dumps({'passed': True, 'processId': 123})
                elif tail[0] == 'logcat':
                    output = logs
                elif tail[:2] == ['shell', 'getconf']:
                    output = '4096'
                else:
                    self.assertEqual(tail, ['shell', 'am', 'force-stop', run_spike.PACKAGE])
                    output = ''
                return subprocess.CompletedProcess(args, 0, output)

            arguments = ['run_spike.py', '--adb', 'adb', '--serial', 'synthetic-device',
                         '--apk', str(apk), '--report', str(report)]
            with patch('sys.argv', arguments), patch('subprocess.run', side_effect=command), contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_spike.main()
            return exit_code, json.loads(report.read_text())

    def test_requires_instrumentation_success_even_when_metrics_claim_pass(self):
        code, report = self.exercise(instrumentation='INSTRUMENTATION_CODE: 0')
        self.assertEqual(1, code)
        self.assertFalse(report['passed'])

    def test_rejects_source_marker_in_logs_without_persisting_logs(self):
        code, report = self.exercise(logs='SYNTHETIC-PDF-PRIVATE-MARKER')
        self.assertEqual(1, code)
        self.assertTrue(report['sourceMarkerFoundInProcessLogs'])
        self.assertNotIn('SYNTHETIC-PDF-PRIVATE-MARKER', json.dumps(report))

    def test_timeout_cannot_be_reported_as_success(self):
        code, report = self.exercise(timeout=True)
        self.assertEqual(1, code)
        self.assertFalse(report['passed'])
        self.assertEqual('TimeoutExpired', report['controllerFailureClass'])

    def test_accepts_only_complete_run_without_marker(self):
        code, report = self.exercise()
        self.assertEqual(0, code)
        self.assertTrue(report['passed'])
        self.assertEqual(64, len(report['apkSha256']))


if __name__ == '__main__':
    unittest.main()
