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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wave 1A (app-feedback, 2026-06-19) — MockMvc unit test for the
 * cancel-event reason-validation contract.
 *
 * <p>The DELETE handler walks two DAO lookups (study_event, study_subject)
 * BEFORE it reaches the reason-code validation guard. We use
 * {@link Mockito#mockConstruction(Class) Mockito.mockConstruction} to
 * intercept the {@code new StudyEventDAO(ds)} / {@code new StudySubjectDAO(ds)}
 * calls inside the controller and short-circuit {@code findByPK} so the
 * request lands on the body-validation branch instead of a 404.
 *
 * <p>The reason-lookup path is exercised against a JDBC mock so the 400
 * for unknown codes lands at the right guard.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>DELETE with missing body → 400 ("reasonCode is required").</li>
 *   <li>DELETE with blank {@code reasonCode} → 400.</li>
 *   <li>DELETE with an unknown code → 400 ("Unknown cancel reasonCode").</li>
 *   <li>DELETE with {@code OTHER} + blank text → 400.</li>
 *   <li>GET event-cancel-reasons anonymous → 401.</li>
 *   <li>GET event-cancel-reasons authenticated → 200.</li>
 * </ul>
 *
 * <p>The 204 happy path needs a real StudyEventDAO + downstream cascade
 * walk so it stays on the manual smoke + integration harness; the
 * contract pinned here is enough to catch the SPA-facing wire shape
 * from drifting.
 */
class EventsApiControllerCancelReasonTest extends AbstractApiControllerTest {

    /** Active for every {@code cancel*} test — close in @AfterEach. */
    private MockedConstruction<StudyEventDAO> seDaoMock;
    /** Active for every {@code cancel*} test — close in @AfterEach. */
    private MockedConstruction<StudySubjectDAO> ssDaoMock;

    @BeforeEach
    void interceptDaoLookups() {
        StudyEventBean ev = Mockito.mock(StudyEventBean.class);
        Mockito.when(ev.getId()).thenReturn(1);
        Mockito.when(ev.getStudySubjectId()).thenReturn(1);
        // Null status + null SubjectEventStatus = "not deleted, not signed,
        // not locked" — the controller falls through to body validation.

        StudySubjectBean ss = Mockito.mock(StudySubjectBean.class);
        Mockito.when(ss.getId()).thenReturn(1);
        Mockito.when(ss.getStudyId()).thenReturn(1);
        // Null status = not LOCKED → SubjectLockGuard.refuseIfLocked returns
        // null.

        seDaoMock = Mockito.mockConstruction(StudyEventDAO.class,
                (mock, ctx) -> Mockito.when(mock.findByPK(Mockito.anyInt())).thenReturn(ev));
        ssDaoMock = Mockito.mockConstruction(StudySubjectDAO.class,
                (mock, ctx) -> Mockito.when(mock.findByPK(Mockito.anyInt())).thenReturn(ss));
    }

    @AfterEach
    void releaseDaoMocks() {
        if (seDaoMock != null) {
            seDaoMock.close();
        }
        if (ssDaoMock != null) {
            ssDaoMock.close();
        }
    }

    /**
     * Build a MockMvc against a DataSource pre-wired with the JDBC mocks
     * the cancel-reason lookups need. The SiteVisibilityFilter mock is
     * stubbed to surface study id 1 so the visibility check passes for
     * the mocked study_subject row.
     */
    private MockMvc mockMvcWithReason(boolean rowExists, boolean isOther) throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);

        Connection conn = Mockito.mock(Connection.class);
        Mockito.when(ds.getConnection()).thenReturn(conn);

        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        Mockito.when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);

        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(rowExists).thenReturn(false);
        Mockito.when(rs.getBoolean(1)).thenReturn(isOther);
        // Defensive: the catalog scan reads by column name; back the
        // mock with the same flag plus stub strings so a misrouted test
        // doesn't blow up.
        Mockito.when(rs.getBoolean("is_other")).thenReturn(isOther);
        Mockito.when(rs.getString("code")).thenReturn("PATIENT_NO_SHOW");
        Mockito.when(rs.getString("label_de")).thenReturn("Patient nicht erschienen");
        Mockito.when(rs.getString("label_en")).thenReturn("Patient did not attend");
        Mockito.when(rs.getInt("sort_order")).thenReturn(10);

        SiteVisibilityFilter visibility = Mockito.mock(SiteVisibilityFilter.class);
        Mockito.when(visibility.visibleStudyIds(
                        Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Set.of(1));

        return mockMvcFor(new EventsApiController(ds, visibility,
                Mockito.mock(VisitIntervalCalculator.class)));
    }

    /* ---------------------------------------------------------------------- */
    /* DELETE /api/v1/events/{id} — Wave 1A body validation                   */
    /* ---------------------------------------------------------------------- */

    @Test
    void cancelReturns400WhenBodyMissing() throws Exception {
        // Authenticated Admin (role 1 — passes the role gate so the
        // request reaches the body check) but no JSON body.
        mockMvcWithReason(false, false)
                .perform(delete("/api/v1/events/1")
                        .session((org.springframework.mock.web.MockHttpSession)
                                authenticatedSessionWithRole(1, "root", 1, "S_DEFAULTS1",
                                        "Default Study", Role.ADMIN, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("reasonCode is required")));
    }

    @Test
    void cancelReturns400WhenReasonCodeBlank() throws Exception {
        mockMvcWithReason(false, false)
                .perform(delete("/api/v1/events/1")
                        .session((org.springframework.mock.web.MockHttpSession)
                                authenticatedSessionWithRole(1, "root", 1, "S_DEFAULTS1",
                                        "Default Study", Role.ADMIN, 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"\",\"reasonText\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("reasonCode is required")));
    }

    @Test
    void cancelReturns400WhenReasonCodeUnknown() throws Exception {
        mockMvcWithReason(false, false)
                .perform(delete("/api/v1/events/1")
                        .session((org.springframework.mock.web.MockHttpSession)
                                authenticatedSessionWithRole(1, "root", 1, "S_DEFAULTS1",
                                        "Default Study", Role.ADMIN, 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Unknown cancel reasonCode")));
    }

    @Test
    void cancelReturns400WhenOtherWithoutText() throws Exception {
        // Reason row exists (rowExists=true) and the row's is_other flag
        // is TRUE — so the controller demands non-blank reasonText.
        mockMvcWithReason(true, true)
                .perform(delete("/api/v1/events/1")
                        .session((org.springframework.mock.web.MockHttpSession)
                                authenticatedSessionWithRole(1, "root", 1, "S_DEFAULTS1",
                                        "Default Study", Role.ADMIN, 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OTHER\",\"reasonText\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("reasonText is required")));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/event-cancel-reasons                                       */
    /* ---------------------------------------------------------------------- */

    @Test
    void listCancelReasonsReturns401WhenAnonymous() throws Exception {
        MockMvc mvc = mockMvcFor(new EventCancelReasonsApiController(mockDataSource()));
        mvc.perform(get("/api/v1/event-cancel-reasons")
                        .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listCancelReasonsReturnsRowsWhenAuthenticated() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        Mockito.when(ds.getConnection()).thenReturn(conn);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        Mockito.when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        // Two rows, then end.
        Mockito.when(rs.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        Mockito.when(rs.getString("code"))
                .thenReturn("PATIENT_NO_SHOW").thenReturn("OTHER");
        Mockito.when(rs.getString("label_de"))
                .thenReturn("Patient nicht erschienen").thenReturn("Sonstiges (Freitext)");
        Mockito.when(rs.getString("label_en"))
                .thenReturn("Patient did not attend").thenReturn("Other (free text)");
        Mockito.when(rs.getInt("sort_order")).thenReturn(10).thenReturn(90);
        Mockito.when(rs.getBoolean("is_other")).thenReturn(false).thenReturn(true);

        MockMvc mvc = mockMvcFor(new EventCancelReasonsApiController(ds));
        mvc.perform(get("/api/v1/event-cancel-reasons")
                        .session((org.springframework.mock.web.MockHttpSession)
                                authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PATIENT_NO_SHOW"))
                .andExpect(jsonPath("$[0].isOther").value(false))
                .andExpect(jsonPath("$[1].code").value("OTHER"))
                .andExpect(jsonPath("$[1].isOther").value(true));
    }
}
