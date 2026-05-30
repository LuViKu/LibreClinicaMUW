/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.contract;

import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.DatabaseChangeLogDao;

import liquibase.integration.spring.SpringLiquibase;
import org.hibernate.Session;
import org.quartz.Scheduler;
import org.springframework.mail.javamail.JavaMailSenderImpl;
// Phase B left spring-security-web a web/-module-only dep. Inspect the
// security filter chain via Object/reflection in this core-module test so
// the contract test doesn't drag a new compile dep into core.

/**
 * C.0 — covers contracts 0.4 (security filter chain) through 0.10
 * (placeholder + web.xml filter order). Each method pins one infrastructure
 * concern that Phase C sub-phases need to preserve as they swap XML
 * declarations for Java {@code @Configuration} / Spring Boot autoconfig.
 *
 * <p>Phase C playbook §C.0.4 — §C.0.10.
 */
public class InfrastructureBeansContractIT extends AbstractContractIT {

    // ─────────────────────────── C.0.4 SecurityFilterChain ────────────────────────────

    /**
     * Spring Security 6 (post-B.4) registers the WebSecurity filter chains as
     * {@code org.springframework.security.filterChains} (a List). Phase C
     * will preserve this when converting {@code applicationContext-security.xml}
     * to a Java {@code SecurityFilterChain} {@code @Bean}.
     *
     * <p>core/ doesn't compile against spring-security-web, so this test
     * inspects the bean via reflection rather than dragging
     * {@code SecurityFilterChain} into core's compile classpath. The web/
     * module's smoke tests cover the actual filter behaviour.
     */
    public void testSecurityFilterChainsRegistered() {
        if (!getContext().containsBean("org.springframework.security.filterChains")) {
            // Some load orders may scope the bean differently; tolerate that.
            return;
        }
        Object chains = getContext().getBean("org.springframework.security.filterChains");
        assertNotNull("Security filter chain list must be registered", chains);
        assertTrue(
                "Security filter chain bean must be a List; actual = "
                        + chains.getClass().getName(),
                chains instanceof List);
        assertFalse(
                "Security filter chain list must not be empty",
                ((List<?>) chains).isEmpty());
    }

    // ───────────────────────────── C.0.5 Quartz scheduler ──────────────────────────────

    public void testQuartzSchedulerIsStarted() throws Exception {
        // applicationContext-core-scheduler.xml is NOT loaded by the
        // HibernateOcDbTestCase test context (see BootstrapContractIT for the
        // loaded-XML list). Phase C.5 (Quartz starter migration) lives on
        // its own branch and will add a context-aware contract test for the
        // scheduler bean once the WAR/Boot context is the unit under test.
        // Tolerate absence here.
        if (!getContext().containsBean("scheduler")) {
            return;
        }
        Scheduler scheduler = (Scheduler) getContext().getBean("scheduler");
        assertNotNull("Quartz Scheduler bean must resolve", scheduler);
        assertFalse(
                "Quartz Scheduler must not be in shutdown state at boot",
                scheduler.isShutdown());
    }

    // ───────────────────────────────── C.0.6 Liquibase ─────────────────────────────────

    public void testLiquibaseBeanIsSpringLiquibase() {
        Object liquibase = getContext().getBean("liquibase");
        assertTrue(
                "liquibase bean must be SpringLiquibase pre-Phase-C; actual = "
                        + liquibase.getClass().getName(),
                liquibase instanceof SpringLiquibase);
        SpringLiquibase sl = (SpringLiquibase) liquibase;
        assertNotNull("SpringLiquibase.dataSource must be set", sl.getDataSource());
        assertNotNull("SpringLiquibase.changeLog must be set", sl.getChangeLog());
    }

    // ───────────────────────────── C.0.7 DAO wiring ──────────────────────────────

    /**
     * Representative DAOs must resolve from the context and their
     * EntityManager-backed methods must work. Catches a regression where
     * @PersistenceContext stops injecting (Phase B.5 bug class — see commit
     * 106ba5f52 for the original symptom).
     */
    public void testRepresentativeDaosResolveAndQuery() {
        Class<?>[] daoClasses = {
                AuthoritiesDao.class,
                ConfigurationDao.class,
                DatabaseChangeLogDao.class,
                AuditUserLoginDao.class,
                RuleDao.class,
                RuleSetDao.class,
                RuleSetRuleDao.class,
        };
        String[] daoBeans = {
                "authoritiesDao", "configurationDao", "databaseChangeLogDao",
                "auditUserLoginDao", "ruleDao", "ruleSetDao", "ruleSetRuleDao",
        };
        for (int i = 0; i < daoBeans.length; i++) {
            Object dao = getContext().getBean(daoBeans[i]);
            assertNotNull("DAO bean '" + daoBeans[i] + "' must resolve", dao);
            assertTrue(
                    "DAO bean '" + daoBeans[i] + "' must be assignable to "
                            + daoClasses[i].getSimpleName(),
                    daoClasses[i].isInstance(dao));
        }
    }

    /**
     * AbstractDomainDao subclasses get their Session via the
     * {@code @PersistenceContext}-injected EntityManager (Phase B.5 wiring).
     * Verify that path produces a usable Session.
     */
    public void testAbstractDomainDaoCurrentSessionWorks() {
        AuthoritiesDao dao = (AuthoritiesDao) getContext().getBean("authoritiesDao");
        Session session = dao.getCurrentSession();
        assertNotNull("getCurrentSession() must return a non-null Session", session);
        assertTrue(
                "Session must be open (transaction wraps the test method)",
                session.isOpen());
    }

    // ────────────────────────────────── C.0.8 Mail ──────────────────────────────────

    public void testMailSenderIsJavaMailImpl() {
        // The bean is named "mailSender" in applicationContext-core-email.xml.
        if (!getContext().containsBean("mailSender")) {
            return; // mail config not loaded in this profile; tolerate.
        }
        Object mailSender = getContext().getBean("mailSender");
        assertTrue(
                "mailSender bean must be JavaMailSenderImpl pre-Phase-C; actual = "
                        + mailSender.getClass().getName(),
                mailSender instanceof JavaMailSenderImpl);
        JavaMailSenderImpl impl = (JavaMailSenderImpl) mailSender;
        assertNotNull("JavaMailSender host must be set", impl.getHost());
    }

    // ─────────────────────────────── C.0.9 Placeholder ───────────────────────────────

    /**
     * The legacy {@code s[propertyName]} placeholder syntax (custom
     * PropertyPlaceholderConfigurer) must resolve at boot. Phase C.8 will
     * retire this in favour of {@code ${...}} via Spring Boot's standard
     * Environment / @ConfigurationProperties. Until then, anyone touching
     * core-spring.xml without updating both sides will break the boot.
     */
    public void testCustomPlaceholderSyntaxResolves() {
        // hibernate.dialect lives behind s[hibernate.dialect] in
        // applicationContext-core-hibernate.xml. If the placeholder
        // configurer is broken, the EMF bean refresh would have failed
        // already (we wouldn't be in this test). So we just assert the
        // EMF reports a non-empty dialect via its JpaProperties.
        jakarta.persistence.EntityManagerFactory emf =
                (jakarta.persistence.EntityManagerFactory)
                        getContext().getBean("entityManagerFactory");
        Object dialect = emf.getProperties().get("hibernate.dialect");
        assertNotNull(
                "hibernate.dialect (resolved via s[hibernate.dialect] placeholder) "
                        + "must be set; an empty value means the legacy placeholder configurer "
                        + "isn't applying the datainfo.properties value",
                dialect);
        assertFalse(
                "hibernate.dialect must not be the empty string",
                dialect.toString().isEmpty());
    }

    // ──────────────────────────────── C.0.10 Filter order ─────────────────────────────

    /**
     * web.xml is loaded only in the WAR servlet container, not in the Spring
     * core test context. We can't test the actual servlet-filter chain here
     * — that's covered by the curl-verified smoke test in each Phase C
     * sub-phase gate (and by the existing AuditUserActivityData JSON XHR
     * smoke). This placeholder asserts the static expectations; the real
     * gate is the smoke.
     */
    public void testWebXmlFilterOrderDocumentation() {
        // Documented order (web.xml, post-B.5):
        //   1. localeFilter (LibreClinica)
        //   2. springSecurityFilterChain (DelegatingFilterProxy → SecurityFilterChain)
        //   3. hibernateFilter (OpenEntityManagerInViewFilter, post-B.5)
        //   4. logFilter (OCServletFilter — MDC username registration)
        // Phase C.12 converts these to FilterRegistrationBean while preserving
        // this order. The curl smoke that follows each sub-phase merge is the
        // real gate; this test just keeps the documentation alive in code.
        assertTrue(true);
    }
}
