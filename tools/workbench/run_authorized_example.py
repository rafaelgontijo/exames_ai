#!/usr/bin/env python3
"""Opt-in local example test. The clinical stream never enters the test APK or host logs."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import runpy
import stat
import subprocess

from instrumentation_events import run_events

APP = "br.com.exames.offline.validation.prototype"
TEST_APK = APP + ".test"
FIFO = "files/authorized-example.pipe"
CHECKLIST_FIFO = "files/authorized-checklist.pipe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--protocol", choices=("legacy", "exam"), default="legacy")
    parser.add_argument("--page", type=int, choices=range(1, 7))
    parser.add_argument("--review-on-device", action="store_true")
    parser.add_argument("--compare-private-checklist", action="store_true")
    parser.add_argument("--via-saf", action="store_true")
    parser.add_argument("--cancel-inference", action="store_true")
    parser.add_argument("--diagnose-final-shape", action="store_true")
    parser.add_argument("--mode", choices=("BASELINE", "ASSISTED"), default="BASELINE")
    parser.add_argument("--owner-approved", action="store_true", required=True)
    args = parser.parse_args()
    if args.protocol == "legacy" and (args.page is not None or args.review_on_device or args.compare_private_checklist or args.via_saf or args.diagnose_final_shape or args.cancel_inference):
        parser.error("PAGE_AND_CHECKLIST_REQUIRE_EXAM_PROTOCOL")
    if args.cancel_inference and (not args.via_saf or args.compare_private_checklist or args.review_on_device):
        parser.error("CANCELLATION_REQUIRES_SAF_WITHOUT_COMPARISON")
    if args.via_saf and (args.page is not None or not (args.compare_private_checklist or args.cancel_inference)):
        parser.error("SAF_REQUIRES_FULL_CHECKLIST")
    if args.via_saf and args.diagnose_final_shape:
        parser.error("SHAPE_DIAGNOSTIC_REQUIRES_DIRECT_RUNNER")
    test_apk = ("br.com.exames.offline.validation.extraction.test" if args.protocol == "exam" else TEST_APK)
    if args.report.exists():
        parser.error("REPORT_EXISTS")
    root = Path(__file__).resolve().parents[2]
    folder = root / "examples"
    files = [p for p in folder.iterdir() if p.suffix.lower() == ".pdf" and not p.is_symlink()
             and stat.S_ISREG(p.stat().st_mode)]
    if len(files) != 1 or files[0].stat().st_size not in range(1, 20 * 1024 * 1024 + 1):
        parser.error("SINGLE_BOUNDED_LOCAL_EXAMPLE_REQUIRED")

    checklist_payload = None
    if args.compare_private_checklist:
        reference_file = folder / ".expected-exam.py"
        try:
            if reference_file.is_symlink() or not reference_file.is_file():
                raise ValueError()
            checklist = runpy.run_path(str(reference_file))["reference"](files[0])
            for page in checklist:
                for row in page["records"]:
                    for selection in row["selections"]:
                        selection.pop("content", None)
            checklist_payload = json.dumps(checklist, ensure_ascii=False).encode()
            if not 1 <= len(checklist_payload) <= 256 * 1024:
                raise ValueError()
        except Exception:
            parser.error("PRIVATE_CHECKLIST_UNAVAILABLE")

    adb = [args.adb, "-s", args.serial]

    def device(*command):
        return subprocess.run(adb + list(command), timeout=20, check=True, capture_output=True, text=True).stdout.strip()

    if device("shell", "getprop", "ro.product.model") != "SM-S931B":
        parser.error("REFERENCE_DEVICE_REQUIRED")
    if (device("shell", "settings", "get", "global", "airplane_mode_on") != "1" or
            device("shell", "settings", "get", "global", "wifi_on") != "0"):
        parser.error("DEVICE_MUST_BE_OFFLINE")
    device("shell", "run-as", test_apk, "mkdir", "-p", "files")
    pipes = [FIFO, CHECKLIST_FIFO] if checklist_payload is not None else [FIFO]
    writers = {}
    created = []
    report = {"date": datetime.now(timezone.utc).isoformat(), "device": "SM-S931B",
              "protocol": args.protocol, "mode": args.mode if args.protocol == "legacy" else "ASSISTED",
              "page": args.page, "viaSaf": args.via_saf, "airplane": True, "wifi": False, "events": [],
              "clinicalValidation": False}

    def event(payload):
        report["events"].append(payload)
        phase = payload.get("phase")
        if phase == "authorized-stream-ready" and FIFO not in writers:
            with files[0].open("rb") as fd:
                writers[FIFO] = subprocess.Popen(adb + ["shell", "-T", f"run-as {test_apk} sh -c 'cat > {FIFO}'"],
                                                stdin=fd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if phase == "authorized-picker-awaiting-selection":
            print("No S25, selecione Ensaio do laudo > Laudo autorizado.pdf.", flush=True)
        if phase == "authorized-checklist-ready" and checklist_payload is not None and CHECKLIST_FIFO not in writers:
            # Only now has inference returned. The checklist never enters the model request.
            writers[CHECKLIST_FIFO] = subprocess.Popen(
                adb + ["shell", "-T", f"run-as {test_apk} sh -c 'cat > {CHECKLIST_FIFO}'"],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            writers[CHECKLIST_FIFO].communicate(checklist_payload, timeout=20)

    try:
        for pipe in pipes:
            device("shell", "run-as", test_apk, "/system/bin/toybox", "mkfifo", "-m", "600", pipe)
            created.append(pipe)
        if args.protocol == "exam":
            test = "br.com.exames.app.PrivateExamAcceptanceTest#extractAuthorizedPdfOffline"
            event_key = "examExtraction"
            extra = ["-e", "ownerPage", str(args.page)] if args.page else []
            if args.review_on_device:
                extra += ["-e", "ownerReview", "true"]
            if args.compare_private_checklist:
                extra += ["-e", "compareChecklist", "true"]
            if args.via_saf:
                extra += ["-e", "viaPicker", "true"]
            if args.cancel_inference:
                extra += ["-e", "cancelInference", "true"]
            if args.diagnose_final_shape:
                extra += ["-e", "ownerDiagnostics", "true"]
        else:
            test = "br.com.exames.app.PrototypeAcceptanceTest#importExplicitlyAuthorizedOwnerPdf"
            event_key = "prototype"
            extra = ["-e", "ownerMode", args.mode]
        result = run_events(adb, ["-e", "class", test, "-e", "ownerApproved", "true", *extra,
            test_apk + "/androidx.test.runner.AndroidJUnitRunner"], event_key, event, timeout=1800 if args.protocol == "exam" else 780)
        report.update(result)
        report["streamDelivered"] = FIFO in writers and writers[FIFO].wait(timeout=10) == 0
        if args.cancel_inference:
            report["cancellationMeasured"] = any(
                e.get("phase") == "authorized-cancellation-measured" for e in report["events"]
            )
            report["completed"] = report["runnerPassed"] and report["streamDelivered"] and report["cancellationMeasured"]
        elif args.protocol == "exam":
            measured = next((e for e in report["events"] if e.get("phase") == "authorized-extraction-measured"), {})
            report["completed"] = report["runnerPassed"] and report["streamDelivered"] and bool(measured)
            report["publishedWithEvidence"] = sum(p["observations"] for p in measured.get("pages", []))
            if checklist_payload is not None:
                report["checklistCompared"] = (
                    CHECKLIST_FIFO in writers and writers[CHECKLIST_FIFO].returncode == 0 and any(
                        e.get("phase") == "authorized-comparison-measured" for e in report["events"]
                    )
                )
                report["completed"] = report["completed"] and report["checklistCompared"]
            if args.via_saf:
                report["pickerControlsMeasured"] = any(
                    e.get("phase") == "authorized-picker-controls" for e in report["events"]
                )
                report["completed"] = report["completed"] and report["pickerControlsMeasured"]
            if report.get("checklistCompared"):
                comparison = next(e for e in report["events"] if e["phase"] == "authorized-comparison-measured")
                report["checklistExact"] = bool(comparison["pages"]) and all(
                    page["exact"] == page["expected"]
                    and not any(page[key] for key in ("omissions", "extras", "measurementDifferences", "referenceDifferences"))
                    and page["expectedAmbiguities"] == page["preservedAmbiguities"]
                    for page in comparison["pages"]
                )
            report["visualComparisonPending"] = True
        else:
            report["passed"] = (report["runnerPassed"] and report["streamDelivered"] and any(
                e.get("phase") == "authorized-import-processed" and e.get("run") != "IMPORT_UNAVAILABLE"
                for e in report["events"]))
    except (TimeoutError, subprocess.SubprocessError) as error:
        report["controllerFailure"] = type(error).__name__
    finally:
        for writer in writers.values():
            if writer.poll() is None:
                writer.kill()
                writer.wait(timeout=10)
        for pipe in created:
            subprocess.run(adb + ["shell", "run-as", test_apk, "sh", "-c",
                                  f"'if test -p {pipe}; then rm {pipe}; fi'"],
                           timeout=10, capture_output=True, check=False)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 0 if report.get("completed", report.get("passed")) else 1


if __name__ == "__main__":
    raise SystemExit(main())
