package com.lurielabs.ordersapi.orders;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/**
 * Every method is a single statement in autocommit: no @Transactional, no BEGIN/COMMIT round
 * trips. Statement and idle-in-transaction timeouts are set server-side on the pool.
 */
@Repository
class OrderRepository {

    // The order and its items in one statement, so they commit together or not at all.
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

    // Keyset page on orders_customer_created (customer_id, created_at desc, id desc); items are
    // joined only to the page's rows. One extra row tells whether another page exists.
    private static final String PAGE = """
            select o.id, o.customer_id, o.status, o.created_at, i.skus, i.qtys
            from (select id, customer_id, status, created_at from orders
                  where customer_id = ? %s
                  order by created_at desc, id desc limit ?) o
            %s
            order by o.created_at desc, o.id desc""";
    private static final String FIRST_PAGE = PAGE.formatted("", ITEMS);
    private static final String NEXT_PAGE = PAGE.formatted("and (created_at, id) < (?, ?)", ITEMS);

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(long customerId, List<Order.Item> items) {
        String[] skus = new String[items.size()];
        Integer[] qtys = new Integer[items.size()];
        for (int n = 0; n < items.size(); n++) {
            skus[n] = items.get(n).sku();
            qtys[n] = items.get(n).qty();
        }
        return jdbc.sql(INSERT)
                .param(customerId)
                .param(new SqlArrayValue("text", (Object[]) skus))
                .param(new SqlArrayValue("int4", (Object[]) qtys))
                .query(Long.class)
                .single();
    }

    Optional<Order> find(long id) {
        return jdbc.sql(FIND).param(id).query(OrderRepository::map).optional();
    }

    /** Up to limit + 1 orders, newest first, strictly after the cursor when there is one. */
    List<Order> pageWithLookahead(long customerId, Cursor after, int limit) {
        if (after == null) {
            return jdbc.sql(FIRST_PAGE).param(customerId).param(limit + 1).query(OrderRepository::map).list();
        }
        return jdbc.sql(NEXT_PAGE)
                .param(customerId)
                .param(OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
                .param(after.id())
                .param(limit + 1)
                .query(OrderRepository::map)
                .list();
    }

    private static Order map(ResultSet rs, int row) throws SQLException {
        List<Order.Item> items = new ArrayList<>();
        Array skuArray = rs.getArray("skus");
        Array qtyArray = rs.getArray("qtys");
        if (skuArray != null && qtyArray != null) {
            String[] skus = (String[]) skuArray.getArray();
            Integer[] qtys = (Integer[]) qtyArray.getArray();
            for (int n = 0; n < skus.length; n++) {
                items.add(new Order.Item(skus[n], qtys[n]));
            }
        }
        return new Order(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                List.copyOf(items));
    }
}
