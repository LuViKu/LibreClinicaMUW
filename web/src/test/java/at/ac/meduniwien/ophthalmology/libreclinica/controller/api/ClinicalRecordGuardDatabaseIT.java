/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.stream.Stream;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link ClinicalRecordGuard}: the data-entry endpoints refuse a locked
 * subject, a removed subject or CRF, and a locked or frozen study, with a
 * 409 that says which, and write nothing. Each test closes records of
 * the seeded study and gives them back their statuses afterwards.
 */
class ClinicalRecordGuardDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mvc() {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        RemoteRetinalInferenceClient remote = Mockito.mock(RemoteRetinalInferenceClient.class);
        return MockMvcBuilders.standaloneSetup(
                        new EventCrfsApiController(DATA_SOURCE, filter,
                                Mockito.mock(CrfFileStorageService.class),
                                new EventCrfPresenceRegistry(),
                                new RetinalResultItemDataPopulator(DATA_SOURCE)),
                        new EventsApiController(DATA_SOURCE, filter,
                                new VisitIntervalCalculator(DATA_SOURCE)),
                        new NamdClinicalApiController(DATA_SOURCE, filter),
                        new RetinalInferenceApiController(
                                DATA_SOURCE, filter,
                                Mockito.mock(RetinalInferenceClient.class), remote,
                                Mockito.mock(RetinalArtifactStorageService.class),
                                Mockito.mock(RetinalMetricComputer.class),
                                new RetinalJobStatusBroadcaster()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @BeforeEach
    void rememberTheStatuses() throws SQLException {
        for (String table : TABLES) {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "CREATE TABLE guard_it_" + table
                    + " AS SELECT " + table + "_id AS id, status_id FROM " + table);
        }
    }

    @AfterEach
    void restoreTheStatuses() throws SQLException {
        for (String table : TABLES) {
            ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE " + table + " t SET status_id = g.status_id"
                    + " FROM guard_it_" + table + " g WHERE g.id = t." + table + "_id");
            ClinicalWriteFixtures.execute(DATA_SOURCE, "DROP TABLE guard_it_" + table);
        }
    }

    /** The tables whose status a test changes. */
    private static final String[] TABLES = {"study", "study_subject", "event_crf"};

    /* ------------------------------------------------------------------ */
    /* A locked subject takes no data                                     */
    /* ------------------------------------------------------------------ */

    static Stream<Arguments> dataEntryWrites() {
        return Stream.of(
                write("save item values", () -> json(post("/api/v1/eventCrfs/9/items"),
                        "{\"values\":{\"I_HEIGHT_CM\":\"181\"}}")),
                write("complete a CRF", () -> post("/api/v1/eventCrfs/9/markComplete")),
                write("reopen a CRF", () -> post("/api/v1/eventCrfs/9/markIncomplete")),
                write("add a repeating-group row", () -> post("/api/v1/eventCrfs/9/groups/IG_ANY/rows")),
                write("delete a repeating-group row",
                        () -> delete("/api/v1/eventCrfs/9/groups/IG_ANY/rows/2")),
                write("upload a CRF file", () -> multipart("/api/v1/eventCrfs/9/items/I_CONSENT_DATE/file")
                        .file(new MockMultipartFile("file", "source.pdf", "application/pdf",
                                new byte[] {1, 2, 3}))),
                write("delete a CRF file", () -> delete("/api/v1/eventCrfs/9/items/I_CONSENT_SIGNED/file")),
                write("commit a double-data-entry pass",
                        () -> json(post("/api/v1/eventCrfs/9/dde-commit"), "{\"values\":{}}")),
                write("resolve a double-data-entry conflict",
                        () -> json(post("/api/v1/eventCrfs/9/dde-conflicts/I_HEIGHT_CM/resolve"),
                                "{\"winner\":\"ide\",\"reasonForChange\":\"checked\"}")),
                write("auto-populate retinal values", () -> post("/api/v1/eventCrfs/9:autoPopulateRetinal")),
                write("start a CRF", () -> json(post("/api/v1/events/12/crfs/3:start"), "{}")),
                write("schedule a visit", () -> json(post("/api/v1/events"),
                        "{\"subjectId\":\"M-001\",\"eventDefinitionOid\":\"SE_V1_INCLUSION\","
                                + "\"dateStarted\":\"2026-01-05\"}")),
                write("save nAMD clinical flags",
                        () -> json(post("/api/v1/study-events/1/namd-clinical-flags"), "{}")),
                write("upload a scan to a CRF", () -> multipart("/api/v1/event-crfs/9/oct-upload")
                        .file(new MockMultipartFile("file", "scan.e2e", "application/octet-stream",
                                new byte[] {1, 2, 3}))
                        .param("task", "fluid").param("laterality", "OD")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dataEntryWrites")
    void aLockedSubjectTakesNoData(String label, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_subject SET status_id = 6 WHERE status_id = 1");

        mvc().perform(request.get().session(investigator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_LOCKED"))
                .andExpect(jsonPath("$.message").value(startsWith("Subject is locked")));
    }

    @Test
    void aSaveRefusedForALockedSubjectChangesNoValue() throws Exception {
        String before = storedHeight();
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_subject SET status_id = 6 WHERE study_subject_id = "
                        + "(SELECT study_subject_id FROM event_crf WHERE event_crf_id = 9)");

        mvc().perform(json(post("/api/v1/eventCrfs/9/items"), "{\"values\":{\"I_HEIGHT_CM\":\"182\"}}")
                        .session(investigator()))
                .andExpect(status().isConflict());

        assertEquals(before, storedHeight());
    }

    /* ------------------------------------------------------------------ */
    /* Removed records and closed studies                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void aRemovedSubjectTakesNoData() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_subject SET status_id = 5 WHERE study_subject_id = "
                        + "(SELECT study_subject_id FROM event_crf WHERE event_crf_id = 9)");

        mvc().perform(saveHeight().session(investigator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REMOVED"))
                .andExpect(jsonPath("$.message").value(startsWith("Subject is removed")));
    }

    @Test
    void aRemovedCrfTakesNoData() throws Exception {
        String before = storedHeight();
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 7 WHERE event_crf_id = 9");

        mvc().perform(saveHeight().session(investigator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CRF_REMOVED"))
                .andExpect(jsonPath("$.message").value(startsWith("The CRF is removed")));
        assertEquals(before, storedHeight());
    }

    @Test
    void aFrozenStudyTakesNoData() throws Exception {
        // The session's study bean still says available: the guard reads
        // the study's state from the database.
        MockHttpSession session = investigator();
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study SET status_id = 9 WHERE study_id = 1");

        mvc().perform(saveHeight().session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDY_FROZEN"))
                .andExpect(jsonPath("$.message").value(startsWith("The study is frozen")));
        mvc().perform(json(post("/api/v1/events/12/crfs/3:start"), "{}").session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDY_FROZEN"))
                .andExpect(jsonPath("$.message").value(startsWith("The study is frozen")));
    }

    @Test
    void aLockedStudyTakesNoData() throws Exception {
        MockHttpSession session = investigator();
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study SET status_id = 6 WHERE study_id = 1");

        mvc().perform(saveHeight().session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDY_LOCKED"))
                .andExpect(jsonPath("$.message").value(startsWith("The study is locked")));
    }

    /* ------------------------------------------------------------------ */
    /* One refusal, one order: the widest closed scope is the one named   */
    /* ------------------------------------------------------------------ */

    @Test
    void aClosedStudyIsNamedBeforeALockedSubject() throws Exception {
        MockHttpSession session = investigator();
        ClinicalWriteFixtures.execute(DATA_SOURCE, "UPDATE study SET status_id = 6 WHERE study_id = 1");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_subject SET status_id = 6 WHERE status_id = 1");

        mvc().perform(saveHeight().session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDY_LOCKED"));
    }

    @Test
    void aLockedSubjectIsNamedBeforeARemovedCrf() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_subject SET status_id = 6 WHERE study_subject_id = "
                        + "(SELECT study_subject_id FROM event_crf WHERE event_crf_id = 9)");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 5 WHERE event_crf_id = 9");

        mvc().perform(saveHeight().session(investigator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_LOCKED"));
    }

    @Test
    void aRemovedCrfIsNamedBeforeALockedOne() throws Exception {
        // Unlike the status, which a row holds once, the scopes combine: a
        // removed event CRF on a visit that is also signed is "removed".
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 5 WHERE event_crf_id = 9");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE study_event SET subject_event_status_id = 8 WHERE study_event_id = "
                        + "(SELECT study_event_id FROM event_crf WHERE event_crf_id = 9)");

        mvc().perform(saveHeight().session(investigator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CRF_REMOVED"));
    }

    @Test
    void anOpenRecordTakesData() throws Exception {
        mvc().perform(saveHeight().session(investigator()))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */

    private static Arguments write(String label, Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(label, request);
    }

    private static MockHttpServletRequestBuilder saveHeight() {
        return json(post("/api/v1/eventCrfs/9/items"), "{\"values\":{\"I_HEIGHT_CM\":\"183\"}}");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private static String storedHeight() throws SQLException {
        return ClinicalWriteFixtures.storedValue(DATA_SOURCE, 9, "I_HEIGHT_CM", 1);
    }
}
