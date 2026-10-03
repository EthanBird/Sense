import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.*;
import java.util.*;

/** Compare real base/full view record IDs against the unchanged exact binary lookup. */
class PrefixLookupAudit {
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        if (Files.exists(out)) throw new IllegalArgumentException("Retain earlier evidence");
        Path assets = Path.of("ime-service/src/main/assets");
        byte[] bytes = Files.readAllBytes(assets.resolve("pinyin_lexicon.bin"));
        var base = PinyinDecoder.Companion.fromBytes(bytes, CharacterBigramModel.Companion.getEMPTY(),
            CorrectionSearchBudget.Companion.getPRODUCTION());
        BinaryCharacterLanguageModel lm;
        try (var stream = Files.newInputStream(assets.resolve("pinyin_character_lm.scng"))) {
            lm = BinaryCharacterLanguageModel.Companion.load(stream);
        }
        var layered = base.withLanguageModel(lm, .5f, -4f);
        var exact = PinyinDecoder.class.getDeclaredMethod("findExact", String.class, int.class, int.class);
        exact.setAccessible(true);
        try (var writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("# lexiconSha256=" + sha(bytes) + "\nview\trecords\tqueries\texactComparisons\n");
            for (var pair : List.of(Map.entry("base", base), Map.entry("layered", layered))) {
                var decoder = pair.getValue();
                var data = (byte[])field(decoder, "data");
                var offsets = (int[])field(field(decoder, "activeIndex"), "offsets");
                long queries = 0, comparisons = 0;
                // Fixed systematic sample, not a quality-label-selected subset.
                for (int index = 0; index < offsets.length; index += 43) {
                    int offset = offsets[index], length = data[offset] & 255;
                    String code = new String(data, offset + 1, length, StandardCharsets.US_ASCII);
                    for (String query : List.of(code, "x" + code + "zz", code + "xy")) {
                        int start = query.startsWith("x" + code + "zz") ? 1 : 0;
                        int end = Math.min(query.length(), start + 24);
                        var records = SortedPinyinPrefixLookupKt.sortedPinyinPrefixRecords(data, offsets, query, start, end);
                        if (records.length != end - start + 1 || records[0] != -1) throw new AssertionError("Bad prefix shape");
                        for (int size = 1; size < records.length; size++) {
                            int expected = (int)exact.invoke(decoder, query, start, start + size);
                            if (records[size] != expected) throw new AssertionError(pair.getKey() + ":" + index + ":" + size);
                            comparisons++;
                        }
                        queries++;
                    }
                }
                writer.write(pair.getKey() + "\t" + offsets.length + "\t" + queries + "\t" + comparisons + "\n");
            }
        }
    }
}
