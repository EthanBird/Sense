"""Compare complete paired typo replays; do not pool correlated variants as IID users."""
import argparse
from collections import defaultdict
import gzip
import hashlib
import json
import random
import statistics
from pathlib import Path


def read(path):
    data = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
    return json.loads(data)


def summary(rows):
    chars = sum(len(x["expected"]) for x in rows)
    errors = sum(x["characterErrors"] for x in rows)
    times = sorted(x["decodeNs"]/1e6 for x in rows)
    return {"rows":len(rows), "families":len({x["sourceId"] for x in rows}),
            "top1":sum(x["rank"]==1 for x in rows), "top3":sum(0<x["rank"]<=3 for x in rows),
            "top10":sum(0<x["rank"]<=10 for x in rows), "covered":sum(x["rank"]>0 for x in rows),
            "mrr":sum(1/x["rank"] if x["rank"] else 0 for x in rows)/len(rows),
            "graphAligned":sum(x["graphAlignedRank"]>0 for x in rows),
            "graphCanonical":sum(x["graphCanonicalRank"]>0 for x in rows),
            "selectedCanonical":sum(x.get("selectedExpectedSpelling",False) for x in rows) if all("selectedExpectedSpelling" in x for x in rows) else None,
            "characterErrors":errors,"characters":chars,"cer":errors/chars,
            "hostMedianMs":statistics.median(times), "hostObservedP95Ms":times[(len(times)*95+99)//100-1]}


def compare(before, after, expected_rows=None):
    for key in ("inputSha256","candidateLimit","lmWeight","assets","graphDiagnosticLimit","learning"):
        if key not in before or before[key] != after.get(key):
            raise ValueError(f"Incompatible execution inputs: {key}")
    a,b=before["observations"],after["observations"]
    if expected_rows is not None and (len(a)!=expected_rows or len(b)!=expected_rows):
        raise ValueError("Reports omitted frozen input rows")
    if not a or len(a)!=len(b) or len({x["id"] for x in a}) != len(a):
        raise ValueError("Missing, duplicated, or mismatched rows")
    for x,y in zip(a,b):
        for key in ("id","sourceId","typed","canonical","expected","operation","zone","stratum","editOffset"):
            if x[key]!=y[key]: raise ValueError(f"Unpaired row: {key}")
        for row in (x,y):
            if not 0<=row["rank"]<=row["candidateCount"] or row["characterErrors"]<0 or row["decodeNs"]<0:
                raise ValueError("Invalid observation")
    output={"schemaVersion":1,"scope":"Paired synthetic errors on known reviewed source text; not fresh natural-error or phone latency evidence",
            "before":summary(a),"after":summary(b),"byOperation":{},"byZone":{},"top1Gains":[],"top1Losses":[]}
    for label,key in [("byOperation","operation"),("byZone","zone")]:
        for value in sorted({x[key] for x in a}):
            output[label][value]={"before":summary([x for x in a if x[key]==value]),"after":summary([x for x in b if x[key]==value])}
    differences=defaultdict(list)
    for x,y in zip(a,b):
        if (x["rank"]==1)!=(y["rank"]==1):
            output["top1Gains" if y["rank"]==1 else "top1Losses"].append({"id":x["id"],"typed":x["typed"],"expected":x["expected"],
                "operation":x["operation"],"zone":x["zone"],"beforeRank":x["rank"],"afterRank":y["rank"],"beforeTop1":x["top5"][:1],"afterTop1":y["top5"][:1]})
        if x["operation"] not in ("clean","joints"):
            differences[x["sourceId"]].append(int(y["rank"]==1)-int(x["rank"]==1))
    family_deltas=[statistics.mean(v) for _,v in sorted(differences.items())]
    if family_deltas:
        randomizer=random.Random(271828)
        samples=sorted(statistics.mean(randomizer.choices(family_deltas,k=len(family_deltas))) for _ in range(2000))
        output["syntheticFamilyWeightedTop1Delta"]={"estimate":statistics.mean(family_deltas),"percentile95":[samples[49],samples[1949]],
            "families":len(family_deltas),"resamples":2000,"seed":271828,"interpretation":"Cluster resampling source families, not evidence of real-user error prevalence or independent validation of source labels"}
    mutations=[v for key,v in output["byOperation"].items() if key not in ("clean","joints")]
    output["qualityGate"]={
        "cleanCountsPreserved":all(output["byOperation"][op]["after"][metric]>=output["byOperation"][op]["before"][metric] for op in ("clean","joints") for metric in ("top1","top10")),
        "typoTop10GainAtLeast20":sum(v["after"]["top10"]-v["before"]["top10"] for v in mutations)>=20,
        "typoCharacterErrorsNonIncreasing":sum(v["after"]["characterErrors"] for v in mutations)<=sum(v["before"]["characterErrors"] for v in mutations),
        "everyMutationCoverageNonDecreasing":all(v["after"]["covered"]>=v["before"]["covered"] for v in mutations)}
    return output


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before",type=Path);parser.add_argument("after",type=Path);parser.add_argument("output",type=Path)
    parser.add_argument("--manifest",type=Path,default=Path(__file__).resolve().parents[1]/"benchmarks/corpus/typo-v1/frozen.json")
    args=parser.parse_args()
    if args.output.exists(): raise ValueError("Retain earlier evidence; use a new output")
    before,after=read(args.before),read(args.after)
    matches=[v for v in read(args.manifest)["outputs"].values() if v["sha256"]==before["inputSha256"]]
    if len(matches)!=1:raise ValueError("Report input is not a unique frozen partition")
    report=compare(before,after,expected_rows=matches[0]["rows"])
    report["inputReports"]={"before":hashlib.sha256(args.before.read_bytes()).hexdigest(),"after":hashlib.sha256(args.after.read_bytes()).hexdigest()}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"before":report["before"],"after":report["after"],"gate":report["qualityGate"]},indent=2))
