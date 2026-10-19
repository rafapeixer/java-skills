package com.example.orders.order;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Every method is one statement in autocommit: no @Transactional, no BEGIN/COMMIT round trips,
 * and each order's items come back in the same statement.
 */
@Repository
class OrderRepository {

    /** The order and its items commit together because they are one statement. */
    private static final String INSERT = """
            with o as (insert into orders (customer_id) values (?) returning id),
                 i as (insert into order_items (order_id, sku, qty)
                       select o.id, s.sku, s.qty from o, unnest(?::text[], ?::int[]) as s(sku, qty))
            select id from o""";

    private static final String ITEMS = """
            left join lateral (select array_agg(sku order by sku) as skus, array_agg(qty order by sku) as qtys
                               from order_items where order_id = o.id) i on true""";

    private static final String FIND = """
            select o.id, o.customer_id, o.status, o.created_at, i.skus, i.qtys
            from orders o
            %s
            where o.id = ?""".formatted(ITEMS);

    // Keyset page on orders_customer_created (customer_id, created_at desc, id desc).
    private static final String PAGE = """
            select o.id, o.customer_id, o.status, o.created_at, i.skus, i.qtys
            from (select id, customer_id, status, created_at from orders
                  where customer_id = ? %s
                  order by created_at desc, id desc limit ?) o
            %s
            order by o.created_at desc, o.id desc""";
    private static final String FIRST_PAGE = PAGE.formatted("", ITEMS);
    private static final String NEXT_PAGE = PAGE.formatted("and (created_at, id) < (?::timestamptz, ?)", ITEMS);

    private static final RowMapper<Order> MAPPER = OrderRepository::map;

    private final JdbcTemplate jdbc;

    OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long insert(long customerId, List<Order.Item> items) {
        String[] skus = items.stream().map(Order.Item::sku).toArray(String[]::new);
        Integer[] qtys = items.stream().map(Order.Item::qty).toArray(Integer[]::new);
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement(INSERT)) {
                ps.setLong(1, customerId);
                ps.setArray(2, con.createArrayOf("text", skus));
                ps.setArray(3, con.createArrayOf("int4", qtys));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        if (id == null) {
            throw new IllegalStateException("insert into orders returned no id");
        }
        return id;
    }

    Optional<Order> find(long id) {
        return jdbc.query(FIND, MAPPER, id).stream().findFirst();
    }

    /** Up to {@code limit} orders, newest first, strictly after {@code after} when given. */
    List<Order> page(long customerId, @Nullable Cursor after, int limit) {
        if (after == null) {
            return jdbc.query(FIRST_PAGE, MAPPER, customerId, limit);
        }
        return jdbc.query(NEXT_PAGE, MAPPER, customerId, after.createdAt(), after.id(), limit);
    }

    private static Order map(ResultSet rs, int row) throws SQLException {
        return new Order(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                items(rs.getArray("skus"), rs.getArray("qtys")));
    }

    private static List<Order.Item> items(@Nullable Array skuArray, @Nullable Array qtyArray) throws SQLException {
        if (skuArray == null || qtyArray == null) {
            return List.of();
        }
        String[] skus = (String[]) skuArray.getArray();
        Integer[] qtys = (Integer[]) qtyArray.getArray();
        List<Order.Item> items = new ArrayList<>(skus.length);
        for (int i = 0; i < skus.length; i++) {
            items.add(new Order.Item(skus[i], qtys[i]));
        }
        return items;
    }
}
