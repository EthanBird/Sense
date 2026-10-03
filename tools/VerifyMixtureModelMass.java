import io.github.ethanbird.senseime.core.CharacterLanguageModel;
import io.github.ethanbird.senseime.core.LinearMixtureCharacterModel;
import io.github.ethanbird.senseime.core.BinaryCharacterLanguageModel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

/** Real-artifact distribution control, not P2C quality evaluation or model selection. */
class VerifyMixtureModelMass {
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static TreeSet<Integer> support(byte[] bytes) {
        var b=ByteBuffer.wrap(bytes);var result=new TreeSet<Integer>();
        for(int i=0;i<b.getInt(6);i++)result.add(b.getInt(26+i*8));
        return result;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Two SCNG paths required");
        var a=Files.readAllBytes(Path.of(args[0]));var b=Files.readAllBytes(Path.of(args[1]));
        var left=BinaryCharacterLanguageModel.Companion.fromBytes(a);
        var right=BinaryCharacterLanguageModel.Companion.fromBytes(b);
        var union=support(a);union.addAll(support(b));
        int bos=0x110000,unk=0x110002;
        int[][] contexts={{bos,bos},{bos,'我'},{'喜','欢'},{'一','起'},{'市','场'},{unk,unk},{0x20000,'茶'},{'龘','靐'}};
        StringBuilder rows=new StringBuilder();double maximum=0;
        for(double alpha:new double[]{.1,.25,.5}) {
            CharacterLanguageModel model=LinearMixtureCharacterModel.Companion.fromBytes(a,b,alpha);
            for(var context:contexts) {
                double total=0,naive=0;
                for(int cp:union) {
                    float value=model.logProbability(context[0],context[1],cp);
                    if(!Float.isFinite(value)||value>0)throw new IllegalStateException("Invalid probability");
                    total+=Math.exp(value);
                    naive+=(1-alpha)*Math.exp(left.logProbability(context[0],context[1],cp))+alpha*Math.exp(right.logProbability(context[0],context[1],cp));
                }
                double error=Math.abs(total-1);maximum=Math.max(maximum,error);
                if(error>1e-6)throw new IllegalStateException("Distribution mass drift "+error);
                if(rows.length()>0)rows.append(',');
                rows.append("{\"alpha\":").append(alpha).append(",\"context\":[").append(context[0]).append(',').append(context[1])
                    .append("],\"mass\":").append(total).append(",\"naiveRepeatedUnknownMass\":").append(naive).append('}');
            }
        }
        System.out.println("{\"schemaVersion\":1,\"passed\":true,\"baselineSha256\":\""+sha(a)+"\",\"adaptationSha256\":\""+sha(b)
            +"\",\"vocabulary\":"+union.size()+",\"distributions\":24,\"maximumMassError\":"+maximum+",\"rows\":["+rows+"]}");
    }
}
