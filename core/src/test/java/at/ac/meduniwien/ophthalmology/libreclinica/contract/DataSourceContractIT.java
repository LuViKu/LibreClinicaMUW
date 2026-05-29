/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.contract;

import javax.sql.DataSource;


import org.apache.commons.dbcp.BasicDataSource;

/**
 * C.0.2 — pins the {@code dataSource} bean shape. Phase C will replace the
 * XML-declared {@code BasicDataSource} with Spring Boot's auto-configured
 * pool (HikariCP by default in Spring Boot 3.5). This test will need to be
 * updated in the same commit as C.3 (Boot-style DataSourceConfig). Until
 * then, any silent change to the pool's class or essential properties
 * surfaces here.
 *
 * <p>Phase C playbook §C.0.2.
 */
public class DataSourceContractIT extends AbstractContractIT {

    public void testDataSourceBeanResolves() {
        DataSource ds = (DataSource) getContext().getBean("dataSource");
        assertNotNull("dataSource bean must resolve", ds);
    }

    public void testDataSourceImplementation() {
        DataSource ds = (DataSource) getContext().getBean("dataSource");
        // Pre-Phase-C: commons-dbcp BasicDataSource. C.3 will swap to
        // HikariDataSource (Spring Boot 3.5 default). When that lands,
        // change this assertion in the same commit.
        assertTrue(
                "dataSource is expected to be commons-dbcp BasicDataSource pre-Phase-C; "
                        + "actual class = " + ds.getClass().getName(),
                ds instanceof BasicDataSource);
    }

    public void testDataSourceUrlReachable() throws Exception {
        DataSource ds = (DataSource) getContext().getBean("dataSource");
        // Don't assert the literal URL (varies per env); just that the pool
        // is actually working. A pre-Phase-C boot must be able to open a
        // connection.
        try (var conn = ds.getConnection()) {
            assertTrue("DataSource connection must be valid", conn.isValid(5));
        }
    }
}
