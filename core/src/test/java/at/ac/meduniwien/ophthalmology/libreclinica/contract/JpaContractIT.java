/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.contract;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;


import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * C.0.3 — pins the JPA / Hibernate 6 wiring. Phase C will convert
 * {@code applicationContext-core-hibernate.xml} to a Java {@code @Configuration}
 * class using Spring Boot's {@code JpaProperties} + {@code HibernateProperties}.
 * This test catches: wrong EMF type, wrong transaction manager type, dropped
 * entity classes, dialect drift.
 *
 * <p>Phase C playbook §C.0.3.
 */
public class JpaContractIT extends AbstractContractIT {

    /**
     * Pre-Phase-C entity count, captured against the post-B.5 /
     * H6+JPA state (lc-develop HEAD 48d53ace5). {@code packagesToScan} on
     * {@code at.ac.meduniwien.ophthalmology.libreclinica.domain} discovers
     * roughly this many {@code @Entity} classes. New entities are fine —
     * but losing more than ~10% would suggest an unintended scan drop.
     */
    private static final int MIN_EXPECTED_ENTITY_COUNT = 75;

    public void testEntityManagerFactoryIsLocalContainer() {
        EntityManagerFactory emf = (EntityManagerFactory) getContext().getBean("entityManagerFactory");
        assertNotNull("entityManagerFactory bean must resolve", emf);
        // The bean is wired via LocalContainerEntityManagerFactoryBean
        // (Phase B.5 swap); the actual EMF instance unwraps to it.
        Object raw = getContext().getBean("&entityManagerFactory");
        assertTrue(
                "entityManagerFactory FactoryBean must be LocalContainerEntityManagerFactoryBean; "
                        + "actual = " + raw.getClass().getName(),
                raw instanceof LocalContainerEntityManagerFactoryBean);
    }

    public void testTransactionManagerIsJpa() {
        PlatformTransactionManager tm = (PlatformTransactionManager) getContext().getBean("transactionManager");
        assertTrue(
                "transactionManager must be JpaTransactionManager (post-Phase-B.5); "
                        + "actual = " + tm.getClass().getName(),
                tm instanceof JpaTransactionManager);
    }

    public void testHibernateSessionFactoryUnwrapsCleanly() {
        EntityManagerFactory emf = (EntityManagerFactory) getContext().getBean("entityManagerFactory");
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        assertNotNull(
                "Hibernate SessionFactory must be unwrappable from the JPA EntityManagerFactory "
                        + "— legacy SessionFactory-using callers (BatchCRFMigrationController, "
                        + "HelperObject) rely on this",
                sf);
    }

    public void testEntityScanFindsBaseline() {
        EntityManagerFactory emf = (EntityManagerFactory) getContext().getBean("entityManagerFactory");
        int entityCount = emf.getMetamodel().getEntities().size();
        assertTrue(
                "JPA entity-scan regression — found " + entityCount + " managed entities, "
                        + "expected at least " + MIN_EXPECTED_ENTITY_COUNT + ". A drop usually "
                        + "means packagesToScan no longer reaches a domain subpackage or an "
                        + "@Entity was inadvertently dropped.",
                entityCount >= MIN_EXPECTED_ENTITY_COUNT);
    }

    /**
     * Pin a few representative entity classes by name. Phase C must not lose
     * these regardless of any packagesToScan refactor.
     */
    public void testCanonicalEntitiesRegistered() {
        EntityManagerFactory emf = (EntityManagerFactory) getContext().getBean("entityManagerFactory");
        String[] required = {
                "at.ac.meduniwien.ophthalmology.libreclinica.domain.user.UserAccount",
                "at.ac.meduniwien.ophthalmology.libreclinica.domain.user.AuthoritiesBean",
                "at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.Study",
                "at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean",
                "at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean",
        };
        java.util.Set<String> registered = new java.util.HashSet<>();
        for (EntityType<?> et : emf.getMetamodel().getEntities()) {
            registered.add(et.getJavaType().getName());
        }
        for (String fqn : required) {
            assertTrue(
                    "Required entity '" + fqn + "' is not registered in the JPA Metamodel — "
                            + "Phase C must keep these reachable",
                    registered.contains(fqn));
        }
    }
}
