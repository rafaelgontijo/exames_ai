#!/usr/bin/env python3
"""Ground-truth v2: unchanged v1 rendering, independently declared canonical evaluator inputs."""
import argparse
import copy
import json
from pathlib import Path
from generate import ROOT, LOCK, generate as render_v1, json_bytes, package, sha


def canonical_metadata(template, observation):
    result = copy.deepcopy(template)
    source = result.pop('unitSource')
    if source == 'ANALYTE_UNIT':
        result['unit'] = observation['unitRaw']
    elif source == 'NONE':
        result['unit'] = None
    elif source != 'LITERAL':
        raise ValueError('UNKNOWN_FIXTURE_UNIT_SOURCE')
    result['observationId'] = observation['observationId']
    return result


def upgrade_truth(truth, scenario):
    result = copy.deepcopy(truth)
    result.update(schemaVersion='1.1', version='2', reviewedAt='2026-10-27T00:00:00Z')
    for observation in result['observations']:
        evaluation = copy.deepcopy(scenario['evaluationInput'])
        if evaluation['selectedReference'] is not None:
            evaluation['selectedReference'] = canonical_metadata(evaluation['selectedReference'], observation)
        for candidate in evaluation['candidates']:
            candidate['metadata'] = canonical_metadata(candidate['metadata'], observation)
            if candidate['metadata']['unit'] is not None:
                candidate['referenceRaw'] += ' ' + candidate['metadata']['unit']
        observation['evaluationInput'] = evaluation
        observation['expectedReason'] = scenario['expectedReason']
        for evidence in observation['evidence']:
            evidence['extractorVersion'] = '2.0'
    return result


def checked_source():
    lock = json.loads((ROOT / 'tools/corpus/toolchain-v2.lock.json').read_text())
    if lock.get('generatorVersion') != '2.0' or {k: v for k, v in lock.items() if k != 'generatorVersion'} != {k: v for k, v in LOCK.items() if k != 'generatorVersion'}:
        raise ValueError('V2_RENDER_TOOLCHAIN_CHANGED')
    original = json.loads((ROOT / 'validation/corpus/cases.json').read_text())
    source = json.loads((ROOT / 'validation/corpus/cases-v2.json').read_text())
    for key in ('seed', 'analytes', 'layouts'):
        if source[key] != original[key]:
            raise ValueError('V2_RENDER_INPUT_CHANGED')
    if len(original['scenarios']) != len(source['scenarios']):
        raise ValueError('V2_SCENARIO_MATRIX_CHANGED')
    for old, new in zip(original['scenarios'], source['scenarios']):
        for key, value in old.items():
            if key != 'expectedReason' and new.get(key) != value:
                raise ValueError('V2_RENDER_OR_EXPECTED_STATUS_CHANGED')
    return source


def generate(output, font):
    source = checked_source()
    manifest = render_v1(output, font)
    scenarios = {s['id']: s for s in source['scenarios']}
    from generate import stable_id
    families = {stable_id('family', key): value for key, value in scenarios.items()}
    for case in manifest['cases']:
        path = output / case['groundTruthPath']
        truth = upgrade_truth(json.loads(path.read_text()), families[case['familyId']])
        data = json_bytes(truth); path.write_bytes(data)
        case['groundTruthSha256'] = sha(data)
    manifest.update(schemaVersion='1.1', corpusVersion=source['corpusVersion'], generatorVersion='2.0')
    (output / 'manifest.json').write_bytes(json_bytes(manifest))
    package(output, manifest, output / 'corpus.zip')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--font', type=Path, default=Path(LOCK['fontDefault']))
    args = parser.parse_args()
    result = generate(args.output, args.font)
    print(f"Generated {len(result['cases'])} v2 variants with unchanged document rendering")
