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
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The role matrix of the SPA's clinical-data write APIs
 * ({@link ClinicalWriteAuthorization}), against a real database.
 *
 * <p>Each session carries the role {@code POST /me/activeStudy} binds: the
 * demo account's own role on Default Study. {@code manual_monitor} is a
 * Monitor, {@code manual_investigator} an Investigator, {@code manual_crc} a
 * coordinator and {@code manual_dm} a director. There is no ra or ra2 demo
 * account, so those roles are put on the Investigator's session.
 *
 * <p>For every write endpoint, a Monitor is refused with 403 and writes
 * nothing, and a role the matrix admits gets past the check. The SDV and
 * note rules, which admit the Monitor, are pinned both ways.
 */
class ClinicalWriteRoleMatrixDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** How every role refusal in the package begins. */
    private static final String REFUSAL = "Your role does not permit";

    private MockMvc mvc() {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        RetinalInferenceApiController retinalInference = new RetinalInferenceApiController(
                DATA_SOURCE, filter,
                Mockito.mock(RetinalInferenceClient.class), remote,
                Mockito.mock(RetinalArtifactStorageService.class),
                Mockito.mock(RetinalMetricComputer.class),
                new RetinalJobStatusBroadcaster());
        return MockMvcBuilders.standaloneSetup(
                        new EventCrfsApiController(DATA_SOURCE, filter,
                                Mockito.mock(CrfFileStorageService.class),
                                new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)),
                        new EventsApiController(DATA_SOURCE, filter,
                                new VisitIntervalCalculator(DATA_SOURCE)),
                        new SubjectsApiController(DATA_SOURCE,
                                Mockito.mock(SecurityManager.class), filter),
                        new EyeCohortTransitionsApiController(DATA_SOURCE, filter),
                        new SdvApiController(DATA_SOURCE, filter),
                        new DiscrepancyApiController(DATA_SOURCE, filter),
                        new ImportApiController(),
                        new NamdClinicalApiController(DATA_SOURCE, filter),
                        retinalInference,
                        new RetinalResultsApiController(DATA_SOURCE, filter,
                                Mockito.mock(RetinalArtifactStorageService.class),
                                null, remote, new RetinalJobStatusBroadcaster(), retinalInference))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* A Monitor may not change clinical data                             */
    /* ------------------------------------------------------------------ */

    static Stream<Arguments> writesAMonitorMayNotMake() {
        return Stream.of(
                // Entering data: ClinicalWriteAuthorization.roleMayEnterData.
                write("save item values", () -> json(post("/api/v1/eventCrfs/1/items"),
                        "{\"values\":{\"I_HEIGHT_CM\":\"199\"},"
                                + "\"reasons\":{\"I_HEIGHT_CM\":\"typo\"}}")),
                write("add a repeating-group row",
                        () -> post("/api/v1/eventCrfs/1/groups/IG_ANY/rows")),
                write("delete a repeating-group row",
                        () -> delete("/api/v1/eventCrfs/1/groups/IG_ANY/rows/2")),
                write("upload a CRF file",
                        () -> multipart("/api/v1/eventCrfs/1/items/I_HEIGHT_CM/file").file(pdf())),
                write("delete a CRF file",
                        () -> delete("/api/v1/eventCrfs/1/items/I_HEIGHT_CM/file")),
                write("commit a double-data-entry pass",
                        () -> json(post("/api/v1/eventCrfs/1/dde-commit"), "{\"values\":{}}")),
                write("complete a CRF", () -> post("/api/v1/eventCrfs/3/markComplete")),
                write("auto-populate retinal values",
                        () -> post("/api/v1/eventCrfs/1:autoPopulateRetinal")),
                write("start a CRF", () -> json(post("/api/v1/events/11/crfs/2:start"), "{}")),
                write("schedule a visit", () -> json(post("/api/v1/events"),
                        "{\"subjectId\":\"M-004\",\"eventDefinitionOid\":\"SE_V3_DAY90\","
                                + "\"dateStarted\":\"2026-01-05\"}")),
                write("add a subject", () -> json(post("/api/v1/subjects"),
                        "{\"id\":\"IT-MON-1\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\"}")),
                write("sign a subject", () -> json(post("/api/v1/subjects/SS_M001/sign"),
                        "{\"password\":\"x\",\"attestation\":true}")),
                write("save nAMD clinical flags",
                        () -> json(post("/api/v1/study-events/1/namd-clinical-flags"), "{}")),
                write("upload a scan to a CRF", () -> multipart("/api/v1/event-crfs/1/oct-upload")
                        .file(e2e()).param("task", "fluid").param("laterality", "OD")),
                write("retry a retinal analysis", () -> post("/api/v1/retinal-jobs/1/retry")),
                write("re-run a retinal analysis",
                        () -> json(post("/api/v1/retinal-jobs/1/rerun-as"), "{\"task\":\"fluid\"}")),
                // Binding a scan to a visit: IngestBindAuthorization.
                write("bind a parked scan",
                        () -> json(patch("/api/v1/retinal-jobs/1/bind"), "{\"eventCrfId\":1}")),
                write("bulk-bind parked scans", () -> json(post("/api/v1/retinal-jobs/bulk-bind"),
                        "{\"jobIds\":[1],\"eventCrfId\":1}")),
                // Editing a subject: SubjectEditAuthorization.
                write("move an eye to another cohort",
                        () -> json(post("/api/v1/subjects/M-001/eyes/OD/transition"),
                                "{\"targetStudyOid\":\"S_OTHER\",\"reason\":\"conversion\"}")),
                write("edit a subject", () -> json(put("/api/v1/subjects/M-001"), "{}")),
                write("edit a subject's groups",
                        () -> json(put("/api/v1/subjects/M-001/groups"), "{\"assignments\":[]}")),
                // The older helpers.
                write("reopen a completed CRF", () -> post("/api/v1/eventCrfs/1/markIncomplete")),
                write("restore a removed CRF", () -> post("/api/v1/eventCrfs/1/restore")),
                write("resolve a DDE conflict",
                        () -> json(post("/api/v1/eventCrfs/1/dde-conflicts/I_HEIGHT_CM/resolve"),
                                "{\"winner\":\"ide\",\"reasonForChange\":\"checked\"}")),
                write("edit a visit", () -> json(put("/api/v1/events/1"), "{\"location\":\"x\"}")),
                write("cancel a visit",
                        () -> json(delete("/api/v1/events/1"), "{\"reasonCode\":\"OTHER\"}")),
                write("restore a visit", () -> post("/api/v1/events/1/restore")),
                write("sign a visit", () -> json(post("/api/v1/events/1/sign"),
                        "{\"password\":\"x\",\"attestation\":true}")),
                write("remove a subject", () -> post("/api/v1/subjects/M-001/remove")),
                write("restore a subject", () -> post("/api/v1/subjects/M-001/restore")),
                write("lock a subject", () -> post("/api/v1/subjects/M-001/lock")),
                write("unlock a subject", () -> post("/api/v1/subjects/M-001/unlock")),
                write("write a reason-for-change note", () -> json(post("/api/v1/discrepancies"),
                        "{\"type\":\"reason-for-change\",\"subjectId\":\"M-001\","
                                + "\"itemOid\":\"I_HEIGHT_CM\",\"eventCrfOid\":\"1\","
                                + "\"description\":\"corrected\"}")),
                write("commit a CRF data import",
                        () -> json(post("/api/v1/import/commit"), "{\"previewToken\":\"t\"}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writesAMonitorMayNotMake")
    void aMonitorIsRefused(String label, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        int monitor = userId("manual_monitor");
        int auditRowsBefore = auditRowsBy(monitor);

        mvc().perform(request.get().session(sessionAs("manual_monitor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));

        assertEquals(auditRowsBefore, auditRowsBy(monitor),
                "a refused request to " + label + " writes nothing");
    }

    /* ------------------------------------------------------------------ */
    /* The roles the matrix admits get past the check                     */
    /* ------------------------------------------------------------------ */

    static Stream<Arguments> writesAPermittedRoleMayMake() {
        return Stream.of(
                // Where the request succeeds, the status says so. Where it
                // cannot succeed without more fixture than the rule needs,
                // it is refused later for a reason that is not the role.
                permitted("save item values", "manual_investigator", 200,
                        () -> json(post("/api/v1/eventCrfs/9/items"),
                                "{\"values\":{\"I_WEIGHT_KG\":\"70.5\"}}")),
                permitted("save item values as ra", Role.RESEARCHASSISTANT, 200,
                        () -> json(post("/api/v1/eventCrfs/9/items"),
                                "{\"values\":{\"I_HEIGHT_CM\":\"171\"}}")),
                permitted("save item values as ra2", Role.RESEARCHASSISTANT2, 200,
                        () -> json(post("/api/v1/eventCrfs/9/items"),
                                "{\"values\":{\"I_HEIGHT_CM\":\"172\"}}")),
                permitted("add a repeating-group row", "manual_investigator", 404,
                        () -> post("/api/v1/eventCrfs/9/groups/IG_ABSENT/rows")),
                permitted("delete a repeating-group row", "manual_investigator", 404,
                        () -> delete("/api/v1/eventCrfs/9/groups/IG_ABSENT/rows/2")),
                permitted("upload a CRF file", "manual_investigator", 413,
                        () -> multipart("/api/v1/eventCrfs/9/items/I_CONSENT_DATE/file").file(pdf())),
                permitted("delete a CRF file", "manual_investigator", 404,
                        () -> delete("/api/v1/eventCrfs/9/items/I_CONSENT_SIGNED/file")),
                permitted("commit a double-data-entry pass", "manual_investigator", 409,
                        () -> json(post("/api/v1/eventCrfs/9/dde-commit"), "{\"values\":{}}")),
                // Event CRF 5 lacks its consent items, so the required check refuses it.
                permitted("complete a CRF", "manual_crc", 400,
                        () -> post("/api/v1/eventCrfs/5/markComplete")),
                permitted("auto-populate retinal values", "manual_investigator", 200,
                        () -> post("/api/v1/eventCrfs/9:autoPopulateRetinal")),
                permitted("start a CRF", "manual_investigator", 201,
                        () -> json(post("/api/v1/events/12/crfs/3:start"), "{}")),
                permitted("schedule a visit", "manual_crc", 409,
                        () -> json(post("/api/v1/events"),
                                "{\"subjectId\":\"M-001\",\"eventDefinitionOid\":\"SE_V1_INCLUSION\","
                                        + "\"dateStarted\":\"2026-01-05\"}")),
                // Default Study requires the Person ID and the date of birth.
                permitted("add a subject", "manual_crc", 201,
                        () -> json(post("/api/v1/subjects"),
                                "{\"id\":\"IT-CRC-1\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\","
                                        + "\"personId\":\"P-IT-CRC-1\",\"dateOfBirth\":\"1970-01-02\"}")),
                permitted("sign a subject", "manual_investigator", 401,
                        () -> json(post("/api/v1/subjects/SS_M001/sign"),
                                "{\"password\":\"wrong\",\"attestation\":true}")),
                permitted("save nAMD clinical flags", "manual_investigator", 404,
                        () -> json(post("/api/v1/study-events/999999/namd-clinical-flags"), "{}")),
                permitted("upload a scan to a CRF", "manual_investigator", 400,
                        () -> multipart("/api/v1/event-crfs/9/oct-upload")
                                .file(e2e()).param("task", "not-a-task").param("laterality", "OD")),
                permitted("retry a retinal analysis", "manual_investigator", 404,
                        () -> post("/api/v1/retinal-jobs/999999/retry")),
                permitted("re-run a retinal analysis", "manual_investigator", 404,
                        () -> json(post("/api/v1/retinal-jobs/999999/rerun-as"), "{\"task\":\"fluid\"}")),
                permitted("bind a parked scan", "manual_dm", 404,
                        () -> json(patch("/api/v1/retinal-jobs/999999/bind"), "{\"eventCrfId\":9}")),
                permitted("bulk-bind parked scans", "manual_dm", 200,
                        () -> json(post("/api/v1/retinal-jobs/bulk-bind"),
                                "{\"jobIds\":[999999],\"eventCrfId\":9}")),
                permitted("move an eye to another cohort", "manual_investigator", 404,
                        () -> json(post("/api/v1/subjects/M-001/eyes/OD/transition"),
                                "{\"targetStudyOid\":\"S_NOT_A_STUDY\",\"reason\":\"conversion\"}")),
                permitted("commit a CRF data import", "manual_investigator", 400,
                        () -> json(post("/api/v1/import/commit"), "{}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writesAPermittedRoleMayMake")
    void aPermittedRoleGetsPastTheCheck(String label, MockHttpSession session, int expectedStatus,
                                        Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        MvcResult result = mvc().perform(request.get().session(session)).andReturn();
        String body = result.getResponse().getContentAsString();

        assertFalse(body.contains(REFUSAL), label + " is not refused for the role: " + body);
        assertEquals(expectedStatus, result.getResponse().getStatus(), label + ": " + body);
    }

    @Test
    void anEntryByAPermittedRoleIsStored() throws Exception {
        mvc().perform(json(post("/api/v1/eventCrfs/16/items"), "{\"values\":{\"I_WEIGHT_KG\":\"64.0\"}}")
                        .session(sessionAs("manual_investigator")))
                .andExpect(status().isOk());

        assertEquals("64.0", ClinicalWriteFixtures.storedValue(DATA_SOURCE, 16, "I_WEIGHT_KG", 1));
    }

    /* ------------------------------------------------------------------ */
    /* SDV: director, coordinator and monitor verify                      */
    /* ------------------------------------------------------------------ */

    @ParameterizedTest
    @ValueSource(strings = {"manual_monitor", "manual_crc", "manual_dm"})
    void anSdvRoleMayVerify(String userName) throws Exception {
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"2\"]}")
                            .session(sessionAs(userName)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verified").value(hasItem("2")));
            assertTrue(sdvStatus(2), userName + " verified event CRF 2");
        } finally {
            setSdvStatus(2, false);
        }
    }

    @Test
    void anInvestigatorMayNotVerify() throws Exception {
        mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"2\"]}")
                        .session(sessionAs("manual_investigator")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
        assertFalse(sdvStatus(2));
    }

    @Test
    void aDataEntryPersonMayNotVerify() throws Exception {
        mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"2\"]}")
                        .session(investigatorHolding(Role.RESEARCHASSISTANT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
        assertFalse(sdvStatus(2));
    }

    @Test
    void aMonitorMayUnverifyWithAReason() throws Exception {
        // Event CRF 6 is seeded verified.
        try {
            mvc().perform(json(post("/api/v1/sdv/unverify"),
                            "{\"eventCrfOids\":[\"6\"],\"reason\":\"source corrected\"}")
                            .session(sessionAs("manual_monitor")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unverified").value(hasItem("6")));
            assertFalse(sdvStatus(6));
        } finally {
            setSdvStatus(6, true);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Notes: a Monitor raises queries and closes them, but does not      */
    /* answer them                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void aMonitorMayRaiseAQueryButOnlyAnEntryRoleMayAnswerIt() throws Exception {
        MvcResult raised = mvc().perform(json(post("/api/v1/discrepancies"),
                        "{\"type\":\"query\",\"subjectId\":\"M-001\",\"itemOid\":\"I_HEIGHT_CM\","
                                + "\"eventCrfOid\":\"2\",\"description\":\"Please check the source\"}")
                        .session(sessionAs("manual_monitor")))
                .andExpect(status().isCreated())
                .andReturn();
        String noteId = new ObjectMapper()
                .readTree(raised.getResponse().getContentAsString()).get("id").asText();

        String answer = "{\"newStatus\":\"updated\",\"description\":\"Source says 162\"}";
        mvc().perform(json(post("/api/v1/discrepancies/" + noteId + "/thread"), answer)
                        .session(sessionAs("manual_monitor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
        mvc().perform(json(post("/api/v1/discrepancies/" + noteId + "/thread"), answer)
                        .session(sessionAs("manual_crc")))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static Arguments write(String label, Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(label, request);
    }

    private static Arguments permitted(String label, String userName, int expectedStatus,
                                       Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(label + " as " + userName, sessionAs(userName), expectedStatus, request);
    }

    private static Arguments permitted(String label, Role role, int expectedStatus,
                                       Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(label, investigatorHolding(role), expectedStatus, request);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "source.pdf", "application/pdf", new byte[] {1, 2, 3});
    }

    private static MockMultipartFile e2e() {
        return new MockMultipartFile("file", "scan.e2e", "application/octet-stream", new byte[] {1, 2, 3});
    }

    private static MockHttpSession sessionAs(String userName) {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, userName);
    }

    private static MockHttpSession investigatorHolding(Role role) {
        return ClinicalWriteFixtures.sessionHolding(DATA_SOURCE, "manual_investigator", role);
    }

    private static int userId(String userName) {
        return ClinicalWriteFixtures.userId(DATA_SOURCE, userName);
    }

    private static int auditRowsBy(int userId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean sdvStatus(int eventCrfId) throws SQLException {
        return ClinicalWriteFixtures.sdvStatus(DATA_SOURCE, eventCrfId);
    }

    private static void setSdvStatus(int eventCrfId, boolean verified) throws SQLException {
        ClinicalWriteFixtures.setSdvStatus(DATA_SOURCE, eventCrfId, verified);
    }
}
