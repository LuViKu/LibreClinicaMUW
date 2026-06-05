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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 — happy-path Testcontainers IT for the in-SPA
 * {@code POST /api/v1/events/{id}/crfs/{edcId}:start} endpoint.
 *
 * <p>Pins the four status surfaces flagged as "ride on Testcontainers
 * Postgres follow-up" in {@link EventCrfStartApiControllerTest}:
 *
 * <ul>
 *   <li><strong>201</strong> — happy path: event 1 + edc 1 (Demographics
 *       on V1 Inclusion) → new {@code event_crf} row, response carries
 *       the freshly persisted id.</li>
 *   <li><strong>404</strong> — unknown {@code eventId}.</li>
 *   <li><strong>404</strong> — unknown {@code edcId}.</li>
 *   <li><strong>404</strong> — {@code edc} belongs to a different event
 *       definition than the one the event is wired to (the controller's
 *       same-definition guard).</li>
 *   <li><strong>403</strong> — the event lives in a different study
 *       than the session's active study (visibility filter).</li>
 *   <li><strong>409</strong> — a non-deleted {@code event_crf} already
 *       exists for the (event, crf_version) tuple; second call is
 *       refused.</li>
 * </ul>
 *
 * <p>The 401 (anonymous) + 400 (no active study) cases ride on the
 * existing mock-DataSource MockMvc class
 * {@link EventCrfStartApiControllerTest} — they don't need a live DB.
 *
 * <p>Seed expectations (from {@code lc-muw-2026-06-01-seed-demo-data.xml}):
 * <ul>
 *   <li>{@code event_definition_crf#1} → sed=1, default_version_id=1.</li>
 *   <li>{@code event_definition_crf#2} → sed=2, default_version_id=1.</li>
 *   <li>{@code event_definition_crf#3} → sed=3, default_version_id=1.</li>
 *   <li>The seed pre-populates {@code event_crf} rows for most
 *       (event, version=1) tuples. Events <strong>without</strong>
 *       a seeded event_crf — usable for happy-path / 409 testing —
 *       are #6, #11, #12, #15, #21 (subjects with not-scheduled
 *       visits or partial cohorts).</li>
 * </ul>
 */
class EventCrfStartApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvc() {
        EventsApiController controller = new EventsApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE));
        // No ApiExceptionHandler so any underlying NPE / SQLException
        // bubbles out as a ServletException with the real cause —
        // production wrapping is already pinned by the mock-DS test.
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /* ====================================================================== */
    /* 201 happy path                                                         */
    /* ====================================================================== */

    @Test
    void startReturns201AndCreatesEventCrf() throws Exception {
        // event#11 = M-004's V2 (sed=2). The seed leaves this slot
        // empty (M-004 only has an event_crf on V1) so the first call
        // creates cleanly. edc#2 wires V2 → Demographics, default
        // version 1.
        mockMvc().perform(post("/api/v1/events/11/crfs/2:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(authenticatedSession()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventCrfId").value(greaterThan(0)))
                .andExpect(jsonPath("$.eventCrfOid").exists())
                .andExpect(jsonPath("$.eventId").value(11))
                .andExpect(jsonPath("$.eventDefinitionCrfId").value(2))
                .andExpect(jsonPath("$.crfVersionId").value(1))
                .andExpect(jsonPath("$.status").value("data-entry-started"));
    }

    /* ====================================================================== */
    /* 404 unknown event                                                      */
    /* ====================================================================== */

    @Test
    void startReturns404WhenEventIdUnknown() throws Exception {
        mockMvc().perform(post("/api/v1/events/999999/crfs/1:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(authenticatedSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No study_event with id 999999")));
    }

    /* ====================================================================== */
    /* 404 unknown event_definition_crf                                       */
    /* ====================================================================== */

    @Test
    void startReturns404WhenEdcIdUnknown() throws Exception {
        mockMvc().perform(post("/api/v1/events/1/crfs/999999:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(authenticatedSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No event_definition_crf with id 999999")));
    }

    /* ====================================================================== */
    /* 404 edc not wired into this event's definition                         */
    /* ====================================================================== */

    @Test
    void startReturns404WhenEdcNotWiredIntoEventsDefinition() throws Exception {
        // event#15 has study_event_definition_id=3; edc#1 has
        // study_event_definition_id=1. Different SEDs → guard fires.
        // (Pick an event without a seeded event_crf to prevent
        // interaction with subsequent 409 testing.)
        mockMvc().perform(post("/api/v1/events/15/crfs/1:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(authenticatedSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(containsString("not wired into this event's definition")));
    }

    /* ====================================================================== */
    /* 403 event in a study outside the visible set                           */
    /* ====================================================================== */

    @Test
    void startReturns403WhenEventBelongsToDifferentStudy() throws Exception {
        // Bind the session to a study_id the user has no grants on — the
        // visibility filter falls through to {currentStudy.id} = {999}.
        // event#1's subject is in study_id=1, so 1 ∉ {999} → 403.
        mockMvc().perform(post("/api/v1/events/1/crfs/1:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(sessionBoundToStudyId(999)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("different study")));
    }

    /* ====================================================================== */
    /* 409 duplicate (already-started)                                        */
    /* ====================================================================== */

    @Test
    void startReturns409OnSecondCallForSameSlot() throws Exception {
        MockMvc mvc = mockMvc();

        // Use event#12 (M-004 V3) + edc#3 (sed=3) — an unseeded slot.
        // First call creates; second is a duplicate.
        mvc.perform(post("/api/v1/events/12/crfs/3:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(authenticatedSession()))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/events/12/crfs/3:start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .session(authenticatedSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(containsString("already exists for this slot")))
                .andExpect(jsonPath("$.eventCrfId").value(greaterThan(0)))
                .andExpect(jsonPath("$.eventCrfOid").exists());
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    /**
     * Session attached to a study_id the seed does not populate. The
     * visibility filter returns {@code {studyId}}; events in study 1
     * fall outside that set ⇒ 403.
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
