import io.github.ethanbird.senseime.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.PrintWriter;
import kotlin.Unit;

/** Actual-decoder short/partial/repeat/personal-word controls; no Android UI claims. */
class VerifyFourgramInteraction {
    static final int LIMIT=255;
    static void check(boolean ok,String why) {if(!ok)throw new IllegalStateException(why);}
    static MemoryUserLexicon store(Collection<LearnedPhrase> rows) {
        return new MemoryUserLexicon(rows,()->1000L,r->Unit.INSTANCE,(p,t)->Unit.INSTANCE,
            MemoryUserLexicon.DEFAULT_MAXIMUM_RECORDS,MemoryUserLexicon.DEFAULT_MAXIMUM_RECORDS_PER_FULL_PINYIN,
            MemoryUserLexicon.DEFAULT_MAXIMUM_ALIASES_PER_RECORD,MemoryUserLexicon.DEFAULT_MAXIMUM_RECORDS_PER_LOOKUP_CODE);
    }
    static String q(String s) {return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    static String texts(List<Candidate> rows) {return "["+String.join(",",rows.stream().map(c->q(c.getText())).toList())+"]";}
    static void valid(ProgressivePinyinDecoding r,int limit) {
        var c=r.getWholeCandidates();check(c.size()<=limit,"Candidate limit");
        check(c.stream().map(Candidate::getText).distinct().count()==c.size(),"Duplicate candidate");
        check(c.stream().allMatch(x->!x.getText().isEmpty()&&Float.isFinite(x.getScore())),"Invalid candidate");
    }
    static Candidate find(List<Candidate> rows,String word) {
        return rows.stream().filter(c->c.getText().equals(word)).findFirst().orElse(null);
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),assets=root.resolve("ime-service/src/main/assets"),out=Path.of(args[2]);
        check(!Files.exists(out),"Preserve evidence");
        var baseBytes=Files.readAllBytes(assets.resolve("pinyin_character_lm.scng"));
        CharacterLanguageModel[] models={BinaryCharacterLanguageModel.Companion.fromBytes(baseBytes),
            BinaryFourgramLanguageModel.Companion.fromBytes(baseBytes,Files.readAllBytes(Path.of(args[1])))};
        var base=PinyinDecoder.Companion.fromBytes(Files.readAllBytes(assets.resolve("pinyin_lexicon.bin")),
            BinaryCharacterBigramModel.Companion.fromBytes(Files.readAllBytes(assets.resolve("pinyin_bigrams.bin"))),
            CorrectionSearchBudget.Companion.getPRODUCTION());
        var syllables=Files.readAllLines(assets.resolve("pinyin_syllables.txt"));
        var segmenter=new PinyinSyllableSegmenter(syllables);
        EnglishLexicon english;
        try(var stream=Files.newInputStream(assets.resolve("english_lexicon.txt"))) {
            english=EnglishLexicon.Companion.load(stream,80000,EnglishWordUsageStore.Companion.getEMPTY());
        }
        var inputs=new LinkedHashSet<String>();
        for(char c='a';c<='z';c++)inputs.add(String.valueOf(c));
        for(var s:syllables)if(s.matches("[a-z]{1,3}"))inputs.add(s);
        // Known regression probes; not additional independent accuracy data.
        for(var word:List.of("chengche","zhinengti","nihao","xian","xi'an","womenmingtianjian",
                "woxiangchifan","changjiang","hello","app","shurufa","zhnengt","gongjijindaikuan"))
            for(int n=1;n<=word.length();n++)inputs.add(word.substring(0,n));
        var decoders=new AdaptivePinyinDecoder[2];
        for(int m=0;m<2;m++)decoders[m]=new AdaptivePinyinDecoder(base.withLanguageModel(models[m],.5f,-4f),store(List.of()),segmenter,english);
        int states=0,repeat=0,equal=0,shortStates=0,shortEqual=0,shortTop1Equal=0;
        try(var writer=new PrintWriter(Files.newBufferedWriter(out,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW))) {
            writer.println("{\"type\":\"header\",\"scope\":\"Known synthetic interaction controls, not independent accuracy or Android timings\",\"queries\":"+inputs.size()+"}");
            for(var input:inputs)for(var context:List.of("","我想"))for(int limit:new int[]{12,255}) {
                var composition=new PinyinComposition(List.of(),input,0);
                var a=decoders[0].decodeProgressively(composition,context,limit);
                var b=decoders[1].decodeProgressively(composition,context,limit);
                valid(a,limit);valid(b,limit);states++;
                for(int m=0;m<2;m++) {
                    var again=decoders[m].decodeProgressively(composition,context,limit);
                    check(again.equals(m==0?a:b),"Nonrepeatable full progressive result");repeat++;
                }
                boolean same=a.equals(b);if(same)equal++;
                var ac=a.getWholeCandidates();var bc=b.getWholeCandidates();
                if(input.length()<=3) {shortStates++;if(same)shortEqual++;
                    if(Objects.equals(ac.isEmpty()?null:ac.get(0).getText(),bc.isEmpty()?null:bc.get(0).getText()))shortTop1Equal++;}
                writer.println("{\"type\":\"row\",\"query\":"+q(input)+",\"context\":"+q(context)+",\"limit\":"+limit
                    +",\"completeEqual\":"+same+",\"beforeTop5\":"+texts(ac.subList(0,Math.min(5,ac.size())))
                    +",\"afterTop5\":"+texts(bc.subList(0,Math.min(5,bc.size())))+"}");
            }
            int personal=0;
            for(int m=0;m<2;m++)for(var row:List.of(new String[]{"chengche","程彻","cc"},new String[]{"zhinengti","智能体","znt"})) {
                var memory=store(List.of());var d=new AdaptivePinyinDecoder(base.withLanguageModel(models[m],.5f,-4f),memory,segmenter,english);
                var candidate=find(d.decode(row[0],255),row[1]);
                // A composed explicit selection can teach a previously absent name.
                if(candidate==null)candidate=new Candidate(row[1],0f,row[0],CandidateMatchKind.USER_FULL,row[2],null,null);
                var learned=d.learn(row[0],candidate,new UserLearningEvidence(UserSelectionKind.EXPLICIT_SELECTION,20));
                check(learned!=null,"Learning lost");
                for(int n=0;n<2;n++) {var first=d.decode(row[0],255).get(0);check(first.getText().equals(row[1]),"Personal word disappeared");
                    d.learn(row[0],first,new UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT,0));personal++;}
                var reloaded=store(memory.lookup(row[0],255));
                var restored=new AdaptivePinyinDecoder(base.withLanguageModel(models[m],.5f,-4f),reloaded,segmenter,english);
                check(restored.decode(row[0],255).get(0).getText().equals(row[1]),"Reload lost preference");personal++;
                writer.println("{\"type\":\"personal\",\"model\":"+m+",\"word\":"+q(row[1])+",\"passed\":true}");
            }
            writer.println("{\"type\":\"summary\",\"states\":"+states+",\"completeEqual\":"+equal+",\"repeatedCompleteResults\":"+repeat
                +",\"shortStates\":"+shortStates+",\"shortCompleteEqual\":"+shortEqual+",\"shortTop1Equal\":"+shortTop1Equal
                +",\"personalAssertions\":"+personal+",\"passed\":true}");
        }
        System.out.println("Completed "+states+" paired interaction states and "+repeat+" exact repeats");
    }
}
