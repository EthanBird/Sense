"""Require E21's scoring-cost refactor to preserve the earlier candidate evidence exactly."""
import gzip
import json

from collect_boundary_stage import read, write_new
from evaluate_literal_completion_stage import ART, ROOT
from audit_sentence_corpus import sha256


def verify():
    report = {"scope": "Exact known-output equivalence; timings and source identities checked separately"}
    paths = []

    def rows(name):
        pair = [ART / f"{mode}-{name}" for mode in ("trial2", "perf")]
        paths.extend(pair)
        def load(path):
            data = gzip.decompress(path.read_bytes()).decode() if path.suffix == ".gz" else path.read_text("utf-8")
            return [json.loads(line) for line in data.splitlines()]
        before, after = map(load, pair)
        assert len(before) == len(after)
        if "sources" in before[0]:
            changed = [p for p, h in before[0]["sources"].items() if h != after[0]["sources"][p]]
            assert changed == ["core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinDecoder.kt"]
            assert {k:v for k,v in before[0].items() if k != "sources"} == {k:v for k,v in after[0].items() if k != "sources"}
            for p, h in after[0]["sources"].items():
                assert sha256(ROOT / p) == h
        return before[1:], after[1:]

    b, a = rows("probe.jsonl.gz")
    assert b == a
    report["completeScoreProbeRowsEqual"] = len(a) - 1
    report["completeCleanProgressiveRowsEqual"] = 0
    for part in ("dev", "test"):
        for domain in ("aishell", "tatoeba"):
            b, a = rows(f"{part}-{domain}.jsonl")
            assert [{k:v for k,v in r.items() if k != "hostNanos"} for r in b] == [
                {k:v for k,v in r.items() if k != "hostNanos"} for r in a]
            report["completeCleanProgressiveRowsEqual"] += len(a) - 1

    report["typoRecordedObservationsEqual"] = 0
    for dataset in ("typo-v1", "typo-layered-v4"):
        pair = [ART / f"{mode}-{dataset}.json" for mode in ("trial2", "perf")]
        paths.extend(pair)
        b, a = map(read, pair)
        assert {k:v for k,v in b.items() if k not in ("sources", "observations")} == {
            k:v for k,v in a.items() if k not in ("sources", "observations")}
        assert [{k:v for k,v in r.items() if k != "decodeNs"} for r in b["observations"]] == [
            {k:v for k,v in r.items() if k != "decodeNs"} for r in a["observations"]]
        report["typoRecordedObservationsEqual"] += len(a["observations"])
    b, a = rows("interaction.jsonl")
    assert b == a
    report["interactionRowsEqual"] = sum(r["type"] == "row" for r in a)
    report["repeatedCompleteResults"] = a[-1]["repeatedCompleteResults"]
    report["personalAssertions"] = a[-1]["personalAssertions"]
    report["files"] = {p.relative_to(ROOT).as_posix(): sha256(p) for p in paths}
    return report


if __name__ == "__main__":
    report = verify()
    write_new(ART / "perf-equivalence.json", report)
    print(json.dumps({k:v for k,v in report.items() if k != "files"}))
