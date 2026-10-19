package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OrderServiceTest {
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    static HikariDataSource dataSource;
    static AnnotationConfigApplicationContext context;
    static OrderService orders;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(4);
        dataSource.addDataSourceProperty("reWriteBatchedInserts", "true");
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(AppConfig.class);
        context.refresh();
        orders = context.getBean(OrderService.class);
        jdbc = context.getBean(JdbcClient.class);
        try (var in = OrderServiceTest.class.getResourceAsStream("/schema.sql")) {
            jdbc.sql(new String(in.readAllBytes(), StandardCharsets.UTF_8)).update();
        }
    }

    @AfterAll
    static void stop() {
        context.close();
        dataSource.close();
        postgres.stop();
    }

    @Test
    void orderItemsAndOutboxCommitOrRollBackTogether() {
        long id = orders.placeOrder(1, 4_200, List.of(new OrderService.Item("A", 1), new OrderService.Item("B", 2)));
        assertEquals(2, count("select count(*) from order_items where order_id = " + id));
        assertEquals(1, count("select count(*) from outbox where payload->>'order_id' = '" + id + "'"));

        long before = count("select count(*) from orders");
        // qty 0 breaks a CHECK after the order row was inserted: the proxy rolls back all of it.
        assertThrows(DataIntegrityViolationException.class,
                () -> orders.placeOrder(1, 100, List.of(new OrderService.Item("C", 0))));
        assertEquals(before, count("select count(*) from orders"));
    }

    @Test
    void keysetPagesReturnEveryRowOnceDespiteTies() {
        OffsetDateTime base = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 25; i++) {
            // Five rows share each timestamp, so created_at alone would drop rows at page edges.
            jdbc.sql("insert into orders (customer_id, total_cents, created_at) values (77, ?, ?)")
                    .param(i).param(base.plusMinutes(i / 5)).update();
        }
        List<OrderService.Order> all = new ArrayList<>();
        OrderService.Cursor cursor = null;
        List<OrderService.Order> page;
        do {
            page = orders.page(77, cursor, 10);
            all.addAll(page);
            if (!page.isEmpty()) {
                var last = page.getLast();
                cursor = new OrderService.Cursor(last.createdAt(), last.id());
            }
        } while (page.size() == 10);
        assertEquals(25, all.size());
        assertEquals(25, all.stream().map(OrderService.Order::id).distinct().count());
    }

    @Test
    void serializationFailureRerunsTheWholeTransaction() throws Exception {
        jdbc.sql("insert into stock (sku, available) values ('X', 10)").update();
        Transactions tx = context.getBean(Transactions.class);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch bothRead = new CountDownLatch(2);
        Runnable reserveOne = () -> tx.run(status -> {
            int available = jdbc.sql("select available from stock where sku = 'X'").query(Integer.class).single();
            if (attempts.incrementAndGet() <= 2) {
                bothRead.countDown(); // first attempts: both read 10 before either writes
                await(bothRead);
            }
            // REPEATABLE READ: the second writer gets 40001 instead of losing an update.
            return jdbc.sql("update stock set available = ? where sku = 'X'").param(available - 1).update();
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(reserveOne);
            var second = executor.submit(reserveOne);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(8, count("select available from stock where sku = 'X'"));
        assertEquals(3, attempts.get(), "one transaction ran twice");
        assertTrue(Transactions.retryable(new java.sql.SQLException("x", "40P01")));
        assertTrue(!Transactions.retryable(new java.sql.SQLException("x", "23505")));
    }

    private static long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
