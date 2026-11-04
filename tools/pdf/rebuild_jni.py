#!/usr/bin/env python3
"""Rebuild pinned PDFium JNI; retain verified engine/classes and emit a test-only patched core AAR."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PREFIX = Path('pdfiumandroid/core/src/main/cpp')
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load_alignments(data):
    if data[:4] != b'\x7fELF' or data[5] != 1:
        raise ValueError('Expected little-endian ELF')
    wide = data[4] == 2
    offset = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    size, count = struct.unpack_from('<HH', data, 54 if wide else 42)
    return [struct.unpack_from('<Q' if wide else '<I', data, offset + i * size + (48 if wide else 28))[0]
            for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--core-aar', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--engine-lock', type=Path,
                        help='Use a verified PDFium engine release with bundled notices')
    args = parser.parse_args()
    pins = json.loads((ROOT / 'validation/pdf/native-sources.lock.json').read_text())
    candidate = json.loads((ROOT / 'validation/pdf/pdfium-candidate.lock.json').read_text())
    expected = next(x for x in candidate['artifacts'] if x['coordinate'] == 'io.legere:pdfiumandroid-core:2.0.1')
    core_data = args.core_aar.read_bytes()
    if sha(core_data) != expected['sha256']:
        parser.error('Upstream core AAR hash mismatch')
    work = ROOT / 'pdf-spike/build/native-rebuild'
    upstream = ROOT / 'pdf-spike/build/native-upstream'
    staged = work / 'source'
    ndk = args.sdk / 'ndk' / pins['ndkVersion']
    cmake = args.sdk / 'cmake' / pins['cmakeVersion'] / 'bin/cmake'
    ninja = cmake.with_name('ninja')
    if not cmake.is_file() or not ndk.is_dir():
        parser.error('Pinned Android NDK/CMake not installed')
    revision = next(line for line in (ndk / 'source.properties').read_text().splitlines() if line.startswith('Pkg.Revision'))
    if revision.split('=', 1)[1].strip() != pins['ndkVersion']:
        parser.error('NDK revision mismatch')
    strip = next((ndk / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-strip'))
    for item in pins['sources']:
        path = Path(item['path'])
        if path.is_absolute() or '..' in path.parts or not path.is_relative_to(PREFIX):
            parser.error('Invalid source lock path')
        cache = upstream / path
        if not cache.is_file() or sha(cache.read_bytes()) != item['sha256']:
            if args.offline:
                parser.error('Verified source unavailable offline: ' + item['path'])
            url = f'https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/{pins["commit"]}/{item["path"]}'
            data = urllib.request.urlopen(url, timeout=30).read(item['bytes'] + 1)
            if len(data) != item['bytes'] or sha(data) != item['sha256']:
                parser.error('Source hash mismatch: ' + item['path'])
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_bytes(data)
        target = staged / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(cache, target)
    patch = ROOT / 'validation/pdf/pdfium-2.0.1-jni-proposal.patch'
    # Always patch a fresh, hash-verified copy; never edit the cache.
    subprocess.run(['patch', '-p1', '--batch', '-i', str(patch)], cwd=staged, check=True)
    engines = {}
    engine_assets = {}
    with zipfile.ZipFile(args.core_aar) as source:
        for abi in ABIS:
            entry = f'jni/{abi}/libpdfium.so'
            data = source.read(entry)
            pinned = next(x for x in expected['native'] if x['path'] == entry)
            if sha(data) != pinned['sha256']:
                parser.error('Engine hash mismatch')
            engines[abi] = data
    engine_pins = json.loads(args.engine_lock.read_text()) if args.engine_lock else None
    if engine_pins:
        if {item['abi'] for item in engine_pins['archives']} != set(ABIS):
            parser.error('Engine lock must cover all four ABIs')
        for item in engine_pins['archives']:
            archive_path = ROOT / 'pdf-spike/build/engine-cache' / item['archive']
            if not archive_path.is_file() or sha(archive_path.read_bytes()) != item['archiveSha256']:
                if args.offline:
                    parser.error('Verified engine archive unavailable offline')
                data = urllib.request.urlopen(item['url'], timeout=60).read(item['archiveBytes'] + 1)
                if len(data) != item['archiveBytes'] or sha(data) != item['archiveSha256']:
                    parser.error('Engine archive hash mismatch')
                archive_path.parent.mkdir(parents=True, exist_ok=True)
                archive_path.write_bytes(data)
            with tarfile.open(archive_path) as archive:
                data = archive.extractfile('lib/libpdfium.so').read()
                if sha(data) != item['engineSha256']:
                    parser.error('Released engine hash mismatch')
                engines[item['abi']] = data
                for name, expected_hash in item['files'].items():
                    path = Path(name)
                    if path.is_absolute() or '..' in path.parts:
                        parser.error('Invalid notice path')
                    contents = archive.extractfile(name).read()
                    if sha(contents) != expected_hash:
                        parser.error('Engine metadata/notice hash mismatch')
                    engine_assets[f'assets/licenses/pdfium-engine-{engine_pins["version"]}/{item["abi"]}/{name}'] = contents
                arguments = archive.extractfile('args.gn').read().decode()
                if 'pdf_enable_v8 = false' not in arguments or 'pdf_enable_xfa = false' not in arguments:
                    parser.error('Engine must disable V8 and XFA')
                minimum = next(line for line in arguments.splitlines() if line.startswith('default_min_sdk_version'))
                if int(minimum.split('=')[1]) > pins['androidApi']:
                    parser.error('Engine requires a higher Android API')
    for abi, data in engines.items():
        target = staged / PREFIX.parent / 'jniLibs' / abi / 'libpdfium.so'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    rebuilt = {}
    for abi in ABIS:
        destination = work / 'cmake' / abi
        subprocess.run([str(cmake), '-S', str(staged / PREFIX), '-B', str(destination), '-G', 'Ninja',
                        '-DCMAKE_MAKE_PROGRAM=' + str(ninja),
                        '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                        '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-' + str(pins['androidApi']),
                        '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release',
                        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'], check=True)
        subprocess.run([str(cmake), '--build', str(destination), '--parallel', '4'], check=True)
        library = destination / 'libpdfiumandroid.so'
        subprocess.run([str(strip), '--strip-unneeded', str(library)], check=True)
        data = library.read_bytes()
        if not load_alignments(data) or any(x < 16384 for x in load_alignments(data)):
            parser.error('Rebuilt JNI is not 16 KiB aligned')
        rebuilt[abi] = data
    profile = '2' if engine_pins else '1'
    output = work / f'pdfiumandroid-core-2.0.1-exames{profile}.aar'
    entries = {}
    with zipfile.ZipFile(args.core_aar) as source:
        for name in source.namelist():
            if not name.startswith('jni/'):
                entries[name] = source.read(name)
    for abi in ABIS:
        entries[f'jni/{abi}/libpdfium.so'] = engines[abi]
        entries[f'jni/{abi}/libpdfiumandroid.so'] = rebuilt[abi]
    entries['assets/licenses/pdfium-jni-binding-LICENSE.txt'] = (ROOT / 'validation/pdf/BINDING_LICENSE').read_bytes()
    entries.update(engine_assets)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, entries[name])
    report = {'schemaVersion': 'pdf-jni-build/1', 'profile': 'pdfium-jni-exames/' + profile,
              'rebuildScriptSha256': sha(Path(__file__).read_bytes()),
              'upstreamCoreSha256': sha(core_data), 'upstreamCommit': pins['commit'],
              'sourceLockSha256': sha((ROOT / 'validation/pdf/native-sources.lock.json').read_bytes()),
              'patchSha256': sha(patch.read_bytes()), 'ndk': pins['ndkVersion'], 'cmake': pins['cmakeVersion'],
              'androidApi': pins['androidApi'], 'aarSha256': sha(output.read_bytes()),
              'engineRecompiled': False, 'productionApproved': False,
              'native': [{'abi': abi, 'jniSha256': sha(rebuilt[abi]),
                          'engineSha256': sha(engines[abi]),
                          'jniLoadAlignments': load_alignments(rebuilt[abi])} for abi in ABIS]}
    if engine_pins:
        report['engineLockSha256'] = sha(args.engine_lock.read_bytes())
        report['engineVersion'] = engine_pins['version']
        report['engineNoticeFilesBundled'] = len(engine_assets)
    (work / f'build-report-{profile}.json').write_text(json.dumps(report, indent=2) + '\n')
    (work / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'aar': str(output), 'sha256': report['aarSha256']}))


if __name__ == '__main__':
    main()
