/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.it;

import java.util.ArrayList;

import javax.sql.DataSource;

import org.akaza.openclinica.bean.admin.AuditEventBean;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.dao.admin.AuditEventDAO;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.templates.HibernateOcDbTestCase;
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
 * {@link AuditEventDAO} write + read path itself. Production audit
 * writes (failed-login, password-request, job-execution, etc.) all
 * route through {@code AuditEventDAO.createRowForUserAccount} /
 * {@code create}. If that path regresses, no production audit-trail
 * write functions correctly regardless of the trigger context.
 *
 * <p><strong>Follow-up:</strong> the full item-18 round-trip
 * ({@code item_data} INSERT → audit_log_event row with old/new values)
 * is in scope for a future PR. It belongs alongside the CRF-data import
 * IT (item 17, {@code OdmImportRoundTripIT}) since both need the same
 * deep setup chain.
 *
 * <p><strong>Phase B.5 gate:</strong> AuditEventDAO is hand-rolled JDBC.
 * The Hibernate 6 migration shouldn't affect it directly; pinned anyway
 * so a connection-pool / transaction-manager drift surfaces.
 */
public class AuditTrailIT extends HibernateOcDbTestCase {

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
     * {@code audit_event} for the user account it references.
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

        // Snapshot the user_account audit rows before our write.
        // Note: createRowForUserAccount stores the i18n KEY "__user_account"
        // (double-underscore prefix), not the literal table name. That's
        // a pre-existing legacy quirk — pinned here so a future cleanup
        // that drops the prefix breaks the test loudly rather than
        // silently changing the on-disk audit_table value.
        ArrayList<AuditEventBean> before = auditDao.findAllByAuditTable("__user_account");
        int beforeCount = before == null ? 0 : before.size();

        // Production failed-login path drives the audit write.
        auditDao.createRowForFailedLogin(user);

        ArrayList<AuditEventBean> after = auditDao.findAllByAuditTable("__user_account");
        assertNotNull("findAllByAuditTable must not return null", after);
        assertTrue("createRowForFailedLogin must insert one audit row"
                + " (before=" + beforeCount + ", after=" + after.size() + ")",
                after.size() > beforeCount);
    }

    /**
     * Pin {@link AuditEventDAO#findAllByAuditTable} as the canonical
     * read path used by the Study Audit Log + the per-subject audit
     * drill-in. Filtering by tableName must return only rows for that
     * table.
     */
    public void testFindAllByAuditTableFiltersByTable() throws Exception {
        DataSource dataSource = (DataSource) getContext().getBean("dataSource");
        AuditEventDAO auditDao = new AuditEventDAO(dataSource);
        UserAccountDAO userDao = (UserAccountDAO) getContext().getBean("userAccountDao");

        UserAccountBean user = (UserAccountBean) userDao.findByPK(1);

        // Write at least one user_account audit row so the filter has
        // something to find. createRowForUserAccount stores the i18n key
        // "__user_account" — see testFailedLoginWritesAuditRow for the
        // pinning note.
        auditDao.createRowForFailedLogin(user);

        ArrayList<AuditEventBean> userAccountRows =
                auditDao.findAllByAuditTable("__user_account");
        ArrayList<AuditEventBean> imaginaryTableRows =
                auditDao.findAllByAuditTable("muw_no_such_audited_table");

        assertNotNull("__user_account audit rows must not be null",
                userAccountRows);
        assertTrue("__user_account audit rows must be non-empty after"
                + " a failed-login write",
                userAccountRows.size() > 0);
        for (AuditEventBean row : userAccountRows) {
            assertEquals("findAllByAuditTable must only return rows"
                    + " for the requested key",
                    "__user_account", row.getAuditTable());
        }
        assertNotNull("an imaginary table returns an empty list, not null",
                imaginaryTableRows);
        assertEquals("an imaginary audit_table returns zero rows",
                0, imaginaryTableRows.size());
    }
}
