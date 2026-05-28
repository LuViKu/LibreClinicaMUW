/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.it;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * Resync postgres sequences with the actual MAX(id) of the table they
 * back.
 *
 * <p>Why this exists: the legacy {@code *DaoTest} fixtures (e.g.
 * {@code RuleSetDaoTest.xml}) insert rows with explicit primary keys
 * like {@code study_subject_id="1"}. DBUnit performs those inserts via
 * JDBC with the PK literal in the statement, which does NOT advance the
 * postgres sequence that backs the column. The {@code study_subject_study_subject_id_seq}
 * sequence remains at its initial value (1).
 *
 * <p>Subsequent inserts via DAO ({@code StudySubjectDAO.create} etc.)
 * use {@code nextval(seq)} → 1, collide with the fixture row, and the
 * insert fails with SQLState 23505 (unique-constraint violation). The
 * DAO swallows this silently (see {@code EntityDAO.executeUpdateWithPK})
 * and returns a bean with {@code id = 0}, which is the symptom the
 * critical-path ITs hit in CI but not when running each IT in
 * isolation against a fresh postgres.
 *
 * <p>Calling {@link #bumpAll(DataSource)} at the start of any IT method
 * that issues DAO-driven inserts re-syncs the sequences so subsequent
 * {@code nextval()} skips past any fixture rows.
 *
 * <p>The longer-term fix is to migrate the legacy fixtures to negative
 * primary keys (consistent with the post-Phase-0 IT convention) so they
 * cannot collide with sequence-generated rows. Documented as a Phase 0
 * follow-up; until then this utility carries the gap.
 */
public final class SequenceUtil {

    private SequenceUtil() {
    }

    /**
     * Resync the postgres sequences for the tables the critical-path ITs
     * write to (subject, study, study_subject, study_event,
     * study_event_definition). Also bumps audit_log_event because the
     * study_subject INSERT trigger writes a row there via
     * nextval('audit_log_event_audit_id_seq'); a stale audit sequence
     * cascades into a study_subject INSERT failure that looks like the
     * IT bug we were chasing. Idempotent — safe to call from every test
     * method's prologue.
     */
    public static void bumpAll(DataSource ds) {
        bump(ds, "subject", "subject_id");
        bump(ds, "study", "study_id");
        bump(ds, "study_subject", "study_subject_id");
        bump(ds, "study_event_definition", "study_event_definition_id");
        bump(ds, "study_event", "study_event_id");
        // The study_subject trigger uses audit_log_event_audit_id_seq.
        bump(ds, "audit_log_event", "audit_id");
    }

    /**
     * Re-sync one sequence: {@code setval(<tbl>_<col>_seq,
     * GREATEST(MAX(<col>), 0) + 1, false)}. With {@code is_called=false},
     * the next {@code nextval()} returns the supplied value.
     */
    public static void bump(DataSource ds, String table, String pkColumn) {
        String seqName = table + "_" + pkColumn + "_seq";
        String sql = "SELECT setval('" + seqName + "',"
                + " GREATEST(COALESCE((SELECT MAX(" + pkColumn + ") FROM " + table
                + "), 0) + 1, 1), false)";
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to bump sequence " + seqName + ": " + e.getMessage(), e);
        }
    }
}
