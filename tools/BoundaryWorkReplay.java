import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Instrumentation only: deterministic calls and complete outputs, never wall-clock claims. */
class BoundaryWorkReplay {
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    static final class CountedBoundary implements CharacterBigramModel {
        final CharacterBigramModel delegate;
        long calls;
        final Set<Long> pairs = new HashSet<>();
        CountedBoundary(CharacterBigramModel delegate) { this.delegate = delegate; }
        public float score(int previous, int next) {
            calls++;
            pairs.add(((long) previous << 32) | (next & 0xffffffffL));
            return delegate.score(previous, next);
        }
        public List<CharacterBigramSuccessor> successors(int previous, int limit) {
            return delegate.successors(previous, limit);
        }
    }
    static final class CountedLanguage implements CharacterLanguageModel {
        final CharacterLanguageModel delegate;
        long probabilityCalls, membershipCalls;
        CountedLanguage(CharacterLanguageModel delegate) { this.delegate = delegate; }
        public float logProbability(int a, int b, int c) {
            probabilityCalls++; return delegate.logProbability(a, b, c);
        }
        public float unigramLogProbability(int c) { return delegate.unigramLogProbability(c); }
        public boolean containsCodePoint(int c) {
            membershipCalls++; return delegate.containsCodePoint(c);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<fixed four-column replay> <new tsv>");
        Path input=Path.of(args[0]), output=Path.of(args[1]);
        Path assets=Path.of("ime-service/src/main/assets");
        CountedBoundary boundary;
        CountedLanguage language;
        try (var f=Files.newInputStream(assets.resolve("pinyin_bigrams.bin"))) {
            boundary=new CountedBoundary(BinaryCharacterBigramModel.Companion.load(f));
        }
        try (var f=Files.newInputStream(assets.resolve("pinyin_character_lm.scng"))) {
            language=new CountedLanguage(BinaryCharacterLanguageModel.Companion.load(f));
        }
        PinyinDecoder base;
        try (var f=Files.newInputStream(assets.resolve("pinyin_lexicon.bin"))) {
            base=PinyinDecoder.Companion.load(f,boundary,CorrectionSearchBudget.Companion.getPRODUCTION())
                .withLanguageModel(language,.5f,-4f);
        }
        EnglishLexicon english;
        try (var f=Files.newInputStream(assets.resolve("english_lexicon.txt"))) {
            english=EnglishLexicon.Companion.load(f,20_000,EnglishWordUsageStore.Companion.getEMPTY());
        }
        var decoder=new AdaptivePinyinDecoder(base,new MemoryUserLexicon(),
            new PinyinSyllableSegmenter(Files.readAllLines(assets.resolve("pinyin_syllables.txt"))),english);
        try (var writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            writer.write("# inputSha256="+sha(Files.readAllBytes(input))+"\n");
            writer.write("id\tscope\tquery\tcontext\tresultSha256\tboundaryCalls\tdistinctBoundaryPairs\tprobabilityCalls\tmembershipCalls\n");
            int count=0;
            for (String line:Files.readAllLines(input)) {
                if (line.isBlank()||line.startsWith("#")) continue;
                var f=line.split("\t",-1);
                if (f.length!=4||!f[2].matches("[a-z']{1,192}")) throw new IllegalArgumentException("Invalid fixed query");
                boundary.calls=0;boundary.pairs.clear();language.probabilityCalls=0;language.membershipCalls=0;
                var result=decoder.decodeProgressively(new PinyinComposition(List.of(),f[2],0),f[3],255);
                writer.write(line+"\t"+sha(result.toString().getBytes(StandardCharsets.UTF_8))+"\t"+
                    boundary.calls+"\t"+boundary.pairs.size()+"\t"+language.probabilityCalls+"\t"+language.membershipCalls+"\n");
                if (++count%500==0) System.out.println("Observed "+count+" complete results");
            }
            System.out.println("Finished "+count+" states");
        }
    }
}
