import unittest
from verify_camera_integration import verify

BASE = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-sdk android:minSdkVersion="24"/>
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="br.com.exames.offline.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
<uses-feature android:name="android.hardware.camera" android:required="false"/>
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false"/>
</manifest>'''
DEPS = 'debugRuntimeClasspath\n+--- androidx.camera:camera-core:1.6.2\n+--- androidx.camera:camera-view:1.6.2\n'


def manifest(extra='', without=''):
    text = BASE.replace('</manifest>', extra + '</manifest>')
    for part in without.split('|'):
        if part:
            text = '\n'.join(l for l in text.splitlines() if part not in l)
    return text


class CameraIntegrationAuditTest(unittest.TestCase):
    def test_good_configuration_passes(self):
        self.assertEqual([], verify(manifest(), DEPS))

    def test_missing_camera_permission_or_required_feature_fails(self):
        self.assertIn('CAMERA_PERMISSION_MISSING', verify(manifest(without='permission.CAMERA'), DEPS))
        required = BASE.replace('android.hardware.camera" android:required="false"', 'android.hardware.camera" android:required="true"')
        self.assertIn('CAMERA_FEATURE_MUST_BE_OPTIONAL', verify(required, DEPS))
        self.assertIn('CAMERA_FEATURE_MUST_BE_OPTIONAL', verify(manifest(without='hardware.camera"'), DEPS))

    def test_each_sensitive_permission_fails(self):
        for p in ['INTERNET', 'ACCESS_NETWORK_STATE', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'RECORD_AUDIO',
                  'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'READ_MEDIA_IMAGES', 'ACCESS_MEDIA_LOCATION', 'MANAGE_EXTERNAL_STORAGE']:
            found = verify(manifest(f'<uses-permission android:name="android.permission.{p}"/>'), DEPS)
            self.assertTrue(any(f.startswith('FORBIDDEN_PERMISSION') for f in found), p)

    def test_unknown_permission_fails_closed(self):
        found = verify(manifest('<uses-permission android:name="android.permission.READ_CONTACTS"/>'), DEPS)
        self.assertIn('UNEXPECTED_PERMISSION android.permission.READ_CONTACTS', found)

    def test_min_sdk_must_stay_24(self):
        self.assertIn('MIN_SDK_NOT_24', verify(BASE.replace('"24"', '"26"'), DEPS))

    def test_unpinned_or_missing_camerax_fails(self):
        self.assertIn('CAMERAX_NOT_PINNED camera-core:1.6.1', verify(manifest(), DEPS.replace('1.6.2', '1.6.1', 1)))
        self.assertIn('CAMERAX_MISSING', verify(manifest(), 'debugRuntimeClasspath\n'))

    def test_network_and_telemetry_clients_fail(self):
        for lib in ['com.squareup.okhttp3:okhttp:4.12.0', 'io.ktor:ktor-client-core:2.0', 'com.google.firebase:firebase-analytics:1',
                    'com.google.android.gms:play-services-base:1', 'androidx.camera:camera-mlkit-vision:1.6.2']:
            found = verify(manifest(), DEPS + '+--- ' + lib + '\n')
            self.assertTrue(any(f.startswith('FORBIDDEN_DEPENDENCY') for f in found), lib)

    def test_report_must_be_the_runtime_classpath(self):
        self.assertIn('DEPENDENCY_REPORT_NOT_RUNTIME_CLASSPATH', verify(manifest(), DEPS.replace('debugRuntimeClasspath', 'debugCompileClasspath')))


if __name__ == '__main__':
    unittest.main()
