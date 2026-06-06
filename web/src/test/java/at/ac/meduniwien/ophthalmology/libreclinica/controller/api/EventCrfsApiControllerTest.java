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
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.5 A2 — MockMvc IT pinning the {@link EventCrfsApiController}
 * session-guard + body-validation contract surface.
 *
 * <p>What this pins:
 * <ul>
 *   <li>{@code GET /api/v1/eventCrfs/{id}} returns {@code 401} when
 *       anonymous, {@code 400} when no active study is bound.</li>
 *   <li>{@code POST /api/v1/eventCrfs/{id}/items} returns {@code 400}
 *       on missing/null {@code values} body.</li>
 *   <li>{@code POST /api/v1/eventCrfs/{id}/markComplete} returns
 *       {@code 401} when anonymous, {@code 400} when no active study.</li>
 * </ul>
 *
 * <p>DAO-touching paths (409 already-complete, 404 unknown id, etc.)
 * require Testcontainers Postgres — out of scope for this cut.
 */
class EventCrfsApiControllerTest extends AbstractApiControllerTest {

    private MockMvc mockMvcWith() {
        return mockMvcFor(new EventCrfsApiController(mockDataSource(),
                Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService.class),
                new EventCrfPresenceRegistry()));
    }

    /* ---------------------------------------------------------------------- */
    /* GET /api/v1/eventCrfs/{id}                                             */
    /* ---------------------------------------------------------------------- */

    @Test
    void getEventCrfReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getEventCrfReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/eventCrfs/{id}/items                                      */
    /* ---------------------------------------------------------------------- */

    @Test
    void saveItemsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{\"values\":{}}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void saveItemsReturns400OnMissingValues() throws Exception {
        // Body present but `values` is null — the controller's guard:
        //   body == null || body.values() == null
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                // Phase E.6 PR (a) reworded the message — now mentions
                // both 'values' and 'groups' as acceptable payload keys.
                .andExpect(jsonPath("$.message")
                        .value(containsString("'values'")));
    }

    @Test
    void saveItemsReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{\"values\":{}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 admin-rfc — body-shape extensions                            */
    /* ---------------------------------------------------------------------- */

    /**
     * Phase E.6 admin-rfc — the request body now optionally carries a
     * `reasons` map. The controller must still 400 on missing values
     * even when `reasons` is present (the reasons-only body is a no-op).
     */
    @Test
    void saveItemsReturns400OnMissingValuesEvenWhenReasonsPresent() throws Exception {
        // Phase E.6 admin-rfc — body that has `reasons` but no `values`
        // and no `groups` is rejected via the empty-body branch (the
        // controller's saveItems guard fires before it inspects the
        // reasons map).
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{\"reasons\":{\"I_HEIGHT_CM\":\"correction\"}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("'values'")));
    }

    /**
     * Phase E.6 admin-rfc — verify the body parses both `values` and
     * `reasons`. The DB layer is mocked so the request goes through the
     * pre-pass + bubbles a 500 (NPE from mockDataSource.getConnection()),
     * but the request is accepted past the body-validation guard. This
     * documents that the SaveItemsRequest record accepts the new shape;
     * the happy-path semantics are covered by ReasonForChangeWriterTest.
     */
    @Test
    void saveItemsAcceptsReasonsFieldInBody() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/items")
                .contentType("application/json")
                .content("{\"values\":{\"I_HEIGHT_CM\":172},\"reasons\":{\"I_HEIGHT_CM\":\"correction\"}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").exists());
    }


    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/eventCrfs/{id}/markComplete                               */
    /* ---------------------------------------------------------------------- */

    @Test
    void markCompleteReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markComplete")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markCompleteReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markComplete")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    /* ---------------------------------------------------------------------- */
    /* POST /api/v1/eventCrfs/{id}/markIncomplete (Phase E A5)                */
    /* ---------------------------------------------------------------------- */

    @Test
    void markIncompleteReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markIncomplete")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markIncompleteReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/markIncomplete")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void markIncompleteReturns500WhenLookupFailsAgainstMockDataSource() throws Exception {
        // The mock DataSource throws NPE on getConnection(); the
        // ApiExceptionHandler wraps it to 500. Pins that the request
        // reached the post-auth/post-study guard layer (i.e. not
        // blocked earlier). Real-DB coverage of the 200/404/409/403
        // branches lives in EventCrfsApiControllerDatabaseIT (queued
        // behind the broader Testcontainers IT slice).
        mockMvcWith().perform(post("/api/v1/eventCrfs/9999/markIncomplete")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").exists());
    }

    /* ---------------------------------------------------------------------- */
    /* CrfReopenAuthorization — pure unit-level role coverage                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void crfReopenAuth_PermittedRoles() {
        // STUDYDIRECTOR (3) = Data Manager
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(3));
        // ADMIN (1)
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(1));
        // INVESTIGATOR (4)
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(4));
        // COORDINATOR (2) = CRC
        org.junit.jupiter.api.Assertions.assertTrue(
                CrfReopenAuthorization.roleMayReopen(2));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 dde — GET /api/v1/eventCrfs/{id}/dde-pass                    */
    /* ---------------------------------------------------------------------- */

    @Test
    void getDdePassReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/dde-pass")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getDdePassReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/dde-pass")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 dde — POST /api/v1/eventCrfs/{id}/dde-commit                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void commitDdePass2Returns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-commit")
                .contentType("application/json")
                .content("{\"values\":{}}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void commitDdePass2Returns400OnMissingValues() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-commit")
                .contentType("application/json")
                .content("{}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Missing 'values'")));
    }

    @Test
    void commitDdePass2Returns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-commit")
                .contentType("application/json")
                .content("{\"values\":{}}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 dde — GET /api/v1/eventCrfs/{id}/dde-conflicts               */
    /* ---------------------------------------------------------------------- */

    @Test
    void getDdeConflictsReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/dde-conflicts")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getDdeConflictsReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/dde-conflicts")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void getDdeConflictsReturns403WhenRoleMayNotReconcile() throws Exception {
        // No userRole bound on the session → roleMayReconcile returns
        // false; the controller returns 403 before any DAO touch.
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/dde-conflicts")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "monitor", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit DDE reconciliation")));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 dde — POST /api/v1/eventCrfs/{id}/dde-conflicts/{oid}/resolve */
    /* ---------------------------------------------------------------------- */

    @Test
    void resolveDdeConflictReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-conflicts/I_FOO/resolve")
                .contentType("application/json")
                .content("{\"winner\":\"ide\",\"reasonForChange\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolveDdeConflictReturns400OnMissingWinner() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-conflicts/I_FOO/resolve")
                .contentType("application/json")
                .content("{\"reasonForChange\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(1, "dm", 1, "S_DEFAULTS1", "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Missing")));
    }

    @Test
    void resolveDdeConflictReturns400OnInvalidWinner() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-conflicts/I_FOO/resolve")
                .contentType("application/json")
                .content("{\"winner\":\"bogus\",\"reasonForChange\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(1, "dm", 1, "S_DEFAULTS1", "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("winner must be one of")));
    }

    @Test
    void resolveDdeConflictReturns400OnManualWithoutValue() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-conflicts/I_FOO/resolve")
                .contentType("application/json")
                .content("{\"winner\":\"manual\",\"reasonForChange\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithRole(1, "dm", 1, "S_DEFAULTS1", "Default Study",
                                at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("winner=manual requires")));
    }

    @Test
    void resolveDdeConflictReturns403WhenRoleMayNotReconcile() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/dde-conflicts/I_FOO/resolve")
                .contentType("application/json")
                .content("{\"winner\":\"ide\",\"reasonForChange\":\"r\"}")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSession(1, "monitor", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value(containsString("does not permit DDE reconciliation")));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 dde — DdeService description round-trip unit tests          */
    /* ---------------------------------------------------------------------- */

    @Test
    void ddeService_describesAndParsesMismatchRoundTrip() {
        String desc = at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde
                .DdeService.formatDdeMismatchDescription("42", "43");
        org.junit.jupiter.api.Assertions.assertTrue(desc.contains("IDE='42'"));
        org.junit.jupiter.api.Assertions.assertTrue(desc.contains("DDE='43'"));
        String[] parsed = at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde
                .DdeService.parseDdeMismatchDescription(desc);
        org.junit.jupiter.api.Assertions.assertEquals("42", parsed[0]);
        org.junit.jupiter.api.Assertions.assertEquals("43", parsed[1]);
    }

    @Test
    void ddeService_parseToleratesGarbage() {
        String[] parsed = at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde
                .DdeService.parseDdeMismatchDescription("not a valid format");
        org.junit.jupiter.api.Assertions.assertEquals("", parsed[0]);
        org.junit.jupiter.api.Assertions.assertEquals("", parsed[1]);
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 crf-entry-advanced — guard contract on the four new          */
    /* read endpoints. Pins 401/400 short-circuit + URL routing.              */
    /* ---------------------------------------------------------------------- */

    @Test
    void lockStatusReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/lock-status")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lockStatusReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/lock-status")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("No active study")));
    }

    @Test
    void heartbeatReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/heartbeat")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeatReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(post("/api/v1/eventCrfs/1/heartbeat")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notesReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/notes")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notesReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/notes")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sectionStatusReturns401WhenAnonymous() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/section-status")
                .session((org.springframework.mock.web.MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sectionStatusReturns400WhenNoActiveStudy() throws Exception {
        mockMvcWith().perform(get("/api/v1/eventCrfs/1/section-status")
                .session((org.springframework.mock.web.MockHttpSession)
                        authenticatedSessionWithoutStudy(1, "root")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crfReopenAuth_ForbiddenRoles() {
        // MONITOR (6) — monitors verify, don't edit
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(6));
        // RA (5) — data entry, not data correction
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(5));
        // RA2 (7)
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(7));
        // INVALID / no role (0)
        org.junit.jupiter.api.Assertions.assertFalse(
                CrfReopenAuthorization.roleMayReopen(0));
    }

    /* ---------------------------------------------------------------------- */
    /* Phase E.6 polish-runtime — show-when filter unit slice                 */
    /* ---------------------------------------------------------------------- */

    /**
     * Phase E.6 polish-runtime — {@code filterByVisibility} is the
     * ghost-data guard the {@code saveItems} controller uses to drop
     * values whose show-when condition resolves false. The DB-level
     * MockMvc IT covering the wired-up GET/POST surface lives behind
     * Testcontainers (real {@code scd_item_metadata} rows); these pure
     * unit tests pin the static filter's contract so a refactor can't
     * silently break the guard.
     */
    @Test
    void filterByVisibility_keepsValueWhenRuleSatisfied() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("I_GROUP", "cohort-A");
        values.put("I_DEP", "typed");

        java.util.Map<String, String> rules = new java.util.HashMap<>();
        rules.put("I_DEP",
                "{\"sourceItemOid\":\"I_GROUP\",\"comparator\":\"==\",\"literal\":\"cohort-A\"}");

        java.util.Map<String, Object> out = EventCrfsApiController.filterByVisibility(values, rules);
        org.junit.jupiter.api.Assertions.assertEquals(2, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("typed", out.get("I_DEP"));
    }

    @Test
    void filterByVisibility_dropsValueWhenRuleFails() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("I_GROUP", "cohort-B");
        values.put("I_DEP", "typed");

        java.util.Map<String, String> rules = new java.util.HashMap<>();
        rules.put("I_DEP",
                "{\"sourceItemOid\":\"I_GROUP\",\"comparator\":\"==\",\"literal\":\"cohort-A\"}");

        java.util.Map<String, Object> out = EventCrfsApiController.filterByVisibility(values, rules);
        // I_GROUP kept (no rule), I_DEP dropped (rule false).
        org.junit.jupiter.api.Assertions.assertEquals(1, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("cohort-B", out.get("I_GROUP"));
        org.junit.jupiter.api.Assertions.assertFalse(out.containsKey("I_DEP"));
    }

    @Test
    void filterByVisibility_keepsValueWhenRulesMapEmpty() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("I_NAME", "Müller");
        java.util.Map<String, Object> out = EventCrfsApiController.filterByVisibility(
                values, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals(1, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("Müller", out.get("I_NAME"));
    }

    @Test
    void filterByVisibility_keepsValueWhenNonEqualityComparator() {
        // The server-side filter conservatively keeps non-equality
        // comparators visible — the SPA evaluator is the authority for
        // >, <, >=, <= (see the javadoc).
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("I_AGE", 25);
        values.put("I_DEP", "typed");

        java.util.Map<String, String> rules = new java.util.HashMap<>();
        rules.put("I_DEP",
                "{\"sourceItemOid\":\"I_AGE\",\"comparator\":\">=\",\"literal\":\"40\"}");

        java.util.Map<String, Object> out = EventCrfsApiController.filterByVisibility(values, rules);
        org.junit.jupiter.api.Assertions.assertEquals(2, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("typed", out.get("I_DEP"));
    }

    @Test
    void filterByVisibility_keepsValueWhenRuleIsNotJson() {
        // Legacy "item_X eq Y" strings can't be parsed by the server
        // filter — it falls back to visible (the SPA evaluator handles
        // legacy strings; this is defense-in-depth, not the source of
        // truth).
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("I_DEP", "typed");
        java.util.Map<String, String> rules = new java.util.HashMap<>();
        rules.put("I_DEP", "item_I_GROUP eq cohort-A");

        java.util.Map<String, Object> out = EventCrfsApiController.filterByVisibility(values, rules);
        org.junit.jupiter.api.Assertions.assertEquals(1, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("typed", out.get("I_DEP"));
    }

    @Test
    void extractJsonField_returnsValueOrNull() {
        String json = "{\"sourceItemOid\":\"I_GROUP\",\"comparator\":\"==\",\"literal\":\"cohort-A\"}";
        org.junit.jupiter.api.Assertions.assertEquals(
                "I_GROUP", EventCrfsApiController.extractJsonField(json, "sourceItemOid"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "==", EventCrfsApiController.extractJsonField(json, "comparator"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "cohort-A", EventCrfsApiController.extractJsonField(json, "literal"));
        org.junit.jupiter.api.Assertions.assertNull(
                EventCrfsApiController.extractJsonField(json, "missing"));
        org.junit.jupiter.api.Assertions.assertNull(
                EventCrfsApiController.extractJsonField(null, "anything"));
        org.junit.jupiter.api.Assertions.assertNull(
                EventCrfsApiController.extractJsonField("not json", "anything"));
    }

    @Test
    void extractJsonField_handlesEscapedQuoteInLiteral() {
        String json = "{\"sourceItemOid\":\"I_X\",\"comparator\":\"==\",\"literal\":\"say \\\"hi\\\"\"}";
        org.junit.jupiter.api.Assertions.assertEquals(
                "say \"hi\"", EventCrfsApiController.extractJsonField(json, "literal"));
    }
}
