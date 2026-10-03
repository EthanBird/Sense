import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Paired oracle-prefix diagnostic from reviewed P2C rows; not a natural typing benchmark. */
class ContextReplay {
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Retain existing evidence");
        Path a = Path.of("ime-service/src/main/assets");
        var b = BinaryCharacterBigramModel.Companion.load(Files.newInputStream(a.resolve("pinyin_bigrams.bin")));
        var m = BinaryCharacterLanguageModel.Companion.load(Files.newInputStream(a.resolve("pinyin_character_lm.scng")));
        var base = PinyinDecoder.Companion.load(Files.newInputStream(a.resolve("pinyin_lexicon.bin")), b,
            CorrectionSearchBudget.Companion.getPRODUCTION()).withLanguageModel(m, .5f, -4f);
        var d = new AdaptivePinyinDecoder(base, new MemoryUserLexicon(),
            new PinyinSyllableSegmenter(Files.readAllLines(a.resolve("pinyin_syllables.txt"))),
            EnglishLexicon.Companion.load(Files.newInputStream(a.resolve("english_lexicon.txt")),
                20_000, EnglishWordUsageStore.Companion.getEMPTY()));
        try (var w = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            w.write("# inputSha256=" + sha(Files.readAllBytes(input)) + "\n");
            for (String name : List.of("pinyin_lexicon.bin", "pinyin_bigrams.bin", "pinyin_character_lm.scng", "pinyin_syllables.txt", "english_lexicon.txt"))
                w.write("# " + name + "=" + sha(Files.readAllBytes(a.resolve(name))) + "\n");
            w.write("id\tcut\tstratum\tmode\tcontext\tquery\texpected\trank\ttop1\ttop5\tresultSha256\n");
            int count = 0;
            for (String line : Files.readAllLines(input)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                var f = line.split("\t", -1);
                if (f.length != 5) throw new IllegalArgumentException("Expected reviewed five-column P2C row");
                var units = f[4].split(" ");
                var chars = f[2].codePoints().toArray();
                if (units.length != chars.length || !String.join("", units).equals(f[1]) || f[1].length() > 96)
                    throw new IllegalArgumentException("Changed reviewed label alignment");
                for (int cut=1; cut<chars.length; ++cut) {
                    var context = new String(chars, Math.max(0, cut-2), Math.min(2, cut));
                    var expected = new String(chars, cut, chars.length-cut);
                    var query = String.join("", Arrays.copyOfRange(units, cut, units.length));
                    for (String mode : List.of("empty", "editor", "boundary", "accepted")) {
                        var prefix = mode.equals("empty") ? "" : mode.equals("boundary") ? context + "。" : context;
                        List<AcceptedPinyinSegment> accepted = List.of();
                        if (mode.equals("accepted")) {
                            prefix = cut > 1 ? new String(chars, cut-2, 1) : "";
                            accepted = List.of(new AcceptedPinyinSegment(new String(chars, cut-1, 1), units[cut-1]));
                        }
                        var result = d.decodeProgressively(new PinyinComposition(accepted, query, 0), prefix, 255);
                        var candidates = result.getWholeCandidates();
                        int rank = 0;
                        for(int i=0; i<candidates.size(); ++i) if(candidates.get(i).getText().equals(expected)) {rank=i+1;break;}
                        w.write(String.join("\t", f[0], ""+cut, f[3], mode, context, query, expected, ""+rank,
                            candidates.isEmpty()?"":candidates.get(0).getText(),
                            String.join("|", candidates.stream().limit(5).map(Candidate::getText).toList()),
                            sha(result.toString().getBytes(StandardCharsets.UTF_8))) + "\n");
                    }
                    count++;
                }
            }
            System.out.println("Replayed " + count + " suffixes in four paired modes");
        }
    }
}
