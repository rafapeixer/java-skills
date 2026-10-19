import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** A build step that creates and checks an AOT cache the wrong way. Compiled, not run. */
public class Main {

    public static void main(String[] args) throws IOException, InterruptedException {
        // MISTAKE: "java" from PATH may be a different JDK than the one that runs the app,
        // and an AOT cache only loads on the exact JDK release that created it.
        // MISTAKE: a class directory on the class path. The AOT cache supports jar files only,
        // so the application classes are not cached at all.
        Process training = new ProcessBuilder("java", "-XX:AOTCacheOutput=app.aot",
                "-cp", "target/classes", "com.example.App").inheritIO().start();
        training.waitFor();

        // MISTAKE: production uses a different class path (the jar) than training did,
        // so the cache does not match and the JVM silently runs without it.
        long start = System.nanoTime();
        Process run = new ProcessBuilder("java", "-XX:AOTCache=app.aot",
                "-jar", "target/app.jar").inheritIO().start();
        run.waitFor();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // MISTAKE: a timing assertion as the only proof the cache works. It is flaky on a
        // busy CI runner and passes on a fast laptop even when the cache was ignored.
        if (millis > 500) {
            throw new AssertionError("startup took " + millis + " ms");
        }
    }
}
