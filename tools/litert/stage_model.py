#!/usr/bin/env python3
"""Stage the one selected, pinned offline model into ignored build assets. Never downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def stage(model, output):
    selected = json.loads((ROOT / 'validation/models/selection.json').read_text())
    lock_path = ROOT / 'validation/models' / selected['modelLock']
    lock = json.loads(lock_path.read_text())
    if (selected['modelId'] != 'gemma4' or lock['sha256'] != selected['modelSha256'] or
            lock['revision'] != selected['modelRevision'] or selected['backend'] != 'GPU' or lock['backend'] != 'gpu'):
        raise ValueError('SELECTION_MISMATCH')
    if model.stat().st_size != lock['sizeBytes'] or digest(model) != lock['sha256']:
        raise ValueError('MODEL_SIZE_OR_HASH')
    license_file = lock_path.parent / lock['license']['file']
    if digest(license_file) != lock['license']['sha256']:
        raise ValueError('LICENSE_HASH')
    output.mkdir(parents=True, exist_ok=True)
    target = output / 'gemma4.litertlm'
    temporary = output / '.gemma4.partial'
    try:
        shutil.copyfile(model, temporary)
        if temporary.stat().st_size != lock['sizeBytes'] or digest(temporary) != lock['sha256']:
            raise ValueError('STAGED_MODEL_HASH')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    shutil.copyfile(license_file, output / 'LICENSE')
    (output / 'manifest.json').write_text(json.dumps({
        'schemaVersion': 'bundled-model/1', 'selection': selected, 'artifact': lock,
        'asset': 'models/gemma4.litertlm', 'privateFile': 'gemma4.litertlm',
    }, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    arguments = parser.parse_args()
    stage(arguments.model, arguments.output)
    print('Pinned model, size/hash and license staged; no network used.')
