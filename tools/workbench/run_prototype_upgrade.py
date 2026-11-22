#!/usr/bin/env python3
"""Compare one disposable synthetic database before and after an APK replacement."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from instrumentation_events import run_events

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "litert"))
from run_bundled import sha_file

PACKAGE = "br.com.exames.offline.validation.workbench"
TEST = PACKAGE + ".test"
RUNNER = TEST + "/androidx.test.runner.AndroidJUnitRunner"
REOPEN = ("br.com.exames.app.WorkbenchReopenTest"
          "#privateHistoryReopensAfterProcessRestartAndDeletionRemovesItsSource")
FINGERPRINT = "br.com.exames.app.PrototypeUpgradeTest#fingerprintSyntheticReviewHistory"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("adb", "serial"):
        parser.add_argument("--" + argument, required=True)
    for argument in ("before-apk", "before-test", "after-apk", "after-test", "report"):
        parser.add_argument("--" + argument, type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        parser.error("REPORT_EXISTS")
    adb = [args.adb, "-s", args.serial]
    aapt = Path(args.adb).resolve().parent.parent / "build-tools/36.0.0/aapt"

    def device(*command, timeout=240):
        return subprocess.run(adb + list(command), check=True, timeout=timeout,
                              capture_output=True, text=True).stdout.strip()

    for apk, package in ((args.before_apk, PACKAGE), (args.before_test, TEST),
                         (args.after_apk, PACKAGE), (args.after_test, TEST)):
        metadata = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True,
                                capture_output=True, text=True, timeout=45)
        if not metadata.stdout.startswith(f"package: name='{package}'"):
            parser.error("DISPOSABLE_PACKAGE_REQUIRED")
    for package in (PACKAGE, TEST):
        if f"package:{package}" in device("shell", "pm", "list", "packages", package).splitlines():
            parser.error("EXISTING_PACKAGE_PRESERVED")

    model = device("shell", "getprop", "ro.product.model")
    api = device("shell", "getprop", "ro.build.version.sdk")
    airplane = device("shell", "settings", "get", "global", "airplane_mode_on")
    wifi = device("shell", "settings", "get", "global", "wifi_on")
    if model == "SM-S931B" and (airplane != "1" or wifi not in ("0", "3")):
        parser.error("S25_OFFLINE_REQUIRED")

    report = {"schemaVersion": 1, "date": datetime.now(timezone.utc).isoformat(),
              "device": {"model": model, "api": int(api), "airplane": airplane, "wifi": wifi},
              "applicationId": PACKAGE, "beforeApkSha256": sha_file(args.before_apk),
              "afterApkSha256": sha_file(args.after_apk), "events": [], "passed": False}
    stage = "seed"
    result = {}

    def instrument(test, *options):
        return run_events(adb, ["-e", "class", test, *options, RUNNER],
                          "prototype", report["events"].append, timeout=180)

    try:
        device("install", "-t", str(args.before_apk))
        device("install", "-t", str(args.before_test))
        result = instrument(REOPEN, "-e", "workbenchPhase", "prepare")
        if not result["runnerPassed"]:
            raise RuntimeError("PREPARE_FAILED")
        stage = "before"
        device("install", "-r", "-t", str(args.after_test))
        result = instrument(FINGERPRINT)
        if not result["runnerPassed"]:
            raise RuntimeError("BEFORE_FINGERPRINT_FAILED")
        stage = "update"
        device("shell", "am", "force-stop", PACKAGE)
        device("install", "-r", "-t", str(args.after_apk))
        result = instrument(FINGERPRINT)
        if not result["runnerPassed"]:
            raise RuntimeError("AFTER_FINGERPRINT_FAILED")
        before, after = report["events"]
        report["dataPreserved"] = before["fingerprint"] == after["fingerprint"] and before["pid"] != after["pid"]
        if not report["dataPreserved"]:
            raise RuntimeError("UPDATE_CHANGED_DATA")
        stage = "review"
        result = instrument(REOPEN, "-e", "workbenchPhase", "resume")
        report["reviewReopenAndDeletePassed"] = result["runnerPassed"]
        report["passed"] = result["runnerPassed"]
    except (RuntimeError, subprocess.SubprocessError, ValueError) as error:
        report.update(failureStage=stage, failureType=type(error).__name__)
        if isinstance(error, subprocess.CalledProcessError):
            import re
            reason = re.search(r"INSTALL_FAILED_[A-Z_]+", (error.stdout or "") + (error.stderr or ""))
            if reason:
                report["installFailureCode"] = reason.group()
    finally:
        if not report["passed"] and "result" in locals():
            report.update({key: result[key] for key in ("failureType", "firstTestFrame") if key in result})
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
