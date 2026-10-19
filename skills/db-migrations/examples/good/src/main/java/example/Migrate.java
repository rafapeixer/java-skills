package example;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

/**
 * The migration command. A Kubernetes Job or a pipeline step runs it once, before the
 * rollout starts; the service itself never migrates on startup.
 */
public final class Migrate {
    private Migrate() {}

    public static void main(String[] args) {
        MigrateResult result = flyway(System.getenv("MIGRATE_JDBC_URL"),
                System.getenv("MIGRATE_USER"), System.getenv("MIGRATE_PASSWORD")).migrate();
        System.out.printf("migrated %d, schema now at %s%n", result.migrationsExecuted, result.targetSchemaVersion);
    }

    public static Flyway flyway(String url, String user, String password) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                // Fail on files Flyway would otherwise skip, such as add_index.sql.
                .validateMigrationNaming(true);
        // Session-level advisory lock. Flyway's docs say the default transactional lock
        // causes problems with CREATE INDEX CONCURRENTLY.
        config.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        return config.load();
    }
}
