import unittest
from summarize_pinyin_cancellation import parse_trace


class TraceClassificationTest(unittest.TestCase):
    def test_interleaved_threads_and_nested_cancel_marker(self):
        def event(tid, time, content):
            return f"worker-{tid} ( 123) [000] ..... {time}: tracing_mark_write: {content}"
        data = "# entries-in-buffer/entries-written: 10/10\n" + "\n".join([
            event(1, "1.000", "B|123|Sense.Pinyin.decode"),
            event(2, "1.010", "B|123|Sense.Pinyin.decode"),
            event(1, "1.020", "B|123|Sense.Pinyin.canceled"),
            event(1, "1.021", "E|123"), event(1, "1.022", "E|123"),
            event(2, "1.050", "E|123"),
        ])
        parsed = parse_trace(data)
        self.assertTrue(parsed["completeTraceBuffers"])
        self.assertEqual(0, parsed["unfinishedDecodeSlices"])
        self.assertEqual([True, False], [s["canceled"] for s in parsed["decodeSlices"]])
        self.assertEqual([22.0, 40.0], [s["durationMs"] for s in parsed["decodeSlices"]])

    def test_unfinished_decode_and_buffer_loss_are_visible(self):
        parsed = parse_trace("# entries-in-buffer/entries-written: 1/10\n"
                             "worker-1 (123) [000] ..... 1.000: tracing_mark_write: B|123|Sense.Pinyin.decode")
        self.assertFalse(parsed["completeTraceBuffers"])
        self.assertEqual(1, parsed["unfinishedDecodeSlices"])
        self.assertEqual([], parsed["decodeSlices"])

    def test_no_instrumentation_is_missing_not_a_fast_decode(self):
        self.assertEqual([], parse_trace("# entries-in-buffer/entries-written: 0/0\n")["decodeSlices"])


if __name__ == "__main__":
    unittest.main()
