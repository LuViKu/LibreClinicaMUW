/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.dao;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.akaza.openclinica.dao.hibernate.AuditUserLoginDao;
import org.akaza.openclinica.dao.hibernate.AuditUserLoginFilter;
import org.akaza.openclinica.domain.technicaladmin.AuditUserLoginBean;
import org.akaza.openclinica.domain.technicaladmin.LoginStatus;
import org.akaza.openclinica.templates.HibernateOcDbTestCase;

public class AuditUserLoginDaoTest extends HibernateOcDbTestCase {

    public AuditUserLoginDaoTest() {
        super();
    }

    public void testSaveOrUpdate() {
        AuditUserLoginDao auditUserLoginDao = (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");
        AuditUserLoginBean auditUserLoginBean = new AuditUserLoginBean();
        auditUserLoginBean.setUserName("testUser");
        auditUserLoginBean.setLoginAttemptDate(new Date());
        auditUserLoginBean.setLoginStatus(LoginStatus.SUCCESSFUL_LOGIN);

        auditUserLoginBean = auditUserLoginDao.saveOrUpdate(auditUserLoginBean);

        assertNotNull("Persistant id is null", auditUserLoginBean.getId());
    }

    public void testfindById() {
        AuditUserLoginDao auditUserLoginDao = (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");
        AuditUserLoginBean auditUserLoginBean = auditUserLoginDao.findById(-1);

        assertEquals("UserName should be testUser", "testUser", auditUserLoginBean.getUserName());
    }

    /**
     * findAll() returns at least the rows declared in the fixture. Uses
     * containment rather than equality to tolerate any app-bootstrap rows
     * that may also be present.
     *
     * <p>Phase 0 backlog: complements the existing single-row testfindById
     * with the broader list path.
     */
    public void testFindAllContainsFixtureRows() {
        AuditUserLoginDao auditUserLoginDao = (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");

        ArrayList<AuditUserLoginBean> all = auditUserLoginDao.findAll();

        assertNotNull("findAll() must never return null", all);
        Set<Integer> ids = new HashSet<>();
        for (AuditUserLoginBean row : all) {
            ids.add(row.getId());
        }
        assertTrue("findAll missing fixture row -1: " + ids, ids.contains(-1));
        assertTrue("findAll missing fixture row -2: " + ids, ids.contains(-2));
        assertTrue("findAll missing fixture row -3: " + ids, ids.contains(-3));
        assertTrue("findAll missing fixture row -4: " + ids, ids.contains(-4));
    }

    /**
     * getCountWithFilter() routed through {@link AuditUserLoginFilter} exercises
     * the Hibernate {@code Criteria} API path: filter.execute(criteria) adds
     * Restrictions to a Criteria. This is the <strong>Phase B.5 gate</strong>:
     * Hibernate 6 <em>removes</em> the {@code Criteria} API entirely in favour
     * of {@code jakarta.persistence.criteria.CriteriaQuery}. When this test
     * stops compiling (because Criteria / Restrictions are gone), the migration
     * plan must convert {@link AuditUserLoginFilter#execute(org.hibernate.Criteria)}
     * to JPA Criteria. When it compiles but starts failing, the JPA translation
     * is buggy and queries are silently returning wrong counts.
     */
    public void testGetCountWithFilterByUserName() {
        AuditUserLoginDao auditUserLoginDao = (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");

        AuditUserLoginFilter byTestUser = new AuditUserLoginFilter();
        byTestUser.addFilter("userName", "testUser");
        int testUserCount = auditUserLoginDao.getCountWithFilter(byTestUser);

        AuditUserLoginFilter byFixtureTwo = new AuditUserLoginFilter();
        byFixtureTwo.addFilter("userName", "muw-fixture-two");
        int fixtureTwoCount = auditUserLoginDao.getCountWithFilter(byFixtureTwo);

        AuditUserLoginFilter byAbsent = new AuditUserLoginFilter();
        byAbsent.addFilter("userName", "muw-no-such-user");
        int absentCount = auditUserLoginDao.getCountWithFilter(byAbsent);

        // Filter applies Restrictions.like(...).ignoreCase() so two fixture
        // rows match "testUser" exactly.
        assertEquals("two fixture rows match userName 'testUser'",
                2, testUserCount);
        assertEquals("one fixture row matches userName 'muw-fixture-two'",
                1, fixtureTwoCount);
        assertEquals("no fixture row matches an absent user",
                0, absentCount);
    }

    /**
     * Filter by {@link LoginStatus} - same Criteria API path, different
     * Restrictions branch. Filter.execute() routes loginStatus through
     * {@code Restrictions.eq(property, LoginStatus.getByName(value))}.
     */
    public void testGetCountWithFilterByLoginStatus() {
        AuditUserLoginDao auditUserLoginDao = (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");

        // Filter passes the value to LoginStatus.getByName(...), which is
        // Enum.valueOf - so the string must be the exact enum constant name.
        AuditUserLoginFilter bySuccess = new AuditUserLoginFilter();
        bySuccess.addFilter("loginStatus", LoginStatus.SUCCESSFUL_LOGIN.name());
        int successCount = auditUserLoginDao.getCountWithFilter(bySuccess);

        AuditUserLoginFilter byFailed = new AuditUserLoginFilter();
        byFailed.addFilter("loginStatus", LoginStatus.FAILED_LOGIN.name());
        int failedCount = auditUserLoginDao.getCountWithFilter(byFailed);

        // Fixture has 2 rows of each login_status_code (1 = SUCCESSFUL_LOGIN,
        // 2 = FAILED_LOGIN).
        assertEquals("two fixture rows have login_status_code 1 (SUCCESSFUL_LOGIN)",
                2, successCount);
        assertEquals("two fixture rows have login_status_code 2 (FAILED_LOGIN)",
                2, failedCount);
    }
}