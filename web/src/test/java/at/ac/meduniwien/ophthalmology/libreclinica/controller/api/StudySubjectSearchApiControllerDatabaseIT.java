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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * P3.6 — label-prefix subject lookup, moved out of the retinal controller.
 *
 * <p>These assertions came across unchanged with the endpoint: the split is a
 * move, and a move that alters what the endpoint answers is not one. The
 * search is the SPA's "find the patient" modal, so the shape of an empty
 * answer matters as much as a hit — a blank query returns an empty list
 * rather than the whole register.
 */
@SuppressWarnings("null")
class StudySubjectSearchApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String BASE = "/api/v1/study-subjects/search";

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new StudySubjectSearchApiController(
                                DATA_SOURCE,
                                new SiteVisibilityFilter(DATA_SOURCE),
                                new StudySubjectFinder(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anUnauthenticatedCallerLearnsNothing() throws Exception {
        mockMvc().perform(get(BASE).param("q", "M-00").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aKnownPrefixReturnsItsSubjects() throws Exception {
        mockMvc().perform(get(BASE)
                .param("q", "M-00")
                .param("limit", "10")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // 7 seeded subjects M-001 .. M-007, all visible to root.
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].label").value("M-001"))
                .andExpect(jsonPath("$[0].studyId").value(1));
    }

    @Test
    void anUnknownPrefixReturnsNothing() throws Exception {
        mockMvc().perform(get(BASE)
                .param("q", "ZZZ-NO-SUCH")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anOversizedLimitIsClampedRatherThanRefused() throws Exception {
        // limit=99 → clamped to 50 internally; with only 7 rows the assertion
        // is "no error, and the matches still come back".
        mockMvc().perform(get(BASE)
                .param("q", "M-")
                .param("limit", "99")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    /**
     * A blank query is not "match everything". The modal fires on keystrokes,
     * and an empty box must not enumerate the subject register.
     */
    @Test
    void aBlankQueryReturnsNothing() throws Exception {
        mockMvc().perform(get(BASE)
                .param("q", "  ")
                .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
