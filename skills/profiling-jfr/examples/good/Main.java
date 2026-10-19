import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Records with the same settings a production JVM uses (default.jfc), runs three small
 * problems (lock contention, a pinned virtual thread, allocation), then reads the file back
 * and asserts that each problem shows up as its JFR event. No timing assertions: each
 * problem lasts several times the 20 ms threshold of default.jfc, whatever the machine.
 */
public class Main {

    static final Object LOCK = new Object();
    static final long HOLD_MILLIS = 200; // 10x the default 20 ms threshold

    /** Sleeps in its static initializer: a virtual thread waiting here pins its carrier. */
    static class SlowInit {
        static final int VALUE;
        static {
            try {
                Thread.sleep(HOLD_MILLIS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            VALUE = 42;
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("app", ".jfr");
        try (Recording recording = new Recording(Configuration.getConfiguration("default"))) {
            recording.setName("example");
            recording.setToDisk(true);
            recording.start();

            contention();
            pinning();
            long kept = allocation();

            recording.stop();
            recording.dump(file);
            System.out.println("allocated and kept " + kept + " bytes");
        }

        Map<String, Integer> counts = new TreeMap<>();
        RecordedEvent pinned = null;
        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            counts.merge(event.getEventType().getName(), 1, Integer::sum);
            if (event.getEventType().getName().equals("jdk.VirtualThreadPinned")) {
                pinned = event;
            }
        }
        for (String type : new String[] {"jdk.JavaMonitorEnter", "jdk.VirtualThreadPinned",
                "jdk.ObjectAllocationSample"}) {
            System.out.println(type + ": " + counts.getOrDefault(type, 0));
            assert counts.getOrDefault(type, 0) > 0 : "no " + type + " event recorded";
        }
        // The stack trace points at the code to fix, below the JDK frames.
        String where = pinned.getStackTrace().getFrames().stream()
                .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                .filter(name -> name.startsWith("Main")).findFirst().orElse("?");
        assert where.contains("SlowInit") : "pinned at " + where + ", want the class initializer";
        System.out.println("pinned at " + where + " for " + pinned.getDuration().toMillis() + " ms");
        Files.delete(file);
        System.out.println("ok: contention, pinning and allocation are visible in JFR");
    }

    /** One thread holds a monitor until a second thread is blocked on it, then a while longer. */
    static void contention() throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().unstarted(() -> {
            synchronized (LOCK) {
                // entering only after the holder lets go
            }
        });
        Thread holder = Thread.ofPlatform().start(() -> {
            synchronized (LOCK) {
                held.countDown();
                while (waiter.getState() != Thread.State.BLOCKED) {
                    Thread.onSpinWait();
                }
                sleep(HOLD_MILLIS);
            }
        });
        held.await();
        waiter.start();
        holder.join();
        waiter.join();
    }

    /** A virtual thread that blocks during class initialization cannot unmount (JEP 491). */
    static void pinning() throws InterruptedException {
        Thread vt = Thread.ofVirtual().start(() -> {
            if (SlowInit.VALUE != 42) {
                throw new AssertionError();
            }
        });
        vt.join();
    }

    /** Allocates about 256 MiB in short-lived arrays; default.jfc samples 150 allocations/s. */
    static long allocation() {
        long kept = 0;
        for (int i = 0; i < 4096; i++) {
            byte[] chunk = new byte[64 * 1024];
            chunk[i % chunk.length] = 1;
            kept += chunk[i % chunk.length];
        }
        return kept;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
