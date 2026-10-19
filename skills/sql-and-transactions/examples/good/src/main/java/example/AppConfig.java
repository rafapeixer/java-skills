package example;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
public class AppConfig {
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JdbcClient jdbcClient(JdbcTemplate template) {
        return JdbcClient.create(template);
    }

    @Bean
    OrderService orderService(JdbcClient jdbc, JdbcTemplate template) {
        return new OrderService(jdbc, template);
    }

    @Bean
    Transactions repeatableRead(PlatformTransactionManager manager) {
        return new Transactions(manager, TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
}
