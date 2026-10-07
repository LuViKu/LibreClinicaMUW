/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 2026-06-11 — Testcontainers IT for the per-study
 * {@link AuditApiController#list} eye-cohort-transition branch.
 *
 * <p>Pins the GxP audit-trail contract that an
 * {@code eye_cohort_transition} row written by
 * {@link EyeCohortTransitionsApiController#emitTransitionAudit} surfaces
 * in BOTH the source-study AND the target-study per-study audit log
 * (a transition affects both studies, so per-study reviewers must see
 * the move from either side).
 *
 * <p>The {@link AuditApiController#listSystem} sysadmin endpoint
 * already surfaces these rows (no per-study scoping, no
 * {@code is_user_visible} filter). This IT covers the per-study
 * scoping that was the bug — pre-fix the row existed in the DB but
 * never reached the SPA Audit Log view.
 */
class AuditApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String SOURCE_STUDY_OID = "S_ECTSRC";
    private static final String TARGET_STUDY_OID = "S_ECTTGT";
    private static int sourceStudyId;
    private static int targetStudyId;

    @BeforeAll
    static void seedTransitionStudies() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            sourceStudyId = insertStudy(c, "ect-src", "ECT source study", SOURCE_STUDY_OID);
            targetStudyId = insertStudy(c, "ect-tgt", "ECT target study", TARGET_STUDY_OID);
        }
    }

    private static int insertStudy(Connection c, String uniqueId, String name, String ocOid)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, "
                        + "name, summary, date_planned_start, date_planned_end, date_created, "
                        + "owner_id, type_id, status_id, principal_investigator, facility_name, "
                        + "facility_city, facility_state, facility_zip, facility_country, "
                        + "facility_recruitment_status, facility_contact_name, facility_contact_degree, "
                        + "facility_contact_phone, facility_contact_email, protocol_type, "
                        + "protocol_description, protocol_date_verification, phase, "
                        + "expected_total_enrollment, sponsor, collaborators, medline_identifier, "
                        + "url, url_description, conditions, keywords, eligibility, gender, "
                        + "age_max, age_min, healthy_volunteer_accepted, purpose, allocation, "
                        + "masking, control, assignment, endpoint, interventions, duration, "
                        + "selection, timing, official_title, results_reference, oc_oid) "
                        + "VALUES (?, ?, ?, ?, '', NOW(), NOW(), NOW(), 1, 1, 1, 'default', "
                        + "'', '', '', '', '', '', '', '', '', '', 'observational', '', NOW(), "
                        + "'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '', "
                        + "false, 'Natural History', '', '', '', '', '', '', 'longitudinal', "
                        + "'Convenience Sample', 'Retrospective', '', false, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, 1);
            ps.setString(2, uniqueId);
            ps.setString(3, uniqueId);
            ps.setString(4, name);
            ps.setString(5, ocOid);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Transition-study insert produced no PK.");
                }
                return keys.getInt(1);
            }
        }
    }

    private MockMvc mockMvc() {
        AuditApiController controller = new AuditApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return ProductionMvc.standalone(controller).build();
    }

    @Test
    void studyAuditLogIncludesEyeTransitionAsSource() throws Exception {
        // Seed the transition row + its audit-log edge. Source = the
        // study bound in the session. The per-study audit query must
        // surface the row when the source side is active.
        int transitionId = insertTransitionRow(sourceStudyId, targetStudyId);
        insertTransitionAuditRow(transitionId, "S_AUDIT_SRC");

        mockMvc().perform(
                get("/api/v1/audit")
                        .session(adminSession(sourceStudyId, SOURCE_STUDY_OID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].id", hasItem(String.valueOf(rowAuditId("S_AUDIT_SRC")))));
    }

    @Test
    void studyAuditLogIncludesEyeTransitionAsTarget() throws Exception {
        // Same row shape — this time the SESSION holds the TARGET
        // study. The reviewer in the receiving study must see the
        // inbound transition just as the source-side reviewer sees
        // the outbound one.
        int transitionId = insertTransitionRow(sourceStudyId, targetStudyId);
        insertTransitionAuditRow(transitionId, "S_AUDIT_TGT");

        mockMvc().perform(
                get("/api/v1/audit")
                        .session(adminSession(targetStudyId, TARGET_STUDY_OID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].id", hasItem(String.valueOf(rowAuditId("S_AUDIT_TGT")))));
    }

    /* ====================================================================== */
    /* 2026-09-27 — rows placed by what they record                           */
    /* ====================================================================== */

    /** Demo seed: study 1's visit 3 for M-001, with CRF 3. */
    private static final int STUDY_ID = 1;
    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final int VISIT = 3;
    private static final int CRF = 3;
    private static final int NO_SUCH_ID = 987_654_321;

    /**
     * An auto-tick row (129) holds the file's id where the item's belongs.
     * It must appear in the study of the CRF it records, and not in a study
     * where some item happens to share the file's number.
     */
    @Test
    void anAutoTickRowIsPlacedByItsCrfNotByItsNumber() throws Exception {
        long inItsStudy = insertAudit(129, "item_data", NO_SUCH_ID, "I_AUDIT_IT", null,
                "1 (from file 1)", CRF, null);
        long numberMatchesOnly = insertAudit(129, "item_data", anItemOfTheStudy(), "I_AUDIT_IT", null,
                "1 (from file 2)", NO_SUCH_ID, null);
        try {
            mockMvc().perform(get("/api/v1/audit").session(adminSession(STUDY_ID, STUDY_OID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[*].id", hasItem(String.valueOf(inItsStudy))))
                    .andExpect(jsonPath("$.events[*].id", not(hasItem(String.valueOf(numberMatchesOnly)))));
        } finally {
            deleteAudit(inItsStudy, numberMatchesOnly);
        }
    }

    /**
     * A file filed to or taken off a visit is in that visit's study log, by
     * the visit column or, for binds written before it, by the visit in the
     * row's value. A file that was never filed, and a visit elsewhere, are not.
     */
    @Test
    void aFileFiledToAVisitIsInThatVisitsStudyLog() throws Exception {
        long filed = insertAudit(127, "ingest_item", NO_SUCH_ID, "file #" + NO_SUCH_ID, "UNBOUND",
                "BOUND;match_policy=manual;study_event_id=" + VISIT, null, VISIT);
        long filedBefore = insertAudit(127, "ingest_item", NO_SUCH_ID, "status", "UNBOUND",
                "BOUND;match_policy=worklist;study_event_id=" + VISIT, null, null);
        long neverFiled = insertAudit(128, "ingest_item", NO_SUCH_ID, "file #" + NO_SUCH_ID, "UNBOUND",
                "DISMISSED", null, null);
        long elsewhere = insertAudit(127, "ingest_item", NO_SUCH_ID, "file #" + NO_SUCH_ID, "UNBOUND",
                "BOUND;match_policy=manual;study_event_id=" + NO_SUCH_ID, null, NO_SUCH_ID);
        try {
            mockMvc().perform(get("/api/v1/audit").session(adminSession(STUDY_ID, STUDY_OID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[*].id", hasItem(String.valueOf(filed))))
                    .andExpect(jsonPath("$.events[*].id", hasItem(String.valueOf(filedBefore))))
                    .andExpect(jsonPath("$.events[*].id", not(hasItem(String.valueOf(neverFiled)))))
                    .andExpect(jsonPath("$.events[*].id", not(hasItem(String.valueOf(elsewhere)))))
                    // Whose visit it was, as the log names it.
                    .andExpect(jsonPath("$.events[?(@.id == '" + filed + "')].subjectId",
                            hasItem(subjectOfVisit(VISIT))));
        } finally {
            deleteAudit(filed, filedBefore, neverFiled, elsewhere);
        }
    }

    private static long insertAudit(int type, String table, int entityId, String entityName,
                                    String oldValue, String newValue, Integer eventCrfId,
                                    Integer studyEventId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                             + "audit_table, entity_id, entity_name, old_value, new_value, "
                             + "event_crf_id, study_event_id) "
                             + "VALUES (?, NOW(), 1, ?, ?, ?, ?, ?, ?, ?) RETURNING audit_id")) {
            ps.setInt(1, type);
            ps.setString(2, table);
            ps.setInt(3, entityId);
            ps.setString(4, entityName);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            if (eventCrfId == null) ps.setNull(7, java.sql.Types.INTEGER); else ps.setInt(7, eventCrfId);
            if (studyEventId == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setInt(8, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void deleteAudit(long... ids) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM audit_log_event WHERE audit_id = ?")) {
            for (long id : ids) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    /** An item_data id that belongs to the study, for the number to collide with. */
    private static int anItemOfTheStudy() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MIN(id.item_data_id) FROM item_data id "
                             + "JOIN event_crf ec ON ec.event_crf_id = id.event_crf_id "
                             + "JOIN study_event se ON se.study_event_id = ec.study_event_id "
                             + "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                             + "WHERE ss.study_id = ?")) {
            ps.setInt(1, STUDY_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt(1);
                if (rs.wasNull()) throw new IllegalStateException("the demo seed has no item_data in study 1");
                return id;
            }
        }
    }

    private static String subjectOfVisit(int studyEventId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.label FROM study_event se "
                             + "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                             + "WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    /**
     * Insert a minimal {@code eye_cohort_transition} row reusing the
     * demo-seed subject_id=1 + study_subject_id=1 in the source study,
     * and a fresh study_subject row in the target study so the FKs
     * resolve. Returns the generated {@code transition_id}.
     */
    private int insertTransitionRow(int srcStudyId, int tgtStudyId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int targetSsId = insertStudySubject(c, tgtStudyId, /*subjectId=*/1,
                    "ECT-TGT-" + System.nanoTime());
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eye_cohort_transition (subject_id, eye, "
                            + "source_study_subject_id, source_study_id, "
                            + "target_study_subject_id, target_study_id, "
                            + "transitioned_at, actor_user_id, reason) "
                            + "VALUES (?, 'OD', ?, ?, ?, ?, NOW(), 1, 'IT seed')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, 1);
                ps.setInt(2, 1);
                ps.setInt(3, srcStudyId);
                ps.setInt(4, targetSsId);
                ps.setInt(5, tgtStudyId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("eye_cohort_transition insert produced no PK.");
                    }
                    return keys.getInt(1);
                }
            }
        }
    }

    private int insertStudySubject(Connection c, int studyId, int subjectId, String label)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                        + "date_created, owner_id, oc_oid, study_eye) "
                        + "VALUES (?, ?, ?, 1, NOW(), 1, ?, 'OD')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label);
            ps.setInt(2, subjectId);
            ps.setInt(3, studyId);
            ps.setString(4, "SS_ECT_" + System.nanoTime());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("study_subject insert produced no PK.");
                }
                return keys.getInt(1);
            }
        }
    }

    /**
     * Insert the {@code audit_log_event} row of type 57 that mirrors
     * what {@link EyeCohortTransitionsApiController#emitTransitionAudit}
     * writes during a real transition. The {@code marker} string is
     * stashed in {@code entity_name} so the test can look up the
     * generated audit_id without depending on row ordering.
     */
    private void insertTransitionAuditRow(int transitionId, String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (57, NOW(), 1, 'eye_cohort_transition', ?, ?, "
                             + "'" + SOURCE_STUDY_OID + "|OD|OD', "
                             + "'" + TARGET_STUDY_OID + "|OD|OU|IT seed')")) {
            ps.setInt(1, transitionId);
            ps.setString(2, marker);
            ps.executeUpdate();
        }
    }

    /**
     * Resolve the {@code audit_id} of the row identified by its
     * {@code entity_name} marker. Returns the most recent matching row
     * so re-runs against the same container do not collide.
     */
    private int rowAuditId(String marker) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT audit_id FROM audit_log_event "
                             + "WHERE audit_table = 'eye_cohort_transition' "
                             + "AND entity_name = ? "
                             + "ORDER BY audit_id DESC LIMIT 1")) {
            ps.setString(1, marker);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No audit row with marker=" + marker);
                }
                return rs.getInt(1);
            }
        }
    }

    private MockHttpSession adminSession(int studyId, String studyOid) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(studyOid);
        study.setName("study-" + studyId);
        session.setAttribute("study", study);

        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(studyId);
        session.setAttribute("userRole", role);
        return session;
    }
}
