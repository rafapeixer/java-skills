import java.text.ParseException;
import java.time.Duration;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Profiling by guesswork and an always-on recording that costs too much. Compiled, not run. */
public class Main {

    public static void main(String[] args) throws Exception {
        // MISTAKE: profile.jfc left running forever. It "can have more overhead" than
        // default.jfc and is meant for short recordings, not for always-on production.
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        // MISTAKE: zero thresholds record every park and every monitor enter with a stack trace,
        // millions of events per minute under load; the 20 ms default keeps only the slow ones.
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
        // MISTAKE: kept only in memory, no maxAge or maxSize, never dumped: when the JVM is
        // OOM-killed, the recording that would explain it is gone.
        recording.setToDisk(false);
        recording.start();

        // MISTAKE: a stopwatch around one call on a cold JVM instead of a profile. It measures
        // class loading and the interpreter, then "optimizes" code that was never the hot path.
        long start = System.nanoTime();
        parse("2026-10-18");
        long micros = (System.nanoTime() - start) / 1_000;
        if (micros > 100) {
            System.out.println("parse is slow (" + micros + " us), caching formatters per thread");
        }
    }

    static Object parse(String date) throws ParseException {
        return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(date);
    }
}
