import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/**
 * Builds a tiny app.jar, trains an AOT cache for it (JEP 514 one-step workflow), runs it with
 * the cache in a mode that fails if the cache cannot be used, then shows that a rebuilt jar
 * invalidates the cache. Asserts outcomes, never timings.
 */
public class Main {

    static final String APP = """
            import java.util.stream.IntStream;
            public class App {
                public static void main(String[] args) {
                    int sum = IntStream.rangeClosed(1, 100).map(i -> i * 2).sum();
                    System.out.println("app ok " + sum);
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("aot-example");
        Path jar = buildJar(dir, APP);
        Path cache = dir.resolve("app.aot");

        // Training run: -XX:AOTCacheOutput runs the app, then writes the cache on exit.
        Result training = java(dir, "-XX:AOTCacheOutput=app.aot", "-jar", "app.jar");
        assert training.exit == 0 : "training run failed:\n" + training.output;
        assert Files.size(cache) > 0 : "no AOT cache written";

        // Production run. "required" (named "on" before JDK 27) exits with an error instead of
        // silently running without the cache, so exit 0 proves the cache was loaded.
        Result production = java(dir, "-XX:AOTCache=app.aot", "-XX:AOTMode=" + requiredMode(), "-jar", "app.jar");
        assert production.exit == 0 : "cache not usable:\n" + production.output;
        assert production.output.contains("app ok 10100") : production.output;

        // Rebuild the jar: the cache is now stale, because it is tied to the exact class path.
        buildJar(dir, APP.replace("app ok", "app ok, rebuilt"));
        Result stale = java(dir, "-XX:AOTCache=app.aot", "-XX:AOTMode=" + requiredMode(), "-jar", "app.jar");
        assert stale.exit != 0 : "a stale cache was accepted:\n" + stale.output;

        // The default mode (auto) keeps running without the cache: correct, but slow again.
        Result fallback = java(dir, "-XX:AOTCache=app.aot", "-jar", "app.jar");
        assert fallback.exit == 0 && fallback.output.contains("rebuilt") : fallback.output;

        System.out.println("ok: cache " + Files.size(cache) / 1024 + " KiB, used when it matches, rejected when stale");
    }

    static String requiredMode() {
        return Runtime.version().feature() >= 27 ? "required" : "on";
    }

    record Result(int exit, String output) {}

    /** Runs the same JDK as this program, so the cache matches the JDK that created it. */
    static Result java(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElseThrow());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        return new Result(p.waitFor(), out);
    }

    /** Compiles App.java and packs it into app.jar. The AOT cache supports jars, not directories. */
    static Path buildJar(Path dir, String source) throws IOException {
        Path src = dir.resolve("App.java");
        Files.writeString(src, source);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "25", "-d", dir.resolve("classes").toString(), src.toString());
        assert rc == 0 : "javac failed";

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "App");
        Path jar = dir.resolve("app.jar");
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.putNextEntry(new JarEntry("App.class"));
            out.write(Files.readAllBytes(dir.resolve("classes/App.class")));
            out.closeEntry();
        }
        return jar;
    }
}
