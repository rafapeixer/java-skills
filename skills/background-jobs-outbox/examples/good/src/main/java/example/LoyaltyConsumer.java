package example;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Consumes order.placed. Delivery is at least once, so the effect is keyed by event id. */
public class LoyaltyConsumer {
    private final JdbcClient jdbc;

    public LoyaltyConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false for a duplicate delivery, which changes nothing. */
    @Transactional
    public boolean onOrderPlaced(long eventId, long customerId) {
        int first = jdbc.sql("insert into processed_events (event_id) values (?) on conflict do nothing")
                .param(eventId).update();
        if (first == 0) {
            return false;
        }
        jdbc.sql("""
                insert into loyalty_points (customer_id, points) values (?, 10)
                on conflict (customer_id) do update set points = loyalty_points.points + 10""")
                .param(customerId).update();
        return true;
    }
}
