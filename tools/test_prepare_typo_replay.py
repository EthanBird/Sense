import unittest
import json
import tempfile
from pathlib import Path
from prepare_typo_replay import variants, neighbors, prepare, sha


class TypoReplayTest(unittest.TestCase):
    def test_source_manifest_tracks_the_actual_version_not_a_hardcoded_v1(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "p2c-new", root / "typo-new"
            source.mkdir()
            for name in ("dev.tsv", "test.tsv", "attribution.jsonl", "annotations.tsv"):
                (source / name).write_text("", encoding="utf-8")
            frozen = {"outputs": {split: {"sha256": sha(source / f"{split}.tsv")} for split in ("dev", "test")},
                      "attributionSha256": sha(source / "attribution.jsonl"),
                      "annotationsSha256": sha(source / "annotations.tsv")}
            (source / "frozen.json").write_text(json.dumps(frozen), encoding="utf-8")
            manifest = prepare(source, output)
            self.assertEqual("../p2c-new", manifest["source"])
            self.assertEqual(sha(source / "frozen.json"), manifest["sourceFrozenSha256"])
            with self.assertRaises(ValueError):
                prepare(source, output)

    def test_deterministic_single_edits_and_zone_alignment(self):
        query = "woxihuanbeijing"
        rows = variants("fixture", query, ["wo", "xi", "huan", "bei", "jing"])
        self.assertEqual(rows, variants("fixture", query, ["wo", "xi", "huan", "bei", "jing"]))
        self.assertEqual(15, len(rows))
        for operation, zone, index, typed in rows:
            if zone in ("head", "middle", "tail"):
                self.assertEqual(("head", "middle", "tail")[index*3//len(query)], zone)
            if operation == "delete":
                self.assertEqual(query[:index]+query[index+1:], typed)
            elif operation == "repeat":
                self.assertEqual(query[:index]+query[index]+query[index:], typed)
            elif operation == "neighbor":
                self.assertIn(typed[index], neighbors(query[index]))
                self.assertEqual(query[:index]+query[index+1:], typed[:index]+typed[index+1:])
            elif operation == "transpose":
                self.assertEqual(query[:index]+query[index+1]+query[index]+query[index+2:], typed)

    def test_clean_and_joint_controls_are_kept_and_bad_annotations_fail(self):
        rows = variants("fixture", "xian", ["xi", "an"])
        self.assertEqual(("clean", "none", -1, "xian"), rows[0])
        self.assertEqual(("joints", "none", -1, "xi'an"), rows[1])
        for query, units in [("nihao", ["ni"]), ("ni1hao", ["ni1", "hao"]), ("NI", ["NI"])]:
            with self.assertRaises(ValueError): variants("fixture", query, units)


if __name__ == "__main__": unittest.main()
