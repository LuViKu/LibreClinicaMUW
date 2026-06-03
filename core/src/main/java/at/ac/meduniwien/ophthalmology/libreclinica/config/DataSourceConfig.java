package at.ac.meduniwien.ophthalmology.libreclinica.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import at.ac.meduniwien.ophthalmology.libreclinica.core.ExtendedBasicDataSource;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.QueryStore;
import liquibase.integration.spring.SpringLiquibase;

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
 * The {@code liquibase} bean (Phase C.13, 2026-05-30) is also defined
 * here as an explicit {@link SpringLiquibase} {@code @Bean}. Spring Boot's
 * {@code LiquibaseAutoConfiguration} activates only after C.14 flips the
 * WAR → JAR; until then, the explicit bean keeps Liquibase parity with
 * the prior {@code <bean id="liquibase"/>} XML form.
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

    @Bean
    @DependsOn("coreResources")
    public SpringLiquibase liquibase(ExtendedBasicDataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:migration/master.xml");
        // Phase E.5 chronic CI fix (2026-06-03): the demo-data seed
        // changesets (lc-muw-2026-06-01-seed-demo-data*.xml + the
        // -seed-demo-test-users.xml + the -prune-dn-item-data-map-*
        // cleanup) are tagged context="demo". They should NOT run on
        // production deployments (Vienna single-site clinic doesn't
        // need M-001..M-007 mock patients) and they break the legacy
        // DBUnit DAO tests (the seeded dn_item_data_map FKs block
        // DBUnit's CLEAN_INSERT on item_data).
        //
        // Liquibase semantics: when contexts is null/empty, EVERY
        // changeset runs — including context-tagged ones. To exclude
        // a context the runtime contexts must use the negation form
        // "!demo". So:
        //   - LIQUIBASE_CONTEXTS unset (prod / CI tests) → "!demo":
        //     untagged changesets run, demo ones are skipped.
        //   - LIQUIBASE_CONTEXTS=demo (dev compose) → "demo":
        //     untagged AND demo-tagged changesets run.
        //   - LIQUIBASE_CONTEXTS=<other> → use the operator's value
        //     verbatim (escape hatch for adding new contexts later).
        String contexts = System.getenv("LIQUIBASE_CONTEXTS");
        if (contexts == null || contexts.isBlank()) {
            contexts = "!demo";
        }
        liquibase.setContexts(contexts);
        return liquibase;
    }
}
