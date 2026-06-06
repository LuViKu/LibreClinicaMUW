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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.6 {@code admin-rfc} — happy-path Testcontainers IT for
 * {@code GET /api/v1/eventCrfs/{id}}.
 *
 * <p>Pins the {@code requiresReasonForChange} flag wire shape that the
 * SPA reads to mount the ReasonForChangeModal on post-complete edits.
 * Seeded event_crf #1 (study_subject_id=1, V1, Demographics, status=1,
 * <em>date_completed</em> set) → requiresReasonForChange = true.
 * Seeded event_crf #3 (V3, status=1, <em>no</em> date_completed) →
 * requiresReasonForChange = false.
 */
class EventCrfsApiControllerAdminRfcDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mockMvc() {
        EventCrfsApiController controller = new EventCrfsApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE),
                Mockito.mock(CrfFileStorageService.class),
                new EventCrfPresenceRegistry());
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
    void getEventCrfReturns200WithRequiresRfcTrueForCompletedRow() throws Exception {
        // event_crf #1 has date_completed set in the seed — admin-rfc
        // gate fires.
        mockMvc().perform(get("/api/v1/eventCrfs/1")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresReasonForChange").value(true))
                .andExpect(jsonPath("$.schema").exists())
                .andExpect(jsonPath("$.schema.crfOid").exists());
    }

    @Test
    void getEventCrfReturns200WithRequiresRfcFalseForIncompleteRow() throws Exception {
        // event_crf #3 has no date_completed in the seed — admin-rfc
        // gate stays closed; SPA omits the modal mount.
        mockMvc().perform(get("/api/v1/eventCrfs/3")
                .session(authenticatedRootSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresReasonForChange").value(false));
    }
}
