from dataclasses import replace
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from build_pinyin_lexicon import LexiconCandidate, read_binary, write_binary
from build_layered_pinyin import build
from layered_lexicon import project_base


class LayeredLexiconTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = {"neng": [LexiconCandidate("能", 100, "n", 0)], "zhineng": [LexiconCandidate("智能", 8850, "zn", 0)],
                     "~zn": [LexiconCandidate("智能", 13, "zn", 0)]}

    def test_projection_is_byte_exact_including_weights_aliases_and_order(self):
        b, c = self.root/'base', self.root/'candidate'
        write_binary(self.base, b)
        supplemental = LexiconCandidate("只能", 1, "zn", 2)
        entries = {**self.base, "zhineng": self.base["zhineng"] + [supplemental],
                   "zhinengti": [LexiconCandidate("智能体", 1, "znt", 2)]}
        write_binary(entries, c, version=4)
        self.assertEqual(b.read_bytes(), project_base(c.read_bytes()))
        self.assertEqual(entries, read_binary(c))
        self.assertEqual(b.read_bytes(), project_base(b.read_bytes()))

    def test_forged_tiers_versions_private_aliases_and_truncation_fail(self):
        path = self.root/'candidate'
        for code, initials in (("~zn", "zn"), ("neng", "n")):
            write_binary({**self.base, code: [LexiconCandidate("智能", 1, initials, 2)]}, path, version=4)
            with self.assertRaises(ValueError): project_base(path.read_bytes())
        with self.assertRaises(ValueError): write_binary({"zhineng": [LexiconCandidate("智能", 1, "zn", 2)]}, path)
        write_binary(self.base, path)
        for data in (path.read_bytes()[:-1], path.read_bytes()+b'x', b'SPLX\x00\x05'+path.read_bytes()[6:]):
            with self.assertRaises(ValueError): project_base(data)

    def test_promoter_rejects_changed_base_even_with_a_self_consistent_prototype_hash(self):
        base = self.root/'base'; folder=self.root/'prototype'; folder.mkdir(); target=folder/'pinyin_lexicon.bin'
        write_binary(self.base, base)
        modified={**self.base, "zhineng": [replace(self.base['zhineng'][0], weight=100000)]}
        write_binary(modified, target)
        sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
        (folder/'build.json').write_text(json.dumps({'policy':{'baseSha256':sha(base)},'asset':{'sha256':sha(target)},'fallbackWeight':1}))
        with self.assertRaises(ValueError): build(base, folder, self.root/'result')


if __name__ == '__main__': unittest.main()
