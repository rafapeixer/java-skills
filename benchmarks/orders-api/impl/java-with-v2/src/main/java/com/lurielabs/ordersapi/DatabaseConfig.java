package com.lurielabs.ordersapi;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    // Budget: 80 connections for this service. At most 8 replicas plus a rolling-deploy surge
    // of 25% rounded up (2) run at once: 80 / (8 + 2) = 8 per instance. If deploys start all new
    // instances before stopping old ones, the peak is 16 and this must drop to 5.
    static final int POOL_SIZE = 8;

    @Bean
    HikariDataSource dataSource(@Value("${DATABASE_URL}") String databaseUrl) {
        PostgresUrl url = PostgresUrl.parse(databaseUrl);
        HikariConfig config = new HikariConfig();
        config.setPoolName("orders-api");
        config.setJdbcUrl(url.jdbcUrl());
        if (url.user() != null) {
            config.setUsername(url.user());
        }
        if (url.password() != null) {
            config.setPassword(url.password());
        }
        config.setMaximumPoolSize(POOL_SIZE);
        // minimumIdle stays unset: a fixed-size pool, no connection churn during bursts.
        config.setMaxLifetime(Duration.ofMinutes(30).minusSeconds(30).toMillis());
        config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());
        // A full pool fails the borrow in 500 ms and the request gets a 503, instead of queueing.
        config.setConnectionTimeout(500);
        config.setValidationTimeout(250);
        config.addDataSourceProperty("ApplicationName", "orders-api");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        // Server-side bounds on every statement and open transaction; socketTimeout (seconds)
        // sits above statement_timeout and only catches a dead network peer.
        config.addDataSourceProperty("options", "-c statement_timeout=5000 -c idle_in_transaction_session_timeout=10000");
        config.addDataSourceProperty("socketTimeout", "10");
        return new HikariDataSource(config);
    }

    /** Accepts postgres://user:pass@host:port/db?params, postgresql://... or jdbc:postgresql://... */
    record PostgresUrl(String jdbcUrl, String user, String password) {

        static PostgresUrl parse(String raw) {
            String value = raw.strip();
            if (value.startsWith("jdbc:")) {
                value = value.substring("jdbc:".length());
            }
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("DATABASE_URL is not a valid URL", e);
            }
            if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
                throw new IllegalArgumentException("DATABASE_URL must use the postgres:// or postgresql:// scheme");
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("DATABASE_URL has no host");
            }
            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
            if (uri.getPort() > 0) {
                jdbc.append(':').append(uri.getPort());
            }
            jdbc.append(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (uri.getRawQuery() != null) {
                jdbc.append('?').append(uri.getRawQuery());
            }
            String user = null;
            String password = null;
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
                password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
            }
            return new PostgresUrl(jdbc.toString(), user, password);
        }

        private static String decode(String s) {
            // Percent-decoding only: a literal '+' in a password stays a '+'.
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "PostgresUrl[jdbcUrl=" + jdbcUrl + ", user=" + user + ", password=***]";
        }
    }
}
