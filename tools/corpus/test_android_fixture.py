import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import zipfile

from stage_android_fixture import stage


class AndroidFixturePartitionTest(unittest.TestCase):
    def cases(self):
        cases=[]
        for partition in ['HELD_OUT','DEVELOPMENT']:
            for fmt in ['PDF_TEXT','PDF_SCANNED','JPEG','PNG']:
                for layout in ['simple-v1','footnote-v1']:
                    identity=f'{partition}-{fmt}-{layout}'
                    cases.append(dict(caseId=identity,origin='SYNTHETIC',format=fmt,layoutId=layout,
                        partition=partition,documentPath=f'documents/{identity}',groundTruthPath=f'truth/{identity}'))
        return cases

    def test_held_out_first_never_becomes_tuning_fixture_and_selected_bytes_stay_original(self):
        cases=self.cases()
        with TemporaryDirectory() as directory:
            source=Path(directory)/'source.zip';output=Path(directory)/'fixture.zip'
            with zipfile.ZipFile(source,'w') as z:
                for case in cases:
                    for key in ['documentPath','groundTruthPath']:
                        z.writestr(case[key],case[key].encode())
            checked={'manifest':{'cases':cases},'reviewedCases':8}
            with patch('stage_android_fixture.validate_package',return_value=checked):
                stage(source,output)
            with zipfile.ZipFile(source) as original,zipfile.ZipFile(output) as fixture:
                selected=json.loads(fixture.read('manifest.json'))['cases']
                self.assertEqual(8,len(selected))
                self.assertTrue(all(c['partition']=='DEVELOPMENT' for c in selected))
                for case in selected:
                    for key in ['documentPath','groundTruthPath']:
                        self.assertEqual(original.read(case[key]),fixture.read(case[key]))
                self.assertFalse(any('HELD_OUT' in name for name in fixture.namelist()))

    def test_missing_development_stratum_fails_instead_of_borrowing_held_out_case(self):
        cases=self.cases()[:-1]
        with TemporaryDirectory() as directory:
            output=Path(directory)/'fixture.zip'
            with patch('stage_android_fixture.validate_package',return_value={'manifest':{'cases':cases}}):
                with self.assertRaisesRegex(ValueError,'EIGHT_DEVELOPMENT'):
                    stage(Path(directory)/'unused.zip',output)
            self.assertFalse(output.exists())
