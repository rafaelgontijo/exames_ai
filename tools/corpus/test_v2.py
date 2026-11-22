import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

from generate import ROOT, LOCK, generate as generate_v1, json_bytes
from generate_v2 import generate, checked_source
from validate import validate_package


class GroundTruthV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        font = Path(os.environ.get('EXAMES_CORPUS_FONT', LOCK['fontDefault']))
        cls.old = cls.root / 'v1'; cls.new = cls.root / 'v2'
        cls.before = generate_v1(cls.old, font)
        cls.after = generate(cls.new, font)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_documents_hashes_families_and_partitions_are_unchanged(self):
        self.assertEqual(216, len(self.after['cases']))
        for old, new in zip(self.before['cases'], self.after['cases']):
            for key in ('caseId', 'familyId', 'partition', 'documentPath', 'documentSha256', 'documentBytes', 'format', 'transformations'):
                self.assertEqual(old[key], new[key])
            self.assertEqual((self.old / old['documentPath']).read_bytes(), (self.new / new['documentPath']).read_bytes())
            self.assertNotEqual(old['groundTruthSha256'], new['groundTruthSha256'])

    def test_both_versions_validate_and_every_v2_observation_has_input(self):
        self.assertEqual(216, validate_package(self.old / 'corpus.zip')['reviewedCases'])
        result = validate_package(self.new / 'corpus.zip')
        self.assertEqual(216, result['reviewedCases'])
        self.assertTrue(all(truth['version'] == '2' and all(obs['evaluationInput'] for obs in truth['observations'])
                            for truth in result['groundTruth'].values()))

    def test_only_approved_reasons_change_and_states_values_remain(self):
        changed = set()
        from generate import stable_id
        allowed = {stable_id('family', key) for key in ('unknown-context', 'competing-reference')}
        for old, new in zip(self.before['cases'], self.after['cases']):
            a = json.loads((self.old / old['groundTruthPath']).read_text())
            b = json.loads((self.new / new['groundTruthPath']).read_text())
            for x, y in zip(a['observations'], b['observations']):
                for key in ('observationId', 'analyteRaw', 'resultRaw', 'unitRaw', 'referenceRaw', 'numericValue', 'low', 'high', 'expectedStatus'):
                    self.assertEqual(x[key], y[key])
                if x['expectedReason'] != y['expectedReason']:
                    changed.add(new['familyId']); self.assertEqual('AMBIGUOUS_INPUT', y['expectedReason'])
        self.assertEqual(allowed, changed)

    def test_jvm_fixtures_equal_the_generated_declarations(self):
        values = [json.loads((self.new / case['groundTruthPath']).read_text()) for case in self.after['cases']
                  if case['layoutId'] == 'simple-v1' and case['format'] == 'PDF_TEXT']
        resource = ROOT / 'domain/src/test/resources/validation/truth-v2-scenarios.json'
        self.assertEqual(values, json.loads(resource.read_text()))

    def test_invalid_selection_is_rejected_after_integrity_recalculation(self):
        manifest = copy.deepcopy(self.after); manifest['cases'] = [manifest['cases'][0]]
        case = manifest['cases'][0]
        truth = json.loads((self.new / case['groundTruthPath']).read_text())
        truth['observations'][0]['evaluationInput']['selectedReference'] = None
        data = json_bytes(truth); case['groundTruthSha256'] = hashlib.sha256(data).hexdigest()
        target = self.root / 'invalid.zip'
        with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('manifest.json', json_bytes(manifest))
            archive.writestr(case['documentPath'], (self.new / case['documentPath']).read_bytes())
            archive.writestr(case['groundTruthPath'], data)
        with self.assertRaisesRegex(ValueError, 'canonical reference selection'):
            validate_package(target)

    def test_source_and_v2_toolchain_do_not_change_rendering_contract(self):
        source = checked_source()
        original = json.loads((ROOT / 'validation/corpus/cases.json').read_text())
        self.assertEqual(original['analytes'], source['analytes'])
        self.assertEqual(original['layouts'], source['layouts'])
        self.assertEqual('synthetic-2.0', source['corpusVersion'])


if __name__ == '__main__':
    unittest.main()
