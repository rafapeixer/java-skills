package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

class DatabaseConfigTest {

    @Test
    void postgresUrlBecomesJdbcUrlAndCredentials() {
        HikariConfig config = new HikariConfig();

        DatabaseConfig.applyUrl(config, "postgres://orders:p%40ss+w0rd@db.internal:6543/orders?sslmode=require");

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/orders?sslmode=require");
        assertThat(config.getUsername()).isEqualTo("orders");
        assertThat(config.getPassword()).isEqualTo("p@ss+w0rd"); // '+' stays a '+'
    }

    @Test
    void portDefaultsTo5432AndCredentialsAreOptional() {
        HikariConfig config = new HikariConfig();

        DatabaseConfig.applyUrl(config, "postgresql://db/orders");

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/orders");
        assertThat(config.getUsername()).isNull();
    }

    @Test
    void jdbcUrlIsUsedAsIs() {
        HikariConfig config = new HikariConfig();

        DatabaseConfig.applyUrl(config, "jdbc:postgresql://db:5432/orders?user=a");

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/orders?user=a");
    }

    @Test
    void invalidUrlsFailWithoutEchoingThePassword() {
        assertThatThrownBy(() -> DatabaseConfig.applyUrl(new HikariConfig(), "mysql://u:secret@db/orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> DatabaseConfig.applyUrl(new HikariConfig(), "postgres://u:sec ret@db/orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("sec ret");
        assertThatThrownBy(() -> DatabaseConfig.applyUrl(new HikariConfig(), "postgres:///orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DATABASE_URL has no host");
    }
}
