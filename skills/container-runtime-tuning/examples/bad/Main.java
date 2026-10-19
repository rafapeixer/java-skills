import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sizes a cache and a CPU pool from the host, not from the container. Compiled, not run. */
public class Main {

    public static void main(String[] args) throws IOException {
        // MISTAKE: /proc/meminfo shows the node's memory, not the cgroup limit. On a 64 GiB node
        // a 1 GiB pod sizes its cache for 16 GiB and dies with OOMKilled, no stack trace.
        long hostBytes = readMemTotalKb() * 1024;
        long cacheBytes = hostBytes / 4;

        // MISTAKE: a CPU-bound pool sized for the machine the code was written on.
        // With a 2-CPU limit, 32 threads only add context switches and CFS throttling.
        ExecutorService cpuPool = Executors.newFixedThreadPool(32);

        System.out.println("cache=" + (cacheBytes >> 20) + " MiB");
        cpuPool.shutdown();
    }

    static long readMemTotalKb() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
            if (line.startsWith("MemTotal:")) {
                return Long.parseLong(line.replaceAll("\\D+", ""));
            }
        }
        return 0;
    }
}
