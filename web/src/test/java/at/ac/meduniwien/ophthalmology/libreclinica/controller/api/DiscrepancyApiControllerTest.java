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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the
 * {@link DiscrepancyApiController} session + body validation contract.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/discrepancies} → {@code 401} anonymous /
 *       {@code 400} no active study.</li>
 *   <li>{@code POST /api/v1/discrepancies} → {@code 400} on missing
 *       {@code description} or missing {@code subjectId}/{@code itemOid}
 *       refs.</li>
 * </ul>
 */
class DiscrepancyApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new DiscrepancyApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/discrepancies                                              */
    /* ---------------------------------------------------------------------- */

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/discrepancies                                             */
    /* ---------------------------------------------------------------------- */

    @Test
    void addReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"itemOid\":\"I_AGE\",\"description\":\"d\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addReturns400OnMissingDescription() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"itemOid\":\"I_AGE\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'description' is required")));
    }

    @Test
    void addReturns400OnMissingEntityRefs() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"description\":\"a query\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'subjectId' and 'itemOid' are required")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/discrepancies/{parentId}/thread (Phase E A1)              */
    /* ---------------------------------------------------------------------- */

    @Test
    void appendThreadReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies/1/thread")
                .contentType("application/json")
                .content("{\"newStatus\":\"updated\",\"description\":\"reply\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appendThreadReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies/1/thread")
                .contentType("application/json")
                .content("{\"newStatus\":\"updated\",\"description\":\"reply\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void appendThreadReturns400WhenNewStatusMissing() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies/1/thread")
                .contentType("application/json")
                .content("{\"description\":\"reply only\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'newStatus' is required")));
    }

    @Test
    void appendThreadReturns400OnUnknownStatus() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies/1/thread")
                .contentType("application/json")
                .content("{\"newStatus\":\"banana\",\"description\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Unknown newStatus")));
    }

    @Test
    void appendThreadReturns400WhenNonClosingStatusOmitsDescription() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies/1/thread")
                .contentType("application/json")
                .content("{\"newStatus\":\"updated\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'description' is required")));
    }

    @Test
    void appendThreadReturns500WhenParentLookupFailsAgainstMockDataSource() throws Exception {
        // With the mock DataSource the DAO's findByPK throws an NPE
        // on getConnection(); ApiExceptionHandler wraps it to 500.
        // Real-DB happy path lives in DiscrepancyApiControllerDatabaseIT.
        // What this pins: the request reached the post-validation
        // DAO call (not blocked by an earlier guard).
        mockMvcWith().perform(post("/api/v1/discrepancies/9999/thread")
                .contentType("application/json")
                .content("{\"newStatus\":\"closed\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* NoteTransitionMatrix — pure unit-level coverage                        */
    /* ---------------------------------------------------------------------- */

    @Test
    void transitionMatrix_OpenToUpdatedAllowedForInvestigator() {
        // current=OPEN(1) → new=UPDATED(2), Investigator role=4
        org.junit.jupiter.api.Assertions.assertEquals(
                NoteTransitionMatrix.Decision.OK,
                NoteTransitionMatrix.check(1, 2, 4));
    }

    @Test
    void transitionMatrix_OpenToResolvedIsIllegalForAnyRole() {
        // current=OPEN(1) → new=RESOLVED(3) — not allowed: must go via UPDATED first.
        org.junit.jupiter.api.Assertions.assertEquals(
                NoteTransitionMatrix.Decision.ILLEGAL_TRANSITION,
                NoteTransitionMatrix.check(1, 3, 4));
    }

    @Test
    void transitionMatrix_ResolvedToClosedAllowedForMonitor() {
        // current=RESOLVED(3) → new=CLOSED(4), Monitor role=6
        org.junit.jupiter.api.Assertions.assertEquals(
                NoteTransitionMatrix.Decision.OK,
                NoteTransitionMatrix.check(3, 4, 6));
    }

    @Test
    void transitionMatrix_ResolvedToClosedForbiddenForInvestigator() {
        // current=RESOLVED(3) → new=CLOSED(4), Investigator role=4
        // (must be Monitor or DM/Admin).
        org.junit.jupiter.api.Assertions.assertEquals(
                NoteTransitionMatrix.Decision.FORBIDDEN_FOR_ROLE,
                NoteTransitionMatrix.check(3, 4, 4));
    }

    @Test
    void transitionMatrix_ClosedIsTerminal() {
        // Any transition out of CLOSED is illegal at this endpoint.
        org.junit.jupiter.api.Assertions.assertEquals(
                NoteTransitionMatrix.Decision.ILLEGAL_TRANSITION,
                NoteTransitionMatrix.check(4, 2, 1));
    }

    @Test
    void transitionMatrix_UpdatedSelfTransitionAllowedForReplies() {
        // current=UPDATED(2) → new=UPDATED(2) — same status, any USER role permitted.
        org.junit.jupiter.api.Assertions.assertEquals(
                NoteTransitionMatrix.Decision.OK,
                NoteTransitionMatrix.check(2, 2, 6));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 discrepancy-full — type field + thread + export.csv          */
    /* ---------------------------------------------------------------------- */

    @Test
    void addReturns400OnUnknownType() throws Exception {
        mockMvcWith().perform(post("/api/v1/discrepancies")
                .contentType("application/json")
                .content("{\"subjectId\":\"M-001\",\"itemOid\":\"I_AGE\","
                        + "\"description\":\"d\",\"type\":\"banana\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Unknown type")));
    }

    @Test
    void getThreadReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies/1/thread")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getThreadReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies/1/thread")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void exportCsvReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies/export.csv")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportCsvReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/discrepancies/export.csv")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    /* ---------------------------------------------------------------------- */
    /* NoteTransitionMatrix — Phase E.6 type helpers                          */
    /* ---------------------------------------------------------------------- */

    @Test
    void typeIdForSpaName_RoundTrips() {
        org.junit.jupiter.api.Assertions.assertEquals(1,
                NoteTransitionMatrix.typeIdForSpaName("failed-validation"));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                NoteTransitionMatrix.typeIdForSpaName("annotation"));
        org.junit.jupiter.api.Assertions.assertEquals(3,
                NoteTransitionMatrix.typeIdForSpaName("query"));
        org.junit.jupiter.api.Assertions.assertEquals(4,
                NoteTransitionMatrix.typeIdForSpaName("reason-for-change"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                NoteTransitionMatrix.typeIdForSpaName("banana"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                NoteTransitionMatrix.typeIdForSpaName(null));
    }

    @Test
    void canCreateType_RfcRestrictedToDmAndAdmin() {
        // type=4 (RFC) only for Admin(1) / Director(3).
        org.junit.jupiter.api.Assertions.assertTrue(
                NoteTransitionMatrix.canCreateType(4, 1)); // Admin
        org.junit.jupiter.api.Assertions.assertTrue(
                NoteTransitionMatrix.canCreateType(4, 3)); // Study Director (DM)
        org.junit.jupiter.api.Assertions.assertFalse(
                NoteTransitionMatrix.canCreateType(4, 4)); // Investigator
        org.junit.jupiter.api.Assertions.assertFalse(
                NoteTransitionMatrix.canCreateType(4, 6)); // Monitor
    }

    @Test
    void canCreateType_NonRfcTypesAllowAnyUserRole() {
        // QUERY(3) allowed for everyone with a USER role.
        org.junit.jupiter.api.Assertions.assertTrue(
                NoteTransitionMatrix.canCreateType(3, 4)); // Investigator
        org.junit.jupiter.api.Assertions.assertTrue(
                NoteTransitionMatrix.canCreateType(3, 6)); // Monitor
        // Role 0 (no role) is rejected.
        org.junit.jupiter.api.Assertions.assertFalse(
                NoteTransitionMatrix.canCreateType(3, 0));
    }

    @Test
    void transitionMatrix_StatusIdForSpaName_RoundTrips() {
        org.junit.jupiter.api.Assertions.assertEquals(1,
                NoteTransitionMatrix.statusIdForSpaName("new"));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                NoteTransitionMatrix.statusIdForSpaName("updated"));
        org.junit.jupiter.api.Assertions.assertEquals(3,
                NoteTransitionMatrix.statusIdForSpaName("resolution-proposed"));
        org.junit.jupiter.api.Assertions.assertEquals(4,
                NoteTransitionMatrix.statusIdForSpaName("closed"));
        org.junit.jupiter.api.Assertions.assertEquals(5,
                NoteTransitionMatrix.statusIdForSpaName("not-applicable"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                NoteTransitionMatrix.statusIdForSpaName("banana"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                NoteTransitionMatrix.statusIdForSpaName(null));
    }
}
