#!/usr/bin/env python3
"""Fail closed on final APK permissions, backup/cleartext flags, artifact mode, and zero telemetry."""
import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

ALLOWED_PERMISSIONS = frozenset({
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    # Added transitively by AndroidX WorkManager for scheduled/boot-resilient workers.
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "br.com.exames.offline.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
})

FORBIDDEN_ANALYTICS_PATTERN = re.compile(
    r"firebase-analytics|firebase-crashlytics|opentelemetry|"
    r"com\.google\.android\.datatransport|com\.google\.mlkit|"
    r"io\.sentry|com\.amplitude|com\.mixpanel|com\.datadoghq|com\.appsflyer",
    re.I,
)


def verify(apk: Path, manifest: Path, aapt: Path, dependencies: Path, bundled_model: bool = False) -> None:
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        assert not any(n.startswith("assets/ocr/") for n in names), "Unexpected legacy OCR assets in APK"
        if bundled_model:
            spec = importlib.util.spec_from_file_location("bundled", ROOT / "tools/litert/run_bundled.py")
            bundled = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(bundled)
            bundled.verify_apk(apk)
        else:
            assert not any(n.startswith("assets/models/") or n.endswith(".litertlm") for n in names), (
                "Default lightweight APK must not bundle .litertlm weights"
            )

    android = "{http://schemas.android.com/apk/res/android}"
    root = ET.parse(manifest).getroot()
    app = root.find("application")
    assert app is not None
    assert app.get(android + "allowBackup") == "false", "allowBackup must be false"
    assert app.get(android + "fullBackupContent") == "false", "fullBackupContent must be false"
    assert app.get(android + "usesCleartextTraffic") == "false", "usesCleartextTraffic must be false"
    assert root.find("uses-sdk").get(android + "minSdkVersion") == "24", "minSdkVersion must be 24"

    permissions = {e.get(android + "name") for e in root.findall("uses-permission")}
    unexpected = sorted(p for p in permissions if p not in ALLOWED_PERMISSIONS)
    assert not unexpected, f"Unexpected APK permissions: {unexpected}"

    tree = subprocess.run(
        [str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert re.search(r"android:allowBackup[^\n]*\(type 0x12\)0x0", tree), "allowBackup=false missing in binary manifest"
    assert re.search(r"android:fullBackupContent[^\n]*\(type 0x12\)0x0", tree), "fullBackupContent=false missing"
    assert re.search(r"android:usesCleartextTraffic[^\n]*\(type 0x12\)0x0", tree), "usesCleartextTraffic=false missing"

    text = dependencies.read_text()
    assert "BUILD SUCCESSFUL" in text and "debugRuntimeClasspath" in text
    assert not FORBIDDEN_ANALYTICS_PATTERN.search(text), "Forbidden analytics/telemetry dependency detected"

    mode = "bundled-model" if bundled_model else "lightweight-on-demand"
    print(f"PASS: APK ({mode}), minSdk24, allowlisted permissions, backup/cleartext disabled, zero analytics/telemetry")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--aapt", type=Path, required=True)
    p.add_argument("--dependencies", type=Path, required=True)
    p.add_argument("--bundled-model", action="store_true")
    a = p.parse_args()
    verify(a.apk, a.manifest, a.aapt, a.dependencies, bundled_model=a.bundled_model)
