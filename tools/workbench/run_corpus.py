#!/usr/bin/env python3
"""Synthetic corpus/report control on emulator, with reversible TalkBack/font settings."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
from run_reopen import ROOT, PACKAGE, TEST_PACKAGE, digest


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--adb',default='adb');parser.add_argument('--serial',required=True)
    parser.add_argument('--report',type=Path,required=True);parser.add_argument('--screenshot',type=Path,required=True)
    a=parser.parse_args()
    if not a.serial.startswith('emulator-'): raise SystemExit('EMULATOR_ONLY')
    if a.report.exists() or a.screenshot.exists(): raise SystemExit('OUTPUT_EXISTS')
    adb=[a.adb,'-s',a.serial]
    def run(*parts): return subprocess.run(adb+list(parts),check=True,capture_output=True,text=True,timeout=300).stdout
    def installed(p): return 'package:'+p in run('shell','pm','list','packages',p).splitlines()
    before={p:installed(p) for p in (PACKAGE,TEST_PACKAGE)}
    app=ROOT/'app/build/outputs/apk/debug';test=ROOT/'app/build/outputs/apk/androidTest/debug'
    for path,p in ((app,PACKAGE),(test,TEST_PACKAGE)):
        if json.loads((path/'output-metadata.json').read_text())['applicationId']!=p: raise SystemExit('DEDICATED_PACKAGE_REQUIRED')
    original={key:run('shell','settings','get',group,key).strip() for group,key in (
        ('system','font_scale'),('secure','enabled_accessibility_services'),('secure','accessibility_enabled'))}
    report={'schemaVersion':1,'date':datetime.now(timezone.utc).isoformat(),'device':a.serial,
        'scope':'Eight synthetic corpus cases: four formats/two layouts, baseline production WorkManager, source revocation, independent truth, numeric page measurements, local report, TalkBack focus, font 200%, deletion.',
        'limits':'Synthetic technical integration only; no real model/S25, speech comprehension or clinical validation.',
        'installedBefore':before,'originalSettings':original,'appApkSha256':digest(app/'app-debug.apk'),
        'testApkSha256':digest(test/'app-debug-androidTest.apk'),'passed':False}
    try:
        if not before[PACKAGE]: run('install','-t',str(app/'app-debug.apk'))
        else: raise RuntimeError('EXISTING_FIXTURE_REQUIRES_EXPLICIT_RECONCILIATION')
        run('install','-r','-t',str(test/'app-debug-androidTest.apk'))
        service='com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService'
        enabled=[] if original['enabled_accessibility_services']=='null' else original['enabled_accessibility_services'].split(':')
        if service not in enabled: enabled.append(service)
        run('shell','settings','put','secure','enabled_accessibility_services',':'.join(enabled))
        run('shell','settings','put','secure','accessibility_enabled','1')
        run('shell','settings','put','system','font_scale','2.0')
        output=run('shell','am','instrument','-w','-r','-e','class','br.com.exames.app.CorpusWorkbenchTest#validatedPackageRunsOfflineAndReportIsAccessibleAtLargeFont',
            '-e','workbenchCorpus','true','-e','workbenchAccessibility','true',TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner')
        screen=re.search(r'INSTRUMENTATION_STATUS: corpusScreenshot=([A-Za-z0-9+/=]+)',output)
        if screen:
            png=base64.b64decode(screen.group(1),validate=True)
            if len(png)>2*1024*1024 or not png.startswith(b'\x89PNG\r\n\x1a\n'): raise RuntimeError('SCREENSHOT_INVALID')
            a.screenshot.parent.mkdir(parents=True,exist_ok=True);a.screenshot.write_bytes(png)
            report['screenshotSha256']=hashlib.sha256(png).hexdigest()
        report['talkBackChecked']='INSTRUMENTATION_STATUS: talkBackChecked=true' in output
        report['cases']=8 if 'INSTRUMENTATION_STATUS: corpusCases=8' in output else None
        report['fontScale200Checked']='INSTRUMENTATION_STATUS: fontScaleChecked=2.0' in output
        report['passed']=('OK (1 test)' in output and 'FAILURES!!!' not in output and report['talkBackChecked']
            and report['fontScale200Checked'] and report['cases']==8 and screen is not None)
        if report['passed']:
            for p in (TEST_PACKAGE,PACKAGE):
                if not before[p]: run('uninstall',p)
            report['removedCreatedPackages']=[p for p in (TEST_PACKAGE,PACKAGE) if not before[p]]
    except (subprocess.SubprocessError,RuntimeError) as error: report['failure']=type(error).__name__
    finally:
        restored=True
        for group,key in (('system','font_scale'),('secure','enabled_accessibility_services'),('secure','accessibility_enabled')):
            value=original[key]
            try:
                if value=='null': run('shell','settings','delete',group,key)
                else: run('shell','settings','put',group,key,value)
                restored &= run('shell','settings','get',group,key).strip()==value
            except subprocess.SubprocessError: restored=False
        report['settingsRestored']=restored;report['passed'] &= restored
        a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS' if report['passed'] else 'FAIL (aggregate report)')
    return 0 if report['passed'] else 1

if __name__=='__main__': raise SystemExit(main())
