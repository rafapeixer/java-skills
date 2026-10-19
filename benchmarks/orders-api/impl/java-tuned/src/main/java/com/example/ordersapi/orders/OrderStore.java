package com.example.ordersapi.orders;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL for orders. Each read is one statement in autocommit: one round trip, no
 * BEGIN/COMMIT, and a single statement already sees one consistent snapshot. The write is
 * one statement too (a data-modifying CTE), so it is atomic without an explicit transaction.
 *
 * <p>Statement timeouts come from the server ({@code statement_timeout} in the connection
 * options, see DatabaseConfig), not from {@code @Transactional(timeout)}: Spring applies that
 * as a JDBC query timeout on every statement, and the PostgreSQL driver arms a timer task for
 * each one.
 */
@Repository
public class OrderStore {

    /**
     * Items of each order as two parallel arrays, aggregated per order by the primary key
     * (order_id, sku), so an order and its items arrive in the same row.
     */
    private static final String WITH_ITEMS = """
            SELECT o.id, o.customer_id, o.status, o.created_at, i.skus, i.qtys
            FROM %s o
            LEFT JOIN LATERAL (
                SELECT array_agg(sku ORDER BY sku) AS skus, array_agg(qty ORDER BY sku) AS qtys
                FROM order_items WHERE order_id = o.id) i ON true
            """;

    private static final String ONE_ORDER = WITH_ITEMS.formatted("orders") + "WHERE o.id = ?";

    // Keyset pagination on orders_customer_created (customer_id, created_at DESC, id DESC);
    // id breaks ties between orders created in the same microsecond. The page is chosen
    // first (index scan + LIMIT), then its items are joined.
    private static final String FIRST_PAGE = WITH_ITEMS.formatted("""
            (SELECT id, customer_id, status, created_at FROM orders
             WHERE customer_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT ?)""") + "ORDER BY o.created_at DESC, o.id DESC";
    private static final String NEXT_PAGE = WITH_ITEMS.formatted("""
            (SELECT id, customer_id, status, created_at FROM orders
             WHERE customer_id = ? AND (created_at, id) < (?, ?)
             ORDER BY created_at DESC, id DESC
             LIMIT ?)""") + "ORDER BY o.created_at DESC, o.id DESC";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    private record OrderRow(long id, long customerId, String status, Instant createdAt, List<Item> items) {

        OrderView view() {
            return new OrderView(id, customerId, status, DateTimeFormatter.ISO_INSTANT.format(createdAt), items);
        }
    }

    OrderStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * Inserts the order and all its items atomically, as one statement: the items arrive as
     * two parallel arrays and the data-modifying CTE inserts both tables. One statement in
     * autocommit is its own transaction, so this is one round trip instead of
     * BEGIN + INSERT + batch INSERT + COMMIT.
     */
    private static final String CREATE = """
            WITH o AS (INSERT INTO orders (customer_id) VALUES (?) RETURNING id),
                 i AS (INSERT INTO order_items (order_id, sku, qty)
                       SELECT o.id, s.sku, s.qty FROM o, unnest(?::text[], ?::int[]) AS s(sku, qty))
            SELECT id FROM o""";

    /** Inserts the order and all its items atomically; returns the new order id. */
    public long create(long customerId, List<Item> items) {
        String[] skus = new String[items.size()];
        Integer[] qtys = new Integer[items.size()];
        for (int i = 0; i < skus.length; i++) {
            skus[i] = items.get(i).sku();
            qtys[i] = items.get(i).qty();
        }
        Long id = template.query(CREATE, ps -> {
            ps.setLong(1, customerId);
            ps.setArray(2, ps.getConnection().createArrayOf("text", skus));
            ps.setArray(3, ps.getConnection().createArrayOf("int4", qtys));
        }, rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) {
            throw new IllegalStateException("INSERT ... RETURNING id returned no row");
        }
        return id;
    }

    /** The order with its items, in one statement. */
    public Optional<OrderView> find(long id) {
        return jdbc.sql(ONE_ORDER).param(id).query(OrderStore::mapOrder).optional().map(OrderRow::view);
    }

    /** The items of an order, or empty when the order does not exist; the same one statement. */
    public Optional<List<Item>> findItems(long orderId) {
        return jdbc.sql(ONE_ORDER).param(orderId).query(OrderStore::mapOrder).optional().map(OrderRow::items);
    }

    /** Up to {@code limit} orders older than {@code after}, newest first, each with its items. */
    public OrderPage page(long customerId, @Nullable Cursor after, int limit) {
        // Fetch one extra row to learn whether another page exists.
        List<OrderRow> rows = (after == null
                ? jdbc.sql(FIRST_PAGE).param(customerId).param(limit + 1)
                : jdbc.sql(NEXT_PAGE).param(customerId)
                        .param(OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
                        .param(after.id())
                        .param(limit + 1))
                .query(OrderStore::mapOrder)
                .list();

        boolean more = rows.size() > limit;
        if (more) {
            rows = rows.subList(0, limit);
        }
        String next = "";
        if (more) {
            OrderRow last = rows.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new OrderPage(rows.stream().map(OrderRow::view).toList(), next);
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                items(rs.getArray("skus"), rs.getArray("qtys")));
    }

    private static List<Item> items(@Nullable Array skus, @Nullable Array qtys) throws SQLException {
        if (skus == null || qtys == null) {
            return List.of(); // an order without items: the LATERAL aggregate is NULL
        }
        String[] s = (String[]) skus.getArray();
        Integer[] q = (Integer[]) qtys.getArray();
        List<Item> items = new ArrayList<>(s.length);
        for (int i = 0; i < s.length; i++) {
            items.add(new Item(s[i], q[i]));
        }
        return items;
    }
}
