package example;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class OrderService {
    public record Item(String sku, int qty) {}
    public record Order(long id, long customerId, long totalCents, OffsetDateTime createdAt) {}
    /** The position after the last row of a page: its sort key plus the unique id. */
    public record Cursor(OffsetDateTime createdAt, long id) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public OrderService(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * One short transaction: the order, its items and an outbox row commit together.
     * The event is delivered after commit by a relay; no network call happens here.
     * Callers reach this through the Spring proxy; a call from another method of this
     * class would bypass it and run without a transaction.
     */
    @Transactional
    public long placeOrder(long customerId, long totalCents, List<Item> items) {
        long id = jdbc.sql("insert into orders (customer_id, total_cents) values (?, ?) returning id")
                .param(customerId).param(totalCents)
                .query(Long.class).single();
        // One round trip with reWriteBatchedInserts=true instead of one insert per item.
        template.batchUpdate("insert into order_items (order_id, sku, qty) values (?, ?, ?)",
                items, 100, (ps, item) -> {
                    ps.setLong(1, id);
                    ps.setString(2, item.sku());
                    ps.setInt(3, item.qty());
                });
        jdbc.sql("insert into outbox (topic, payload) values ('order-placed', ?::jsonb)")
                .param("{\"order_id\":" + id + "}")
                .update();
        return id;
    }

    /** Keyset page, newest first, served by the index (customer_id, created_at, id). */
    @Transactional(readOnly = true)
    public List<Order> page(long customerId, Cursor after, int limit) {
        if (after == null) {
            return jdbc.sql("""
                    select id, customer_id, total_cents, created_at from orders
                    where customer_id = ?
                    order by created_at desc, id desc limit ?""")
                    .param(customerId).param(limit)
                    .query(Order.class).list();
        }
        return jdbc.sql("""
                select id, customer_id, total_cents, created_at from orders
                where customer_id = ? and (created_at, id) < (?, ?)
                order by created_at desc, id desc limit ?""")
                .param(customerId).param(after.createdAt()).param(after.id()).param(limit)
                .query(Order.class).list();
    }
}
