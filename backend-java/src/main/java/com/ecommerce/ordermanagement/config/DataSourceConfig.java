package com.ecommerce.ordermanagement.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;

/**
 * The single PostgreSQL connection factory.
 *
 * <p>Kept deliberately on the same {@code PG_DSN} shape the former asyncpg pool
 * used ({@code postgresql://user:pass@host:port/db}); this translates that DSN
 * into the JDBC form Spring's DataSource needs, so no deployment file changes.</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(AppProperties props) {
        URI dsn = URI.create(props.getPgDsn());
        String host = dsn.getHost();
        int port = dsn.getPort() == -1 ? 5432 : dsn.getPort();
        String database = dsn.getPath() == null ? "" : dsn.getPath().replaceFirst("^/", "");
        String userInfo = dsn.getUserInfo() == null ? "" : dsn.getUserInfo();
        String user = userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
        String password = userInfo.contains(":") ? userInfo.substring(userInfo.indexOf(':') + 1) : "";

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(1);
        ds.setPoolName("ecommerce-pg");
        // Fail fast when PostgreSQL is down so /api/health reports DOWN promptly
        // instead of blocking the request on the driver's default timeout.
        ds.setConnectionTimeout(5000);
        ds.setValidationTimeout(3000);
        return ds;
    }

    /** The explicit transaction boundary the order service drives. */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
