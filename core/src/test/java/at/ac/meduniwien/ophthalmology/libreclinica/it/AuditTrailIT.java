/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.it;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.dbunit.operation.DatabaseOperation;

/**
 * Phase 0 integration-test backlog (MIGRATION.md item 18, simplified):
 * institutional regression net for the audit-trail write path.
 *
 * <p>The full MIGRATION.md item 18 calls for an end-to-end test that
 * exercises INSERT / UPDATE / DELETE on {@code item_data} and verifies
 * a matching {@code audit_log_event} row appears with the correct
 * {@code old_value} / {@code new_value} / {@code reason_for_change}.
 * That requires the deep CRF setup chain (Study → StudyEventDefinition
 * → Subject + StudySubject → StudyEvent → CRF → CRFVersion → EventCRF
 * → ItemGroup + Item → ItemData) — 8+ DAOs to wire up before a single
 * {@code item_data} row can be written.
 *
 * <p>This IT pins a narrower but equally important contract: the
 * {@link AuditEventDAO} write path itself. Production audit writes
 * (failed-login, password-request, job-execution, etc.) all route
 * through {@code AuditEventDAO.createRow*}. If that path regresses, no
 * production audit-trail write functions correctly regardless of the
 * trigger context.
 *
 * <p><strong>Phase audit-unification (2026-06-12):</strong> assertions
 * swapped from the legacy {@code audit_event} table
 * ({@code findAllByAuditTable("__user_account")}) to the typed
 * {@code audit_log_event} table queried by
 * {@code audit_log_event_type_id=101} (USER_LOGIN_FAILED) — same row
 * the SPA Audit Log view consumes, same write path the production
 * failed-login filter drives.
 *
 * <p><strong>Phase B.5 gate:</strong> AuditEventDAO is hand-rolled JDBC.
 * The Hibernate 6 migration shouldn't affect it directly; pinned anyway
 * so a connection-pool / transaction-manager drift surfaces.
 */
public class AuditTrailIT extends HibernateOcDbTestCase {

    /**
     * {@code audit_log_event_type_id} for failed login attempts. Mirrors
     * {@code AuditTypeIds.USER_LOGIN_FAILED} (web module) — the core
     * test module can't import the controller-package constant without
     * a layering violation, so it's hardcoded here with a sibling
     * comment. Type id 101 is seeded by
     * {@code lc-muw-2026-06-12-audit-event-types-unification.xml} and
     * is hidden ({@code is_user_visible=false}) — surfaces only in the
     * sysadmin {@code /api/v1/audit/system} view.
     */
    private static final int AUDIT_TYPE_USER_LOGIN_FAILED = 101;

    public AuditTrailIT() {
        super();
    }

    @Override
    protected DatabaseOperation getSetUpOperation() {
        return DatabaseOperation.REFRESH;
    }

    @Override
    protected DatabaseOperation getTearDownOperation() {
        return DatabaseOperation.NONE;
    }

    /**
     * Item 18 (simplified to DAO layer): an audit event written via
     * {@link AuditEventDAO#createRowForFailedLogin} appears in
     * {@code audit_log_event} for the user account it references.
     *
     * <p>The production failed-login filter
     * ({@code OpenClinicaAuthenticationProcessingFilter.unsuccessfulAuthentication})
     * calls into this exact DAO method per failed login.
     */
    public void testFailedLoginWritesAuditRow() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        AuditEventDAO auditDao = new AuditEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean user = (UserAccountBean) userDao.findByPK(1);
        assertNotNull("Bootstrap user (id=1) must exist", user);

        // Snapshot the typed audit_log_event rows for this user +
        // failed-login type before our write so we measure a delta of
        // exactly +1.
        int beforeCount = countAuditLogEventRows(dataSource,
                AUDIT_TYPE_USER_LOGIN_FAILED, user.getId());

        // Production failed-login path drives the audit write.
        auditDao.createRowForFailedLogin(user);

        int afterCount = countAuditLogEventRows(dataSource,
                AUDIT_TYPE_USER_LOGIN_FAILED, user.getId());
        assertEquals("createRowForFailedLogin must insert exactly one"
                + " audit_log_event row of type=" + AUDIT_TYPE_USER_LOGIN_FAILED
                + " for user " + user.getId()
                + " (before=" + beforeCount + ", after=" + afterCount + ")",
                beforeCount + 1, afterCount);

        // Inspect the most-recent row for the (type, user) pair to pin
        // the column shape: audit_table='user_account', entity_id =
        // user_id (the entity being audited is the user account itself).
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, audit_table, entity_id, entity_name "
                             + "FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND entity_id = ? "
                             + "ORDER BY audit_id DESC LIMIT 1")) {
            ps.setInt(1, AUDIT_TYPE_USER_LOGIN_FAILED);
            ps.setInt(2, user.getId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue("audit_log_event row must exist after createRowForFailedLogin",
                        rs.next());
                assertEquals("user_id must match the failed-login user",
                        user.getId(), rs.getInt("user_id"));
                assertEquals("audit_table must be 'user_account'",
                        "user_account", rs.getString("audit_table"));
                assertEquals("entity_id must match the failed-login user",
                        user.getId(), rs.getInt("entity_id"));
                assertEquals("entity_name must carry the username for SPA Audit Log grouping",
                        user.getName() == null ? "" : user.getName(),
                        rs.getString("entity_name"));
            }
        }
    }

    /**
     * Pin the typed-table write path: repeated
     * {@link AuditEventDAO#createRowForFailedLogin} calls accumulate
     * rows of type 101 for the targeted user without leaking into rows
     * of any other type (the SPA Audit Log view filters by type and
     * must not see false positives).
     */
    public void testFailedLoginWritesOnlyTheExpectedType() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        AuditEventDAO auditDao = new AuditEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean user = (UserAccountBean) userDao.findByPK(1);

        int failedBefore = countAuditLogEventRows(dataSource,
                AUDIT_TYPE_USER_LOGIN_FAILED, user.getId());
        // 102 = USER_LOGGED_IN — a sibling type the failed-login writer
        // must NOT touch.
        int loginBefore = countAuditLogEventRows(dataSource, 102, user.getId());

        auditDao.createRowForFailedLogin(user);
        auditDao.createRowForFailedLogin(user);

        int failedAfter = countAuditLogEventRows(dataSource,
                AUDIT_TYPE_USER_LOGIN_FAILED, user.getId());
        int loginAfter = countAuditLogEventRows(dataSource, 102, user.getId());

        assertEquals("two createRowForFailedLogin calls must add exactly two rows of type 101",
                failedBefore + 2, failedAfter);
        assertEquals("createRowForFailedLogin must not write rows of type 102 (USER_LOGGED_IN)",
                loginBefore, loginAfter);
    }

    /**
     * Count {@code audit_log_event} rows matching the {@code (typeId,
     * entityId)} pair. Mirrors the query the SPA Audit Log view runs
     * to render the per-user audit drill-in.
     */
    private static int countAuditLogEventRows(DataSource ds,
                                              int typeId,
                                              int entityId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND entity_id = ?")) {
            ps.setInt(1, typeId);
            ps.setInt(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
