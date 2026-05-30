package at.ac.meduniwien.ophthalmology.libreclinica.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.ac.meduniwien.ophthalmology.libreclinica.core.ExtendedBasicDataSource;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.QueryStore;

/**
 * Phase C.3-finish: Java replacement for the dataSource + queryStore beans
 * from applicationContext-core-db.xml.
 * <p>
 * Wires:
 * <ul>
 *   <li>{@code dataSource}  — {@link ExtendedBasicDataSource} (DBCP 1.x
 *       {@code BasicDataSource} subclass adding {@code SetBigStringTryClob}).
 *       Pool sizing identical to the XML form (maxActive=50, maxIdle=2,
 *       maxWait=180s, removeAbandoned timeout=5min, eviction every 5min,
 *       idle eviction at 10min).</li>
 *   <li>{@code queryStore}  — query catalog initialised against the DataSource;
 *       consumed by {@code viewNotesDao}.</li>
 * </ul>
 * The {@code liquibase} bean stays in the XML stub for now;
 * C.13 retires it in favour of {@code spring-boot-starter-liquibase}
 * autoconfig, which removes the {@code liquibase-core} runtime-scope quirk
 * (a compile-scope dep would be needed to define the {@code SpringLiquibase}
 * bean from Java).
 * <p>
 * JDBC URL hardening ({@code sslMode}, {@code scramMaxIterations}, opt-in via
 * {@code datainfo.properties}) is appended by {@code CoreResources.setDatabaseProperties()}
 * before the {@code url} property reaches this bean — see C.3 commit
 * {@code a7bc9d3ec}.
 */
@Configuration
public class DataSourceConfig {

    @Value("${driver}")
    private String driverClassName;

    @Value("${url}")
    private String url;

    @Value("${username}")
    private String username;

    @Value("${password}")
    private String password;

    @Bean
    public ExtendedBasicDataSource dataSource() {
        ExtendedBasicDataSource ds = new ExtendedBasicDataSource();
        ds.setDriverClassName(driverClassName);
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaxActive(50);
        ds.setMaxIdle(2);
        ds.setMaxWait(180_000);
        ds.setRemoveAbandoned(true);
        ds.setRemoveAbandonedTimeout(300);
        ds.setLogAbandoned(true);
        ds.setTestWhileIdle(true);
        ds.setTestOnReturn(true);
        ds.setTimeBetweenEvictionRunsMillis(300_000);
        ds.setMinEvictableIdleTimeMillis(600_000);
        ds.setBigStringTryClob("true");
        return ds;
    }

    @Bean(initMethod = "init")
    public QueryStore queryStore(ExtendedBasicDataSource dataSource) {
        QueryStore queryStore = new QueryStore();
        queryStore.setDataSource(dataSource);
        return queryStore;
    }
}
