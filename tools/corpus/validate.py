#!/usr/bin/env python3
"""Bounded, read-only validation of a local corpus ZIP. Never extracts paths to disk."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
MAX_ARCHIVE = 128 * 1024 * 1024
MAX_MEMBER = 20 * 1024 * 1024
MAX_JSON = 2 * 1024 * 1024
MAX_ENTRIES = 2500


def safe_path(name: str) -> str:
    path = PurePosixPath(name)
    if (not name or len(name) > 200 or not re.fullmatch(r"[A-Za-z0-9_./-]+", name)
            or path.is_absolute() or any(x in (".", "..") for x in name.split("/"))
            or str(path) != name or name.endswith("/")):
        raise ValueError("Unsafe or noncanonical package path")
    return name


def _object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("Duplicate JSON key")
        obj[key] = value
    return obj


def _finite(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Nonfinite JSON number")
    if isinstance(value, dict):
        for child in value.values():
            _finite(child)
    if isinstance(value, list):
        for child in value:
            _finite(child)


def parse_json(data: bytes):
    if len(data) > MAX_JSON:
        raise ValueError("JSON exceeds package limit")
    try:
        result = json.loads(data, object_pairs_hook=_object,
                            parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid JSON constant")))
        _finite(result)
        return result
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValueError("Invalid package JSON") from error


def _schema(name: str):
    schema = json.loads((ROOT / f"validation/corpus/schema/{name}.schema.json").read_text())
    return Draft202012Validator(schema, format_checker=FormatChecker())


def _validate_schema(validator, value):
    # Do not include clinical values in error messages.
    if next(validator.iter_errors(value), None) is not None:
        raise ValueError("Package does not match declared schema")


def validate_package(path: Path) -> dict:
    if path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Archive exceeds limit")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if not 1 <= len(entries) <= MAX_ENTRIES or len(names) != len(set(names)):
            raise ValueError("Invalid or duplicate archive entries")
        total = 0
        for entry in entries:
            safe_path(entry.filename)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode) or entry.flag_bits & 1:
                raise ValueError("Symlink or encrypted ZIP entry")
            if entry.file_size > MAX_MEMBER:
                raise ValueError("Package member exceeds limit")
            if entry.file_size > max(1, entry.compress_size) * 1000:
                raise ValueError("Suspicious compression ratio")
            total += entry.file_size
        if total > MAX_ARCHIVE or "manifest.json" not in names:
            raise ValueError("Expanded archive too large or missing manifest")

        def read(name: str, limit: int = MAX_MEMBER):
            safe_path(name)
            if name not in names:
                raise ValueError("Missing package member")
            with archive.open(name) as stream:
                data = stream.read(limit + 1)
            if len(data) > limit:
                raise ValueError("Package member exceeds read limit")
            return data

        manifest = parse_json(read("manifest.json", MAX_JSON))
        _validate_schema(_schema("manifest"), manifest)
        truth_schema = _schema("ground-truth")
        ids, families, truth_versions = set(), {}, {}
        referenced = {"manifest.json"}
        reviewed = 0
        for case in manifest["cases"]:
            case_id = case["caseId"]
            if case_id in ids:
                raise ValueError("Duplicate case ID")
            ids.add(case_id)
            family = case["familyId"]
            if family in families and families[family] != case["partition"]:
                raise ValueError("Family crosses evaluation partitions")
            families[family] = case["partition"]
            provenance = case["provenance"]
            if case["origin"] == "PUBLIC_DEMONSTRATION":
                if (not provenance or not provenance["url"].startswith("https://")
                        or not provenance["eligibilityReviewed"] or provenance["containsIdentifiableData"]):
                    raise ValueError("Public example is not eligible for shared corpus")
            elif provenance is not None:
                raise ValueError("Synthetic example has public provenance")
            for key in ("documentPath", "groundTruthPath"):
                name = safe_path(case[key])
                if name in referenced:
                    raise ValueError("Package reuses a case artifact path")
                referenced.add(name)
            document = read(case["documentPath"])
            if len(document) != case["documentBytes"] or hashlib.sha256(document).hexdigest() != case["documentSha256"]:
                raise ValueError("Document size or hash mismatch")
            prefix = {"PDF_TEXT": b"%PDF-", "PDF_SCANNED": b"%PDF-", "PNG": b"\x89PNG\r\n\x1a\n", "JPEG": b"\xff\xd8\xff"}[case["format"]]
            if not document.startswith(prefix):
                raise ValueError("Document signature mismatch")
            truth_data = read(case["groundTruthPath"], MAX_JSON)
            if hashlib.sha256(truth_data).hexdigest() != case["groundTruthSha256"]:
                raise ValueError("Ground truth hash mismatch")
            truth = parse_json(truth_data)
            _validate_schema(truth_schema, truth)
            if truth["caseId"] != case_id:
                raise ValueError("Ground truth belongs to another case")
            if truth["reviewState"] == "REVIEWED":
                if not truth["reviewerId"] or not truth["reviewedAt"]:
                    raise ValueError("Reviewed ground truth lacks audit fields")
                reviewed += 1
            observations = truth["observations"]
            if len({obs["observationId"] for obs in observations}) != len(observations):
                raise ValueError("Duplicate expected observation")
            for obs in observations:
                for evidence in obs["evidence"]:
                    r = evidence["region"]
                    if not (0 <= r["left"] < r["right"] <= r["pageWidth"]
                            and 0 <= r["top"] < r["bottom"] <= r["pageHeight"]):
                        raise ValueError("Evidence region outside page")
            truth_versions[case_id] = truth
        if set(names) != referenced:
            raise ValueError("Unreferenced package files")
    return dict(manifest=manifest, groundTruth=truth_versions, reviewedCases=reviewed,
                exploratoryCases=len(ids) - reviewed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    result = validate_package(parser.parse_args().package)
    print(json.dumps({"cases":len(result["manifest"]["cases"]),
                      "reviewedCases":result["reviewedCases"], "exploratoryCases":result["exploratoryCases"]}))
