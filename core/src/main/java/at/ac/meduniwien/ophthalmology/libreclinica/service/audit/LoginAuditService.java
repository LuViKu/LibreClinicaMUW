/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.audit;

import java.util.Date;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase D.5 (DR-014): shared audit-write hook for all auth paths.
 *
 * <p>Replaces the inline {@code auditUserLogin()} private method on
 * {@code OpenClinicaUsernamePasswordAuthenticationFilter}: both the
 * existing local username/password path and the new D.3 SSO pre-auth
 * path write to {@code audit_user_login} via the same code, so the
 * audit-trail contract (one row per login attempt) holds uniformly.
 *
 * <p>Failures during the audit write itself are swallowed (logged at
 * WARN). A broken audit DB must NOT fail the user's login — the
 * authentication has already happened by the time this service is
 * called; refusing to complete the request would deny clinicians
 * access during an operational incident.
 *
 * @see <a href="../../../../../../../docs/development/modernization/decision-record.md#dr-014--institution-agnostic-sso-via-reverse-proxy-pre-authentication">DR-014</a>
 */
public class LoginAuditService {

    private static final Logger log =
            LoggerFactory.getLogger(LoginAuditService.class);

    private final AuditUserLoginDao auditUserLoginDao;

    public LoginAuditService(AuditUserLoginDao auditUserLoginDao) {
        this.auditUserLoginDao = auditUserLoginDao;
    }

    /**
     * Write an audit row for a local username/password login attempt.
     * Used by {@code OpenClinicaUsernamePasswordAuthenticationFilter}
     * — preserves the legacy contract: user_account_id is null when
     * the user is null or not active.
     *
     * @param username    the user_name typed at the login form
     * @param status      one of SUCCESSFUL_LOGIN, FAILED_LOGIN,
     *                    FAILED_LOGIN_LOCKED, SUCCESSFUL_LOGOUT
     * @param userAccount the looked-up user bean, may be null
     */
    public void recordLocalAttempt(String username,
                                   LoginStatus status,
                                   UserAccountBean userAccount) {
        AuditUserLoginBean row = new AuditUserLoginBean();
        row.setUserName(username);
        row.setLoginStatus(status);
        row.setLoginAttemptDate(new Date());
        row.setUserAccountId(
                (userAccount != null && userAccount.isActive())
                        ? userAccount.getId() : null);
        saveQuiet(row);
    }

    /**
     * Write an audit row for a successful SSO pre-auth.
     * Status = {@link LoginStatus#SSO_LOGIN}. {@code username} is
     * the LibreClinica user_name (the local handle the SSO
     * principal resolved to via the
     * {@code external_id_provider + external_id} pair).
     */
    public void recordSsoSuccess(UserAccountBean userAccount, String principal) {
        AuditUserLoginBean row = new AuditUserLoginBean();
        row.setUserName(userAccount.getName());
        row.setLoginStatus(LoginStatus.SSO_LOGIN);
        row.setLoginAttemptDate(new Date());
        row.setUserAccountId(userAccount.getId());
        row.setDetails("sso-principal=" + principal);
        saveQuiet(row);
    }

    /**
     * Write an audit row for a rejected SSO pre-auth — the principal
     * header was present (from a trusted upstream) but the
     * provisioning strategy rejected. user_account_id is null because
     * by definition no row matches. The details field captures the
     * raw principal value for operator forensics.
     */
    public void recordSsoFailure(String principal, String provider) {
        AuditUserLoginBean row = new AuditUserLoginBean();
        // user_name on the audit row is the raw principal — we have
        // no local user_name to fall back to since lookup failed.
        row.setUserName(principal);
        row.setLoginStatus(LoginStatus.SSO_LOGIN_FAILED);
        row.setLoginAttemptDate(new Date());
        row.setUserAccountId(null);
        row.setDetails("sso-principal=" + principal
                + " sso-provider=" + provider);
        saveQuiet(row);
    }

    private void saveQuiet(AuditUserLoginBean row) {
        try {
            auditUserLoginDao.saveOrUpdate(row);
        } catch (Exception e) {
            // Audit DB failure must NOT propagate — the user has
            // already authenticated (or failed authentication) by
            // the time we get here. Log and move on.
            log.warn("audit_user_login write failed for status={} userName={}"
                    + " — login flow continues. Cause: {}",
                    row.getLoginStatus(), row.getUserName(), e.getMessage());
        }
    }
}
