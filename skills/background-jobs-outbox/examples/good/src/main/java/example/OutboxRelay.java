package example;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Publishes outbox rows at least once. Run one loop per replica; replicas share the
 * table without publishing the same row twice while its lease holds.
 */
public class OutboxRelay {
    /** Sends one event to the broker and returns only once the broker acknowledged it. */
    public interface Publisher {
        void publish(long eventId, String topic, String payload) throws Exception;
    }

    record Event(long id, String topic, String payload, int attempts) {}

    // One autocommit statement: lock a batch, skip rows another relay holds, and move
    // available_at forward as a lease. No transaction stays open while publishing.
    static final String CLAIM_SQL = """
            update outbox set attempts = attempts + 1,
                   available_at = now() + ? * interval '1 second'
            where id in (
                select id from outbox
                where sent_at is null and dead_at is null and available_at <= now()
                order by available_at, id
                limit ?
                for update skip locked)
            returning id, topic, payload::text as payload, attempts""";

    private final JdbcClient jdbc;
    private final Publisher publisher;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;

    public OutboxRelay(JdbcClient jdbc, Publisher publisher, int batchSize, int leaseSeconds, int maxAttempts) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** Claims and publishes one batch; returns how many events were sent. */
    public int relayOnce() {
        List<Event> batch = jdbc.sql(CLAIM_SQL).param(leaseSeconds).param(batchSize)
                .query(Event.class).list();
        int sent = 0;
        for (Event event : batch) {
            try {
                publisher.publish(event.id(), event.topic(), event.payload());
            } catch (Exception e) {
                if (event.attempts() >= maxAttempts) {
                    // Parked for a human; alert on count(*) where dead_at is not null.
                    jdbc.sql("update outbox set dead_at = now() where id = ?").param(event.id()).update();
                }
                continue; // otherwise the lease expires and another pass retries it
            }
            jdbc.sql("update outbox set sent_at = now() where id = ?").param(event.id()).update();
            sent++;
        }
        return sent;
    }
}
