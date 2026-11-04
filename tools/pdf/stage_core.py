#!/usr/bin/env python3
"""Stage only the verified corrected core into a local build-time Maven repository."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]


def main():
    build = ROOT / 'pdf-spike/build/native-rebuild'
    report = json.loads((build / 'build-report-2.json').read_text())
    for key, name in [('patchSha256', 'validation/pdf/pdfium-2.0.1-jni-proposal.patch'),
                      ('sourceLockSha256', 'validation/pdf/native-sources.lock.json'),
                      ('engineLockSha256', 'validation/pdf/engine.lock.json')]:
        if report[key] != hashlib.sha256((ROOT / name).read_bytes()).hexdigest():
            raise ValueError('Native build is stale; rebuild before staging')
    source = build / 'pdfiumandroid-core-2.0.1-exames2.aar'
    if hashlib.sha256(source.read_bytes()).hexdigest() != report['aarSha256']:
        raise ValueError('Corrected core hash mismatch')
    target = ROOT / 'build/pdfium-repository/br/com/exames/thirdparty/pdfiumandroid-core/2.0.1-exames2'
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target / source.name)
    pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>br.com.exames.thirdparty</groupId><artifactId>pdfiumandroid-core</artifactId>
  <version>2.0.1-exames2</version><packaging>aar</packaging>
  <licenses><license><name>Apache-2.0 binding; see bundled engine notices</name></license></licenses>
  <dependencies>
    <dependency><groupId>io.legere</groupId><artifactId>pdfiumandroid-api</artifactId><version>2.0.1</version></dependency>
    <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.3.21</version></dependency>
  </dependencies>
</project>
'''
    (target / 'pdfiumandroid-core-2.0.1-exames2.pom').write_text(pom)
    (target / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Staged corrected PDFium core:', report['aarSha256'])


if __name__ == '__main__':
    main()
