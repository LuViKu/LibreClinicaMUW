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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;

/**
 * De-identification required, the paths that need no database: the routes
 * that are closed outright, the property and its default, and the sysadmin
 * surface of the nightly scan. The upload checks themselves are in
 * {@code DeidentificationRequiredDatabaseIT}.
 */
class DeidentificationRequiredControllerTest extends AbstractApiControllerTest {

    private static final byte[] SOME_BYTES = {1, 2, 3, 4};

    private MockHttpSession dataEntry() {
        return (MockHttpSession) authenticatedSessionWithRole(
                2, "physician", 1, "S_DEFAULTS1", "Default Study", Role.INVESTIGATOR, 1);
    }

    /* ---------------- (a/g) closed routes ---------------- */

    @Test
    void theDirectOctUploadIsDeniedWhenRequiredAndUntouchedWhenNot() throws Exception {
        RetinalInferenceApiController c = new RetinalInferenceApiController(
                mockDataSource(), Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(RetinalInferenceClient.class), Mockito.mock(RemoteRetinalInferenceClient.class),
                Mockito.mock(RetinalArtifactStorageService.class), Mockito.mock(RetinalMetricComputer.class),
                Mockito.mock(RetinalJobStatusBroadcaster.class));

        c.setDeidentificationPolicy(DeidentificationPolicy.of(true));
        mockMvcFor(c).perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(new MockMultipartFile("file", "scan.e2e", "application/octet-stream", SOME_BYTES))
                .param("task", "fluid").param("laterality", "OD").session(dataEntry()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEID_REQUIRED"));

        // off: the request is judged as it always was (here: an unsupported task is a 400)
        c.setDeidentificationPolicy(DeidentificationPolicy.of(false));
        mockMvcFor(c).perform(multipart("/api/v1/event-crfs/1/oct-upload")
                .file(new MockMultipartFile("file", "scan.e2e", "application/octet-stream", SOME_BYTES))
                .param("task", "not-a-task").param("laterality", "OD").session(dataEntry()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCrfItemFileUploadIsDeniedWithAClearMessageWhenRequired() throws Exception {
        EventCrfsApiController c = new EventCrfsApiController(
                mockDataSource(), Mockito.mock(SiteVisibilityFilter.class),
                Mockito.mock(CrfFileStorageService.class), null, null);
        c.setDeidentificationPolicy(DeidentificationPolicy.of(true));

        mockMvcFor(c).perform(multipart("/api/v1/eventCrfs/1/items/I_FILE/file")
                .file(new MockMultipartFile("file", "report.pdf", "application/pdf", SOME_BYTES))
                .session(dataEntry()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("disabled on this deployment")))
                .andExpect(jsonPath("$.message").value(containsString("de-identified")));
    }

    @Test
    void theAccountlessUploadRoutesAreClosedWhenRequired() throws Exception {
        StudySubjectFinder finder = Mockito.mock(StudySubjectFinder.class);
        PublicOctUploadController oct = new PublicOctUploadController(mockDataSource(), finder);
        PublicImageUploadController images = new PublicImageUploadController(mockDataSource(), finder);
        DeidentificationPolicy on = DeidentificationPolicy.of(true);
        oct.setDeidentificationPolicy(on);
        images.setDeidentificationPolicy(on);
        PublicUploadController combined = new PublicUploadController(
                mockDataSource(), finder, oct, images,
                new IngestUploadService(mockDataSource(), null, null));
        combined.setDeidentificationPolicy(on);

        MockMultipartFile file = new MockMultipartFile("file", "a.e2e", "application/octet-stream", SOME_BYTES);
        mockMvcFor(oct).perform(multipart("/api/v1/public/oct-upload/commit").file(file)
                .param("patientId", "M-001").param("scanDate", "2021-01-04").param("laterality", "OD")
                .param("park", "true"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEID_REQUIRED"));
        mockMvcFor(images).perform(multipart("/api/v1/public/image-upload/commit")
                .file(new MockMultipartFile("file", "a.png", "image/png", SOME_BYTES)))
                .andExpect(status().isForbidden());
        mockMvcFor(combined).perform(multipart("/api/v1/public/upload/commit").file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStaffRouteWithoutAPolicyBehavesAsBefore() {
        // a hand-built controller (every pre-existing test) has no policy: not required
        assertFalse(DeidentificationPolicy.required(null));
        assertFalse(DeidentificationPolicy.required(DeidentificationPolicy.of(false)));
        assertTrue(DeidentificationPolicy.required(DeidentificationPolicy.of(true)));
    }

    /* ---------------- the property and its default ---------------- */

    private static boolean resolve(Map<String, Object> props, boolean systemEnv) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(systemEnv
                    ? new SystemEnvironmentPropertySource("env", props)
                    : new MapPropertySource("props", props));
            ctx.register(DeidentificationPolicy.class);
            ctx.refresh();
            return ctx.getBean(DeidentificationPolicy.class).isRequired();
        }
    }

    @Test
    void theDefaultIsTheInternetFacingFlagAndTheExplicitValueWins() {
        assertFalse(resolve(Map.of(), false), "internal deployment: off");
        assertTrue(resolve(Map.of("libreclinica.deployment.internet-facing", "true"), false));
        assertFalse(resolve(Map.of("libreclinica.deployment.internet-facing", "false"), false));
        assertTrue(resolve(Map.of("libreclinica.ingest.deidentification.required", "true"), false),
                "on without being internet-facing");
        assertFalse(resolve(Map.of("libreclinica.deployment.internet-facing", "true",
                "libreclinica.ingest.deidentification.required", "false"), false), "explicit off wins");
    }

    @Test
    void theEnvironmentVariablesMapOntoTheProperties() {
        assertTrue(resolve(Map.of("LIBRECLINICA_INGEST_DEIDENTIFICATION_REQUIRED", "true"), true));
        assertTrue(resolve(Map.of("LIBRECLINICA_DEPLOYMENT_INTERNET_FACING", "true"), true));
        assertFalse(resolve(Map.of("LIBRECLINICA_DEPLOYMENT_INTERNET_FACING", "true",
                "LIBRECLINICA_INGEST_DEIDENTIFICATION_REQUIRED", "false"), true));
    }

    /* ---------------- the scan's admin surface ---------------- */

    private SystemHealthApiController health(DeidentificationScanner scanner) {
        SystemHealthApiController c = new SystemHealthApiController(
                mockDataSource(), Mockito.mock(StorageUsageSampler.class));
        if (scanner != null) c.setDeidentificationScanner(scanner);
        return c;
    }

    private MockHttpSession sysadmin() {
        return (MockHttpSession) authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study");
    }

    @Test
    void theScanSurfaceIsSysadminOnly() throws Exception {
        MockMvc mvc = mockMvcFor(health(Mockito.mock(DeidentificationScanner.class)));
        mvc.perform(get("/api/v1/admin/deidentification").session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/deidentification/scan").session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/deidentification").session(dataEntry())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/deidentification/scan").session(dataEntry()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStatusReportsTheLastScanAndItsFindingsAsNamesOnly() throws Exception {
        DeidentificationScanner scanner = Mockito.mock(DeidentificationScanner.class);
        Mockito.when(scanner.isRequired()).thenReturn(true);
        Mockito.when(scanner.last()).thenReturn(new DeidentificationScanner.Report(
                Instant.parse("2026-10-07T02:30:00Z"), Instant.parse("2026-10-07T02:31:00Z"), "scheduled",
                3, 2, 7, 1, true,
                List.of(new DeidentificationScanner.Finding("e2e", 42L, List.of("e2e.surname"), "ab".repeat(32)))));

        mockMvcFor(health(scanner)).perform(get("/api/v1/admin/deidentification").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.lastScan.trigger").value("scheduled"))
                .andExpect(jsonPath("$.lastScan.findingCount").value(1))
                .andExpect(jsonPath("$.lastScan.incomplete").value(true))
                .andExpect(jsonPath("$.lastScan.findings[0].ingestItemId").value(42))
                .andExpect(jsonPath("$.lastScan.findings[0].violations[0]").value("e2e.surname"));
    }

    @Test
    void noScanYetAndNoScannerAreAnsweredPlainly() throws Exception {
        DeidentificationScanner scanner = Mockito.mock(DeidentificationScanner.class);
        mockMvcFor(health(scanner)).perform(get("/api/v1/admin/deidentification").session(sysadmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastScan").doesNotExist());
        mockMvcFor(health(null)).perform(get("/api/v1/admin/deidentification").session(sysadmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.required").value(false));
        mockMvcFor(health(null)).perform(post("/api/v1/admin/deidentification/scan").session(sysadmin()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void postingStartsAScan() throws Exception {
        DeidentificationScanner scanner = Mockito.mock(DeidentificationScanner.class);
        Mockito.when(scanner.requestScan()).thenReturn(true);
        mockMvcFor(health(scanner)).perform(post("/api/v1/admin/deidentification/scan").session(sysadmin()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.started").value(true));
        Mockito.verify(scanner).requestScan();
        assertEquals(1, Mockito.mockingDetails(scanner).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("requestScan")).count());
    }
}
