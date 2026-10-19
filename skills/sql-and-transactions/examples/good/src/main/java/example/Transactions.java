package example;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs a whole transaction and reruns all of it on serialization failure or deadlock. */
public final class Transactions {
    // 40001 serialization_failure, 40P01 deadlock_detected. Nothing else is retried.
    private static final Set<String> RETRYABLE = Set.of("40001", "40P01");

    private final TransactionTemplate template;
    private final RetryTemplate retry = new RetryTemplate(RetryPolicy.builder()
            .predicate(Transactions::retryable)
            .maxRetries(4)
            .delay(Duration.ofMillis(20))
            .jitter(Duration.ofMillis(20))
            .multiplier(2)
            .maxDelay(Duration.ofMillis(500))
            .build());

    public Transactions(PlatformTransactionManager manager, int isolation) {
        this.template = new TransactionTemplate(manager);
        this.template.setIsolationLevel(isolation);
        // No setTimeout: with pgJDBC it becomes a query timeout, and a driver timer task,
        // on every statement. The bound is server-side: statement_timeout and
        // idle_in_transaction_session_timeout in the connection options (see the test pool).
    }

    /** The retry wraps the transaction, so every attempt is a fresh BEGIN ... COMMIT. */
    public <T> T run(TransactionCallback<T> work) {
        return retry.invoke(() -> template.execute(work));
    }

    static boolean retryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && RETRYABLE.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
