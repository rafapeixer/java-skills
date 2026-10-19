package example;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class OrderService {
    public record Item(String sku, int qty) {}
    public record Order(long id, long customerId, long totalCents, OffsetDateTime createdAt, List<Item> items) {}
    /** The position after the last row of a page: its sort key plus the unique id. */
    public record Cursor(OffsetDateTime createdAt, long id) {}

    /**
     * The order, its items and an outbox row in one data-modifying CTE. One statement in
     * autocommit is its own transaction: all three tables commit together or not at all,
     * in one round trip instead of BEGIN + INSERT + batch INSERT + INSERT + COMMIT.
     */
    private static final String PLACE = """
            with o as (insert into orders (customer_id, total_cents) values (?, ?) returning id),
                 i as (insert into order_items (order_id, sku, qty)
                       select o.id, s.sku, s.qty from o, unnest(?::text[], ?::int[]) as s(sku, qty)),
                 e as (insert into outbox (topic, payload)
                       select 'order-placed', jsonb_build_object('order_id', o.id) from o)
            select id from o""";

    /** A page of orders (keyset, newest first) with each order's items in the same row. */
    private static final String PAGE = """
            select o.id, o.customer_id, o.total_cents, o.created_at, i.skus, i.qtys
            from (select id, customer_id, total_cents, created_at from orders
                  where customer_id = ? %s
                  order by created_at desc, id desc limit ?) o
            left join lateral (select array_agg(sku order by sku) as skus, array_agg(qty order by sku) as qtys
                               from order_items where order_id = o.id) i on true
            order by o.created_at desc, o.id desc""";
    private static final String FIRST_PAGE = PAGE.formatted("");
    private static final String NEXT_PAGE = PAGE.formatted("and (created_at, id) < (?, ?)");

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public OrderService(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** One statement, so no @Transactional: the implicit transaction is enough. */
    public long placeOrder(long customerId, long totalCents, List<Item> items) {
        String[] skus = items.stream().map(Item::sku).toArray(String[]::new);
        Integer[] qtys = items.stream().map(Item::qty).toArray(Integer[]::new);
        Long id = template.query(PLACE, ps -> {
            ps.setLong(1, customerId);
            ps.setLong(2, totalCents);
            ps.setArray(3, ps.getConnection().createArrayOf("text", skus));
            ps.setArray(4, ps.getConnection().createArrayOf("int4", qtys));
        }, rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) {
            throw new IllegalStateException("insert ... returning id returned no row");
        }
        return id;
    }

    /**
     * Several statements that must commit together: this one needs @Transactional. Callers
     * reach it through the Spring proxy; placeOrder joins this transaction, so a failure in
     * any order rolls back every order of the import.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> importOrders(long customerId, List<List<Item>> orders) {
        List<Long> ids = new ArrayList<>();
        for (List<Item> items : orders) {
            ids.add(placeOrder(customerId, 0, items));
        }
        return ids;
    }

    /** One statement in autocommit: no @Transactional, no BEGIN/COMMIT round trips. */
    public List<Order> page(long customerId, Cursor after, int limit) {
        if (after == null) {
            return jdbc.sql(FIRST_PAGE).param(customerId).param(limit)
                    .query(OrderService::mapOrder).list();
        }
        return jdbc.sql(NEXT_PAGE)
                .param(customerId).param(after.createdAt()).param(after.id()).param(limit)
                .query(OrderService::mapOrder).list();
    }

    private static Order mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new Order(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("total_cents"),
                rs.getObject("created_at", OffsetDateTime.class), items(rs.getArray("skus"), rs.getArray("qtys")));
    }

    private static List<Item> items(Array skus, Array qtys) throws SQLException {
        if (skus == null || qtys == null) {
            return List.of(); // no items: the LATERAL aggregate is NULL
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
