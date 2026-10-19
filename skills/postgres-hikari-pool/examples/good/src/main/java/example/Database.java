package example;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;

/** Builds the service's only connection pool. */
public final class Database {
    private Database() {}

    /**
     * The pool size is this service's share of max_connections divided by the most
     * pods that can run at once: the autoscaler maximum plus the rolling-update surge.
     * Kubernetes rounds maxSurge up from a percentage and defaults it to 25%, so
     * 8 replicas run at most 8 + 2 = 10 pods during a deploy, not 16.
     */
    public static int poolSize(int connectionBudget, int maxReplicas, int maxSurgePercent) {
        int surge = Math.ceilDiv(maxReplicas * maxSurgePercent, 100);
        int peakPods = maxReplicas + surge;
        int size = connectionBudget / peakPods;
        if (size < 2) {
            throw new IllegalArgumentException("budget " + connectionBudget + " for " + peakPods
                    + " pods leaves under 2 connections each; add a pooler or raise the budget");
        }
        return size;
    }

    /**
     * @param infraLimit the shortest connection lifetime imposed by the database, a proxy
     *                   or a load balancer between the service and PostgreSQL
     */
    public static HikariDataSource create(String jdbcUrl, String user, String password,
                                          int poolSize, Duration infraLimit) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("orders-db");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        // minimumIdle stays unset, so the pool is fixed-size: no connection churn under bursts.
        config.setMaxLifetime(infraLimit.minusSeconds(30).toMillis());
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        // A full pool fails the borrow in 500 ms instead of queueing requests for 30 s.
        config.setConnectionTimeout(500);
        config.setValidationTimeout(250);
        config.addDataSourceProperty("ApplicationName", "orders-api");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        // Seconds. A backstop above the role's statement_timeout for a dead network peer.
        config.addDataSourceProperty("socketTimeout", "60");
        return new HikariDataSource(config);
    }
}
