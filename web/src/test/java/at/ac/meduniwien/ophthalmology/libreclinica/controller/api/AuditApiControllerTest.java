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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link AuditApiController}
 * session-guard contract surface.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/audit} → {@code 401} anonymous /
 *       {@code 400} no active study.</li>
 *   <li>Query-string filter wiring — {@code actor=…}, {@code variant=…},
 *       {@code subjectId=…} parse without 4xx; the actual SQL pass-through
 *       needs a real DataSource (out of scope for this cut, but the
 *       request being accepted demonstrates routing + binding work).
 *       With the mock DataSource the SQL throws an SQLException inside
 *       a try/catch and yields {@code 500} — assert that as the path-
 *       reached marker.</li>
 * </ul>
 */
class AuditApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new AuditApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class)));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/audit")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/audit")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* A5 — value-prettification helpers                                      */
    /* ---------------------------------------------------------------------- */

    @Test
    void prettifyMapsStudySubjectStatusCodesToHumanLabels() {
        assertEquals("Available", AuditApiController.prettifyValue("study_subject", "Status", "1"));
        assertEquals("Unavailable", AuditApiController.prettifyValue("study_subject", "Status", "2"));
        assertEquals("Removed", AuditApiController.prettifyValue("study_subject", "Status", "5"));
        assertEquals("Signed", AuditApiController.prettifyValue("study_subject", "Status", "8"));
    }

    @Test
    void prettifyMapsEventCrfStatusCodesToHumanLabels() {
        assertEquals("Available", AuditApiController.prettifyValue("event_crf", "Status", "1"));
        assertEquals("Signed", AuditApiController.prettifyValue("event_crf", "Status", "8"));
    }

    @Test
    void prettifyMapsStudyEventStatusCodesToHumanLabels() {
        assertEquals("Scheduled", AuditApiController.prettifyValue("study_event", "Status", "1"));
        assertEquals("Completed", AuditApiController.prettifyValue("study_event", "Status", "4"));
        assertEquals("Signed", AuditApiController.prettifyValue("study_event", "Status", "8"));
    }

    @Test
    void prettifyMapsSdvStatusBooleansToSemanticLabels() {
        assertEquals("SDV complete",
                AuditApiController.prettifyValue("event_crf", "EventCRF SDV Status", "TRUE"));
        assertEquals("SDV pending",
                AuditApiController.prettifyValue("event_crf", "EventCRF SDV Status", "FALSE"));
    }

    @Test
    void prettifyMapsRawBooleansToYesNo() {
        // Outside the (audit_table, type_name) status-mapped pairs,
        // a raw TRUE/FALSE column-value still gets prettified.
        assertEquals("yes",
                AuditApiController.prettifyValue("event_crf", "Required",  "TRUE"));
        assertEquals("no",
                AuditApiController.prettifyValue("event_crf", "Required",  "FALSE"));
        assertEquals("yes",
                AuditApiController.prettifyValue(null, null, "true"));
    }

    @Test
    void prettifyPassesThroughOtherValuesUnchanged() {
        assertEquals("2026-06-01",
                AuditApiController.prettifyValue("item_data", "value", "2026-06-01"));
        assertEquals("free-text comment",
                AuditApiController.prettifyValue("discrepancy_note", "Description", "free-text comment"));
        assertNull(AuditApiController.prettifyValue(null, null, null));
    }

    @Test
    void buildInClauseEmitsExpectedPlaceholders() {
        assertEquals("(?)", AuditApiController.buildInClause(1));
        assertEquals("(?,?,?)", AuditApiController.buildInClause(3));
        // Zero-arity is a defensive case — the caller pre-clamps to
        // at least one id (falling back to the current study) before
        // reaching this point.
        assertEquals("(NULL)", AuditApiController.buildInClause(0));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 (2026-06-05) — dataset-export branch in the audit query      */
    /* ---------------------------------------------------------------------- */

    /**
     * Pins that the SQL template now UNIONs in {@code audit_table = 'dataset'}.
     * Without this branch the {@code ExportAuditService.emitExportAudit}
     * rows would never surface in {@code GET /api/v1/audit}. The literal
     * {@code dataset_id FROM dataset WHERE study_id IN __IN__} subquery is
     * what makes the egress-event row study-scoped via the dataset.study_id
     * foreign key.
     */
    @Test
    void sqlTemplateIncludesDatasetExportBranch() throws Exception {
        Field f = AuditApiController.class.getDeclaredField("STUDY_SCOPED_AUDIT_SQL_TEMPLATE");
        f.setAccessible(true);
        String sql = (String) f.get(null);
        assertTrue(sql.contains("a.audit_table = 'dataset'"),
                "SQL template should UNION in audit_table='dataset' for dataset-export rows");
        assertTrue(sql.contains("FROM dataset WHERE study_id IN __IN__"),
                "dataset branch should be study-scoped via dataset.study_id FK join");
    }

    /**
     * Pins that dataset-export rows (type 52) fall into the "admin" bucket.
     * The SPA's audit-log view uses the variant chip to colour-code rows —
     * dropping these into "data" by accident would break operator pivoting
     * on the admin filter.
     */
    @Test
    void variantForDatasetExportedMapsToAdmin() throws Exception {
        java.lang.reflect.Method m =
                AuditApiController.class.getDeclaredMethod("variantForType", int.class, String.class);
        m.setAccessible(true);
        assertEquals("admin", m.invoke(null, 52, null));
        assertEquals("admin", m.invoke(null, 52, ""));
        // A non-empty reason-for-change still wins, mirroring the legacy
        // mapping the SPA's existing variants depend on.
        assertEquals("reason-for-change", m.invoke(null, 52, "operator override"));
    }

    /**
     * MockMvc surface: {@code GET /api/v1/audit?variant=admin} accepts
     * the filter parameter and reaches the SQL try-block. With the mock
     * DataSource the query throws inside the try/catch and we get a 500;
     * that's the contract-reached marker — proves the bind-loop change
     * from 6 → 7 placeholder branches didn't break routing.
     */
    @Test
    void listAcceptsAdminVariantFilterAfterDatasetBranchAdded() throws Exception {
        mockMvcWith().perform(get("/api/v1/audit")
                .param("variant", "admin")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void listAcceptsFilterQueryParameters() throws Exception {
        // Filters bind correctly: routing reaches the SQL try-block,
        // which errors with the mock DataSource (no real getConnection()
        // → NPE on prepareStatement). With the Phase E.5 #6
        // ApiExceptionHandler registered on the MockMvc builder, that
        // NPE wraps to a 500 JSON body with {"message": ...} — the
        // contract surface the SPA's ApiError model consumes.
        // Once Testcontainers Postgres lands this test asserts 200 +
        // filter semantics instead.
        mockMvcWith().perform(get("/api/v1/audit")
                .param("actor", "root")
                .param("variant", "data")
                .param("subjectId", "M-001")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").exists());
    }
}
