/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.it;

import java.util.Date;

import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.dao.hibernate.AuditUserLoginDao;
import org.akaza.openclinica.dao.hibernate.AuditUserLoginFilter;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.domain.technicaladmin.AuditUserLoginBean;
import org.akaza.openclinica.domain.technicaladmin.LoginStatus;
import org.akaza.openclinica.templates.HibernateOcDbTestCase;
import org.dbunit.operation.DatabaseOperation;

/**
 * Phase 0 integration-test backlog (MIGRATION.md items 1 + 2):
 * institutional GCP regression net for the login flow.
 *
 * <p>Pins two contracts the production authentication path relies on:
 * <ol>
 *   <li><strong>{@link UserAccountDAO#findByUserName} round-trips a persisted
 *       user.</strong> The Spring-Security {@code DaoAuthenticationProvider}
 *       chain (configured in {@code applicationContext-core-security.xml})
 *       calls into {@code ocUserDetailsService}, which delegates to
 *       {@link UserAccountDAO#findByUserName}. Without this round-trip every
 *       login fails before the password encoder is even invoked.</li>
 *   <li><strong>Failed logins persist an {@link AuditUserLoginBean} row.</strong>
 *       {@code OpenClinicaAuthenticationProcessingFilter.unsuccessfulAuthentication}
 *       writes one {@code audit_user_login} row per failure (login_status_code
 *       = 2, FAILED_LOGIN). This test pins the DAO write path; the filter-level
 *       integration is deferred to a follow-up because it requires a live
 *       servlet container.</li>
 * </ol>
 *
 * <p>The password-encoder layer is pinned separately by
 * {@code OpenClinicaPasswordEncoderTest} (MIGRATION.md item 3, already
 * landed). Together with this test, the auth flow has DAO + encoder + audit
 * coverage; only the filter-level integration is still gapped.
 *
 * <p><strong>Phase B.4 gate:</strong> the {@code findByUserName} contract
 * here must keep passing through the Spring 5 → 6 migration. Spring 6's
 * {@code UserDetailsService} signature change is non-breaking at the DAO
 * layer, but any wiring drift in {@code ocUserDetailsService} would surface
 * here as a {@code findByUserName} returning an empty bean.
 *
 * <p><strong>Phase B.5 gate:</strong> the audit-row assertion path goes
 * through {@link AuditUserLoginFilter#execute} which uses the Hibernate
 * {@code Criteria} API. Hibernate 6 removes that API entirely; the
 * cross-reference is already pinned by {@code AuditUserLoginDaoTest}, so
 * this test deliberately uses the simpler {@code saveOrUpdate} +
 * {@code findById} path that survives the migration unchanged.
 */
public class LoginFlowIT extends HibernateOcDbTestCase {

    public LoginFlowIT() {
        super();
    }

    /**
     * Override DBUnit's default {@code CLEAN_INSERT}: the base class is
     * fine for tables nothing else references, but user_account is the
     * root of the FK graph — study, audit_log_event, study_event, etc.
     * all carry {@code user_id} columns referencing user_account.id, so a
     * {@code DELETE FROM user_account} fails on the existing app-bootstrap
     * row (user_id=1, the root sysadmin) before any insert runs.
     *
     * <p>{@code REFRESH} = INSERT for primary keys not yet present, UPDATE
     * for primary keys already present, no delete. The fixture's
     * user_id=-100 doesn't collide with any bootstrap row, and existing
     * rows are left alone.
     */
    @Override
    protected DatabaseOperation getSetUpOperation() {
        return DatabaseOperation.REFRESH;
    }

    /**
     * Symmetric with {@link #getSetUpOperation()}: don't try to clean the
     * fixture rows out either. The per-test Spring transaction rollback in
     * {@link HibernateOcDbTestCase#tearDown()} reverts test-level writes
     * regardless, and the fixture row (id=-100) is harmless even if it
     * persists between test classes — it just sits there in the negative-ID
     * keyspace the rest of the integration suite uses too.
     */
    @Override
    protected DatabaseOperation getTearDownOperation() {
        return DatabaseOperation.NONE;
    }

    /**
     * Item 1: persisted users round-trip through {@code findByUserName}.
     *
     * <p>The DBUnit fixture inserts a user_account row with id -100 and
     * user_name "muw-login-fixture". A {@code findByUserName} for that
     * username must return a populated bean with the right id + username.
     */
    public void testFindByUserNameReturnsPersistedUser() {
        UserAccountDAO userAccountDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean user = (UserAccountBean) userAccountDao.findByUserName("muw-login-fixture");

        assertNotNull("findByUserName must not return null for an existing username", user);
        assertEquals("user_id round-trips from the fixture",
                -100, user.getId());
        assertEquals("user_name round-trips from the fixture",
                "muw-login-fixture", user.getName());
    }

    /**
     * Item 1 (negative): {@code findByUserName} for an absent user yields
     * a non-null bean with id = 0 (legacy contract — the DAO returns a
     * fresh {@code UserAccountBean} when no row matches rather than null).
     *
     * <p>Pinned because every login-failure path depends on it: the auth
     * filter checks {@code bean.getId() != 0} to distinguish "user not
     * found" from "user found, password wrong". A drift here would
     * misclassify login failures.
     */
    public void testFindByUserNameReturnsZeroIdForUnknown() {
        UserAccountDAO userAccountDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean user = (UserAccountBean) userAccountDao.findByUserName("muw-no-such-user");

        assertNotNull("findByUserName returns a fresh bean rather than null"
                + " for an absent username", user);
        assertEquals("absent username yields id=0 (the production marker"
                + " that distinguishes 'no such user' from 'found')",
                0, user.getId());
    }

    /**
     * Item 2 (simplified to DAO layer): a failed-login audit row persists
     * via {@link AuditUserLoginDao#saveOrUpdate} and round-trips via
     * {@link AuditUserLoginDao#findById}.
     *
     * <p>The full {@code OpenClinicaAuthenticationProcessingFilter} flow
     * that drives this write under real login traffic is filter-level and
     * needs a servlet container; the filter is wired to this exact DAO bean
     * in {@code applicationContext-core-security.xml}, so a DAO-layer round-
     * trip here covers the post-filter contract.
     */
    public void testFailedLoginAuditRowRoundTripsThroughDao() {
        AuditUserLoginDao auditUserLoginDao =
                (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");

        AuditUserLoginBean failed = new AuditUserLoginBean();
        failed.setUserName("muw-login-fixture");
        failed.setLoginAttemptDate(new Date());
        failed.setLoginStatus(LoginStatus.FAILED_LOGIN);

        AuditUserLoginBean persisted = auditUserLoginDao.saveOrUpdate(failed);

        assertNotNull("Persisted id must be non-null", persisted.getId());
        AuditUserLoginBean roundTripped = auditUserLoginDao.findById(persisted.getId());
        assertEquals("userName round-trips through saveOrUpdate + findById",
                "muw-login-fixture", roundTripped.getUserName());
        assertEquals("loginStatus round-trips as FAILED_LOGIN",
                LoginStatus.FAILED_LOGIN, roundTripped.getLoginStatus());
    }
}
