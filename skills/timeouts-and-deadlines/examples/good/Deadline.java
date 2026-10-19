import java.time.Duration;

/**
 * The time left to answer one request. Created once at the edge of the request and passed
 * to every call it makes, so each call is bounded by what is left, not by a fixed number.
 */
public final class Deadline {
    /** Below this, a call cannot finish anyway; fail before sending it. */
    static final Duration MIN_USEFUL = Duration.ofMillis(10);

    private final long expiresAtNanos; // System.nanoTime is monotonic; wall-clock time can jump

    private Deadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, expiresAtNanos - System.nanoTime()));
    }

    /** Timeout for one call: its own cap or what is left of the budget, whichever is smaller. */
    public Duration timeoutFor(String operation, Duration cap) {
        Duration left = remaining();
        if (left.compareTo(MIN_USEFUL) < 0) {
            throw new Exceeded(operation, left);
        }
        return left.compareTo(cap) < 0 ? left : cap;
    }

    /** JDBC takes whole seconds and 0 means "no limit", so round up and never return 0. */
    public int queryTimeoutSeconds(String operation, Duration cap) {
        long millis = timeoutFor(operation, cap).toMillis();
        return (int) Math.max(1, (millis + 999) / 1000);
    }

    /** One exception type for every spent budget, naming the operation that hit it. */
    public static final class Exceeded extends RuntimeException {
        public Exceeded(String operation, Duration timeout) {
            super(operation + ": deadline exceeded (timeout " + timeout.toMillis() + " ms)");
        }

        public Exceeded(String operation, Duration timeout, Throwable cause) {
            super(operation + ": deadline exceeded (timeout " + timeout.toMillis() + " ms)", cause);
        }
    }
}
