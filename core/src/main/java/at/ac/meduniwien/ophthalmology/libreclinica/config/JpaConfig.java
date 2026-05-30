package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase C.4: Java replacement for the infrastructure half of
 * applicationContext-core-hibernate.xml.
 * <p>
 * Wires:
 * <ul>
 *   <li>{@code entityManagerFactory} — JPA-style
 *       {@link LocalContainerEntityManagerFactoryBean} with Hibernate 6.4 vendor
 *       adapter and the {@code at.ac.meduniwien.ophthalmology.libreclinica.domain}
 *       package scan. Drop-in replacement for the XML form; identical jpaProperties.</li>
 *   <li>{@code transactionManager} — {@link JpaTransactionManager} (replaces
 *       the H5-era {@code HibernateTransactionManager}, Phase B.5).</li>
 *   <li>{@code sharedTransactionTemplate} — {@link TransactionTemplate} used
 *       by a handful of legacy callers that need programmatic tx scope.</li>
 *   <li>{@code PersistenceAnnotationBeanPostProcessor} — processes
 *       {@code @PersistenceUnit} / {@code @PersistenceContext} on DAOs. Spring 6
 *       no longer auto-registers it via {@code <context:annotation-config/>}
 *       outside JPA-aware contexts.</li>
 * </ul>
 * The {@code <tx:annotation-driven proxy-target-class="true"/>} XML directive
 * is replaced by {@link EnableTransactionManagement#proxyTargetClass()}.
 * <p>
 * The ~50 DAO beans ({@code parent="abstractDomainDao" autowire="byName"})
 * stay in the XML stub for now — converting them to Java would force a
 * coordinated rename across the JPA persistence-unit name-injection contract.
 * They move in a later C.x sub-phase or get scanned via
 * {@code @ComponentScan} once their classes are annotated with
 * {@code @Repository}.
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
public class JpaConfig {

    @Value("${hibernate.dialect}")
    private String hibernateDialect;

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPersistenceUnitName("libreclinica");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emf.setPackagesToScan("at.ac.meduniwien.ophthalmology.libreclinica.domain");

        Properties jpa = new Properties();
        jpa.setProperty("hibernate.show_sql", "false");
        jpa.setProperty("hibernate.format_sql", "true");
        jpa.setProperty("hibernate.dialect", hibernateDialect);
        jpa.setProperty("hibernate.generate_statistics", "true");
        // Phase B.5: 2L cache re-enabled via JCache (JSR-107) + ehcache 3 (jakarta classifier).
        jpa.setProperty("hibernate.cache.use_query_cache", "true");
        jpa.setProperty("hibernate.cache.use_second_level_cache", "true");
        jpa.setProperty("hibernate.cache.region.factory_class", "jcache");
        jpa.setProperty("hibernate.javax.cache.provider", "org.ehcache.jsr107.EhcacheCachingProvider");
        jpa.setProperty("hibernate.javax.cache.missing_cache_strategy", "create");
        jpa.setProperty("hibernate.allow_update_outside_transaction", "true");
        jpa.setProperty(
                "hibernate.implicit_naming_strategy",
                "at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.cfg.LegacyImprovedImplicitNamingStrategy");
        // Phase B.5: Hibernate 6 enforces @SequenceGenerator.allocationSize matches DB INCREMENT BY.
        // LibreClinica schema uses INCREMENT BY 1; entities default to allocationSize=50.
        // LOG so Hibernate warns rather than refuses to start.
        jpa.setProperty("hibernate.id.sequence.increment_size_mismatch_strategy", "LOG");
        // Phase B.5: disable pooled-optimizer to avoid duplicate-id inserts against
        // INCREMENT BY 1 sequences. "none" forces a DB round-trip per id allocation.
        jpa.setProperty("hibernate.id.optimizer.pooled.preferred", "none");
        emf.setJpaProperties(jpa);

        return emf;
    }

    @Bean
    public JpaTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        JpaTransactionManager tm = new JpaTransactionManager();
        tm.setEntityManagerFactory(entityManagerFactory.getObject());
        return tm;
    }

    @Bean
    public TransactionTemplate sharedTransactionTemplate(JpaTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate();
        template.setTransactionManager(transactionManager);
        return template;
    }

    @Bean
    public static PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
        return new PersistenceAnnotationBeanPostProcessor();
    }
}
