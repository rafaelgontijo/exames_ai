#!/usr/bin/env python3
"""Host-only, synthetic WorkManager recovery control; no clinical data or model weights."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "br.com.exames.datalocal.test"
TEST = "br.com.exames.datalocal.ProcessingWorkerTest#processDeathRecoveryAcrossSeparateInstrumentationInvocations"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        raise SystemExit("REPORT_EXISTS")
    apk = ROOT / "data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk"
    adb = [args.adb, "-s", args.serial]

    def run(*parts):
        return subprocess.run(adb + list(parts), check=True, capture_output=True, text=True, timeout=120).stdout

    existing = "package:" + PACKAGE in run("shell", "pm", "list", "packages", PACKAGE).splitlines()
    report = {"schemaVersion": 1, "date": datetime.now(timezone.utc).isoformat(),
              "testApkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "deviceModel": run("shell", "getprop", "ro.product.model").strip(),
              "api": run("shell", "getprop", "ro.build.version.sdk").strip(),
              "installedBefore": existing, "phases": [], "passed": False,
              "limits": "Two-page synthetic private PDF, baseline only, explicit restart after force-stop; no automatic execution during force-stop, native cancellation, real AI or clinical validation."}
    try:
        run("install", "-r", "-t", str(apk))
        for phase in ("prepare", "resume"):
            if phase == "resume":
                run("shell", "am", "force-stop", PACKAGE)
                report["forceStopIssued"] = True
            output = run("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                         "-e", "recoveryPhase", phase, PACKAGE + "/androidx.test.runner.AndroidJUnitRunner")
            pid = re.search(r"INSTRUMENTATION_STATUS: recoveryPid=(\d+)", output)
            passed = "OK (1 test)" in output and "INSTRUMENTATION_CODE: -1" in output and "FAILURES!!!" not in output
            report["phases"].append({"phase": phase, "passed": passed, "pid": int(pid.group(1)) if pid else None})
            if not passed or pid is None:
                raise RuntimeError("RECOVERY_PHASE_FAILED")
        report["passed"] = report["phases"][0]["pid"] != report["phases"][1]["pid"]
        if not existing and report["passed"]:
            run("uninstall", PACKAGE)
            report["removedCreatedTestPackage"] = True
    except (subprocess.SubprocessError, RuntimeError) as error:
        # Never propagate instrumentation exception bodies. Retain failed synthetic fixture for investigation.
        report["controllerFailure"] = type(error).__name__
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print("PASS" if report["passed"] else "FAIL (see aggregate report)")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
