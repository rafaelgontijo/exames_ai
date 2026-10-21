from __future__ import annotations

import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from PIL import Image
from pypdf import PdfReader

from generate import ROOT, generate, json_bytes, stable_id
from validate import parse_json, safe_path, validate_package


class CorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.output = cls.root / "first"
        cls.manifest = generate(cls.output)
        case = cls.manifest["cases"][0]
        cls.minimal_manifest = copy.deepcopy(cls.manifest)
        cls.minimal_manifest["cases"] = [case]
        cls.minimal_files = {name:(cls.output/name).read_bytes()
                             for name in (case["documentPath"], case["groundTruthPath"])}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def make_package(self, manifest=None, files=None, extra=None):
        manifest = copy.deepcopy(manifest if manifest is not None else self.minimal_manifest)
        files = dict(files if files is not None else self.minimal_files)
        data = {"manifest.json":json_bytes(manifest), **files, **(extra or {})}
        path = self.root / "test.zip"
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in data.items():
                archive.writestr(name, content)
        return path

    def test_fixture_matrix_has_explicit_independent_answers(self):
        source = json.loads((ROOT / "validation/corpus/cases.json").read_text())
        scenarios = {s["id"]:s for s in source["scenarios"]}
        self.assertEqual(len(scenarios), 18)
        self.assertEqual(len(source["analytes"]), 5)
        self.assertEqual(set(source["layouts"]), {"simple-v1", "footnote-v1"})
        self.assertEqual(scenarios["inclusive-low"]["expectedStatus"], "WITHIN_RANGE")
        self.assertEqual(scenarios["exclusive-high"]["expectedStatus"], "ABOVE_RANGE")
        self.assertEqual(scenarios["unit-mismatch"]["expectedReason"], "UNIT_UNRESOLVED")
        self.assertEqual(scenarios["missing-reference"]["expectedStatus"], "NOT_CLASSIFIABLE")
        self.assertEqual(scenarios["unknown-context"]["expectedStatus"], "NEEDS_REVIEW")
        self.assertEqual(scenarios["unreadable"]["resultRaw"], "")

    def test_generated_package_has_all_formats_and_no_partition_leak(self):
        result = validate_package(self.output / "corpus.zip")
        self.assertEqual(len(result["manifest"]["cases"]), 216)
        self.assertEqual(result["reviewedCases"], 216)
        self.assertEqual(result["exploratoryCases"], 0)
        self.assertEqual({c["format"] for c in self.manifest["cases"]}, {"PDF_TEXT","PDF_SCANNED","PNG","JPEG"})
        partitions = {}
        for case in self.manifest["cases"]:
            partitions.setdefault(case["familyId"], set()).add(case["partition"])
        self.assertEqual(len(partitions), 18)
        self.assertTrue(all(len(values) == 1 for values in partitions.values()))

    def test_pdf_text_and_scan_are_genuinely_different(self):
        text = next(c for c in self.manifest["cases"] if c["format"] == "PDF_TEXT")
        scan = next(c for c in self.manifest["cases"] if c["format"] == "PDF_SCANNED")
        self.assertIn("Glicose", PdfReader(self.output/text["documentPath"]).pages[0].extract_text())
        self.assertEqual(PdfReader(self.output/scan["documentPath"]).pages[0].extract_text().strip(), "")

    def test_rotated_image_and_evidence_use_output_coordinates(self):
        case = next(c for c in self.manifest["cases"] if "ROTATE_CCW_90" in c["transformations"])
        with Image.open(self.output/case["documentPath"]) as image:
            self.assertEqual(image.size, (1600,1200))
        truth = json.loads((self.output/case["groundTruthPath"]).read_text())
        for observation in truth["observations"]:
            for evidence in observation["evidence"]:
                region = evidence["region"]
                self.assertEqual((region["pageWidth"],region["pageHeight"],region["rotationDegrees"]), (1600,1200,90))
                self.assertLess(region["left"],region["right"])
                self.assertLessEqual(region["bottom"],1200)

    def test_regeneration_is_byte_identical(self):
        second = self.root / "second"
        other = generate(second)
        self.assertEqual(self.manifest,other)
        self.assertEqual(hashlib.sha256((self.output/"corpus.zip").read_bytes()).hexdigest(),
                         hashlib.sha256((second/"corpus.zip").read_bytes()).hexdigest())

    def test_generator_does_not_overwrite_existing_directory(self):
        with self.assertRaises(ValueError):
            generate(self.output)

    def test_paths_reject_traversal_absolute_alias_and_windows(self):
        for name in ("../escape", "/tmp/a", "a/../b", "a//b", "a/./b", "C:/a", "a\\b", "", "a/"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                safe_path(name)
        self.assertEqual(safe_path("documents/a.pdf"),"documents/a.pdf")

    def test_hash_tampering_is_rejected(self):
        files = dict(self.minimal_files)
        files[next(iter(files))] += b"tampered"
        with self.assertRaises(ValueError):
            validate_package(self.make_package(files=files))

    def test_unknown_schema_and_extra_fields_are_rejected(self):
        for key,value in (("schemaVersion","2.0"),("unexpected",True)):
            manifest = copy.deepcopy(self.minimal_manifest)
            manifest[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_package(self.make_package(manifest=manifest))

    def test_duplicate_case_ids_and_cross_partition_family_are_rejected(self):
        manifest = copy.deepcopy(self.minimal_manifest)
        manifest["cases"].append(copy.deepcopy(manifest["cases"][0]))
        with self.assertRaises(ValueError):
            validate_package(self.make_package(manifest=manifest))
        manifest["cases"][1]["caseId"] = "d"*64
        manifest["cases"][1]["partition"] = "HELD_OUT" if manifest["cases"][0]["partition"] == "DEVELOPMENT" else "DEVELOPMENT"
        with self.assertRaisesRegex(ValueError,"Family crosses"):
            validate_package(self.make_package(manifest=manifest))

    def test_symlinks_and_unreferenced_files_are_rejected(self):
        with self.assertRaises(ValueError):
            validate_package(self.make_package(extra={"extra.txt":b"unreferenced"}))
        path = self.make_package()
        with zipfile.ZipFile(path,"a") as archive:
            entry = zipfile.ZipInfo("link")
            entry.external_attr = 0o120777 << 16
            archive.writestr(entry,"../outside")
        with self.assertRaises(ValueError):
            validate_package(path)

    def test_duplicate_json_and_nonfinite_numbers_are_rejected(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":1e999}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_json(raw)

    def test_compression_bomb_is_rejected_before_json_parsing(self):
        path = self.root / "bomb.zip"
        with zipfile.ZipFile(path,"w",compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json",b"0"*(2*1024*1024))
        with self.assertRaisesRegex(ValueError,"compression ratio"):
            validate_package(path)

    def test_unreviewed_public_ground_truth_is_exploratory(self):
        manifest = copy.deepcopy(self.minimal_manifest)
        case = manifest["cases"][0]
        case.update(origin="PUBLIC_DEMONSTRATION",provenance=dict(url="https://example.org/model.pdf",
            obtainedOn="2026-10-21",reuseTerms="Synthetic public-record example only; not an actual acquisition",
            eligibilityReviewed=True,containsIdentifiableData=False))
        files = dict(self.minimal_files)
        truth = json.loads(files[case["groundTruthPath"]])
        truth.update(reviewState="PENDING",reviewerId=None,reviewedAt=None)
        files[case["groundTruthPath"]] = json_bytes(truth)
        case["groundTruthSha256"] = hashlib.sha256(files[case["groundTruthPath"]]).hexdigest()
        result = validate_package(self.make_package(manifest,files))
        self.assertEqual((result["reviewedCases"],result["exploratoryCases"]),(0,1))
        case["provenance"]["eligibilityReviewed"] = False
        with self.assertRaisesRegex(ValueError,"not eligible"):
            validate_package(self.make_package(manifest,files))

    def test_ineligible_public_example_is_rejected(self):
        manifest = copy.deepcopy(self.minimal_manifest)
        manifest["cases"][0]["origin"] = "PUBLIC_DEMONSTRATION"
        with self.assertRaisesRegex(ValueError,"not eligible"):
            validate_package(self.make_package(manifest))

    def test_geometry_is_validated_after_hash_verification(self):
        manifest = copy.deepcopy(self.minimal_manifest)
        case = manifest["cases"][0]
        files = dict(self.minimal_files)
        truth = json.loads(files[case["groundTruthPath"]])
        truth["observations"][0]["evidence"][0]["region"]["right"] = 10000
        files[case["groundTruthPath"]] = json_bytes(truth)
        case["groundTruthSha256"] = hashlib.sha256(files[case["groundTruthPath"]]).hexdigest()
        with self.assertRaisesRegex(ValueError,"outside page"):
            validate_package(self.make_package(manifest,files))


if __name__ == "__main__":
    unittest.main()
