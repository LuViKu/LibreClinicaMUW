/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.contract;

import java.util.Date;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.dbunit.dataset.DefaultDataSet;
import org.dbunit.dataset.IDataSet;
import org.dbunit.operation.DatabaseOperation;

/**
 * Phase D.0 characterisation: pin that every {@link LoginStatus} enum
 * value round-trips through {@link AuditUserLoginDao#saveOrUpdate} +
 * {@link AuditUserLoginDao#findById}.
 *
 * <p>{@link at.ac.meduniwien.ophthalmology.libreclinica.it.LoginFlowIT}
 * already pins the FAILED_LOGIN round-trip. This contract test extends
 * coverage to all 5 currently-defined codes (SUCCESSFUL_LOGIN,
 * FAILED_LOGIN, FAILED_LOGIN_LOCKED, SUCCESSFUL_LOGOUT,
 * ACCESS_CODE_VIEWED) so D.5's enum extension to SSO_LOGIN(6) +
 * SSO_LOGIN_FAILED(7) and the audit-write extraction into
 * {@code LoginAuditService} cannot silently drop a code from the wire
 * format.
 *
 * <p>The DAO write path bypasses the {@code OpenClinicaUsernamePasswordAuthenticationFilter}
 * but uses the exact same {@code saveOrUpdate} call the filter invokes
 * under live login traffic — see
 * {@code OpenClinicaUsernamePasswordAuthenticationFilter.auditUserLogin()}.
 *
 * <p>Phase D playbook §D.0.
 */
public class AuditLoginContractIT extends HibernateOcDbTestCase {

    @Override
    protected IDataSet getDataSet() {
        // No fixture needed — every test inserts its own audit row and
        // verifies the round-trip. Transactional rollback in tearDown()
        // reverts each insert.
        return new DefaultDataSet();
    }

    /**
     * Don't try to clean a table the test didn't populate (parent default
     * is CLEAN_INSERT which fails on FK constraints from existing
     * audit_user_login rows that point at the bootstrap user).
     */
    @Override
    protected DatabaseOperation getSetUpOperation() {
        return DatabaseOperation.NONE;
    }

    @Override
    protected DatabaseOperation getTearDownOperation() {
        return DatabaseOperation.NONE;
    }

    /**
     * SUCCESSFUL_LOGIN round-trip. Phase D.5 keeps this code stable;
     * a regression here is a wire-format break.
     */
    public void testSuccessfulLoginRoundTrips() {
        roundTripStatus(LoginStatus.SUCCESSFUL_LOGIN, "muw-d0-succ");
    }

    /**
     * FAILED_LOGIN_LOCKED round-trip. Distinct from FAILED_LOGIN — the
     * filter writes this code when {@code account_non_locked = false},
     * and the UI renders it as "account locked" rather than generic
     * "failed login". D.5's audit-write extraction must keep both codes
     * routed correctly.
     */
    public void testFailedLoginLockedRoundTrips() {
        roundTripStatus(LoginStatus.FAILED_LOGIN_LOCKED, "muw-d0-locked");
    }

    /**
     * SUCCESSFUL_LOGOUT round-trip. The {@code openClinicaLogoutHandler}
     * writes this code; D.3's pre-auth filter must not perturb the logout
     * path (logout via {@code /j_spring_security_logout} still produces
     * a code-4 row even for SSO-authenticated sessions).
     */
    public void testSuccessfulLogoutRoundTrips() {
        roundTripStatus(LoginStatus.SUCCESSFUL_LOGOUT, "muw-d0-logout");
    }

    /**
     * ACCESS_CODE_VIEWED round-trip. Participant access-code views are
     * audited under this code; included for enum completeness — D-Sec
     * does not modify this path.
     */
    public void testAccessCodeViewedRoundTrips() {
        roundTripStatus(LoginStatus.ACCESS_CODE_VIEWED, "muw-d0-access");
    }

    /**
     * Details column is nullable and accepts free-form strings used by
     * the production filter to attach context (e.g. "locked at attempt
     * 5"). D.5's {@code LoginAuditService} extraction must preserve the
     * nullable-vs-populated semantics.
     */
    public void testDetailsRoundTripsBothNullAndPopulated() {
        AuditUserLoginDao dao =
                (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");

        AuditUserLoginBean withDetails = newAuditRow(LoginStatus.FAILED_LOGIN, "muw-d0-details");
        withDetails.setDetails("locked at attempt 5");
        AuditUserLoginBean persisted = dao.saveOrUpdate(withDetails);
        assertEquals("locked at attempt 5",
                dao.findById(persisted.getId()).getDetails());

        AuditUserLoginBean nullDetails = newAuditRow(LoginStatus.FAILED_LOGIN, "muw-d0-nodetails");
        // setDetails(null) is the production default
        AuditUserLoginBean persisted2 = dao.saveOrUpdate(nullDetails);
        assertNull("null details persists as null, not an empty string",
                dao.findById(persisted2.getId()).getDetails());
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private void roundTripStatus(LoginStatus status, String userName) {
        AuditUserLoginDao dao =
                (AuditUserLoginDao) getContext().getBean("auditUserLoginDao");

        AuditUserLoginBean row = newAuditRow(status, userName);
        AuditUserLoginBean persisted = dao.saveOrUpdate(row);

        assertNotNull("persisted id must be non-null after saveOrUpdate",
                persisted.getId());

        AuditUserLoginBean reloaded = dao.findById(persisted.getId());
        assertNotNull("findById must return the just-persisted row", reloaded);
        assertEquals("loginStatus must round-trip as " + status.name(),
                status, reloaded.getLoginStatus());
        assertEquals("userName must round-trip", userName, reloaded.getUserName());
    }

    private AuditUserLoginBean newAuditRow(LoginStatus status, String userName) {
        AuditUserLoginBean row = new AuditUserLoginBean();
        row.setUserName(userName);
        row.setLoginAttemptDate(new Date());
        row.setLoginStatus(status);
        return row;
    }
}
