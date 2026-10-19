package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OutboxTest {
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    static HikariDataSource dataSource;
    static AnnotationConfigApplicationContext context;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(6);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(AppConfig.class);
        context.refresh();
        jdbc = context.getBean(JdbcClient.class);
        try (var in = OutboxTest.class.getResourceAsStream("/schema.sql")) {
            jdbc.sql(new String(in.readAllBytes(), StandardCharsets.UTF_8)).update();
        }
    }

    @AfterAll
    static void stop() {
        context.close();
        dataSource.close();
        postgres.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.sql("truncate orders, outbox, processed_events, loyalty_points").update();
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRow() {
        OrderService orders = context.getBean(OrderService.class);
        orders.placeOrder(1, 500);
        assertEquals(1, count("select count(*) from outbox"));

        // The caller's transaction fails after placeOrder returned: the order and its
        // event roll back together, so the relay has nothing to publish.
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> {
            orders.placeOrder(2, 700);
            status.setRollbackOnly();
        });
        assertEquals(1, count("select count(*) from orders"));
        assertEquals(1, count("select count(*) from outbox"));
    }

    @Test
    void twoConcurrentRelaysNeverPublishTheSameRow() throws Exception {
        OrderService orders = context.getBean(OrderService.class);
        for (int i = 0; i < 200; i++) {
            orders.placeOrder(i + 1, 100);
        }
        Map<Long, Integer> published = new ConcurrentHashMap<>();
        OutboxRelay.Publisher broker = (id, topic, payload) -> {
            published.merge(id, 1, Integer::sum);
            Thread.sleep(1); // a broker round trip, so the two relays overlap
        };
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> a = executor.submit(() -> drain(new OutboxRelay(jdbc, broker, 10, 30, 5), start));
            Future<Integer> b = executor.submit(() -> drain(new OutboxRelay(jdbc, broker, 10, 30, 5), start));
            start.countDown();
            int sentA = a.get(30, TimeUnit.SECONDS);
            int sentB = b.get(30, TimeUnit.SECONDS);
            assertEquals(200, sentA + sentB);
            assertTrue(sentA > 0 && sentB > 0, "both relays took part: " + sentA + "/" + sentB);
        }
        assertEquals(200, published.size());
        assertTrue(published.values().stream().allMatch(n -> n == 1), "a row was published twice");
        assertEquals(0, count("select count(*) from outbox where sent_at is null"));
    }

    @Test
    void failedPublishIsRetriedAfterTheLeaseAndParkedAfterMaxAttempts() {
        context.getBean(OrderService.class).placeOrder(1, 100);
        OutboxRelay.Publisher down = (id, topic, payload) -> {
            throw new java.io.IOException("broker unavailable");
        };
        var relay = new OutboxRelay(jdbc, down, 10, 0, 3); // lease 0 s: retry on the next pass
        for (int i = 0; i < 5; i++) {
            assertEquals(0, relay.relayOnce());
        }
        assertEquals(3, count("select attempts from outbox"));
        assertEquals(1, count("select count(*) from outbox where dead_at is not null"));
    }

    @Test
    void duplicateDeliveryChangesNothing() {
        LoyaltyConsumer consumer = context.getBean(LoyaltyConsumer.class);
        assertTrue(consumer.onOrderPlaced(41, 7));
        assertFalse(consumer.onOrderPlaced(41, 7)); // redelivered after a relay crash
        assertEquals(10, count("select points from loyalty_points where customer_id = 7"));
    }

    private static int drain(OutboxRelay relay, CountDownLatch start) throws InterruptedException {
        start.await();
        int total = 0;
        for (int n; (n = relay.relayOnce()) > 0; ) {
            total += n;
        }
        return total;
    }

    private static long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
