import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import kotlin.Unit;

/** Host diagnostic: labels are inspected only after ordinary production decoding. */
class MixedRecallReplay {
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    public static void main(String[] args) throws Exception {
        Path lexicon = Path.of(args[0]), replay = Path.of(args[1]), out = Path.of(args[2]);
        if (Files.exists(out)) throw new IllegalArgumentException("Retain prior evidence");
        Path assets = Path.of("ime-service/src/main/assets");
        var big = BinaryCharacterBigramModel.Companion.load(Files.newInputStream(assets.resolve("pinyin_bigrams.bin")));
        var lm = BinaryCharacterLanguageModel.Companion.load(Files.newInputStream(assets.resolve("pinyin_character_lm.scng")));
        var base = PinyinDecoder.Companion.load(Files.newInputStream(lexicon), big, CorrectionSearchBudget.Companion.getPRODUCTION())
            .withLanguageModel(lm, .5f, -4f);
        var decoder = new AdaptivePinyinDecoder(base, new MemoryUserLexicon(),
            new PinyinSyllableSegmenter(Files.readAllLines(assets.resolve("pinyin_syllables.txt"))),
            EnglishLexicon.Companion.load(Files.newInputStream(assets.resolve("english_lexicon.txt")), 20_000, EnglishWordUsageStore.Companion.getEMPTY()));
        try (var w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            w.write("# lexiconSha256=" + sha(Files.readAllBytes(lexicon)) + "\n# inputSha256=" + sha(Files.readAllBytes(replay)) + "\n");
            w.write("id\tstratum\tmode\tquery\texpected\trank\ttop1\ttop10\twholeCount\tprefixCount\tresultSha256\tdecodeNs\n");
            int count = 0;
            for (String line : Files.readAllLines(replay)) {
                if (line.startsWith("#") || line.isBlank()) continue;
                var f = line.split("\t");
                var composition = new PinyinComposition(List.of(), f[3], 0);
                long start = System.nanoTime();
                var result = decoder.decodeProgressively(composition, "", 255);
                long elapsed = System.nanoTime() - start;
                var values = result.getWholeCandidates();
                int rank = 0;
                for (int i = 0; i < values.size(); i++) if (values.get(i).getText().equals(f[4])) { rank = i + 1; break; }
                String top1 = values.isEmpty() ? "" : values.get(0).getText();
                String top10 = String.join("|", values.stream().limit(10).map(Candidate::getText).toList());
                w.write(String.join("\t", f[0], f[1], f[2], f[3], f[4], Integer.toString(rank), top1, top10,
                    Integer.toString(values.size()), Integer.toString(result.getPrefixCandidates().size()),
                    sha(result.toString().getBytes(StandardCharsets.UTF_8)), Long.toString(elapsed)) + "\n");
                if (++count % 200 == 0) System.out.println("Replayed " + count);
            }
            System.out.println("Completed " + count + " retrieval rows");
        }
    }
}
