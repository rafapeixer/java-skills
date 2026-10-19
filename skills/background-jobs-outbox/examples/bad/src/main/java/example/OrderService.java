package example;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OrderService {
    public interface Broker {
        void send(String topic, String payload);
    }

    private final JdbcClient jdbc;
    private final Broker broker;

    public OrderService(JdbcClient jdbc, Broker broker) {
        this.jdbc = jdbc;
        this.broker = broker;
    }

    @Transactional
    public long placeOrder(long customerId, long totalCents) {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, ?) returning id")
                .param(customerId).param(totalCents)
                .query(Long.class).single();
        // MISTAKE: publishes inside the transaction. If the commit fails or the caller rolls
        // back, consumers already saw an order that does not exist; while the broker is slow,
        // the row locks and the pooled connection wait on it.
        broker.send("order.placed", "{\"order_id\":" + id + "}");
        return id;
    }

    @Transactional
    public long placeOrderAfterCommit(long customerId, long totalCents) {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, ?) returning id")
                .param(customerId).param(totalCents)
                .query(Long.class).single();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // MISTAKE: "after commit" with nothing durable behind it. A crash, a deploy or
                // a broker error between COMMIT and this send loses the event for good, and
                // nothing records that it was lost.
                broker.send("order.placed", "{\"order_id\":" + id + "}");
            }
        });
        return id;
    }

    // MISTAKE: one transaction held open while publishing, and FOR UPDATE without SKIP LOCKED.
    // A second replica blocks on these rows until this one commits, so replicas take turns;
    // a broker error on row 50 rolls back sent_at for rows 1-49, which go out again.
    @Transactional
    public void relay() {
        List<Map<String, Object>> rows = jdbc.sql(
                "select id, topic, payload::text as payload from outbox where sent_at is null limit 100 for update")
                .query().listOfRows();
        for (Map<String, Object> row : rows) {
            broker.send((String) row.get("topic"), (String) row.get("payload"));
            jdbc.sql("update outbox set sent_at = now() where id = ?").param(row.get("id")).update();
        }
    }
}
