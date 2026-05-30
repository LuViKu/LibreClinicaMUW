/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Phase D.0 characterisation: pin the integer wire codes of {@link LoginStatus}
 * before Phase D.5 extends the enum with {@code SSO_LOGIN(6)} +
 * {@code SSO_LOGIN_FAILED(7)}.
 *
 * <p>{@code audit_user_login.login_status_code} stores these codes as
 * {@code int} — every row written before D.5 must continue to render
 * correctly on the StudyAuditLog + AuditUserActivity screens after D.5
 * lands. Pinning the existing codes 1-5 here means any reordering during
 * the enum extension is caught at test time, not in production.
 *
 * <p>Phase D playbook §D.0.
 */
public class LoginStatusWireCodeTest {

    /**
     * The existing 5 enum values map to the exact wire codes upstream
     * OpenClinica ships and that the production {@code audit_user_login}
     * rows already use. Any drift breaks historical audit-log rendering.
     */
    @Test
    public void wireCodesArePinned() {
        assertEquals("SUCCESSFUL_LOGIN must remain code 1",
                Integer.valueOf(1), LoginStatus.SUCCESSFUL_LOGIN.getCode());
        assertEquals("FAILED_LOGIN must remain code 2",
                Integer.valueOf(2), LoginStatus.FAILED_LOGIN.getCode());
        assertEquals("FAILED_LOGIN_LOCKED must remain code 3",
                Integer.valueOf(3), LoginStatus.FAILED_LOGIN_LOCKED.getCode());
        assertEquals("SUCCESSFUL_LOGOUT must remain code 4",
                Integer.valueOf(4), LoginStatus.SUCCESSFUL_LOGOUT.getCode());
        assertEquals("ACCESS_CODE_VIEWED must remain code 5",
                Integer.valueOf(5), LoginStatus.ACCESS_CODE_VIEWED.getCode());
    }

    /**
     * {@link LoginStatus#getByCode(Integer)} must round-trip every defined
     * code back to its enum constant. This is the lookup path
     * {@code AuditUserLoginBean} uses when reading a row from
     * {@code audit_user_login}; a drift here renders historical rows as
     * {@code null} on the audit screens.
     */
    @Test
    public void getByCodeRoundTripsEveryDefinedCode() {
        assertSame(LoginStatus.SUCCESSFUL_LOGIN, LoginStatus.getByCode(1));
        assertSame(LoginStatus.FAILED_LOGIN, LoginStatus.getByCode(2));
        assertSame(LoginStatus.FAILED_LOGIN_LOCKED, LoginStatus.getByCode(3));
        assertSame(LoginStatus.SUCCESSFUL_LOGOUT, LoginStatus.getByCode(4));
        assertSame(LoginStatus.ACCESS_CODE_VIEWED, LoginStatus.getByCode(5));
    }

    /**
     * Phase D.5 (landed 2026-05-30): codes 6 + 7 are now assigned.
     * Pin the new wire codes so any future re-numbering surfaces
     * here.
     */
    @Test
    public void d5SsoCodesAssigned() {
        assertNotNull(LoginStatus.SSO_LOGIN);
        assertEquals("SSO_LOGIN must be code 6",
                Integer.valueOf(6), LoginStatus.SSO_LOGIN.getCode());
        assertEquals("SSO_LOGIN_FAILED must be code 7",
                Integer.valueOf(7), LoginStatus.SSO_LOGIN_FAILED.getCode());
        assertSame(LoginStatus.SSO_LOGIN, LoginStatus.getByCode(6));
        assertSame(LoginStatus.SSO_LOGIN_FAILED, LoginStatus.getByCode(7));
    }
}
