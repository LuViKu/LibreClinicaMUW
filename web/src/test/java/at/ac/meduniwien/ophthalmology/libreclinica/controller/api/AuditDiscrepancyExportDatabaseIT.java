/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 {@code audit-discrepancy-export} — happy-path Testcontainers
 * IT for the {@code AuditApiController} and {@code DiscrepancyApiController}
 * list + export endpoints.
 *
 * <p>Combined into a single IT because the two clusters share the same
 * filter shape + the same exporter audit-emission pattern. Each test
 * targets one of the four user-visible surfaces:
 *
 * <ul>
 *   <li>{@code GET /api/v1/audit} — array of {@code AuditEventDto}
 *       (200 even on a freshly-seeded DB; empty array is fine).</li>
 *   <li>{@code GET /api/v1/audit/export.xlsx} — XLSX byte stream with
 *       the {@code application/vnd.openxmlformats…spreadsheetml.sheet}
 *       Content-Type, and a side-effect audit row (type 55).</li>
 *   <li>{@code GET /api/v1/discrepancies} — array of
 *       {@code DiscrepancyNoteDto}.</li>
 *   <li>{@code GET /api/v1/discrepancies/export.csv} — CSV byte stream
 *       with a UTF-8 BOM + the side-effect audit row (type 56).</li>
 * </ul>
 */
class AuditDiscrepancyExportDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc auditMockMvc() {
        AuditApiController controller = new AuditApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockMvc discrepancyMockMvc() {
        DiscrepancyApiController controller = new DiscrepancyApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession authenticatedRootSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    @Test
    void auditListReturns200WithArrayPayload() throws Exception {
        auditMockMvc().perform(get("/api/v1/audit")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void auditExportXlsxReturnsBytesAndEmitsAuditRow() throws Exception {
        // Count rows of type 55 before + after; expect at least one delta.
        int before = countAuditRowsOfType(55);

        auditMockMvc().perform(get("/api/v1/audit/export.xlsx")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        int after = countAuditRowsOfType(55);
        org.junit.jupiter.api.Assertions.assertTrue(after >= before + 1,
                "audit_log_event type 55 (audit-log exported) row expected after export");
    }

    @Test
    void discrepancyListReturns200WithArrayPayload() throws Exception {
        discrepancyMockMvc().perform(get("/api/v1/discrepancies")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(0)));
    }

    @Test
    void discrepancyExportCsvReturnsBytesAndEmitsAuditRow() throws Exception {
        int before = countAuditRowsOfType(56);

        discrepancyMockMvc().perform(get("/api/v1/discrepancies/export.csv")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));

        int after = countAuditRowsOfType(56);
        org.junit.jupiter.api.Assertions.assertTrue(after >= before + 1,
                "audit_log_event type 56 (discrepancy-log exported) row expected after export");
    }

    private int countAuditRowsOfType(int typeId) throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event "
                     + "WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                return 0;
            }
        }
    }
}
