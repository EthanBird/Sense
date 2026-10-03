import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Random;

/** Offline, isolated JVM comparison of two frozen core jars against the same SCNG asset. */
public final class LanguageModelEquivalence {
    static final int BOS = 0x110000;
    static final int MASK = 0x1fffff;
    static final class Model implements AutoCloseable {
        final URLClassLoader loader;
        final Object model;
        final Method contains, unigram, probability, retainedBytes;
        Model(String jar, String kotlin, byte[] data) throws Exception {
            loader = new URLClassLoader(new URL[]{new File(jar).toURI().toURL(), new File(kotlin).toURI().toURL()},
                                        ClassLoader.getPlatformClassLoader());
            Class<?> cls = loader.loadClass("io.github.ethanbird.senseime.core.BinaryCharacterLanguageModel");
            Object companion = cls.getField("Companion").get(null);
            model = companion.getClass().getMethod("fromBytes", byte[].class).invoke(companion, (Object)data);
            contains = cls.getMethod("containsCodePoint", int.class);
            unigram = cls.getMethod("unigramLogProbability", int.class);
            probability = cls.getMethod("logProbability", int.class, int.class, int.class);
            retainedBytes = cls.getMethod("getEstimatedRetainedBytes");
        }
        int score(int a, int b, int c) throws Exception {
            return Float.floatToRawIntBits((Float)probability.invoke(model, a, b, c));
        }
        public void close() throws Exception { loader.close(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("baseline.jar candidate.jar kotlin.jar model.scng");
        byte[] data = Files.readAllBytes(new File(args[3]).toPath());
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (bytes.getInt() != 0x53434e47 || bytes.getShort() != 1) throw new IllegalArgumentException("SCNG/1 required");
        int vocabulary = bytes.getInt(), bigrams = bytes.getInt(), biContexts = bytes.getInt();
        int trigrams = bytes.getInt(), triContexts = bytes.getInt();
        int[] tokens = new int[vocabulary];
        for (int i=0;i<vocabulary;i++) { tokens[i] = bytes.getInt(); bytes.getFloat(); }
        try (Model before = new Model(args[0],args[2],data); Model after = new Model(args[1],args[2],data)) {
            int membership = 0, unigrams = 0, transitions = 0;
            for (int cp=-1;cp<=0x110002;cp++) {
                if (!before.contains.invoke(before.model,cp).equals(after.contains.invoke(after.model,cp)))
                    throw new AssertionError("Membership changed at " + cp);
                membership++;
            }
            for (int cp:tokens) {
                int a = Float.floatToRawIntBits((Float)before.unigram.invoke(before.model,cp));
                int b = Float.floatToRawIntBits((Float)after.unigram.invoke(after.model,cp));
                if (a!=b) throw new AssertionError("Unigram changed at " + cp);
                unigrams++;
            }
            for (int i=0;i<bigrams;i++) {
                long key=bytes.getLong();bytes.getFloat();int a=(int)(key>>>21),b=(int)(key&MASK);
                if (before.score(BOS,a,b)!=after.score(BOS,a,b)) throw new AssertionError("Bigram changed: " + key);
                transitions++;
            }
            bytes.position(bytes.position()+biContexts*8);
            for (int i=0;i<trigrams;i++) {
                long key=bytes.getLong();bytes.getFloat();int a=(int)(key>>>42),b=(int)((key>>>21)&MASK),c=(int)(key&MASK);
                if (before.score(a,b,c)!=after.score(a,b,c)) throw new AssertionError("Trigram changed: " + key);
                transitions++;
            }
            bytes.position(bytes.position()+triContexts*12);
            if (bytes.hasRemaining()) throw new AssertionError("Unexpected model tail");
            Random random=new Random(2603);
            for (int i=0;i<20000;i++) {
                int a=i%4==0?BOS:tokens[random.nextInt(tokens.length)];
                int b=i%5==0?random.nextInt(0x110000):tokens[random.nextInt(tokens.length)];
                int c=i%3==0?random.nextInt(0x110000):tokens[random.nextInt(tokens.length)];
                if (before.score(a,b,c)!=after.score(a,b,c)) throw new AssertionError("Backoff changed: " + a+","+b+","+c);
                transitions++;
            }
            System.out.println("{\"passed\":true,\"membershipQueries\":"+membership+",\"unigramQueries\":"+unigrams+
                ",\"transitionQueries\":"+transitions+",\"baselineRetainedBytes\":"+before.retainedBytes.invoke(before.model)+
                ",\"candidateRetainedBytes\":"+after.retainedBytes.invoke(after.model)+"}");
        }
    }
}
