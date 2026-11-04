#!/usr/bin/env python3
"""Actual S25, offline first install, paired corpus runs, review, restart and deletion."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'litert'))
from run_bundled import verify_apk,sha_file
from instrumentation_events import run_events
ROOT=Path(__file__).resolve().parents[2]
PACKAGE='br.com.exames.offline.validation.final'
TEST_PACKAGE=PACKAGE+'.test'
TEST='br.com.exames.app.FinalWorkbenchAcceptanceTest#realOfflinePairsSurviveRestartAndReviewWithoutChangingOriginalMetrics'

def main():
    p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--report',type=Path,required=True)
    a=p.parse_args()
    if a.serial.startswith('emulator-') or a.report.exists(): raise SystemExit('REFERENCE_S25_AND_NEW_REPORT_REQUIRED')
    adb=[a.adb,'-s',a.serial]
    def run(*parts,timeout=180): return subprocess.run(adb+list(parts),check=True,capture_output=True,text=True,timeout=timeout).stdout.strip()
    if run('shell','getprop','ro.product.model')!='SM-S931B': raise SystemExit('REFERENCE_S25_REQUIRED')
    if run('shell','settings','get','global','airplane_mode_on')!='1' or run('shell','settings','get','global','wifi_on') not in ('0','3'):
        raise SystemExit('AIRPLANE_MODE_AND_WIFI_OFF_REQUIRED_BEFORE_INSTALL')
    app=ROOT/'app/build/outputs/apk/debug/app-debug.apk';test=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    verify_apk(app)
    for path,package in ((app,PACKAGE),(test,TEST_PACKAGE)):
        if json.loads((path.parent/'output-metadata.json').read_text())['applicationId']!=package: raise SystemExit('DEDICATED_PACKAGE_REQUIRED')
        if 'package:'+package in run('shell','pm','list','packages',package).splitlines(): raise SystemExit('EXISTING_PACKAGE_PRESERVED')
    report={'schemaVersion':1,'date':datetime.now(timezone.utc).isoformat(),'cleanOfflineFirstInstall':True,'applicationId':PACKAGE,
        'appApkSha256':sha_file(app),'testApkSha256':sha_file(test),'clinicalValidation':False,'thoughtPersisted':False,
        'device':{k:run('shell','getprop',v) for k,v in [('model','ro.product.model'),('android','ro.build.version.release'),('api','ro.build.version.sdk')]},
        'events':[],'passed':False,'limits':['Eight synthetic readable cases, four formats/two layouts; not all corpus scenarios or public coverage.',
        '4 GB and 16 KiB hardware pending; sampled PSS is not peak or complete GPU accounting.',
        'Native SDK decode totals; separate thought/final token counts unavailable; no warm Engine reuse.']}
    installed=[]
    try:
        for package,path in ((PACKAGE,app),(TEST_PACKAGE,test)):
            run('install','-t',str(path),timeout=240);installed.append(package)
        for phase in ('measure','resume'):
            if phase=='resume': run('shell','am','force-stop',PACKAGE);report['forceStopIssued']=True
            def record(event):
                report['events'].append(event)
                a.report.parent.mkdir(parents=True,exist_ok=True)
                a.report.write_text(json.dumps(report,indent=2)+'\n')
            summary=run_events(adb,['-e','class',TEST,'-e','finalPhase',phase,
                TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner'],'finalAcceptance',record,
                timeout=2700 if phase=='measure' else 180)
            for key in ('failureType','firstTestFrame'):
                if key in summary: report[key]=summary[key]
            if not summary['runnerPassed']: raise RuntimeError('FINAL_PHASE_FAILED')
            a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
        measured=next(e for e in report['events'] if e.get('phase')=='measured')
        resumed=next(e for e in report['events'] if e.get('phase')=='resumed-deleted')
        report['passed']=(measured['passed'] and resumed['passed'] and measured['pid']!=resumed['pid'] and measured['pairs']==8
            and measured['acceptedModelPages']>0 and resumed['originalMetricsPreserved'] and resumed['staticWeightsPreserved'])
    except (subprocess.SubprocessError,RuntimeError,StopIteration) as error:
        report['controllerFailure']=type(error).__name__
        if PACKAGE in installed: run('shell','am','force-stop',PACKAGE)
    finally:
        report['removedCreatedPackages']=[]
        if report['passed']:
            for package in reversed(installed): run('uninstall',package);report['removedCreatedPackages'].append(package)
        a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS' if report['passed'] else 'FAIL (aggregate report)');return 0 if report['passed'] else 1
if __name__=='__main__': raise SystemExit(main())
