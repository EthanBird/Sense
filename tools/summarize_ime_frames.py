"""Audit bounded HWUI frame windows separately from external-editor confirmations."""
import argparse
import csv
import json
from pathlib import Path
import re

from fetch_e37_model import digest
from run_e37_rescoring import distribution, write_new

PROCESS = 'io.github.ethanbird.senseime.debug:ime'
MAX_LONG = 2**63-1
REQUIRED = ['Flags','IntendedVsync','Vsync','HandleInputStart','AnimationStart',
            'PerformTraversalsStart','DrawStart','SyncQueued','SyncStart',
            'IssueDrawCommandsStart','SwapBuffers','FrameCompleted']
CASES = [('nihao','你好'),('woxihuanbeijing','我喜欢北京'),
         ('tangmumeiyouzhuyidaomalihuanlexinfaxing','汤姆没有注意到玛丽换了新发型')]


def require(value, message):
    if not value:raise ValueError(message)


def parse_frames(text, expected_pid, start, end):
    require(type(start) is int and type(end) is int and 0 < start < end, 'Invalid sampling clocks')
    require(f'for pid {expected_pid} [{PROCESS}]' in text, 'Wrong IME process or PID')
    windows = re.findall(r'^Window: (.+)$',text,re.M)
    require([w.strip() for w in windows] == ['InputMethod'], 'Ambiguous or missing IME window')
    chunks = text.split('---PROFILEDATA---')
    require(len(chunks)==3, 'Incomplete or multiple frame streams')
    lines = chunks[1].strip().splitlines()
    require(len(lines)>1, 'Empty frame stream')
    reader = csv.DictReader(lines)
    header = reader.fieldnames
    require(header and len(header)==len(set(header)) and all(c in header for c in REQUIRED), 'Missing/duplicate frame columns')
    parsed=[];seen=set()
    for raw in reader:
        require(None not in raw and all(v is not None for k,v in raw.items() if k), 'Truncated frame row')
        try:row={k:int(v) for k,v in raw.items() if k}
        except ValueError as e:raise ValueError('Noninteger frame timestamp') from e
        identity=(row['IntendedVsync'],row.get('FrameTimelineVsyncId'))
        require(identity not in seen,'Duplicate frame');seen.add(identity)
        parsed.append(row)
    totals=[int(v) for v in re.findall(r'^Total frames rendered: (\d+)',text,re.M)]
    require(len(totals)==2 and totals[0]==totals[1], 'Process/window frame totals disagree')
    # Do not treat a rolling suffix as the complete workload. Also reject a
    # retained earlier epoch, even if filtering would make its values look fast.
    require(totals[1]==len(parsed),'Ring truncated, stale frames, or unrepresented frames')
    require(len(parsed)<120,'Frame ring capacity approached; shorten the workload before interpretation')
    selected=[r for r in parsed if start<=r['IntendedVsync']<=end]
    require(selected,'No frames in the requested window')
    ordinary=[];flagged=[]
    for row in selected:
        if row['Flags']!=0:
            flagged.append(row);continue
        stamps=[row[k] for k in ['IntendedVsync','Vsync','HandleInputStart','AnimationStart',
                                 'PerformTraversalsStart','DrawStart','SyncQueued','SyncStart',
                                 'IssueDrawCommandsStart','SwapBuffers','FrameCompleted']]
        require(all(0<x<MAX_LONG for x in stamps),'Incomplete/sentinel frame completion')
        require(all(a<=b for a,b in zip(stamps,stamps[1:])), 'Reversed frame timestamps')
        ordinary.append(row)
    require(ordinary,'No ordinary frames to summarize')
    report=dict(process=PROCESS,pid=str(expected_pid),window='InputMethod',totalAfterReset=totals[1],
        records=len(parsed),windowRecords=len(selected),outsideWindow=len(parsed)-len(selected),
        ordinaryFrames=len(ordinary),flaggedFrames=len(flagged),completeRing=True,
        components=frame_metrics(ordinary))
    return report,ordinary


def frame_metrics(rows):
    components={
        'hwuiCompletion':('IntendedVsync','FrameCompleted'),
        'scheduledVsyncDelay':('IntendedVsync','Vsync'),
        'uiStartDelay':('Vsync','HandleInputStart'),
        'mainThreadWork':('HandleInputStart','SyncQueued'),
        'viewTraversal':('PerformTraversalsStart','DrawStart'),
        'recordDrawCommands':('DrawStart','SyncQueued'),
        'renderQueueWait':('SyncQueued','SyncStart'),
        'renderAndCompletionWait':('IssueDrawCommandsStart','FrameCompleted'),
    }
    metrics={name:distribution([r[b]-r[a] for r in rows]) for name,(a,b) in components.items()}
    deadlines=[r for r in rows if r['IntendedVsync']<r.get('FrameDeadline',0)<MAX_LONG]
    metrics['reportedDeadline']=dict(available=len(deadlines),missing=len(rows)-len(deadlines),
        completedAfter=sum(r['FrameCompleted']>r['FrameDeadline'] for r in deadlines),
        scope='Per-row HWUI deadline, not a reimplementation of all Android JankTracker rules')
    return metrics


def audit_input(row, expected):
    query,answer=expected
    require(row['query']==query and row['sequence']==query+' ' and row['expected']==answer and row['actual']==answer,
            'Changed or failed input workload')
    source=row['source'];starts=source['keyStartMs']
    require(source['status']=='ok' and source['sourceUid']==2000 and source['targetUid']>=10000,'Wrong event source')
    require(len(starts)==len(query)+1 and all(type(t)is int and t>0 for t in starts),'Missing key timestamps')
    intervals=[b-a for a,b in zip(starts,starts[1:])]
    require(all(t>=32 for t in intervals),'Invalid sender cadence')
    require([(a['keyIndex'],a['action']) for a in source['actions']]==[(i,a) for i in range(len(starts)) for a in [0,1]],
            'Missing/reordered/duplicated touch actions')
    previous=-1
    for a in source['actions']:
        require(a['accepted'] is True and previous<=a['beginNs']<=a['endNs'],'Bad action receipt')
        previous=a['endNs']
    changes=row['editorChanges']
    require(changes and all(a['uptimeMs']<=b['uptimeMs'] for a,b in zip(changes,changes[1:])),'Reversed editor clock')
    prefixes=[c for c in changes if c['text'] and query.startswith(c['text'])]
    require([c['text'] for c in prefixes]==[query[:i] for i in range(1,len(query)+1)],'Lost/reordered raw prefixes')
    require(all(c['uptimeMs']>=starts[i] for i,c in enumerate(prefixes)),'Prefix precedes its touch')
    commits=[c['uptimeMs'] for c in changes if c['text']==answer]
    require(commits and changes[-1]['text']==answer and commits[0]==row['firstExpectedTextMs'],'Missing final editor receipt')
    latency=commits[0]-starts[-1]
    require(0<=latency<=8000,'Bad confirmation clock')
    return intervals,latency,[c['uptimeMs']-starts[i] for i,c in enumerate(prefixes)]


def summarize(directory):
    lock=json.loads((directory/'pre-run-lock.json').read_text('utf-8'))
    execution=json.loads((directory/'execution.json').read_text('utf-8'))
    require(execution['passed'] and not execution['errors'],'Failed device execution')
    require(execution['restoration']['ime']==lock['originalIme'] and execution['restoration']['debugApp']=='null','Incomplete restoration')
    rows=[json.loads(l) for l in (directory/'device/frame-workload.jsonl').read_text('utf-8').splitlines()]
    expected=[(f'typing-{rnd}-{i}','typing',rnd,pair) for rnd in range(2)
              for i,pair in enumerate(CASES if rnd==0 else CASES[::-1])]
    for position,rnd in [(3,0),(7,1)]:expected.insert(position,(f'expanded-{rnd}','expanded',rnd,None))
    require(len(rows)==len(expected),'Incomplete workload windows')
    require(len({r['imePid'] for r in rows})==1,'Process restart inside workload')
    groups={'typing':[],'expanded':[]};samples=[];intervals=[];confirmations=[];prefix_receipts=[]
    for row,(label,kind,rnd,case) in zip(rows,expected):
        require((row['label'],row['kind'],row['round'])==(label,kind,rnd) and row['passed'] is True,'Wrong/failed window')
        require(row['frameFile']==label+'-frames.txt','Unexpected evidence path')
        path=directory/'device'/row['frameFile']
        summary,frames=parse_frames(path.read_text('utf-8'),row['imePid'],row['startNanos'],row['endNanos'])
        if case:
            cadence,latency,prefixes=audit_input(row,case)
            intervals+=cadence;confirmations.append(latency);prefix_receipts+=prefixes
            summary['confirmationMs']=latency
            summary['rawPrefixReceipt']=distribution([x*1_000_000 for x in prefixes])
        else:
            require(bool(row['selectedWord']) and row['selectedWord']==row['actual'],'Scrolled selection mismatch')
        groups[kind]+=frames
        samples.append(dict(label=label,kind=kind,frameFileSha256=digest(path),**summary))
    return dict(stage='E40',scope=lock['scope'],productApkSha256=lock['productApkSha256'],
        captureIntegrityPassed=True,functionalWindows=len(rows),samples=samples,
        typing=frame_metrics(groups['typing']),expanded=frame_metrics(groups['expanded']),
        confirmation=distribution([x*1_000_000 for x in confirmations]),
        rawPrefixReceipt=distribution([x*1_000_000 for x in prefix_receipts]),
        senderIntervals=distribution([x*1_000_000 for x in intervals]),
        productionChanged=False,productImprovementClaim=False,
        limitations=['One dedicated API37 emulator; graphics backend/host scheduling affect completion.',
            'Warm ready runtime, no personal learning; not cold-start or other Android/OEM coverage.',
            'Frames within a window are correlated; frame percentiles are not per-key latency percentiles.',
            'HWUI FrameCompleted is not guaranteed physical display presentation or input-to-photon time.'])


if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('directory',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();report=summarize(a.directory);write_new(a.output,report)
    print(json.dumps({k:report[k] for k in ['captureIntegrityPassed','functionalWindows','typing','expanded','confirmation']},indent=2))
