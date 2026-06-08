/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 — Testcontainers IT for
 * {@link EyeCohortTransitionsApiController#transition}.
 *
 * <p>Pins the per-eye cohort hand-off write path. The endpoint requires
 * a live PostgreSQL because the {@code FOR UPDATE} row lock + the
 * {@code eye_cohort_transition} INSERT + the {@code audit_log_event}
 * INSERT all execute against real JDBC; the mock-DataSource sibling
 * {@code EyeCohortTransitionsApiControllerTest} covers the request-shape
 * guards (eye validation, missing body, etc).
 *
 * <h2>Fixtures</h2>
 *
 * <p>The shared seed leaves every {@code study_subject.study_eye} row
 * NULL. Each test method invokes {@link #setStudyEye} on the source
 * subject(s) before exercising the endpoint, so test fixtures are
 * explicit per case + don't interact between tests. A second study row
 * ({@code study_id=2}, {@code oc_oid=S_GA01}) is created class-wide in
 * {@link #seedSecondStudyAndAdminRole} as a SITE under study #1
 * (parent_study_id=1) so {@link SiteVisibilityFilter} grants the
 * Admin-roled session visibility on BOTH the source (top-level) and
 * the target (the new site) — a sibling top-level study would not be
 * visible from study #1's vantage.
 *
 * <h2>Session shape</h2>
 *
 * <p>Each happy-path test session binds the user to study #1 with
 * {@link Role#ADMIN} so {@link SiteVisibilityFilter#visibleStudyIds}
 * returns {@code {1, 2}} (the full top-level + sites tree). The 403
 * test inverts this by binding to a non-existent study #999 with no
 * role — {@code visibleStudyIds} returns {@code {999}} and the target
 * (study #2) is rejected.
 */
class EyeCohortTransitionApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** OID of the second study seeded in {@link #seedSecondStudyAndAdminRole}. */
    private static final String TARGET_STUDY_OID = "S_GA01";

    /** Set by {@link #seedSecondStudyAndAdminRole}. */
    private static int targetStudyId;

    @BeforeAll
    static void seedSecondStudyAndAdminRole() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Insert a second study used as the transition target. Mirror
            // the minimal-required column shape from the legacy
            // changeLogDataInsert.xml study seed (NOT NULL columns
            // marked with empty strings or sensible defaults).
            int id;
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
                // Make it a site under study #1 so SiteVisibilityFilter
                // walks the parent → site tree for our Admin-roled user.
                ps.setInt(1, 1);
                ps.setString(2, "ga-study");
                ps.setString(3, "ga-study");
                ps.setString(4, "GA Study");
                ps.setString(5, TARGET_STUDY_OID);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Second study insert produced no PK.");
                    }
                    id = keys.getInt(1);
                }
            }
            targetStudyId = id;
        }
    }

    private MockMvc mockMvc() {
        EyeCohortTransitionsApiController controller = new EyeCohortTransitionsApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /* ====================================================================== */
    /* 201 — OD-only iAMD → GA                                                */
    /* ====================================================================== */

    @Test
    void transitionReturns201ForOdOnlySourceWithoutExistingTarget() throws Exception {
        // M-001 (study_subject_id=1, subject_id=1) — single eye OD on
        // study #1; target study #2 has no existing row for subject_id=1.
        setStudyEye(1, "OD");
        unenrollSubjectFromStudy(targetStudyId, 1);

        mockMvc().perform(post("/api/v1/subjects/M-001/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"OCT confirms GA per protocol\"}")
                .session(adminSession(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transitionId").value(greaterThan(0)))
                .andExpect(jsonPath("$.sourceStudySubjectId").value(1))
                .andExpect(jsonPath("$.targetStudySubjectId").value(greaterThan(0)))
                .andExpect(jsonPath("$.sourceEyeAfter").doesNotExist())
                .andExpect(jsonPath("$.targetEyeAfter").value("OD"));

        // Verify source study_eye is now NULL.
        assertStudyEye(1, null);
        // Verify a new target study_subject row exists with study_eye=OD.
        int targetSsId = findStudySubjectId(targetStudyId, 1);
        assertStudyEye(targetSsId, "OD");
        // Verify the audit row.
        assertAuditRowExistsForType57();
    }

    /* ====================================================================== */
    /* 201 — OU iAMD source, transition OD                                    */
    /* ====================================================================== */

    @Test
    void transitionReturns201ForOuSourceTransitioningOd() throws Exception {
        // M-002 (study_subject_id=2, subject_id=2) — OU on study #1;
        // transitioning OD leaves OS on the source.
        setStudyEye(2, "OU");
        unenrollSubjectFromStudy(targetStudyId, 2);

        mockMvc().perform(post("/api/v1/subjects/M-002/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"OD GA confirmed; OS stays in iAMD\"}")
                .session(adminSession(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceEyeAfter").value("OS"))
                .andExpect(jsonPath("$.targetEyeAfter").value("OD"));

        assertStudyEye(2, "OS");
        int targetSsId = findStudySubjectId(targetStudyId, 2);
        assertStudyEye(targetSsId, "OD");
    }

    /* ====================================================================== */
    /* 201 — second eye to existing GA OD enrollment (upgrade to OU)          */
    /* ====================================================================== */

    @Test
    void transitionReturns201AndUpgradesExistingTargetToOu() throws Exception {
        // M-003 (study_subject_id=3, subject_id=3) — OU on study #1.
        // Target study #2 already has subject 3 enrolled with study_eye=OD
        // (representing a prior OD transition).
        setStudyEye(3, "OU");
        unenrollSubjectFromStudy(targetStudyId, 3);
        int preExistingTargetSsId =
                insertTargetStudySubject(targetStudyId, 3, "M-003-GA", "OD");

        mockMvc().perform(post("/api/v1/subjects/M-003/eyes/OS/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"OS now also GA — bilateral\"}")
                .session(adminSession(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceEyeAfter").value("OD"))
                .andExpect(jsonPath("$.targetEyeAfter").value("OU"))
                .andExpect(jsonPath("$.targetStudySubjectId").value(preExistingTargetSsId));

        assertStudyEye(3, "OD");
        assertStudyEye(preExistingTargetSsId, "OU");
    }

    /* ====================================================================== */
    /* 404 — unknown subject label                                            */
    /* ====================================================================== */

    @Test
    void transitionReturns404WhenSubjectLabelUnknown() throws Exception {
        mockMvc().perform(post("/api/v1/subjects/MNOEX99/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"never used\"}")
                .session(adminSession(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(containsString("MNOEX99")));
    }

    /* ====================================================================== */
    /* 404 — unknown target study OID                                         */
    /* ====================================================================== */

    @Test
    void transitionReturns404WhenTargetStudyOidUnknown() throws Exception {
        // Use M-007 with a fresh study_eye assignment so the eye-scope
        // guard doesn't fire before the target-resolution guard.
        setStudyEye(7, "OD");

        mockMvc().perform(post("/api/v1/subjects/M-007/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"X_DOES_NOT_EXIST\","
                        + "\"reason\":\"unknown target OID\"}")
                .session(adminSession(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(containsString("X_DOES_NOT_EXIST")));
    }

    /* ====================================================================== */
    /* 400 — source's study_eye does not include the requested eye            */
    /* ====================================================================== */

    @Test
    void transitionReturns400WhenSourceEyeNotInScope() throws Exception {
        // M-004 (study_subject_id=4) has study_eye=OD; requesting OS
        // means OS is NOT in scope → 400.
        setStudyEye(4, "OD");

        mockMvc().perform(post("/api/v1/subjects/M-004/eyes/OS/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"OS attempted on OD-only source\"}")
                .session(adminSession(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not include eye 'OS'")));
    }

    /* ====================================================================== */
    /* 409 — eye already present in target row                                */
    /* ====================================================================== */

    @Test
    void transitionReturns409WhenTargetAlreadyHasEye() throws Exception {
        // M-005 (study_subject_id=5, subject_id=5) has OU on source;
        // target study #2 already has subject 5 with study_eye=OD.
        // Transitioning OD → 409 (target already covers OD).
        setStudyEye(5, "OU");
        unenrollSubjectFromStudy(targetStudyId, 5);
        insertTargetStudySubject(targetStudyId, 5, "M-005-GA", "OD");

        mockMvc().perform(post("/api/v1/subjects/M-005/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"duplicate OD transition\"}")
                .session(adminSession(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(containsString("already has eye 'OD'")));
    }

    /* ====================================================================== */
    /* 403 — target study outside the visible study set                       */
    /* ====================================================================== */

    @Test
    void transitionReturns403WhenTargetStudyOutsideVisibleSet() throws Exception {
        // Session bound to a synthetic study #999 with no role —
        // visibleStudyIds returns {999}, which does not include
        // study #1's id (the source) → source visibility fails 403.
        // (Whether the 403 trips on source or target side is fine; the
        // contract is that an out-of-scope target also yields 403.)
        setStudyEye(6, "OD");

        mockMvc().perform(post("/api/v1/subjects/M-006/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"" + TARGET_STUDY_OID + "\","
                        + "\"reason\":\"unauthorised cross-study attempt\"}")
                .session(sessionBoundToStudyId(999)))
                // M-006 is not in study #999 → 404 ("Subject not found
                // in active study"). The 403 surface is only reachable
                // when the source IS found but the target is out of
                // scope, which the next assertion covers.
                .andExpect(status().isNotFound());

        // Direct 403 case: session bound to study #1 with an Admin role
        // there, but visibleStudyIds for parent-only top-level w/o
        // any sub-sites returns {1}. The target site_id=2 is a child of
        // #1, so Admin sees both. To hit the 403 cleanly we need the
        // target to be a top-level study OUTSIDE the visible tree. We
        // create one inline + tear it down at the end of the test.
        int orphanStudyId = insertOrphanTopLevelStudy("S_ORPHAN1");
        try {
            mockMvc().perform(post("/api/v1/subjects/M-006/eyes/OD/transition")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetStudyOid\":\"S_ORPHAN1\","
                            + "\"reason\":\"target outside visible tree\"}")
                    .session(adminSession(1)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value(containsString("not in your visible study set")));
        } finally {
            deleteStudy(orphanStudyId);
        }
    }

    /* ====================================================================== */
    /* 201 — reverse transition using NULL stub                              */
    /* ====================================================================== */

    /**
     * Phase E.6 study-nurse polish — reverse-transition lock-in.
     *
     * <p>An OD-only subject (M-007) was previously transitioned from
     * iAMD (study #1) to GA (study #2). Per the source-downgrade rule,
     * the iAMD row's {@code study_eye} is now NULL (single-eye → NULL).
     * The operator wants to "reverse" the transition: bring OD back to
     * iAMD from GA. We POST against the GA-side label as the new
     * source; the iAMD row already exists with study_eye=NULL, so
     * {@code resolveOrCreateTarget} hits the "row exists with
     * study_eye=null → re-occupy with eye" branch and the test asserts
     * 201 (NOT 409) plus the NULL-stubbed iAMD row upgrades back to OD.
     */
    @Test
    void transitionReturns201ForReverseTransitionUsingNullStub() throws Exception {
        // Phase 1: build the "after a prior transition" state for M-007.
        // M-007 (study_subject_id=7, subject_id=7) — start fresh: source
        // study_eye=NULL (post-transition stub), target M-007-GA in
        // study #2 with study_eye=OD (the eye that left).
        setStudyEye(7, null);
        unenrollSubjectFromStudy(targetStudyId, 7);
        int targetSsId = insertTargetStudySubject(targetStudyId, 7, "M-007-GA", "OD");

        // Phase 2: reverse the transition. Active study bound to study
        // #2 (GA); source = "M-007-GA", target = iAMD (S_DEFAULTS1).
        // Since the iAMD row exists with study_eye=NULL, the controller
        // upgrades it back to study_eye='OD' instead of creating a new
        // row or 409'ing.
        mockMvc().perform(post("/api/v1/subjects/M-007-GA/eyes/OD/transition")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStudyOid\":\"S_DEFAULTS1\","
                        + "\"reason\":\"Reverse transition — patient re-classified to iAMD\"}")
                .session(adminSession(targetStudyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceEyeAfter").doesNotExist())
                .andExpect(jsonPath("$.targetEyeAfter").value("OD"));

        // The GA source row downgrades single-eye → NULL.
        assertStudyEye(targetSsId, null);
        // The iAMD NULL-stub row upgrades to OD.
        assertStudyEye(7, "OD");
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private void setStudyEye(int studySubjectId, String eye) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_subject SET study_eye = ? WHERE study_subject_id = ?")) {
            if (eye == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, eye);
            }
            ps.setInt(2, studySubjectId);
            ps.executeUpdate();
        }
    }

    private void unenrollSubjectFromStudy(int studyId, int subjectId) throws SQLException {
        // Tear down any leftover study_subject rows from prior tests.
        // eye_cohort_transition rows referencing those study_subject_ids
        // would block the delete via the RESTRICT FK; clear them first
        // so the same Postgres container can run the test suite
        // repeatedly without cross-test interference.
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eye_cohort_transition "
                    + "WHERE subject_id = ? AND (source_study_id = ? OR target_study_id = ?)")) {
                ps.setInt(1, subjectId);
                ps.setInt(2, studyId);
                ps.setInt(3, studyId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM study_subject WHERE study_id = ? AND subject_id = ?")) {
                ps.setInt(1, studyId);
                ps.setInt(2, subjectId);
                ps.executeUpdate();
            }
        }
    }

    private int insertTargetStudySubject(int studyId, int subjectId, String label, String studyEye)
            throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                             + "date_created, owner_id, oc_oid, study_eye) "
                             + "VALUES (?, ?, ?, 1, NOW(), 1, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label);
            ps.setInt(2, subjectId);
            ps.setInt(3, studyId);
            ps.setString(4, "SS_PRE_" + label);
            ps.setString(5, studyEye);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
                throw new SQLException("Pre-existing target study_subject insert returned no PK.");
            }
        }
    }

    private int findStudySubjectId(int studyId, int subjectId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_subject_id FROM study_subject "
                             + "WHERE study_id = ? AND subject_id = ?")) {
            ps.setInt(1, studyId);
            ps.setInt(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException(
                        "Expected study_subject row for (study=" + studyId
                                + ", subject=" + subjectId + ") not found.");
            }
        }
    }

    private void assertStudyEye(int studySubjectId, String expected) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_eye FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next(),
                        "study_subject_id=" + studySubjectId + " not found");
                String actual = rs.getString(1);
                org.junit.jupiter.api.Assertions.assertEquals(
                        expected, actual,
                        "study_eye on study_subject_id=" + studySubjectId);
            }
        }
    }

    private void assertAuditRowExistsForType57() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = 57")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);
                org.junit.jupiter.api.Assertions.assertTrue(count >= 1,
                        "Expected at least one audit_log_event of type 57; got " + count);
            }
        }
    }

    private int insertOrphanTopLevelStudy(String oid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
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
                             + "VALUES (NULL, ?, ?, ?, '', NOW(), NOW(), NOW(), 1, 1, 1, 'default', "
                             + "'', '', '', '', '', '', '', '', '', '', 'observational', '', NOW(), "
                             + "'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '', "
                             + "false, 'Natural History', '', '', '', '', '', '', 'longitudinal', "
                             + "'Convenience Sample', 'Retrospective', '', false, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "orphan-" + oid);
            ps.setString(2, "orphan-" + oid);
            ps.setString(3, "Orphan Study " + oid);
            ps.setString(4, oid);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
                throw new SQLException("Orphan study insert returned no PK.");
            }
        }
    }

    private void deleteStudy(int studyId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            ps.executeUpdate();
        }
    }

    /**
     * Build a session attached to user #1 (seeded "root"), with the
     * given study bound + an explicit Admin role on that study so
     * {@link SiteVisibilityFilter} walks the full top-level + sites
     * tree.
     */
    private MockHttpSession adminSession(int studyId) {
        // StudyUserRoleBean.setRole(...) → Term.getName() → ResourceBundle
        // lookup on the current thread. Bind a locale so the ThreadLocal
        // bundle resolves (mirrors AbstractApiControllerTest @BeforeEach
        // landed in PR #116 for the mock-DS layer).
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);

        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(studyId == 1 ? "S_DEFAULTS1" : "synthetic-" + studyId);
        study.setName("study-" + studyId);
        // parent_study_id defaults to 0 (top-level), which is what
        // SiteVisibilityFilter expects for the "include parent + walk
        // sites" branch.
        session.setAttribute("study", study);

        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        role.setStudyId(studyId);
        session.setAttribute("userRole", role);
        return session;
    }

    /**
     * Session attached to a study_id the seed does not populate, with
     * no role — the visibility filter returns {@code {studyId}}; M-006
     * lives in study #1, so the source-side visibility check would
     * fail. Used to assert the 403 branch.
     */
    private MockHttpSession sessionBoundToStudyId(int studyId) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid("synthetic-study-" + studyId);
        study.setName("synthetic-study-" + studyId);
        session.setAttribute("study", study);
        return session;
    }
}
