/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

/**
 * P2-6 — the due-visits list.
 *
 * <p>Without it there is no list of who is expected. A visit nobody schedules a
 * patient for simply does not happen, and in a treat-and-extend study a missed
 * visit is a missed injection — so overdue rows are the point of the view, not
 * a side effect.
 *
 * <p>These use the seeded demo visits (2020-11-02 to 2021-01-04), which are all
 * in the past and therefore all overdue.
 */
class DueVisitsApiDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvcWith(Set<Integer> visible) {
        SiteVisibilityFilter filter = Mockito.mock(SiteVisibilityFilter.class);
        Mockito.when(filter.visibleStudyIds(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(visible);
        return MockMvcBuilders
                // The interval calculator is only touched by the scheduling
                // endpoints, which this file does not exercise.
                .standaloneSetup(new EventsApiController(
                        DATA_SOURCE, filter, null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession session() {
        UserAccountBean user = new UserAccountBean();
        user.setId(1);
        user.setName("root");
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userBean", user);
        s.setAttribute("study", study);
        s.setAttribute("userRole", role);
        return s;
    }

    @Test
    void anonymousCallersGetNothing() throws Exception {
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWindowInThePastListsTheVisitsAsOverdue() throws Exception {
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due")
                .param("from", "2020-11-02").param("to", "2020-11-08")
                .session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(2))
                .andExpect(jsonPath("$.visits[0].subjectLabel").value("M-004"))
                .andExpect(jsonPath("$.visits[0].overdue").value(true))
                .andExpect(jsonPath("$.visits[0].status").value("data-entry-started"));
    }

    /**
     * A monitor with site-only grants must not learn the other sites'
     * schedules — the list is a roster of who is coming in.
     */
    @Test
    void visitsOutsideTheVisibleStudiesAreNotListed() throws Exception {
        mockMvcWith(Set.of()).perform(get("/api/v1/events/due")
                .param("from", "2020-11-02").param("to", "2020-11-08")
                .session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(0));
    }

    /** Naming a study you cannot see narrows to nothing rather than widening. */
    @Test
    void namingAnInvisibleStudyReturnsNothing() throws Exception {
        mockMvcWith(Set.of(999)).perform(get("/api/v1/events/due")
                .param("from", "2020-11-02").param("to", "2020-11-08")
                .param("studyOid", "S_DEFAULTS1")
                .session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(0));
    }

    @Test
    void namingAVisibleStudyNarrowsToIt() throws Exception {
        mockMvcWith(Set.of(1, 102)).perform(get("/api/v1/events/due")
                .param("from", "2020-11-02").param("to", "2020-11-08")
                .param("studyOid", "S_DEFAULTS1")
                .session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(2));
    }

    @Test
    void anUnknownStudyOidIs404() throws Exception {
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due")
                .param("studyOid", "S_NO_SUCH_STUDY")
                .session(session()))
                .andExpect(status().isNotFound());
    }

    /** A request that would dump a year's schedule is refused, not silently clamped. */
    @Test
    void anOverlyWideWindowIsRefused() throws Exception {
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due")
                .param("from", "2020-01-01").param("to", "2021-12-31")
                .session(session()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invertedBoundsAreRefused() throws Exception {
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due")
                .param("from", "2021-01-04").param("to", "2021-01-02")
                .session(session()))
                .andExpect(status().isBadRequest());
    }

    /** No params → a window around today, well-formed even when empty. */
    @Test
    void noParamsGiveAWindowAroundToday() throws Exception {
        String today = java.time.LocalDate.now().toString();
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits").isArray())
                .andExpect(jsonPath("$.from").value(
                        java.time.LocalDate.parse(today).minusDays(14).toString()))
                .andExpect(jsonPath("$.to").value(
                        java.time.LocalDate.parse(today).plusDays(14).toString()));
    }

    /** A completed visit is not due — it happened. */
    @Test
    void closedVisitsAreNotListed() throws Exception {
        mockMvcWith(Set.of(1)).perform(get("/api/v1/events/due")
                .param("from", "2020-11-05").param("to", "2020-11-05")
                .session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(0));
    }

    /* ---------------- P2-5: duplicate scheduling ---------------- */

    /**
     * A second request for a visit that is already scheduled is refused.
     *
     * <p>Scheduling is now triggered from the treat-and-extend decision panel,
     * where a double click, a retried request or two clinicians acting on the
     * same decision would otherwise put the same patient on the calendar twice
     * for the same day. In a study where a visit means an injection, a
     * duplicate appointment is a real harm.
     *
     * <p>The pending visit is inserted directly: creating one through the
     * endpoint needs a Spring context for the rules listener, which a
     * standalone MockMvc setup does not have. What matters here is that the
     * guard sees an existing row and refuses, naming the visit it found.
     */
    @Test
    void schedulingAVisitThatIsAlreadyOnTheCalendarIsRefused() throws Exception {
        String date = "2027-03-15";
        int existing = insertScheduledVisit(date);
        try {
            mockMvcWith(Set.of(102)).perform(post("/api/v1/events")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"subjectId\":\"EIAMD139\",\"eventDefinitionOid\":\"SE_RIS_VISIT\","
                            + "\"dateStarted\":\"" + date + "\"}")
                    .session(sessionFor(102, "S_RIS_DEMO")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.studyEventId").value(existing));
        } finally {
            exec("DELETE FROM study_event WHERE study_event_id = " + existing);
        }
    }

    /**
     * The same definition on a different day is a second visit, not a
     * duplicate, and must not be refused by this guard. It fails later for an
     * unrelated reason (the rules listener needs a Spring context), so the
     * assertion is only that it is not a 409.
     */
    @Test
    void theSameVisitOnAnotherDayIsNotTreatedAsADuplicate() throws Exception {
        int existing = insertScheduledVisit("2027-03-15");
        try {
            mockMvcWith(Set.of(102)).perform(post("/api/v1/events")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"subjectId\":\"EIAMD139\",\"eventDefinitionOid\":\"SE_RIS_VISIT\","
                            + "\"dateStarted\":\"2027-04-19\"}")
                    .session(sessionFor(102, "S_RIS_DEMO")))
                    .andExpect(result -> assertEquals(false,
                            result.getResponse().getStatus() == 409,
                            "a visit on another day is not a duplicate"));
        } finally {
            exec("DELETE FROM study_event WHERE study_event_id = " + existing);
        }
    }

    /** A pending visit of the seeded repeating definition, on the given day. */
    private int insertScheduledVisit(String isoDate) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_event (study_event_definition_id, study_subject_id, location, "
                             + " sample_ordinal, date_start, owner_id, status_id, "
                             + " subject_event_status_id, date_created, start_time_flag, end_time_flag) "
                             + "SELECT sed.study_event_definition_id, ss.study_subject_id, '', "
                             + "       COALESCE((SELECT MAX(sample_ordinal) FROM study_event se2 "
                             + "                  WHERE se2.study_subject_id = ss.study_subject_id "
                             + "                    AND se2.study_event_definition_id = sed.study_event_definition_id), 0) + 1, "
                             + "       ?::date, 1, 1, 1, NOW(), false, false "
                             + "  FROM study_event_definition sed, study_subject ss "
                             + " WHERE sed.oc_oid = 'SE_RIS_VISIT' AND ss.label = 'EIAMD139' "
                             + "RETURNING study_event_id")) {
            ps.setString(1, isoDate);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void exec(String sql) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** Session bound to a named study, for definitions that live outside the default one. */
    private MockHttpSession sessionFor(int studyId, String oid) {
        MockHttpSession s = session();
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(oid);
        s.setAttribute("study", study);
        return s;
    }
}
