import struct
import unittest
from summarize_art_profile import summarize


class ArtProfileTest(unittest.TestCase):
    def trace(self):
        header = ("*version\n3\nclock=dual\ndata-file-overflow=false\nis_streaming=false\n"
                  "*threads\n16\tsense-candidate-decoder\n*methods\n"
                  "0x4\tio.github.ethanbird.senseime.core.AdaptivePinyinDecoder\tdecodeProgressively\t()V\tTest.kt\n"
                  "0x8\tTest\tchild\t()V\tTest.kt\n0xc\tTest\tleaf\t()V\tTest.kt\n*end\n").encode()
        binary = struct.pack("<4sHHQH", b"SLOW", 3, 32, 0, 14) + bytes(14)
        return header + binary + b"".join(struct.pack("<HIII", 16, method, cpu, cpu + 100)
                                          for method, cpu in [(4, 0), (8, 10), (12, 15), (13, 25), (9, 40), (5, 60)])

    def test_exclusive_and_inclusive_clock_accounting(self):
        result = summarize(self.trace())
        self.assertEqual(60, result["observedDecodeCpuUs"])
        self.assertEqual(20, dict(result["exclusiveCpuUs"])["Test.child"])
        self.assertEqual(30, dict(result["inclusiveCpuUs"])["Test.child"])
        self.assertEqual(10, dict(result["exclusiveCpuUs"])["Test.leaf"])

    def test_overflow_truncation_and_broken_stack_are_rejected(self):
        for data in [self.trace().replace(b"overflow=false", b"overflow=true"), self.trace()[:-1],
                     self.trace()[:-14] + struct.pack("<HIII", 16, 9, 60, 160)]:
            with self.assertRaises(ValueError):
                summarize(data)

    def test_full_export_retains_methods_below_the_display_limit(self):
        data = self.trace()
        end = data.index(b"*end\n") + 5
        header = data[:end].replace(b"*end\n", b"".join(
            f"0x{16+4*i:x}\tTest\tchild{i}\t()V\tTest.kt\n".encode() for i in range(60)) + b"*end\n")
        events = [(4, 0)]
        for i in range(60):
            events += [(16+4*i, 2*i+1), (17+4*i, 2*i+2)]
        events.append((5, 121))
        binary = data[end:end+32] + b"".join(struct.pack("<HIII", 16, method, cpu, cpu+100) for method,cpu in events)
        default = summarize(header + binary)
        full = summarize(header + binary, include_all_methods=True)
        self.assertEqual(30, len(default["exclusiveCpuUs"]))
        self.assertEqual(50, len(default["inclusiveCpuUs"]))
        self.assertEqual(61, len(full["exclusiveCpuUs"]))
        self.assertEqual(61, len(full["inclusiveCpuUs"]))
        self.assertEqual(default["observedDecodeCpuUs"], sum(n for _,n in full["exclusiveCpuUs"]))


if __name__ == "__main__":
    unittest.main()
