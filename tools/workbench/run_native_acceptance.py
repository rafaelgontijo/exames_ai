#!/usr/bin/env python3
"""Clean, offline reference-device acceptance of actual production inference. No source/output text."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'litert'))
from run_bundled import verify_apk,sha_file
ROOT=Path(__file__).resolve().parents[2]
PACKAGE='br.com.exames.offline.validation.native'
TEST_PACKAGE=PACKAGE+'.test'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True);parser.add_argument('--report',type=Path,required=True)
    a=parser.parse_args()
    if a.serial.startswith('emulator-') or a.report.exists(): raise SystemExit('REFERENCE_DEVICE_AND_NEW_REPORT_REQUIRED')
    app=ROOT/'app/build/outputs/apk/debug/app-debug.apk';test=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    verify_apk(app)
    for path,package in ((app,PACKAGE),(test,TEST_PACKAGE)):
        if json.loads((path.parent/'output-metadata.json').read_text())['applicationId']!=package: raise SystemExit('DEDICATED_APPLICATION_ID_REQUIRED')
    adb=[a.adb,'-s',a.serial]
    def run(*parts,timeout=180): return subprocess.run(adb+list(parts),check=True,capture_output=True,text=True,timeout=timeout).stdout.strip()
    if run('shell','getprop','ro.product.model')!='SM-S931B': raise SystemExit('REFERENCE_S25_REQUIRED')
    for p in (PACKAGE,TEST_PACKAGE):
        if 'package:'+p in run('shell','pm','list','packages',p).splitlines(): raise SystemExit('EXISTING_PACKAGE_PRESERVED')
    if run('shell','settings','get','global','airplane_mode_on')!='1' or run('shell','settings','get','global','wifi_on') not in ('0','3'):
        raise SystemExit('AIRPLANE_MODE_AND_WIFI_OFF_REQUIRED')
    report={'schemaVersion':1,'date':datetime.now(timezone.utc).isoformat(),'cleanOfflineInstall':True,'thoughtPersisted':False,
        'applicationId':PACKAGE,'appApkSha256':sha_file(app),'testApkSha256':sha_file(test),'events':[],
        'device':{k:run('shell','getprop',p) for k,p in [('model','ro.product.model'),('android','ro.build.version.release'),('api','ro.build.version.sdk')]},'passed':False}
    installed=[]
    try:
        for package,path in ((PACKAGE,app),(TEST_PACKAGE,test)):
            run('install','-t',str(path),timeout=240);installed.append(package)
        output=run('shell','am','instrument','-w','-r','-e','class',
            'br.com.exames.app.ProductionInferenceAcceptanceTest#freshProductionProcessesValidateRealFieldsAndRetireAfterNativeCancellation',
            '-e','nativeAcceptance','true',TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner',timeout=900)
        for line in output.splitlines():
            if line.startswith('INSTRUMENTATION_STATUS: nativeAcceptance='):
                report['events'].append(json.loads(line.split('nativeAcceptance=',1)[1]))
            if line.startswith('INSTRUMENTATION_STATUS: stack='): report['failureType']=line.split('stack=',1)[1].split(':',1)[0]
        report['passed']='OK (1 test)' in output and 'FAILURES!!!' not in output and any(e.get('phase')=='complete' and e.get('passed') for e in report['events'])
    except subprocess.SubprocessError as error: report['controllerFailure']=type(error).__name__
    finally:
        report['removedCreatedPackages']=[]
        if report['passed']:
            for p in reversed(installed): run('uninstall',p);report['removedCreatedPackages'].append(p)
        a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS' if report['passed'] else 'FAIL (aggregate report)');return 0 if report['passed'] else 1
if __name__=='__main__': raise SystemExit(main())
