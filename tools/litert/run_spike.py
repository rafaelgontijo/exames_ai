#!/usr/bin/env python3
"""Opt-in S25 synthetic spike; writes aggregate metrics only, never model text.

Build :inference-local:assembleDebugAndroidTest first. This harness installs
only the disposable library test APK, streams verified weights into its private
no_backup directory and uses process termination for a native phase deadline.
Manual model provisioning is specific to this experiment, not app distribution.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "br.com.exames.inferenceexperimental.test"


def model_lock_path(model_id, backend):
    choices = {
        ("lfm", "CPU"): "lfm2.5-1.2b-thinking/model.lock.json",
        ("lfm", "GPU"): "lfm2.5-1.2b-thinking/gpu-spike.lock.json",
        ("qwen3", "CPU"): "qwen3-0.6b/model.lock.json",
        ("qwen3", "GPU"): "qwen3-0.6b/model.lock.json",
        ("gemma4", "GPU"): "gemma4-e2b/model.lock.json",
    }
    return ROOT / "validation/models" / choices[(model_id, backend)]


def next_deadline(current, event, now, phase_timeout):
    # Heartbeats and native submission MUST NOT extend the active phase budget.
    if event.get("phase") in {"load", "conversation", "generate", "result", "failure"}:
        return now + phase_timeout
    return current


def experiment_passed(runner_success, timed_out, crashed, instrumentation_code, events):
    results = [event for event in events if event.get("phase") == "result"]
    expected_keys = {"analito", "valor", "unidade", "referencia", "evidencia"}
    return (runner_success and not timed_out and not crashed and instrumentation_code == -1
            and len(results) == 2 and {event.get("case") for event in results} == {0, 1}
            and all(event.get("validEvidenceJson") is True
                    and set(event.get("fieldMatches", {})) == expected_keys
                    and all(value is True for value in event["fieldMatches"].values())
                    for event in results))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--model-id", choices=("lfm", "qwen3", "gemma4"), default="lfm")
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--phase-timeout", type=int, default=120)
    parser.add_argument("--structured-json", action="store_true")
    parser.add_argument("--foreground", action="store_true")
    parser.add_argument("--english-prompt", action="store_true")
    parser.add_argument("--backend", choices=("CPU", "GPU"), default="CPU")
    parser.add_argument("--float32", action="store_true")
    parser.add_argument("--qwen-no-thinking", action="store_true")
    parser.add_argument("--synchronous", action="store_true")
    parser.add_argument("--context-tokens", type=int, default=4096)
    parser.add_argument("--output-tokens", type=int, default=2048)
    parser.add_argument("--reuse-device-model", action="store_true",
                        help="Skip transfer; the test still verifies the private model's full hash")
    parser.add_argument("--production-adapter", action="store_true",
                        help="Exercise the production adapter with the existing two synthetic controls")
    parser.add_argument("--proposal-protocol", action="store_true",
                        help="Exercise versioned prompt/schema and the independent evidence validator")
    args = parser.parse_args()
    if (args.production_adapter or args.proposal_protocol) and (args.model_id != "gemma4" or args.backend != "GPU" or
            not args.synchronous or not args.english_prompt or args.structured_json or
            args.context_tokens != 2048 or args.output_tokens != 1024):
        raise SystemExit("PRODUCTION_ADAPTER_CONFIG: Gemma GPU, synchronous, English control, 2048/1024, no native JSON schema")
    if args.qwen_no_thinking and args.model_id != "qwen3":
        raise SystemExit("QWEN_ONLY_SETTING (never apply Qwen thinking control to LFM)")
    if args.report.exists():
        raise SystemExit("REPORT_EXISTS (choose a new path to preserve prior evidence)")
    if not 1 <= args.phase_timeout <= 600:
        raise SystemExit("PHASE_TIMEOUT_RANGE (1..600 seconds)")
    if not 0 < args.output_tokens <= args.context_tokens - 512 or args.context_tokens > 4096:
        raise SystemExit("TOKEN_BUDGET (reserve at least 512 context tokens for the fixed test input/template)")
    try:
        lock = json.loads(model_lock_path(args.model_id, args.backend).read_text())
    except KeyError:
        raise SystemExit("UNSUPPORTED_MODEL_BACKEND (Gemma's downloaded GPU artifact is tested only on GPU)") from None
    if args.model.stat().st_size != lock["sizeBytes"]:
        raise SystemExit("MODEL_SIZE")
    digest = hashlib.sha256()
    with args.model.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != lock["sha256"]:
        raise SystemExit("MODEL_HASH")
    adb = [args.adb, "-s", args.serial]

    def run(*parts):
        # Never invoke a host shell; all remote shell strings below are constants.
        return subprocess.run(adb + list(parts), check=True, capture_output=True, text=True, timeout=120).stdout.strip()

    apk = ROOT / "inference-local/build/outputs/apk/androidTest/debug/inference-local-debug-androidTest.apk"
    apk_sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    try:
        run("install", "-r", "-t", str(apk))
    except subprocess.CalledProcessError as error:
        # Package manager output has no document/model content; expose the actionable install reason.
        raise SystemExit("INSTALL_FAILED: " + (error.stdout + error.stderr).strip()) from None
    except subprocess.TimeoutExpired:
        raise SystemExit("INSTALL_TIMEOUT (120 seconds)") from None
    run("shell", "run-as", PACKAGE, "mkdir", "-p", "no_backup/models")
    if args.reuse_device_model:
        try:
            observed_size = run("shell", "run-as", PACKAGE, "stat", "-c", "%s",
                                "no_backup/models/" + args.model_id + ".litertlm")
        except subprocess.CalledProcessError:
            raise SystemExit("PRIVATE_TEST_MODEL_MISSING: rerun without --reuse-device-model") from None
        if observed_size != str(lock["sizeBytes"]):
            raise SystemExit("PRIVATE_TEST_MODEL_SIZE: rerun without --reuse-device-model")
    if not args.reuse_device_model:
        # Only fixed enum-selected paths are sent to the remote shell.
        remote_command = {"lfm": "'cat > no_backup/models/lfm.litertlm'",
                          "qwen3": "'cat > no_backup/models/qwen3.litertlm'",
                          "gemma4": "'cat > no_backup/models/gemma4.litertlm'"}[args.model_id]
        with args.model.open("rb") as source:
            subprocess.run(adb + ["shell", "-T", "run-as", PACKAGE, "sh", "-c",
                                  remote_command],
                           stdin=source, check=True, capture_output=True, timeout=180)
    device = {
        key: run("shell", "getprop", prop) for key, prop in {
            "model": "ro.product.model", "android": "ro.build.version.release",
            "api": "ro.build.version.sdk", "abi": "ro.product.cpu.abilist",
        }.items()
    }
    device["pageSizeBytes"] = run("shell", "getconf", "PAGE_SIZE")
    device["memTotal"] = run("shell", "head", "-1", "/proc/meminfo")
    events = []
    success = False
    timed_out = False
    instrumentation_code = None
    process_crashed = False
    test_failure_type = None
    started = time.monotonic()
    deadline = started + args.phase_timeout
    process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r",
        "-e", "class", ("br.com.exames.inferenceexperimental.ProposalProtocolDeviceTest"
                         if args.proposal_protocol else "br.com.exames.inferenceexperimental.ProductionAdapterTest#freshProductionAdapterEnginesCopyTwoDifferentSyntheticRows"
                         if args.production_adapter else "br.com.exames.inferenceexperimental.LocalModelDeviceSpikeTest"),
        "-e", "productionAdapter", str(args.production_adapter).lower(),
        "-e", "proposalProtocol", str(args.proposal_protocol).lower(),
        "-e", "lfmSpike", "true", "-e", "structuredJson", str(args.structured_json).lower(),
        "-e", "foreground", str(args.foreground).lower(),
        "-e", "englishPrompt", str(args.english_prompt).lower(),
        "-e", "backend", args.backend,
        "-e", "modelId", args.model_id,
        "-e", "qwenNoThinking", str(args.qwen_no_thinking).lower(),
        "-e", "synchronous", str(args.synchronous).lower(),
        "-e", "float32", str(args.float32).lower(),
        "-e", "contextTokens", str(args.context_tokens), "-e", "outputTokens", str(args.output_tokens),
        PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = queue.Queue()

    def read_lines():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_lines, daemon=True).start()
    try:
        while True:
            if time.monotonic() > deadline:
                timed_out = True
                break
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line is None:
                break
            if line.startswith("INSTRUMENTATION_STATUS: lfm_spike="):
                event = json.loads(line.split("lfm_spike=", 1)[1])
                events.append(event)
                print(json.dumps(event), flush=True)
                deadline = next_deadline(deadline, event, time.monotonic(), args.phase_timeout)
            if "OK (1 test)" in line:
                success = True
            if line.startswith("INSTRUMENTATION_CODE:"):
                instrumentation_code = int(line.split(":", 1)[1])
            if line.startswith("INSTRUMENTATION_STATUS: stack="):
                match = re.match(r"INSTRUMENTATION_STATUS: stack=([A-Za-z0-9_.$]+)", line)
                if match:
                    test_failure_type = match.group(1)
            if line.startswith("INSTRUMENTATION_RESULT: shortMsg=") and "Process crashed" in line:
                process_crashed = True
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)
        # Also terminate native execution on host timeout/disconnection; killing adb alone is insufficient.
        run("shell", "am", "force-stop", PACKAGE)
    report = {
        "schemaVersion": 1, "date": time.strftime("%Y-%m-%d"),
        "device": device, "runtime": "0.18.1", "backend": args.backend,
        "threads": 4 if args.backend == "CPU" else None,
        "activationDataType": "FLOAT32" if args.float32 else "runtime-default",
        "modelFile": lock["file"], "testApkSha256": apk_sha256,
        "modelId": args.model_id, "modelRepository": lock["repository"],
        "fixtureVersion": "positioned-biochemistry-control/1" if args.proposal_protocol else "portuguese-row-copy/1",
        "modelRevision": lock["revision"], "modelSha256": lock["sha256"],
        "contextTokens": args.context_tokens, "maxOutputTokens": args.output_tokens,
        "requestedThoughtBudget": 0 if args.qwen_no_thinking else None if args.structured_json or args.model_id == "gemma4" else 300,
        "qwenEnableThinking": False if args.qwen_no_thinking else None,
        "promptVersion": "biochemistry-evidence/1" if args.proposal_protocol else "adapter-control/1" if args.production_adapter else "lfm-device-spike/2-en" if args.english_prompt else "lfm-device-spike/1",
        "schema": "biochemistry-fields/1" if args.proposal_protocol else "five-strings/1",
        "harnessVersion": 14, "productionAdapter": args.production_adapter or args.proposal_protocol,
        "proposalProtocol": args.proposal_protocol,
        "api": "synchronous" if args.synchronous else "async-callback",
        "outputValidator": "strict-full-json-string-object-no-duplicates/1",
        "firstFinalTimingMeaning": "complete response available" if args.synchronous else "first non-empty final-content chunk",
        "thinkingConfigAtSend": args.model_id != "gemma4" and (args.qwen_no_thinking or not args.structured_json),
        "channelPolicy": "bundle-metadata" if args.model_id == "gemma4" else "explicit-think-tags",
        "foregroundRequested": args.foreground,
        "responseFormat": "json-schema-five-strings-length1to128/2" if args.structured_json else None,
        "sampling": {"topK": 1, "topP": 1.0, "temperature": 0.0, "seed": 0,
                     "repetitionPenalty": None},
        "phaseTimeoutSeconds": args.phase_timeout, "timedOut": timed_out,
        "instrumentationCode": instrumentation_code, "processCrashed": process_crashed,
        "testFailureType": test_failure_type,
        "freshEnginePerRequest": True, "elapsedSeconds": round(time.monotonic() - started, 3),
        "memoryMethod": "not sampled in adapter smoke control" if args.production_adapter or args.proposal_protocol else "Debug.getPss every 100 ms; sampled maximum, not guaranteed peak",
        "thoughtPersisted": False, "thoughtTokens": None, "finalTokens": None,
        "passed": experiment_passed(success, timed_out, process_crashed, instrumentation_code, events),
        "events": events,
        "limits": "Plans two synthetic single-row inputs, stops at first failure; no OCR, A/B, safe reset or clinical validation. Manual test model provisioning; not offline APK acceptance.",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print("PASS" if report["passed"] else "FAIL (see aggregate report)")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
