/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

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
                        DATA_SOURCE, filter, (VisitIntervalCalculator) null))
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
}
