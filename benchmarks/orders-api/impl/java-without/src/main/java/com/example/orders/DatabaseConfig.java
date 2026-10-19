package com.example.orders;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    /**
     * Postgres allows 80 connections for this service across 8 replicas. A fixed pool of 8 per
     * replica uses 64, leaving headroom for a rolling deploy that briefly runs extra replicas and
     * for operators connecting manually. Virtual threads make request concurrency effectively
     * unbounded, so the pool is the real limit on DB concurrency: requests that cannot get a
     * connection within the connection timeout fail fast with 503 instead of piling up.
     */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(
            @Value("${DATABASE_URL:}") String databaseUrl,
            @Value("${DB_POOL_SIZE:8}") int poolSize,
            @Value("${DB_CONNECTION_TIMEOUT_MS:2000}") long connectionTimeoutMs,
            @Value("${DB_STATEMENT_TIMEOUT_MS:5000}") long statementTimeoutMs) {
        DatabaseUrl url = DatabaseUrl.parse(databaseUrl);

        HikariConfig config = new HikariConfig();
        config.setPoolName("orders-db");
        config.setJdbcUrl(url.jdbcUrl());
        if (url.username() != null) {
            config.setUsername(url.username());
        }
        if (url.password() != null) {
            config.setPassword(url.password());
        }
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setValidationTimeout(1_000);
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        config.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        config.addDataSourceProperty("ApplicationName", "orders-api");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("connectTimeout", "5");
        // Backstop for a dead peer; statement_timeout handles slow queries.
        config.addDataSourceProperty("socketTimeout", "30");
        return new HikariDataSource(config);
    }
}
