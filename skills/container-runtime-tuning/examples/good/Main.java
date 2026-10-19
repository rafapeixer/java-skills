import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the JVM twice, the way a container would: once with no options and once
 * with the options from the Dockerfile in this folder (passed through JAVA_TOOL_OPTIONS).
 * Each child checks its own heap against the memory the JVM detected, so the example
 * works with or without a container memory limit and on JDK 25 and 27.
 */
public class Main {

    // The same value as ENV JAVA_TOOL_OPTIONS in the Dockerfile.
    static final String JAVA_TOOL_OPTIONS = "-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:+UseG1GC";

    // Above this the default cap applies on JDK 25 (MaxRAM, 128 GB) and the check is skipped.
    static final long CAP = 128L << 30;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("check")) {
            check(Double.parseDouble(args[1]), System.getenv("JAVA_TOOL_OPTIONS") != null);
            return;
        }
        runChild(null, 25);              // the default: a quarter of the container
        runChild(JAVA_TOOL_OPTIONS, 75); // the rule: 75%, the rest for non-heap memory
        System.out.println("ok: the heap follows MaxRAMPercentage of the detected memory");
    }

    static void runChild(String toolOptions, double expectedPercent) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> cmd = new ArrayList<>(List.of(java, "-ea", "--source", "25", "Main.java", "check",
                Double.toString(expectedPercent)));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        if (toolOptions != null) {
            pb.environment().put("JAVA_TOOL_OPTIONS", toolOptions);
        }
        int exit = pb.start().waitFor();
        if (exit != 0) {
            throw new AssertionError("child with JAVA_TOOL_OPTIONS=" + toolOptions + " exited " + exit);
        }
    }

    static void check(double expectedPercent, boolean tuned) {
        // Container-aware since JDK 14: the cgroup memory limit when there is one.
        var os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        long detected = os.getTotalMemorySize();
        var hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        long maxHeapSize = Long.parseLong(hotspot.getVMOption("MaxHeapSize").getValue());
        long initialHeapSize = Long.parseLong(hotspot.getVMOption("InitialHeapSize").getValue());
        double percent = Double.parseDouble(hotspot.getVMOption("MaxRAMPercentage").getValue());
        long maxMemory = Runtime.getRuntime().maxMemory();
        List<String> gcs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList();

        System.out.printf("detected=%d MiB cpus=%d MaxRAMPercentage=%.1f InitialHeapSize=%d MiB MaxHeapSize=%d MiB maxMemory=%d MiB gc=%s%n",
                detected >> 20, Runtime.getRuntime().availableProcessors(), percent,
                initialHeapSize >> 20, maxHeapSize >> 20, maxMemory >> 20, gcs);

        assert percent == expectedPercent : "MaxRAMPercentage is " + percent + ", want " + expectedPercent;
        assert maxMemory <= maxHeapSize : "maxMemory above MaxHeapSize";
        if (tuned) {
            // The GC is named in the options, so JDK 25 and 27 agree even in a small container.
            assert gcs.stream().anyMatch(n -> n.startsWith("G1")) : "want G1, got " + gcs;
            // Initial = max: G1 does not start from a tiny heap and resize it under load.
            assert initialHeapSize == maxHeapSize
                    : "InitialHeapSize " + (initialHeapSize >> 20) + " MiB, MaxHeapSize " + (maxHeapSize >> 20) + " MiB";
        }
        if (detected > CAP || detected < (1L << 30)) {
            // Over the cap, or small enough that MinRAMPercentage (heaps near 125 MB) applies.
            System.out.println("skipping the size check for " + (detected >> 20) + " MiB");
            return;
        }
        long expected = (long) (detected * expectedPercent / 100);
        // Heap alignment (G1 regions are 1 to 32 MB) moves the value a little; allow 2% + 32 MiB.
        long slack = detected / 50 + (32L << 20);
        assert Math.abs(maxHeapSize - expected) <= slack
                : "MaxHeapSize " + (maxHeapSize >> 20) + " MiB, want about " + (expected >> 20) + " MiB";
    }
}
