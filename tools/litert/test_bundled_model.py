import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import run_bundled
import stage_model


class BundledModelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
        self.model = self.root / 'source.litertlm'; self.model.write_bytes(b'synthetic weights')
        folder = self.root / 'validation/models/gemma4-e2b'; folder.mkdir(parents=True)
        (folder / 'LICENSE').write_text('synthetic license')
        self.lock = dict(sizeBytes=self.model.stat().st_size, sha256=stage_model.digest(self.model),
                         revision='pinned', backend='gpu',
                         license=dict(file='LICENSE', sha256=stage_model.digest(folder / 'LICENSE')))
        self.selection = dict(modelId='gemma4', modelLock='gemma4-e2b/model.lock.json',
                              modelSha256=self.lock['sha256'], modelRevision='pinned', backend='GPU')
        self.lock_path = folder / 'model.lock.json'
        self.selection_path = folder.parent / 'selection.json'
        self.save()
        self.addCleanup(patch.stopall)
        patch.object(stage_model, 'ROOT', self.root).start()
        patch.object(run_bundled, 'ROOT', self.root).start()

    def save(self):
        self.lock_path.write_text(json.dumps(self.lock))
        self.selection_path.write_text(json.dumps(self.selection))

    def archive(self, compression=zipfile.ZIP_STORED):
        output = self.root / 'staged'; stage_model.stage(self.model, output)
        apk = self.root / 'synthetic.apk'
        with zipfile.ZipFile(apk, 'w', compression=compression) as archive:
            for name in ('gemma4.litertlm', 'LICENSE', 'manifest.json'):
                archive.write(output / name, 'assets/models/' + name)
        return apk

    def test_stages_one_verified_model_license_and_manifest(self):
        apk = self.archive(); run_bundled.verify_apk(apk)
        self.assertEqual({'gemma4.litertlm', 'LICENSE', 'manifest.json'},
                         {p.name for p in (self.root / 'staged').iterdir()})

    def test_rejects_same_size_modified_weights_before_staging(self):
        self.model.write_bytes(b'X' * self.lock['sizeBytes'])
        with self.assertRaisesRegex(ValueError, 'MODEL_SIZE_OR_HASH'):
            stage_model.stage(self.model, self.root / 'staged')
        self.assertFalse((self.root / 'staged').exists())

    def test_rejects_revision_divergence_and_changed_license(self):
        self.selection['modelRevision'] = 'other'; self.save()
        with self.assertRaisesRegex(ValueError, 'SELECTION_MISMATCH'):
            stage_model.stage(self.model, self.root / 'staged')
        self.selection['modelRevision'] = 'pinned'; self.save()
        (self.lock_path.parent / 'LICENSE').write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'LICENSE_HASH'):
            stage_model.stage(self.model, self.root / 'staged')

    def test_apk_rejects_compressed_model(self):
        with self.assertRaisesRegex(ValueError, 'COMPRESSION'):
            run_bundled.verify_apk(self.archive(zipfile.ZIP_DEFLATED))

    def test_apk_rejects_tampered_asset_and_manifest(self):
        apk = self.archive()
        for name, content, reason in (
                ('gemma4.litertlm', b'X' * self.lock['sizeBytes'], 'BUNDLED_MODEL_HASH'),
                ('LICENSE', b'changed', 'BUNDLED_LICENSE_HASH'),
                ('manifest.json', b'{"artifact":{}}', 'BUNDLED_MANIFEST_MISMATCH')):
            broken = self.root / ('broken-' + name + '.apk')
            with zipfile.ZipFile(apk) as original, zipfile.ZipFile(broken, 'w') as result:
                for entry in original.infolist():
                    result.writestr(entry.filename, content if entry.filename.endswith('/' + name) else original.read(entry))
            with self.assertRaisesRegex(ValueError, reason):
                run_bundled.verify_apk(broken)


if __name__ == '__main__':
    unittest.main()
