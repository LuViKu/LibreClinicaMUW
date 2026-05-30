/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.contract;



/**
 * C.0.1 — pins the high-level bootstrap contract of the Spring application
 * context. Phase C will progressively replace the
 * {@code applicationContext-core-*.xml} files with Java {@code @Configuration}
 * classes; this test catches a silent bean-graph regression if any of those
 * conversions drops the bean count below the documented baseline.
 *
 * <p>The baseline is captured as a minimum threshold rather than an exact
 * count so that intentional additions don't constantly fail this test;
 * unintentional drops (i.e. someone deletes a bean by mistake) will trip it.
 *
 * <p>Phase C playbook §C.0.1.
 */
public class BootstrapContractIT extends AbstractContractIT {

    /**
     * Pre-Phase-C bean count in the test context (HibernateOcDbTestCase loads
     * a subset of the full WAR context: applicationContext-core-db /
     * -email / -hibernate / -service / -timer / -security.xml — but NOT
     * core-security.xml, core-spring.xml, core-scheduler.xml, or
     * pages-servlet.xml. Runtime WAR context has ~250 beans). 111 today;
     * threshold set with headroom so a small refactor doesn't flap.
     */
    private static final int MIN_EXPECTED_BEAN_COUNT = 100;

    public void testApplicationContextLoads() {
        assertNotNull("ApplicationContext must load", getContext());
    }

    public void testBeanCountAboveBaselineFloor() {
        int actual = getContext().getBeanDefinitionCount();
        assertTrue(
                "Pre-Phase-C bean-graph regression suspected — bean definition count "
                        + actual + " dropped below the documented baseline " + MIN_EXPECTED_BEAN_COUNT
                        + ". If this is an intentional architectural change, lower the "
                        + "MIN_EXPECTED_BEAN_COUNT constant in this same commit and document why.",
                actual >= MIN_EXPECTED_BEAN_COUNT);
    }

    /**
     * The post-Phase-B bean graph still includes the canonical infrastructure
     * beans by their well-known ids. Phase C's XML→Java conversions must keep
     * the same bean names so that the rest of the codebase's by-name autowire
     * (e.g. {@code parent="abstractDomainDao" autowire="byName"}) keeps working.
     */
    public void testCanonicalInfrastructureBeansResolveByName() {
        // Beans guaranteed by the test context's loaded XMLs (db, email,
        // hibernate, service, timer, security). openClinicaPasswordEncoder
        // + ocUserDetailsService + scheduler live in
        // {core-security, core-scheduler, core-spring}.xml — not loaded by
        // HibernateOcDbTestCase. Phase C sub-phases that touch those XMLs
        // can add their own contract test with the appropriate context.
        String[] required = {
                "dataSource",
                "entityManagerFactory",
                "transactionManager",
                "sharedTransactionTemplate",
                "abstractDomainDao",
                "authoritiesDao",
                "configurationDao",
                "databaseChangeLogDao",
                "auditUserLoginDao",
                "ruleSetDao",
                "ruleDao",
        };
        for (String name : required) {
            assertTrue(
                    "Bean named '" + name + "' must be registered in the application context "
                            + "(Phase C must not drop or rename this canonical bean)",
                    getContext().containsBean(name));
        }
    }
}
