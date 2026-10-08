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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
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
 * note rules, which admit the Monitor, are pinned both ways, and so are the
 * rules narrower than data entry, which refuse ra and ra2. The admin binding
 * is admitted; a system administrator is not, by that alone. The role is
 * checked before the row a request names is read: a Monitor naming a row
 * that does not exist is refused, not told so.
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
                        new MeApiController(DATA_SOURCE),
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
                        () -> json(post("/api/v1/import/commit"), "{\"previewToken\":\"t\"}")),
                // The role is checked before the row is read.
                write("save item values of a CRF that does not exist",
                        () -> json(post("/api/v1/eventCrfs/999999/items"),
                                "{\"values\":{\"I_HEIGHT_CM\":\"199\"}}")),
                write("start a CRF of a visit that does not exist",
                        () -> json(post("/api/v1/events/999999/crfs/1:start"), "{}")),
                write("sign a subject that does not exist",
                        () -> json(post("/api/v1/subjects/SS_NOPE/sign"),
                                "{\"password\":\"x\",\"attestation\":true}")),
                write("move an eye of a subject that does not exist",
                        () -> json(post("/api/v1/subjects/NOPE/eyes/OD/transition"),
                                "{\"targetStudyOid\":\"S_OTHER\",\"reason\":\"conversion\"}")),
                write("save nAMD flags of a visit that does not exist",
                        () -> json(post("/api/v1/study-events/999999/namd-clinical-flags"), "{}")),
                write("upload a scan to a CRF that does not exist",
                        () -> multipart("/api/v1/event-crfs/999999/oct-upload")
                                .file(e2e()).param("task", "fluid").param("laterality", "OD")));
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
    /* ra and ra2 enter data, but do not sign, move eyes or bind scans    */
    /* ------------------------------------------------------------------ */

    static Stream<Arguments> writesTheResearchAssistantsMayNotMake() {
        return Stream.of(Role.RESEARCHASSISTANT, Role.RESEARCHASSISTANT2).flatMap(role -> Stream.of(
                // Signing: EventEditAuthorization.roleMayEdit.
                narrower("sign a subject", role, () -> json(post("/api/v1/subjects/SS_M001/sign"),
                        "{\"password\":\"x\",\"attestation\":true}")),
                // Editing a subject: SubjectEditAuthorization.
                narrower("move an eye to another cohort", role,
                        () -> json(post("/api/v1/subjects/M-001/eyes/OD/transition"),
                                "{\"targetStudyOid\":\"S_OTHER\",\"reason\":\"conversion\"}")),
                // Binding a scan to a visit: IngestBindAuthorization.
                narrower("bind a parked scan", role,
                        () -> json(patch("/api/v1/retinal-jobs/1/bind"), "{\"eventCrfId\":1}")),
                narrower("bulk-bind parked scans", role,
                        () -> json(post("/api/v1/retinal-jobs/bulk-bind"),
                                "{\"jobIds\":[1],\"eventCrfId\":1}"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writesTheResearchAssistantsMayNotMake")
    void aResearchAssistantIsRefusedWhereTheRuleIsNarrower(
            String label, MockHttpSession session, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        mvc().perform(request.get().session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
    }

    /* ------------------------------------------------------------------ */
    /* /me says what the binding may write                                */
    /* ------------------------------------------------------------------ */

    static Stream<Arguments> bindingsAndWhatTheyMayWrite() {
        // role, enter data, edit a subject, sign a subject
        return Stream.of(
                Arguments.of(Role.INVESTIGATOR, true, true, true),
                Arguments.of(Role.STUDYDIRECTOR, true, true, true),
                Arguments.of(Role.COORDINATOR, true, true, false),
                Arguments.of(Role.ADMIN, true, true, false),
                Arguments.of(Role.RESEARCHASSISTANT, true, false, false),
                Arguments.of(Role.RESEARCHASSISTANT2, true, false, false),
                Arguments.of(Role.MONITOR, false, false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindingsAndWhatTheyMayWrite")
    void meReportsWhatTheBindingMayWrite(Role role, boolean enterData, boolean editSubject,
                                         boolean signSubject) throws Exception {
        // ra and ra2 are the SPA's "Investigator" too: only these flags
        // tell the SPA that they may not sign or move an eye.
        mvc().perform(get("/api/v1/me").session(investigatorHolding(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeStudy.permissions.enterData").value(enterData))
                .andExpect(jsonPath("$.activeStudy.permissions.editSubject").value(editSubject))
                .andExpect(jsonPath("$.activeStudy.permissions.signSubject").value(signSubject));
    }

    @Test
    void meTellsASystemAdministratorBoundAsMonitorThatItMayNotWrite() throws Exception {
        // /me projects every system administrator as "Administrator"; the
        // flags follow the binding, as the write endpoints do.
        MockHttpSession session = asSystemAdministrator(
                ClinicalWriteFixtures.sessionHolding(DATA_SOURCE, "manual_monitor", Role.MONITOR));
        mvc().perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Administrator"))
                .andExpect(jsonPath("$.activeStudy.permissions.enterData").value(false))
                .andExpect(jsonPath("$.activeStudy.permissions.editSubject").value(false))
                .andExpect(jsonPath("$.activeStudy.permissions.signSubject").value(false));
    }

    /* ------------------------------------------------------------------ */
    /* A system administrator is held to the binding                      */
    /* ------------------------------------------------------------------ */

    @Test
    void aSystemAdministratorBoundAsMonitorDoesNotSignASubject() throws Exception {
        // Deliberately narrower than SignStudySubjectServlet, which admits
        // any system administrator: the signature attests as the binding.
        MockHttpSession session = asSystemAdministrator(
                ClinicalWriteFixtures.sessionHolding(DATA_SOURCE, "manual_monitor", Role.MONITOR));

        mvc().perform(json(post("/api/v1/subjects/SS_M001/sign"),
                        "{\"password\":\"x\",\"attestation\":true}").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
    }

    @Test
    void aSystemAdministratorBoundAsMonitorDoesNotEnterData() throws Exception {
        MockHttpSession session = asSystemAdministrator(
                ClinicalWriteFixtures.sessionHolding(DATA_SOURCE, "manual_monitor", Role.MONITOR));

        mvc().perform(json(post("/api/v1/eventCrfs/9/items"), "{\"values\":{\"I_HEIGHT_CM\":\"174\"}}")
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
    }

    @Test
    void aSystemAdministratorBoundAsInvestigatorDoesNotVerify() throws Exception {
        mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"2\"]}")
                        .session(asSystemAdministrator(sessionAs("manual_investigator"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
        assertFalse(sdvStatus(2));
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
                permitted("save item values", "manual_admin", 200,
                        () -> json(post("/api/v1/eventCrfs/9/items"),
                                "{\"values\":{\"I_HEIGHT_CM\":\"173\"}}")),
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
    @ValueSource(strings = {"manual_monitor", "manual_crc", "manual_dm", "manual_admin"})
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
    void aMonitorBindingBesideAnInvestigatorBindingMayVerifyAndUnverify() throws Exception {
        // The Investigator also holds Monitor on Default Study. The session is
        // bound as Investigator, whom /me/activeStudy ranks higher, while the
        // SPA offers the SDV page for the Monitor binding.
        grant("manual_investigator", "monitor");
        try {
            mvc().perform(json(post("/api/v1/sdv/verify"), "{\"eventCrfOids\":[\"2\"]}")
                            .session(investigatorHolding(Role.INVESTIGATOR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verified").value(hasItem("2")));
            mvc().perform(json(post("/api/v1/sdv/unverify"),
                            "{\"eventCrfOids\":[\"2\"],\"reason\":\"compared with the wrong source\"}")
                            .session(investigatorHolding(Role.INVESTIGATOR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unverified").value(hasItem("2")));
        } finally {
            revoke("manual_investigator", "monitor");
            setSdvStatus(2, false);
        }
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
    /* Notes: a Monitor raises, re-queries and closes queries, but does   */
    /* not propose their resolution                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void aMonitorMayRaiseAndReQueryAQueryButOnlyAnEntryRoleMayProposeItsResolution() throws Exception {
        MvcResult raised = mvc().perform(json(post("/api/v1/discrepancies"),
                        "{\"type\":\"query\",\"subjectId\":\"M-001\",\"itemOid\":\"I_HEIGHT_CM\","
                                + "\"eventCrfOid\":\"2\",\"description\":\"Please check the source\"}")
                        .session(sessionAs("manual_monitor")))
                .andExpect(status().isCreated())
                .andReturn();
        String noteId = new ObjectMapper()
                .readTree(raised.getResponse().getContentAsString()).get("id").asText();

        // Legacy offers a monitor "Update Note" on the thread: a re-query.
        mvc().perform(json(post("/api/v1/discrepancies/" + noteId + "/thread"),
                        "{\"newStatus\":\"updated\",\"description\":\"Also compare with V1\"}")
                        .session(sessionAs("manual_monitor")))
                .andExpect(status().isOk());

        String proposal = "{\"newStatus\":\"resolution-proposed\",\"description\":\"Source says 162\"}";
        mvc().perform(json(post("/api/v1/discrepancies/" + noteId + "/thread"), proposal)
                        .session(sessionAs("manual_monitor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString(REFUSAL)));
        mvc().perform(json(post("/api/v1/discrepancies/" + noteId + "/thread"), proposal)
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

    private static Arguments narrower(String label, Role role,
                                      Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(label + " as " + role.getName(), investigatorHolding(role), request);
    }

    /** The session, its user made a system administrator. */
    private static MockHttpSession asSystemAdministrator(MockHttpSession session) {
        ((UserAccountBean) Objects.requireNonNull(session.getAttribute("userBean"))).addUserType(UserType.SYSADMIN);
        return session;
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

    private static void grant(String userName, String roleName) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, "
                        + "user_name) VALUES ('" + roleName + "', 1, 1, 1, now(), '" + userName + "')");
    }

    private static void revoke(String userName, String roleName) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "DELETE FROM study_user_role WHERE study_id = 1 AND user_name = '" + userName
                        + "' AND role_name = '" + roleName + "'");
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
