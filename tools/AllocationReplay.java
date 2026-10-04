import io.github.ethanbird.senseime.core.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JVM current-thread allocation estimates around synchronous decoding, not a latency benchmark. */
class AllocationReplay {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<fixed replay> <new tsv>");
        var platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Current-thread allocation counter required");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().getId();
        if (bean.getThreadAllocatedBytes(threadId) < 0) throw new IllegalStateException("Counter inactive");
        Path input=Path.of(args[0]), output=Path.of(args[1]);
        Path assets=Path.of("ime-service/src/main/assets");
        BinaryCharacterBigramModel bigram;
        BinaryCharacterLanguageModel model;
        try (var s=Files.newInputStream(assets.resolve("pinyin_bigrams.bin"))) {
            bigram=BinaryCharacterBigramModel.Companion.load(s);
        }
        try (var s=Files.newInputStream(assets.resolve("pinyin_character_lm.scng"))) {
            model=BinaryCharacterLanguageModel.Companion.load(s);
        }
        var counted=new EmissionMemoReplay.CountedModel(model);
        PinyinDecoder base;
        try (var s=Files.newInputStream(assets.resolve("pinyin_lexicon.bin"))) {
            base=PinyinDecoder.Companion.load(s,bigram,CorrectionSearchBudget.Companion.getPRODUCTION())
                .withLanguageModel(counted,.5f,-4f);
        }
        EnglishLexicon english;
        try (var s=Files.newInputStream(assets.resolve("english_lexicon.txt"))) {
            english=EnglishLexicon.Companion.load(s,20_000,EnglishWordUsageStore.Companion.getEMPTY());
        }
        var decoder=new AdaptivePinyinDecoder(base,new MemoryUserLexicon(),
            new PinyinSyllableSegmenter(Files.readAllLines(assets.resolve("pinyin_syllables.txt"))),english);
        try (var writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            writer.write("# inputSha256="+EmissionMemoReplay.sha(Files.readAllBytes(input))+"\n");
            writer.write("id\tscope\tquery\tcontext\tresultSha256\tprobabilityCalls\tmembershipCalls\tdecoderAllocatedBytes\n");
            int count=0;
            for (String line:Files.readAllLines(input)) {
                if (line.isBlank()||line.startsWith("#")) continue;
                var f=line.split("\t",-1);
                if (f.length!=4||!f[2].matches("[a-z']{1,192}")) throw new IllegalArgumentException("Invalid query");
                counted.probabilityCalls=0;counted.membershipCalls=0;
                long before=bean.getThreadAllocatedBytes(threadId);
                var result=decoder.decodeProgressively(new PinyinComposition(List.of(),f[2],0),f[3],255);
                long bytes=bean.getThreadAllocatedBytes(threadId)-before;
                if (bytes<0) throw new IllegalStateException("Invalid allocation measurement");
                writer.write(line+"\t"+EmissionMemoReplay.sha(result.toString().getBytes(StandardCharsets.UTF_8))+"\t"+
                    counted.probabilityCalls+"\t"+counted.membershipCalls+"\t"+bytes+"\n");
                if (++count%500==0) System.out.println("Allocated replay: "+count);
            }
            System.out.println("Finished "+count+" states");
        }
    }
}
