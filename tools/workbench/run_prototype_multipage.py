#!/usr/bin/env python3
"""Run the standalone synthetic PDF regression through import, summary and the local recipient."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from instrumentation_events import run_events

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "litert"))
from run_bundled import sha_file, verify_apk

PACKAGE = "br.com.exames.offline.validation.prototype"
TEST = PACKAGE + ".test"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    for name in ("apk", "test-apk", "report"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--capture", type=Path)
    args = parser.parse_args()
    if args.report.exists() or (args.capture and args.capture.exists()):
        parser.error("NEW_EVIDENCE_PATH_REQUIRED")
    verify_apk(args.apk)
    adb = [args.adb, "-s", args.serial]
    aapt = Path(args.adb).resolve().parent.parent / "build-tools/36.0.0/aapt"

    def device(*command, timeout=240):
        return subprocess.run(adb + list(command), check=True, timeout=timeout,
                              capture_output=True, text=True).stdout.strip()

    model = device("shell", "getprop", "ro.product.model")
    emulator = args.serial.startswith("emulator-")
    if not emulator and model != "SM-S931B":
        parser.error("REFERENCE_S25_REQUIRED")
    api = int(device("shell", "getprop", "ro.build.version.sdk"))
    airplane = device("shell", "settings", "get", "global", "airplane_mode_on")
    wifi = device("shell", "settings", "get", "global", "wifi_on")
    if not emulator and (airplane != "1" or wifi not in ("0", "3")):
        parser.error("AIRPLANE_MODE_AND_WIFI_OFF_REQUIRED_BEFORE_INSTALL")
    for apk, package in ((args.apk, PACKAGE), (args.test_apk, TEST)):
        info = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True,
                              capture_output=True, text=True, timeout=45).stdout
        if not info.startswith(f"package: name='{package}'"):
            parser.error("DISPOSABLE_PACKAGE_REQUIRED")
        if f"package:{package}" in device("shell", "pm", "list", "packages", package).splitlines():
            parser.error("EXISTING_PACKAGE_PRESERVED")

    report = {"schemaVersion": 1, "date": datetime.now(timezone.utc).isoformat(),
              "device": {"model": model, "api": api, "airplane": airplane, "wifi": wifi},
              "applicationId": PACKAGE, "cleanInstall": True, "appApkSha256": sha_file(args.apk),
              "testApkSha256": sha_file(args.test_apk), "runs": [], "events": [], "passed": False}
    try:
        device("install", "-t", str(args.apk))
        device("install", "-t", str(args.test_apk))
        methods = ["multipageBaselineAndLocalRecipient"]
        if not emulator:
            methods.append("multipageAssistedAndLocalRecipient")
        for method in methods:
            arguments = ["-e", "class", "br.com.exames.app.PrototypeAcceptanceTest#" + method]
            if args.capture and method == methods[0]:
                arguments += ["-e", "captureSynthetic", "true"]
            arguments.append(TEST + "/androidx.test.runner.AndroidJUnitRunner")
            result = run_events(adb, arguments, "prototype", report["events"].append, timeout=850)
            report["runs"].append({"test": method, **result})
            if not result["runnerPassed"]:
                raise RuntimeError("MULTIPAGE_TEST_FAILED")
        if args.capture:
            screenshot = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat",
                                                "cache/prototype-synthetic-preview.png"],
                                        check=True, capture_output=True, timeout=20).stdout
            if not screenshot.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("SCREENSHOT_UNAVAILABLE")
            args.capture.write_bytes(screenshot)
            report["capture"] = args.capture.name
        report["passed"] = all(run["runnerPassed"] for run in report["runs"])
    except (RuntimeError, subprocess.SubprocessError) as failure:
        report["controllerFailure"] = type(failure).__name__
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
