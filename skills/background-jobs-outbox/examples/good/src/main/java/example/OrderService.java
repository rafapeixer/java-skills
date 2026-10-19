package example;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class OrderService {
    private final JdbcClient jdbc;

    public OrderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The order and its event commit together or not at all. Nothing here talks to the
     * broker: the relay publishes the outbox row after commit.
     */
    @Transactional
    public long placeOrder(long customerId, long totalCents) {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, ?) returning id")
                .param(customerId).param(totalCents)
                .query(Long.class).single();
        jdbc.sql("insert into outbox (topic, payload) values ('order.placed', ?::jsonb)")
                .param("{\"order_id\":" + id + ",\"customer_id\":" + customerId + "}")
                .update();
        return id;
    }
}
