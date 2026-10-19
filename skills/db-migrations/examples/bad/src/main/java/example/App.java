package example;

import org.flywaydb.core.Flyway;

public final class App {
    private App() {}

    public static void main(String[] args) {
        // MISTAKE: every replica migrates on startup with the service's own credentials.
        // Ten pods wait on one lock, a slow migration fails their startup probes, and the
        // rollout loops while the old pods already serve against the new schema.
        Flyway.configure()
                .dataSource(System.getenv("JDBC_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
                // MISTAKE: validation turned off to get past the checksum error from
                // editing V1, which hides every future edit and missing file as well.
                .validateOnMigrate(false)
                .load()
                .migrate();
        startHttpServer();
    }

    private static void startHttpServer() {
        // The service starts serving here.
    }
}
