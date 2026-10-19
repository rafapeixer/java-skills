package example;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class Database {
    private Database() {}

    public static HikariDataSource create(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        // MISTAKE: "virtual threads are cheap, so give each one a connection". Connections
        // are PostgreSQL processes; past a few per core they add contention, not throughput.
        config.setMaximumPoolSize(1000);
        // MISTAKE: minimumIdle below the maximum turns the fixed-size pool into a
        // flexible one that opens connections during the burst that needs them.
        config.setMinimumIdle(5);
        // MISTAKE: 0 means no lifetime limit, so the pool never replaces a connection
        // before the database, PgBouncer or a load balancer closes it.
        config.setMaxLifetime(0);
        // MISTAKE: keepalive disabled; an idle connection dropped by a firewall is only
        // found when a request borrows it.
        config.setKeepaliveTime(0);
        // MISTAKE: prepareThreshold=0 copied from an old PgBouncer guide. It turns off
        // server-side prepared statements for a service that connects to PostgreSQL directly.
        config.addDataSourceProperty("prepareThreshold", "0");
        return new HikariDataSource(config);
    }

    public static long countOrders(DataSource pool) throws SQLException {
        // MISTAKE: the connection is never closed, so it never returns to the pool.
        // After maximumPoolSize calls every borrow waits for connectionTimeout and fails.
        Connection c = pool.getConnection();
        ResultSet rs = c.createStatement().executeQuery("select count(*) from orders");
        rs.next();
        return rs.getLong(1);
    }
}
