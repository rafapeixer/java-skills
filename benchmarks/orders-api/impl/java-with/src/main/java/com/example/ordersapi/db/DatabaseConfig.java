package com.example.ordersapi.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    /**
     * Pool size = connection budget / most pods that can run at once. The service may hold
     * 80 connections; 8 replicas can double to 16 during a rolling deploy, so 80 / 16 = 5.
     * Never derive this from thread count: with virtual threads the pool is the bound on
     * concurrent database work, and a full pool fails a borrow in 500 ms (answered as 503).
     */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(@Value("${orders.database-url}") String databaseUrl,
                                @Value("${orders.db.pool-size}") int poolSize) {
        if (poolSize < 2) {
            throw new IllegalArgumentException("orders.db.pool-size must be at least 2, was " + poolSize);
        }
        PostgresUrl url = PostgresUrl.parse(databaseUrl);

        HikariConfig config = new HikariConfig();
        config.setPoolName("orders-db");
        config.setJdbcUrl(url.jdbcUrl());
        if (url.user() != null) {
            config.setUsername(url.user());
        }
        if (url.password() != null) {
            config.setPassword(url.password());
        }
        config.setMaximumPoolSize(poolSize);
        // minimumIdle stays unset: a fixed-size pool, no connection churn during bursts.
        // RDS imposes no connection lifetime and no proxy sits in between; keep 30 min.
        config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        config.setConnectionTimeout(500);
        config.setValidationTimeout(250);
        config.addDataSourceProperty("ApplicationName", "orders-api");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        // Server cancels slow statements first; the socket timeout catches a dead peer.
        config.addDataSourceProperty("options",
                "-c statement_timeout=5000 -c idle_in_transaction_session_timeout=10000");
        config.addDataSourceProperty("socketTimeout", "15");
        return new HikariDataSource(config);
    }
}
