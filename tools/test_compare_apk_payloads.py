from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile, ZIP_STORED, ZIP_DEFLATED
import unittest
import warnings
from compare_apk_payloads import compare


class ApkPayloadTest(unittest.TestCase):
    def test_different_compression_with_identical_payload_passes(self):
        with TemporaryDirectory() as d:
            a, b = Path(d)/'a.apk', Path(d)/'b.apk'
            for p, method in ((a, ZIP_STORED), (b, ZIP_DEFLATED)):
                with ZipFile(p, 'w', compression=method) as z: z.writestr('assets/word', b'word'*100)
            result = compare(a, b)
            self.assertTrue(result['passed'])
            self.assertNotEqual(result['before']['sha256'], result['after']['sha256'])

    def test_changed_or_removed_entry_fails(self):
        with TemporaryDirectory() as d:
            a, b = Path(d)/'a.apk', Path(d)/'b.apk'
            with ZipFile(a, 'w') as z: z.writestr('classes.dex', b'a')
            for names in ({'classes.dex': b'b'}, {'other': b'a'}, {}):
                with ZipFile(b, 'w') as z:
                    for name, data in names.items(): z.writestr(name, data)
                with self.assertRaises(ValueError): compare(a, b)

    def test_duplicate_zip_name_fails_even_with_same_content(self):
        with TemporaryDirectory() as d:
            a = Path(d)/'a.apk'
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with ZipFile(a, 'w') as z:
                    z.writestr('same', b'a'); z.writestr('same', b'a')
            with self.assertRaises(ValueError): compare(a, a)


if __name__ == '__main__': unittest.main()
