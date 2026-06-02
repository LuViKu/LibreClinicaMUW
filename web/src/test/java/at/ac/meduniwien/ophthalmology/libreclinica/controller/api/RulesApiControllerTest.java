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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleActionRunLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E RX.1 — session-guard contract surface for the read-only
 * rules viewer.
 *
 * <p>DAO-bound paths (round-trip list with attached rules + actions,
 * 404 on unknown rule_set, per-action-type projection, run-log
 * round-trip + the {@code findByRuleOids} HQL) need Testcontainers
 * Postgres — deferred to the IT infra slice. In particular, the
 * 404-on-unknown-rule_set path for the run-log endpoint is not
 * covered here because asserting it needs the {@link RuleSetDao}
 * mock to return {@code null} only for a specific (id, study) pair,
 * which the standalone MockMvc setup doesn't wire deeply enough to
 * exercise without DB-bound IT scaffolding.
 */
class RulesApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new RulesApiController(mockDataSource(),
                Mockito.mock(RuleSetDao.class),
                Mockito.mock(RuleSetRuleDao.class),
                Mockito.mock(RuleActionRunLogDao.class)));
    }

    @Test
    void listReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void getOneReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOneReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ----------------------------------------------------------------- */
    /* RX.1b — run-log endpoint                                            */
    /* ----------------------------------------------------------------- */

    @Test
    void getRunLogReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42/run-log")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRunLogReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42/run-log")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void getRunLogReturns400OnNegativeLimit() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42/run-log")
                .param("limit", "-5")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("limit")));
    }

    @Test
    void getRunLogReturns400OnZeroLimit() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42/run-log")
                .param("limit", "0")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("limit")));
    }

    @Test
    void getRunLogReturns400OnNegativeOffset() throws Exception {
        mockMvcWith().perform(get("/api/v1/rule-sets/42/run-log")
                .param("offset", "-1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 7, "S_DEMO", "Demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("offset")));
    }

    /* ----------------------------------------------------------------- */
    /* RX.4 — lifecycle mutations                                          */
    /* ----------------------------------------------------------------- */

    /* 401 — anonymous on each of the 5 endpoints */

    @Test
    void disableRuleSetReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoreRuleSetReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRuleSetReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(delete("/api/v1/rule-sets/42")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableAttachedRuleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/rules/7/disable")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoreAttachedRuleReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/rules/7/restore")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    /* 400 — no active study on each of the 5 endpoints */

    @Test
    void disableRuleSetReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/disable")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void restoreRuleSetReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void deleteRuleSetReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(delete("/api/v1/rule-sets/42")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void disableAttachedRuleReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/rules/7/disable")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void restoreAttachedRuleReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/rule-sets/42/rules/7/restore")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* 403 — Investigator role gated out by StudyAdminAuthorization */

    @Test
    void disableRuleSetReturns403WhenInvestigator() throws Exception {
        // Investigator is neither sysadmin nor director/coordinator,
        // so roleMayEditStudy returns false. The other four mutating
        // endpoints share the same gate via roleMayEditStudy — covering
        // /disable here is enough; the contract is identical.
        mockMvcWith().perform(post("/api/v1/rule-sets/42/disable")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(2, "physician", 1, "S_DEMO", "Demo",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.INVESTIGATOR, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit")));
    }
}
