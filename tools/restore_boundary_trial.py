"""Archive the failed E17 opt-in trial, restoring only its two explicitly edited runtime files."""
import gzip
from pathlib import Path
from collect_boundary_stage import read,write_new,sha

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts/input-quality/e17'
EDITED=['core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinDecoder.kt',
        'core-input/src/main/kotlin/io/github/ethanbird/senseime/core/PinyinLanguageScorer.kt']
ADDED=['core-input/src/main/kotlin/io/github/ethanbird/senseime/core/M22BoundaryAblationBenchmark.kt',
       'core-input/src/test/kotlin/io/github/ethanbird/senseime/core/PinyinInternalBigramCalibrationTest.kt']


def main():
    assert not (ART/'restoration.json').exists()
    freeze=read(ART/'development-freeze.json');assert freeze['selectedMode'] is None
    lock=read(ART/'pre-dev-lock.json');old=read(ROOT/'benchmarks/results/e13-evidence-manifest.json')
    target=read(ROOT/'benchmarks/results/e16-acceptance-gate.json')['sourcePins']
    # Validate every input before touching any trial source.
    for p,h in lock['files'].items():assert sha(Path(p).read_bytes())==h,p
    restore={}
    for relative in EDITED:
        entry=old['files'][relative];packed=(ROOT/entry['archive']).read_bytes()
        assert sha(packed)==entry['archiveSha256']
        data=gzip.decompress(packed);assert sha(data)==entry['sha256']==target[relative]
        restore[relative]=data
    entries={}
    for relative in EDITED+ADDED:
        path=(ROOT/relative).resolve();archive=(ART/'trial-source'/relative).resolve()
        assert path.is_relative_to(ROOT) and archive.is_relative_to(ART) and not archive.exists()
        data=path.read_bytes();archive.parent.mkdir(parents=True,exist_ok=True)
        if relative in ADDED:
            path.rename(archive)  # Exact newly-created file, no recursive deletion or computed directory move.
        else:
            archive.write_bytes(data);path.write_bytes(restore[relative])
        entries[relative]=dict(trialSha256=sha(data),snapshot=archive.relative_to(ROOT).as_posix(),
                              restoredSha256=sha(path.read_bytes()) if relative in EDITED else None)
    assert all(sha((ROOT/p).read_bytes())==h for p,h in target.items())
    write_new(ART/'restoration.json',dict(schemaVersion=1,files=entries,restoredTo='E16 runtime source pins',
        reason='Neither predeclared E17 trial passed; avoid adding an unaccepted branch to the production scoring hot path',
        scriptSha256=sha(Path(__file__).read_bytes())))
    print('Archived four trial sources; two runtime files restored; M22 and its experimental test moved out of production build')


if __name__=='__main__':main()
