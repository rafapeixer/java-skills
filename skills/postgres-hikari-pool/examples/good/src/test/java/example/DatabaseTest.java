package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DatabaseTest {
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @BeforeAll
    static void start() {
        postgres.start();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void poolSizeIsTheBudgetDividedByPeakPods() {
        // 80 connections, 8 replicas, the default 25% maxSurge: 80 / (8 + 2) = 8, not 80 / 16 = 5.
        assertEquals(8, Database.poolSize(80, 8, 25));
        // A Deployment with maxSurge: 100% does double during a deploy.
        assertEquals(5, Database.poolSize(80, 8, 100));
        // ceil(25% of 6) = 2 surge pods: 200 / 8 = 25.
        assertEquals(25, Database.poolSize(200, 6, 25));
        assertThrows(IllegalArgumentException.class, () -> Database.poolSize(10, 8, 25));
    }

    @Test
    void poolCapsServerConnectionsAndFailsFastWhenExhausted() throws Exception {
        try (var pool = pool(3)) {
            List<Connection> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                held.add(pool.getConnection());
            }
            assertEquals(3, serverConnections());

            long start = System.nanoTime();
            assertThrows(SQLTransientConnectionException.class, pool::getConnection);
            long waited = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(waited < 2_000, "borrow from a full pool waited " + waited + " ms");
            for (Connection c : held) {
                c.close();
            }
        }
    }

    @Test
    void virtualThreadsAreBoundedByThePoolNotByTheirCount() throws Exception {
        Set<Integer> backends = ConcurrentHashMap.newKeySet();
        try (var pool = pool(4); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> backends.add(backendPid(pool)));
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTrue(backends.size() <= 4, "200 virtual threads used " + backends.size() + " backends");
    }

    @Test
    void springBootPropertiesBindToTheSamePool() throws Exception {
        var props = new Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        HikariConfig config = new Binder(new MapConfigurationPropertySource(props))
                .bind("spring.datasource.hikari", Bindable.ofInstance(new HikariConfig())).get();
        assertEquals(8, config.getMaximumPoolSize());
        assertEquals(-1, config.getMinimumIdle());
        assertEquals(500, config.getConnectionTimeout());
        assertEquals("orders-api", config.getDataSourceProperties().getProperty("ApplicationName"));
        assertEquals("true", config.getDataSourceProperties().getProperty("reWriteBatchedInserts"));
    }

    @Test
    void driverIsAtLeastTheSecurityRelease() {
        assertEquals("42.7.14", org.postgresql.util.DriverInfo.DRIVER_VERSION);
    }

    private static com.zaxxer.hikari.HikariDataSource pool(int size) {
        return Database.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                size, Duration.ofMinutes(30));
    }

    private static int backendPid(DataSource pool) throws Exception {
        try (var c = pool.getConnection(); var rs = c.createStatement().executeQuery("select pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int serverConnections() throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var rs = c.createStatement().executeQuery(
                     "select count(*) from pg_stat_activity where application_name = 'orders-api'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
