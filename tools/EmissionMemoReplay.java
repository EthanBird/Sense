import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Host-only deterministic call counts and full results, never a latency benchmark. */
class EmissionMemoReplay {
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    static final class CountedModel implements CharacterLanguageModel {
        final CharacterLanguageModel delegate;
        long probabilityCalls, membershipCalls;
        CountedModel(CharacterLanguageModel delegate) { this.delegate = delegate; }
        public float logProbability(int a, int b, int c) {
            probabilityCalls++; return delegate.logProbability(a, b, c);
        }
        public float unigramLogProbability(int c) { return delegate.unigramLogProbability(c); }
        public boolean containsCodePoint(int c) { membershipCalls++; return delegate.containsCodePoint(c); }
    }
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Retain prior evidence");
        Path assets = Path.of("ime-service/src/main/assets");
        BinaryCharacterBigramModel bigram;
        BinaryCharacterLanguageModel model;
        PinyinDecoder base;
        EnglishLexicon english;
        try (var s = Files.newInputStream(assets.resolve("pinyin_bigrams.bin"))) {
            bigram = BinaryCharacterBigramModel.Companion.load(s);
        }
        try (var s = Files.newInputStream(assets.resolve("pinyin_character_lm.scng"))) {
            model = BinaryCharacterLanguageModel.Companion.load(s);
        }
        var counted = new CountedModel(model);
        try (var s = Files.newInputStream(assets.resolve("pinyin_lexicon.bin"))) {
            base = PinyinDecoder.Companion.load(s, bigram, CorrectionSearchBudget.Companion.getPRODUCTION())
                .withLanguageModel(counted, .5f, -4f);
        }
        try (var s = Files.newInputStream(assets.resolve("english_lexicon.txt"))) {
            english = EnglishLexicon.Companion.load(s, 20_000, EnglishWordUsageStore.Companion.getEMPTY());
        }
        var decoder = new AdaptivePinyinDecoder(base, new MemoryUserLexicon(),
            new PinyinSyllableSegmenter(Files.readAllLines(assets.resolve("pinyin_syllables.txt"))), english);
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("# inputSha256=" + sha(Files.readAllBytes(input)) + "\n");
            writer.write("id\tscope\tquery\tcontext\tresultSha256\tprobabilityCalls\tmembershipCalls\n");
            int count = 0;
            for (String line : Files.readAllLines(input)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                var f = line.split("\t", -1);
                if (f.length != 4 || !f[2].matches("[a-z']{1,192}")) throw new IllegalArgumentException("Unexpected query");
                counted.probabilityCalls = 0; counted.membershipCalls = 0;
                var result = decoder.decodeProgressively(new PinyinComposition(List.of(), f[2], 0), f[3], 255);
                writer.write(line + "\t" + sha(result.toString().getBytes(StandardCharsets.UTF_8)) + "\t" +
                    counted.probabilityCalls + "\t" + counted.membershipCalls + "\n");
                if (++count % 500 == 0) System.out.println("Full results: " + count);
            }
            System.out.println("Finished " + count + " full progressive results");
        }
    }
}
