package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class MigrationsTest {
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
    void filesFollowTheConventions() throws Exception {
        Path dir = Path.of(MigrationsTest.class.getResource("/db/migration").toURI());
        List<Path> sql;
        try (var files = Files.list(dir)) {
            sql = files.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        assertEquals(3, sql.size());
        for (Path file : sql) {
            String name = file.getFileName().toString();
            String text = Files.readString(file);
            assertTrue(name.matches("V\\d+__[a-z0-9_]+\\.sql"), name + " is not V<n>__<name>.sql");
            if (text.contains("CONCURRENTLY")) {
                Path conf = file.resolveSibling(name + ".conf");
                assertTrue(Files.readString(conf).contains("executeInTransaction=false"), name);
                assertTrue(text.contains("SET lock_timeout") && text.contains("RESET lock_timeout"), name);
            } else {
                assertTrue(text.contains("SET LOCAL lock_timeout"), name + " has no lock_timeout");
            }
        }
    }

    @Test
    void misnamedFileFailsInsteadOfBeingSkipped() {
        Flyway flyway = Migrate.flyway(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var misnamed = Flyway.configure().configuration(flyway.getConfiguration()).locations("classpath:db/misnamed");
        assertThrows(FlywayException.class, () -> misnamed.load().info());
        assertEquals(0, misnamed.validateMigrationNaming(false).load().info().pending().length);
    }

    @Test
    void lockTimeoutFailsFastAndTheRetrySucceeds() throws Exception {
        Flyway flyway = Migrate.flyway(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway toV1 = Flyway.configure().configuration(flyway.getConfiguration()).target("1").load();
        assertEquals(1, toV1.migrate().migrationsExecuted);

        try (Connection report = connect()) {
            // A long report holds ACCESS SHARE on orders; ALTER TABLE needs ACCESS EXCLUSIVE.
            report.setAutoCommit(false);
            report.createStatement().executeQuery("select count(*) from orders").close();
            long start = System.nanoTime();
            assertThrows(FlywayException.class, flyway::migrate);
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(waitedMs < 15_000, "migration waited " + waitedMs + " ms for the lock");
            report.commit();
        }

        // The failed V2 rolled back; the job's retry applies V2 and V3.
        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals("3", flyway.info().current().getVersion().getVersion());
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (Connection c = connect(); var rs = c.createStatement().executeQuery(
                "select indisvalid from pg_index where indexrelid = 'orders_customer_created_idx'::regclass")) {
            assertTrue(rs.next() && rs.getBoolean(1), "index is missing or INVALID");
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
