package com.example.orders;

import com.example.orders.Dtos.OrderItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {

    record OrderRow(long id, long customerId, String status, Instant createdAt) {
    }

    // One round trip, atomic without an explicit transaction: a single statement either
    // inserts the order and all of its items or nothing.
    private static final String INSERT_ORDER = """
            WITH new_order AS (
              INSERT INTO orders (customer_id) VALUES (?) RETURNING id
            ), new_items AS (
              INSERT INTO order_items (order_id, sku, qty)
              SELECT new_order.id, item.sku, item.qty
              FROM new_order, unnest(?::text[], ?::int[]) AS item (sku, qty)
            )
            SELECT id FROM new_order
            """;

    private static final String SELECT_ORDER = """
            SELECT id, customer_id, status, created_at FROM orders WHERE id = ?
            """;

    // Both queries are served by the (customer_id, created_at DESC, id DESC) index.
    private static final String SELECT_CUSTOMER_FIRST_PAGE = """
            SELECT id, customer_id, status, created_at FROM orders
            WHERE customer_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    private static final String SELECT_CUSTOMER_NEXT_PAGE = """
            SELECT id, customer_id, status, created_at FROM orders
            WHERE customer_id = ? AND (created_at, id) < (?, ?)
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    private static final String SELECT_ITEMS = """
            SELECT order_id, sku, qty FROM order_items WHERE order_id = ANY (?) ORDER BY order_id, sku
            """;

    private static final RowMapper<OrderRow> ORDER_ROW = (rs, n) -> new OrderRow(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long customerId, List<OrderItem> items) {
        String[] skus = items.stream().map(OrderItem::sku).toArray(String[]::new);
        Integer[] qtys = items.stream().map(OrderItem::qty).toArray(Integer[]::new);
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement(INSERT_ORDER)) {
                ps.setLong(1, customerId);
                ps.setArray(2, con.createArrayOf("text", skus));
                ps.setArray(3, con.createArrayOf("integer", qtys));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("insert returned no id");
                    }
                    return rs.getLong(1);
                }
            }
        });
        return id;
    }

    public Optional<OrderRow> findOrder(long id) {
        return jdbc.query(SELECT_ORDER, ORDER_ROW, id).stream().findFirst();
    }

    public List<OrderRow> findCustomerOrders(long customerId, Cursor after, int limit) {
        if (after == null) {
            return jdbc.query(SELECT_CUSTOMER_FIRST_PAGE, ORDER_ROW, customerId, limit);
        }
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC);
        return jdbc.query(SELECT_CUSTOMER_NEXT_PAGE, ORDER_ROW, customerId, createdAt, after.id(), limit);
    }

    /** Items for all given orders in one query, keyed by order id, each list sorted by SKU. */
    public Map<Long, List<OrderItem>> findItems(List<Long> orderIds) {
        Map<Long, List<OrderItem>> result = new HashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        Long[] ids = orderIds.toArray(Long[]::new);
        jdbc.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(SELECT_ITEMS);
                    ps.setArray(1, con.createArrayOf("bigint", ids));
                    return ps;
                },
                (RowCallbackHandler) rs -> result
                        .computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>())
                        .add(new OrderItem(rs.getString("sku"), rs.getInt("qty"))));
        return result;
    }
}
