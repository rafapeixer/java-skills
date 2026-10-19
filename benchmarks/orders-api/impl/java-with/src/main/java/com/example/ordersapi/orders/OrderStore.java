package com.example.ordersapi.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** All SQL for orders. Every public method is one short transaction reached through the proxy. */
@Repository
public class OrderStore {

    private static final String ORDER_COLUMNS = "SELECT id, customer_id, status, created_at FROM orders ";

    // Keyset pagination on orders_customer_created (customer_id, created_at DESC, id DESC);
    // id breaks ties between orders created in the same microsecond.
    private static final String FIRST_PAGE = ORDER_COLUMNS + """
            WHERE customer_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?""";
    private static final String NEXT_PAGE = ORDER_COLUMNS + """
            WHERE customer_id = ? AND (created_at, id) < (?, ?)
            ORDER BY created_at DESC, id DESC
            LIMIT ?""";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    OrderStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    private record OrderRow(long id, long customerId, String status, Instant createdAt) {

        OrderView withItems(List<Item> items) {
            return new OrderView(id, customerId, status, DateTimeFormatter.ISO_INSTANT.format(createdAt), items);
        }
    }

    /** Inserts the order and all its items atomically; returns the new order id. */
    @Transactional(timeout = 5)
    public long create(long customerId, List<Item> items) {
        long id = jdbc.sql("INSERT INTO orders (customer_id) VALUES (?) RETURNING id")
                .param(customerId)
                .query(Long.class)
                .single();
        // One batch, rewritten into a multi-row insert by reWriteBatchedInserts=true.
        template.batchUpdate("INSERT INTO order_items (order_id, sku, qty) VALUES (?, ?, ?)",
                items, items.size(), (ps, item) -> {
                    ps.setLong(1, id);
                    ps.setString(2, item.sku());
                    ps.setInt(3, item.qty());
                });
        return id;
    }

    @Transactional(readOnly = true, timeout = 2)
    public Optional<OrderView> find(long id) {
        return jdbc.sql(ORDER_COLUMNS + "WHERE id = ?")
                .param(id)
                .query(OrderStore::mapOrder)
                .optional()
                .map(row -> row.withItems(itemsByOrder(List.of(row.id())).getOrDefault(row.id(), List.of())));
    }

    /** The items of an order, or empty when the order does not exist. */
    @Transactional(readOnly = true, timeout = 2)
    public Optional<List<Item>> findItems(long orderId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM orders WHERE id = ?)")
                .param(orderId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("SELECT sku, qty FROM order_items WHERE order_id = ? ORDER BY sku")
                .param(orderId)
                .query((rs, rowNum) -> new Item(rs.getString("sku"), rs.getInt("qty")))
                .list());
    }

    /** Up to {@code limit} orders older than {@code after}, newest first, each with its items. */
    @Transactional(readOnly = true, timeout = 2)
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
        Map<Long, List<Item>> items = itemsByOrder(rows.stream().map(OrderRow::id).toList());
        List<OrderView> orders = rows.stream()
                .map(row -> row.withItems(items.getOrDefault(row.id(), List.of())))
                .toList();
        String next = "";
        if (more) {
            OrderRow last = rows.getLast();
            next = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new OrderPage(orders, next);
    }

    /** Items of several orders in one query instead of one query per order. */
    private Map<Long, List<Item>> itemsByOrder(List<Long> orderIds) {
        Map<Long, List<Item>> byOrder = new HashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        Object[] ids = orderIds.toArray();
        template.query("SELECT order_id, sku, qty FROM order_items WHERE order_id = ANY (?) ORDER BY order_id, sku",
                (PreparedStatementSetter) ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                (RowCallbackHandler) rs -> byOrder
                        .computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>())
                        .add(new Item(rs.getString("sku"), rs.getInt("qty"))));
        return byOrder;
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
