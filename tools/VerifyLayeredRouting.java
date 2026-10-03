import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

/** Host-only whole-result equivalence, not input quality or a new held-out corpus. */
class VerifyLayeredRouting {
    static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]), current = Path.of(args[1]), assets = Path.of(args[2]), report = Path.of(args[3]);
        if (Files.exists(report)) throw new IllegalArgumentException("Retain prior reports");
        var big = BinaryCharacterBigramModel.Companion.load(Files.newInputStream(assets.resolve("pinyin_bigrams.bin")));
        var words = Files.readAllLines(assets.resolve("pinyin_syllables.txt"));
        var segmenter = new PinyinSyllableSegmenter(words);
        var english = EnglishLexicon.Companion.getEMPTY();
        var old = new AdaptivePinyinDecoder(PinyinDecoder.Companion.load(Files.newInputStream(base), big,
            CorrectionSearchBudget.Companion.getPRODUCTION()), new MemoryUserLexicon(), segmenter, english);
        var now = new AdaptivePinyinDecoder(PinyinDecoder.Companion.load(Files.newInputStream(current), big,
            CorrectionSearchBudget.Companion.getPRODUCTION()), new MemoryUserLexicon(), segmenter, english);
        var out = new ArrayList<String>();
        out.add("# baseSha256=" + sha(base)); out.add("# layeredSha256=" + sha(current));
        out.add("kind\tquery\tcompleteResultEqual\tfirst");
        var queries = new LinkedHashSet<>(List.of("nihao", "ni'hao", "yisscp", "hunshenxs", "zhinengti", "xiaszf", "dancs", "dagn", "nihaoshijiee"));
        for (var line : Files.readAllLines(Path.of("benchmarks/replay/vocabulary-audit-v2/test.tsv")))
            if (!line.startsWith("#") && !line.isBlank()) queries.add(line.split("\t")[2]);
        for (var q : queries) {
            var a = old.decode(q, 255); var b = now.decode(q, 255);
            out.add("legacy\t" + q + "\t" + a.equals(b) + "\t" + (b.isEmpty() ? "" : b.get(0).getText()));
            if (!a.equals(b)) throw new AssertionError("Legacy result changed: " + q);
        }
        var index = new T9SyllableIndex(words);
        var digits = new ArrayList<>(List.of("486", "64426", "64'426", "94664936", "7487832", "486743697", "7426".repeat(8), "7426".repeat(24)));
        var random = new Random(20261003L);
        for (int i = 0; i < 96; i++) {
            var text = new StringBuilder();
            for (int j = 0, n = 3 + random.nextInt(10); j < n; j++) text.append((char)('2' + random.nextInt(8)));
            digits.add(text.toString());
        }
        for (var q : digits) {
            var c = new T9Composition();
            for (char key : q.toCharArray()) c = key == '\'' ? c.forceJoint() : c.typeDigit(key);
            var a = T9AlternativeInputDecoder.INSTANCE.decode(c, index, old, "", 64, () -> true);
            var b = T9AlternativeInputDecoder.INSTANCE.decode(c, index, now, "", 64, () -> true);
            out.add("t9\t" + q + "\t" + a.equals(b) + "\t" + (b.getCandidates().isEmpty() ? "" : b.getCandidates().get(0).getText()));
            if (!a.equals(b)) throw new AssertionError("T9 result/provenance changed: " + q);
        }
        Files.write(report, out);
        System.out.println("Complete-result equality passed: legacy=" + queries.size() + ", T9=" + digits.size());
    }
}
