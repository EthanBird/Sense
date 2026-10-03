import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.*;

/** Host-only frozen-JAR oracle. Run once before changing the spelling graph. */
public final class SpellingGraphSnapshot {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("jar stdlib inventory queries new-output");
        Path output = Path.of(args[4]);
        if (Files.exists(output)) throw new IllegalArgumentException("Keep the prior oracle");
        try (var loader = new URLClassLoader(new java.net.URL[] {
                Path.of(args[0]).toUri().toURL(), Path.of(args[1]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var type = loader.loadClass("io.github.ethanbird.senseime.core.PinyinSpellingGraph");
            var constructor = type.getConstructor(Collection.class);
            var paths = type.getMethod("paths", String.class, int.class, float.class);
            Map<String,Object> graphs = new HashMap<>();
            graphs.put("syllables", constructor.newInstance(Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8)));
            graphs.put("ambiguous", constructor.newInstance(List.of("a", "aa", "aaa", "aaaa", "ao", "ni", "hao", "wo", "xi", "an", "xian")));
            graphs.put("long", constructor.newInstance(List.of("abcdefghijklmnopqrstuvwx", "ni", "hao", "wo", "a", "o")));
            try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writer.write("# inventory\tinput\tmaxPaths\tmaxCost\tpathsSha256\n");
                for (String line : Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8)) {
                    if (line.startsWith("#") || line.isBlank()) continue;
                    String[] row = line.split("\t", -1);
                    var result = paths.invoke(graphs.get(row[0]), row[1], Integer.parseInt(row[2]), Float.parseFloat(row[3]));
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(result.toString().getBytes(StandardCharsets.UTF_8));
                    writer.write(line + "\t" + HexFormat.of().formatHex(digest) + "\n");
                }
            }
        }
    }
}
