#!/usr/bin/env python3
"""Emulator-only UI/source recovery control, synthetic fixture, dedicated validation package."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "br.com.exames.offline.validation.workbench"
TEST_PACKAGE = PACKAGE + ".test"
TEST = "br.com.exames.app.WorkbenchReopenTest#privateHistoryReopensAfterProcessRestartAndDeletionRemovesItsSource"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("EMULATOR_REQUIRED_S25_RESERVED_FOR_FINAL_STAGE")
    if args.report.exists():
        raise SystemExit("REPORT_EXISTS")
    app_dir = ROOT / "app/build/outputs/apk/debug"
    test_dir = ROOT / "app/build/outputs/apk/androidTest/debug"
    for directory, package in ((app_dir, PACKAGE), (test_dir, TEST_PACKAGE)):
        if json.loads((directory / "output-metadata.json").read_text())["applicationId"] != package:
            raise SystemExit("BUILD_DEDICATED_VALIDATION_APPLICATION_ID_FIRST")
    app_apk = app_dir / "app-debug.apk"
    test_apk = test_dir / "app-debug-androidTest.apk"
    adb = [args.adb, "-s", args.serial]

    def run(*parts):
        return subprocess.run(adb + list(parts), check=True, capture_output=True, text=True, timeout=180).stdout

    def installed(package):
        return "package:" + package in run("shell", "pm", "list", "packages", package).splitlines()

    before = {PACKAGE: installed(PACKAGE), TEST_PACKAGE: installed(TEST_PACKAGE)}
    report = {"schemaVersion": 1, "date": datetime.now(timezone.utc).isoformat(),
              "applicationId": PACKAGE, "appApkSha256": digest(app_apk), "testApkSha256": digest(test_apk),
              "deviceModel": run("shell", "getprop", "ro.product.model").strip(),
              "api": run("shell", "getprop", "ro.build.version.sdk").strip(),
              "installedBefore": before, "phases": [], "passed": False,
              "scope": "Actual Hilt import callback, encrypted private copy, baseline WorkManager, force-stop reopening, source highlights, UI correction/reassessment/truth revision/version-specific comparison/new baseline extraction, original metrics preservation, deletion and clinical-draft saved-state inspection.",
              "limits": "Single synthetic PDF on API 36 emulator; invokes SAF result callback directly instead of automating DocumentsUI. No real AI, S25, A/B, TalkBack audit or clinical validation."}
    try:
        report["controllerStage"] = "install-target"
        matching_target = False
        if before[PACKAGE]:
            paths = [line.removeprefix("package:") for line in run("shell", "pm", "path", PACKAGE).splitlines()
                     if line.startswith("package:") and line.endswith("/base.apk")]
            if len(paths) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9_~=/+.-]+/base\.apk", paths[0]) and ".." not in paths[0].split("/"):
                matching_target = run("shell", "sha256sum", paths[0]).split()[0] == report["appApkSha256"]
        report["reusedMatchingTargetApk"] = matching_target
        if not matching_target:
            run("install", "-r", "-t", str(app_apk))
        report["controllerStage"] = "install-test"
        run("install", "-r", "-t", str(test_apk))
        report["controllerStage"] = "navigation"
        navigation = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
                         "br.com.exames.app.NavigationTest#initialNavigationHasNoClinicalResults",
                         TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner")
        report["navigationPassed"] = "OK (1 test)" in navigation and "INSTRUMENTATION_CODE: -1" in navigation and "FAILURES!!!" not in navigation
        if not report["navigationPassed"]:
            raise RuntimeError("WORKBENCH_NAVIGATION_FAILED")
        for phase in ("prepare", "resume"):
            report["controllerStage"] = phase
            if phase == "resume":
                run("shell", "am", "force-stop", PACKAGE)
                report["forceStopIssued"] = True
            output = run("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                         "-e", "workbenchPhase", phase, TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner")
            pid = re.search(r"INSTRUMENTATION_STATUS: workbenchPid=(\d+)", output)
            rows = re.search(r"INSTRUMENTATION_STATUS: workbenchRows=(\d+)", output)
            passed = "OK (1 test)" in output and "INSTRUMENTATION_CODE: -1" in output and "FAILURES!!!" not in output
            report["phases"].append({"phase": phase, "passed": passed, "pid": int(pid.group(1)) if pid else None,
                                     "rows": int(rows.group(1)) if rows else None,
                                     "evidenceHighlighted": "INSTRUMENTATION_STATUS: workbenchEvidenceChecked=true" in output,
                                     "reviewChecked": "INSTRUMENTATION_STATUS: workbenchReviewChecked=true" in output,
                                     "deleted": "INSTRUMENTATION_STATUS: workbenchDeleted=true" in output})
            if not passed or pid is None:
                raise RuntimeError("WORKBENCH_PHASE_FAILED")
        report["passed"] = (report["phases"][0]["pid"] != report["phases"][1]["pid"]
                            and report["phases"][1]["rows"] == 5 and report["phases"][1]["deleted"]
                            and report["phases"][1]["evidenceHighlighted"] and report["phases"][1]["reviewChecked"])
        if report["passed"]:
            report["controllerStage"] = "cleanup"
            removed = []
            for package in (TEST_PACKAGE, PACKAGE):
                if not before[package]:
                    run("uninstall", package)
                    removed.append(package)
            report["removedCreatedPackages"] = removed
    except (subprocess.SubprocessError, RuntimeError) as error:
        # Retain failed fixture for investigation; never relay assertion/clinical bodies to host logs.
        report["controllerFailure"] = type(error).__name__
        if isinstance(error, subprocess.CalledProcessError):
            code = re.search(r"INSTALL_FAILED_[A-Z_]+", (error.stdout or "") + (error.stderr or ""))
            if code:
                report["installFailureCode"] = code.group(0)
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print("PASS" if report["passed"] else "FAIL (see aggregate report)")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
