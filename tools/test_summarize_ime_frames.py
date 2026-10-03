import copy
import unittest
from summarize_ime_frames import MAX_LONG, PROCESS, REQUIRED, audit_input, parse_frames


def frame(start=100_000_000,flags=0):
    values=dict(zip(REQUIRED,[flags]+[start+i*100_000 for i in range(len(REQUIRED)-1)]))
    values['FrameCompleted']=start+5_000_000
    values['FrameDeadline']=start+4_000_000
    return values


def dump(rows,total=None,pid='123',window='InputMethod',process=PROCESS):
    header=list(rows[0])
    total=len(rows) if total is None else total
    return (f'** Graphics info for pid {pid} [{process}] **\nTotal frames rendered: {total}\n'
        f'Window: {window}\nTotal frames rendered: {total}\n---PROFILEDATA---\n'+
        ','.join(header)+',\n'+'\n'.join(','.join(str(r[k]) for k in header)+',' for r in rows)+
        '\n---PROFILEDATA---\n')


class FrameParsingTests(unittest.TestCase):
    def parse(self,text,start=90_000_000,end=200_000_000):
        return parse_frames(text,'123',start,end)

    def test_actual_column_names_and_nanosecond_units(self):
        report,rows=self.parse(dump([frame()]))
        self.assertEqual(report['components']['hwuiCompletion']['medianMs'],5.)
        self.assertEqual(report['components']['reportedDeadline']['completedAfter'],1)
        self.assertEqual(report['ordinaryFrames'],1);self.assertTrue(report['completeRing'])

    def test_column_reordering_and_extra_columns(self):
        row=frame();row['FutureColumn']=45;row=dict(reversed(list(row.items())))
        self.assertEqual(self.parse(dump([row]))[0]['ordinaryFrames'],1)

    def test_wrong_process_or_pid_rejected(self):
        for text in [dump([frame()],pid='124'),dump([frame()],process='external.fixture')]:
            with self.assertRaisesRegex(ValueError,'process'):self.parse(text)

    def test_non_ime_or_multiple_windows_rejected(self):
        for text in [dump([frame()],window='Settings'),dump([frame()])+'Window: InputMethod\n']:
            with self.assertRaisesRegex(ValueError,'window'):self.parse(text)

    def test_missing_or_multiple_stream_markers_rejected(self):
        for text in [dump([frame()]).replace('---PROFILEDATA---','',1),dump([frame()])*2]:
            with self.assertRaises(ValueError):self.parse(text)

    def test_missing_or_duplicate_required_columns_rejected(self):
        missing=frame();del missing['SyncQueued']
        for text in [dump([missing]),dump([frame()]).replace('Flags,','Flags,Flags,',1)]:
            with self.assertRaises(ValueError):self.parse(text)

    def test_partial_noninteger_and_duplicate_rows_rejected(self):
        with self.assertRaisesRegex(ValueError,'Duplicate frame'):self.parse(dump([frame(),frame()]))
        value=frame();value['DrawStart']='invalid'
        with self.assertRaisesRegex(ValueError,'Noninteger'):self.parse(dump([value]))
        text=dump([frame()]);lines=text.splitlines();lines[-2]=lines[-2].split(',')[0]
        with self.assertRaisesRegex(ValueError,'Truncated'):self.parse('\n'.join(lines))

    def test_ring_suffix_is_not_treated_as_complete(self):
        with self.assertRaisesRegex(ValueError,'Ring truncated'):self.parse(dump([frame()],total=10))

    def test_ring_capacity_conservatively_rejected(self):
        rows=[frame(100_000_000+i*10_000_000) for i in range(120)]
        with self.assertRaisesRegex(ValueError,'capacity'):self.parse(dump(rows),end=2_000_000_000)

    def test_flagged_frames_counted_but_not_in_ordinary_distribution(self):
        report,_=self.parse(dump([frame(),frame(110_000_000,1)]))
        self.assertEqual(report['flaggedFrames'],1);self.assertEqual(report['ordinaryFrames'],1)
        with self.assertRaisesRegex(ValueError,'No ordinary'):self.parse(dump([frame(flags=1)]))

    def test_sentinel_and_reversed_timestamps_rejected(self):
        for field,value in [('FrameCompleted',MAX_LONG),('FrameCompleted',0),('SyncQueued',1)]:
            row=frame();row[field]=value
            with self.assertRaises(ValueError):self.parse(dump([row]))

    def test_sampling_window_excludes_other_timestamps_explicitly(self):
        report,_=self.parse(dump([frame(80_000_000),frame(100_000_000)]))
        self.assertEqual(report['outsideWindow'],1);self.assertEqual(report['ordinaryFrames'],1)
        with self.assertRaisesRegex(ValueError,'No frames'):self.parse(dump([frame(1)]))

    def test_invalid_sampling_clocks_and_counter_mismatch(self):
        with self.assertRaisesRegex(ValueError,'clocks'):self.parse(dump([frame()]),start=3,end=2)
        text=dump([frame()]).replace('Total frames rendered: 1','Total frames rendered: 2',1)
        with self.assertRaisesRegex(ValueError,'totals disagree'):self.parse(text)

    def test_missing_deadline_is_not_assumed_met(self):
        row=frame();del row['FrameDeadline']
        report,_=self.parse(dump([row]))
        self.assertEqual(report['components']['reportedDeadline']['missing'],1)


class InputClockTests(unittest.TestCase):
    def setUp(self):
        self.row=dict(query='nihao',sequence='nihao ',expected='你好',actual='你好',firstExpectedTextMs=1250,
            source=dict(status='ok',sourceUid=2000,targetUid=10100,keyStartMs=[1000+33*i for i in range(6)],
                        actions=[dict(keyIndex=i,action=a,accepted=True,beginNs=100+i*20+a*5,endNs=101+i*20+a*5)
                                 for i in range(6) for a in [0,1]]),
            editorChanges=[dict(uptimeMs=1010+i*33,text='nihao'[:i+1]) for i in range(5)]+[dict(uptimeMs=1250,text='你好')])

    def test_separates_prefix_receipts_from_final_confirmation(self):
        cadence,latency,prefixes=audit_input(self.row,('nihao','你好'))
        self.assertEqual(cadence,[33]*5);self.assertEqual(latency,85);self.assertEqual(prefixes,[10]*5)

    def test_missing_or_reordered_raw_prefix_rejected(self):
        for edit in ['missing','reordered']:
            row=copy.deepcopy(self.row)
            if edit=='missing':del row['editorChanges'][2]
            else:row['editorChanges'][0],row['editorChanges'][1]=row['editorChanges'][1],row['editorChanges'][0]
            with self.assertRaises(ValueError):audit_input(row,('nihao','你好'))

    def test_clock_cadence_or_action_loss_rejected(self):
        for edit in ['negative','fast','action']:
            row=copy.deepcopy(self.row)
            if edit=='negative':row['editorChanges'][0]['uptimeMs']=990
            if edit=='fast':row['source']['keyStartMs'][1]=1001
            if edit=='action':row['source']['actions'].pop()
            with self.assertRaises(ValueError):audit_input(row,('nihao','你好'))

    def test_failed_output_or_changed_workload_rejected(self):
        for key in ['actual','query','sequence']:
            row=copy.deepcopy(self.row);row[key]='changed'
            with self.assertRaises(ValueError):audit_input(row,('nihao','你好'))


if __name__=='__main__':unittest.main()
