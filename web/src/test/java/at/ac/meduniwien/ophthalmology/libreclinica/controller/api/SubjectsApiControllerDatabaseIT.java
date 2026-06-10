/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.5 #1 — first happy-path IT against a Testcontainers Postgres.
 *
 * <p>Pins that {@code GET /api/v1/subjects} returns the 7 seeded
 * subjects (M-001 .. M-007) when an authenticated user has study #1
 * bound, with M-003 and M-006 marked {@code signed: true} per the seed
 * file's status_id=8 SIGNED rows.
 *
 * <p>This IT proves the {@link AbstractApiControllerDatabaseIT} wiring
 * works end-to-end against a real DB: container start → Liquibase
 * apply → DAO query → JSON response. Subsequent adapter PRs can opt
 * in (per-IT, additive) as their happy paths become worth pinning.
 */
class SubjectsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void listReturnsSevenSeededSubjects() throws Exception {
        SubjectsApiController controller = buildSubjectsController();
        // No ApiExceptionHandler so any underlying NPE / SQLException bubbles
        // out as a ServletException with the real cause, giving us a useful
        // stack trace if the IT regresses. Production wraps via the advice;
        // the wrapping is already pinned by SubjectsApiControllerTest.
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/v1/subjects").session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[?(@.id == 'M-001')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'M-003')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'M-006')]").exists())
                .andExpect(jsonPath("$[?(@.signed == true)].id")
                        .value(containsInAnyOrder("M-003", "M-006")));
    }

    /* ====================================================================== */
    /* Phase E.6 retrospective-backfill — match-preflight (dedup lookup)     */
    /* ====================================================================== */

    /**
     * No seeded subject carries the (first_name, last_name, dob) triplet
     * out of the box, so a fresh preflight should return an empty list
     * and HTTP 200.
     */
    @Test
    void matchPreflightReturnsEmptyArrayWhenNoMatch() throws Exception {
        SubjectsApiController controller = buildSubjectsController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/subjects/match-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Nobody\",\"lastName\":\"Unknown\","
                        + "\"dateOfBirth\":\"1900-01-01\"}")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Seed a subject with the triplet then call preflight — the match
     * comes back with the subject's identifying fields and the visible
     * studies they're in (lookup is case-insensitive: lowercase input
     * still finds an uppercase-typed name).
     */
    @Test
    void matchPreflightReturnsCandidateCaseInsensitive() throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE subject SET first_name = ?, last_name = ?, "
                             + "date_of_birth = ?, dob_collected = true "
                             + "WHERE subject_id = 1")) {
            ps.setString(1, "Maria");
            ps.setString(2, "Müller");
            ps.setDate(3, java.sql.Date.valueOf("1965-04-12"));
            ps.executeUpdate();
        }

        SubjectsApiController controller = buildSubjectsController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        // Lowercase the operator's input; partial index on
        // LOWER(first_name)/LOWER(last_name) still hits the row.
        mockMvc.perform(post("/api/v1/subjects/match-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"maria\",\"lastName\":\"müller\","
                        + "\"dateOfBirth\":\"1965-04-12\"}")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectId").value(1))
                .andExpect(jsonPath("$[0].dateOfBirth").value("1965-04-12"));
    }

    /**
     * Soft-deleted (status_id=5) subjects are excluded by the partial
     * dedup index — even with an exact PHI match they must not appear
     * in the candidate list so a tombstoned record can't shadow a real
     * re-enrolment.
     */
    @Test
    void matchPreflightSkipsSoftDeletedSubjects() throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE subject SET first_name = ?, last_name = ?, "
                             + "date_of_birth = ?, dob_collected = true, "
                             + "status_id = 5 WHERE subject_id = 2")) {
            ps.setString(1, "Karl");
            ps.setString(2, "Tombstone");
            ps.setDate(3, java.sql.Date.valueOf("1950-01-01"));
            ps.executeUpdate();
        }

        SubjectsApiController controller = buildSubjectsController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/subjects/match-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Karl\",\"lastName\":\"Tombstone\","
                        + "\"dateOfBirth\":\"1950-01-01\"}")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Phase E.6 follow-up 2026-06-10 — the candidate row echoes back
     * {@code firstName} + {@code lastName} as persisted (operator
     * confirmation aid: they just typed it). The seeded subject #1
     * has visible enrolment as M-001 in default-study (oc_oid
     * S_DEFAULTS1) → the {@code studies} array carries the protocol
     * short-code, the system OID, the display name, and the per-study
     * subject label.
     */
    @Test
    void matchPreflightReturnsNameAndStudyEnrolments() throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE subject SET first_name = ?, last_name = ?, "
                             + "date_of_birth = ?, dob_collected = true "
                             + "WHERE subject_id = 1")) {
            ps.setString(1, "Anna");
            ps.setString(2, "Schmidt");
            ps.setDate(3, java.sql.Date.valueOf("1970-03-15"));
            ps.executeUpdate();
        }

        SubjectsApiController controller = buildSubjectsController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/subjects/match-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Anna\",\"lastName\":\"Schmidt\","
                        + "\"dateOfBirth\":\"1970-03-15\"}")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectId").value(1))
                .andExpect(jsonPath("$[0].firstName").value("Anna"))
                .andExpect(jsonPath("$[0].lastName").value("Schmidt"))
                .andExpect(jsonPath("$[0].studies").isArray())
                .andExpect(jsonPath("$[0].studies.length()").value(1))
                .andExpect(jsonPath("$[0].studies[0].studyUniqueIdentifier")
                        .value("default-study"))
                .andExpect(jsonPath("$[0].studies[0].studyOid").value("S_DEFAULTS1"))
                .andExpect(jsonPath("$[0].studies[0].label").value("M-001"));
    }

    /**
     * Phase E.6 follow-up 2026-06-10 — enrolments in studies the
     * operator can't see are suppressed from {@code studies} but still
     * counted in {@code otherStudyCount}. The seeded subject #2 has a
     * visible enrolment in default-study (study_id=1) which the operator
     * CAN see; flipping its study_id to a hidden study id (=2, absent
     * from the seed) hides the enrolment row instead. The candidate is
     * still returned but {@code studies} is empty and
     * {@code otherStudyCount} is 1.
     */
    @Test
    void matchPreflightHidesEnrolmentsInInaccessibleStudies() throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection()) {
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "UPDATE subject SET first_name = ?, last_name = ?, "
                            + "date_of_birth = ?, dob_collected = true "
                            + "WHERE subject_id = 2")) {
                ps.setString(1, "Hidden");
                ps.setString(2, "Enrolment");
                ps.setDate(3, java.sql.Date.valueOf("1980-06-20"));
                ps.executeUpdate();
            }
            // The study row MUST exist BEFORE we re-point the enrolment
            // (FK study_subject.study_id → study.study_id fires on the
            // UPDATE). Then re-point subject #2's enrolment row at a
            // study the operator does NOT have a role on. study_id=2
            // has no seeded study_user_role grant for "root" →
            // loadVisibleStudyOids excludes it.
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study (study_id, name, unique_identifier, oc_oid, "
                            + "type_id, status_id, owner_id, date_created, parent_study_id) "
                            + "VALUES (2, 'Hidden Study', 'hidden-study', 'S_HIDDEN', "
                            + "1, 1, 1, NOW(), NULL) ON CONFLICT (study_id) DO NOTHING")) {
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "UPDATE study_subject SET study_id = 2 WHERE subject_id = 2")) {
                ps.executeUpdate();
            }
        }

        SubjectsApiController controller = buildSubjectsController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/subjects/match-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Hidden\",\"lastName\":\"Enrolment\","
                        + "\"dateOfBirth\":\"1980-06-20\"}")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectId").value(2))
                .andExpect(jsonPath("$[0].studies").isArray())
                .andExpect(jsonPath("$[0].studies.length()").value(0))
                .andExpect(jsonPath("$[0].otherStudyCount").value(1));
    }

    /**
     * Missing fields → 400 with an explicit message. The SPA waits
     * until all three are populated before firing, but defensive
     * rejection here keeps the contract clean.
     */
    @Test
    void matchPreflightReturns400WhenAnyFieldMissing() throws Exception {
        SubjectsApiController controller = buildSubjectsController();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/subjects/match-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Maria\",\"lastName\":\"\","
                        + "\"dateOfBirth\":\"1965-04-12\"}")
                .session(authenticatedSession()))
                .andExpect(status().isBadRequest());
    }
}
