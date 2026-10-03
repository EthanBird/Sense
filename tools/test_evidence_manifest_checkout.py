"""Evidence SHA pins refer to raw bytes, so Git must preserve their manifests."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class EvidenceManifestCheckoutTest(unittest.TestCase):
    def roundtrip(self, name):
        attributes=(Path(__file__).resolve().parents[1]/'.gitattributes').read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            def git(*args):
                return subprocess.check_output(['git','-c','core.autocrlf=false',*args],cwd=root,stderr=subprocess.PIPE)
            git('init','-q')
            (root/'.gitattributes').write_bytes(attributes)
            path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
            raw=b'{\r\n  "sha256": "synthetic"\r\n}\r\n';path.write_bytes(raw)
            git('add','.gitattributes',name)
            return raw,git('show',':'+name)

    def test_hashed_manifest_retains_exact_bytes_in_git(self):
        before,after=self.roundtrip('benchmarks/results/e25-evidence-manifest.json')
        self.assertEqual(before,after)

    def test_ordinary_json_keeps_standard_lf_policy(self):
        before,after=self.roundtrip('benchmarks/results/ordinary-report.json')
        self.assertEqual(before.replace(b'\r\n',b'\n'),after)


if __name__=='__main__':unittest.main()
