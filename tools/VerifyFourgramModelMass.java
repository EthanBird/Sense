import io.github.ethanbird.senseime.core.BinaryCharacterLanguageModel;
import io.github.ethanbird.senseime.core.BinaryFourgramLanguageModel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Real SCQ4 numeric controls. Not sentence accuracy or Android performance. */
class VerifyFourgramModelMass {
    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
    public static void main(String[] args) throws Exception {
        var bytes=Files.readAllBytes(Path.of(args[0]));var ext=Files.readAllBytes(Path.of(args[1]));
        var lower=BinaryCharacterLanguageModel.Companion.fromBytes(bytes);
        var model=BinaryFourgramLanguageModel.Companion.fromBytes(bytes,ext);
        var base=ByteBuffer.wrap(bytes);var buf=ByteBuffer.wrap(ext);
        var vocab=new int[base.getInt(6)];for(int i=0;i<vocab.length;i++)vocab[i]=base.getInt(26+i*8);
        int direct=buf.getInt(38), count=buf.getInt(42), bos=0x110000,unk=0x110002;
        var contexts=new ArrayList<int[]>();
        for(int i=0;i<64;i++) {
            long packed=buf.getLong(46+direct*16+(int)((long)i*(count-1)/63)*12);
            contexts.add(new int[]{(int)(packed>>>42),(int)((packed>>>21)&0x1FFFFF),(int)(packed&0x1FFFFF)});
        }
        contexts.addAll(List.of(new int[]{bos,bos,bos},new int[]{bos,bos,'我'},new int[]{bos,'我','们'},
            new int[]{unk,'我','们'},new int[]{0x20000,'我','们'},new int[]{'龘','靐','齉'}));
        double maxError=0;int lowerEquivalent=0,bosEquivalent=0;var rows=new StringBuilder();
        for(var c:contexts) {
            double total=0;
            for(int w:vocab) {
                float value=model.logProbabilityWithThirdContext(c[0],c[1],c[2],w);
                if(!Float.isFinite(value)||value>0)throw new IllegalStateException("Invalid probability");
                total+=Math.exp(value);
                int lowBits=Float.floatToIntBits(lower.logProbability(c[1],c[2],w));
                if(lowBits!=Float.floatToIntBits(model.logProbability(c[1],c[2],w)))throw new IllegalStateException("Changed lower API");
                lowerEquivalent++;
                if(c[0]==bos) {
                    if(lowBits!=Float.floatToIntBits(value))throw new IllegalStateException("Changed first-three-character probability");
                    bosEquivalent++;
                }
            }
            var error=Math.abs(total-1);maxError=Math.max(maxError,error);
            if(error>1e-6)throw new IllegalStateException("Mass drift "+error);
            if(rows.length()>0)rows.append(',');
            rows.append("{\"context\":").append(Arrays.toString(c)).append(",\"mass\":").append(total).append('}');
        }
        System.out.println("{\"passed\":true,\"baselineSha256\":\""+sha(bytes)+"\",\"extensionSha256\":\""+sha(ext)
            +"\",\"vocabulary\":"+vocab.length+",\"distributions\":"+contexts.size()+",\"lowerApiBitEqual\":"+lowerEquivalent
            +",\"bosThirdContextBitEqual\":"+bosEquivalent+",\"maximumMassError\":"+maxError
            +",\"estimatedRetainedBytes\":"+model.getEstimatedRetainedBytes()+",\"rows\":["+rows+"]}");
    }
}
