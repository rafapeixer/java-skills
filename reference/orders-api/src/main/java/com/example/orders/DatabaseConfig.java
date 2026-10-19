package com.example.orders;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    // The service may hold 80 connections in total. 8 replicas, plus a rolling deploy that
    // starts new instances before old ones stop (25% surge, rounded up: 2):
    // 80 / (8 + 2) = 8 per instance. Never sized from thread or CPU counts.
    static final int POOL_SIZE = 8;

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(@Value("${database.url}") String databaseUrl) {
        HikariConfig config = new HikariConfig();
        applyUrl(config, databaseUrl);
        config.setPoolName("orders-db");
        config.setMaximumPoolSize(POOL_SIZE);
        // minimumIdle stays unset: a fixed-size pool, no connection churn during bursts.
        config.setMaxLifetime(Duration.ofMinutes(30).minusSeconds(30).toMillis());
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        // A full pool fails the borrow fast (mapped to 503) instead of queueing for 30 s.
        config.setConnectionTimeout(500);
        config.setValidationTimeout(250);
        // Start even when the database is down: /healthz must not depend on it.
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("ApplicationName", "orders-api");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("connectTimeout", "5");
        // Server-side bounds; socketTimeout (seconds) sits above them for a dead peer.
        config.addDataSourceProperty("options",
                "-c statement_timeout=5000 -c idle_in_transaction_session_timeout=10000");
        config.addDataSourceProperty("socketTimeout", "15");
        return new HikariDataSource(config);
    }

    /** Accepts postgres://user:pass@host:port/db?params, or a jdbc:postgresql: URL as-is. */
    static void applyUrl(HikariConfig config, String databaseUrl) {
        if (databaseUrl.startsWith("jdbc:")) {
            config.setJdbcUrl(databaseUrl);
            return;
        }
        URI uri;
        try {
            uri = URI.create(databaseUrl);
        } catch (IllegalArgumentException e) {
            // The cause is dropped on purpose: its message contains the URL and its password.
            throw new IllegalArgumentException("DATABASE_URL is not a valid URL");
        }
        if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must start with postgres:// or postgresql://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("DATABASE_URL has no host");
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        config.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + path + query);

        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            config.setUsername(decode(colon < 0 ? userInfo : userInfo.substring(0, colon)));
            if (colon >= 0) {
                config.setPassword(decode(userInfo.substring(colon + 1)));
            }
        }
    }

    private static String decode(String s) {
        // Percent-decoding only: a literal '+' in a password stays a '+'.
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
