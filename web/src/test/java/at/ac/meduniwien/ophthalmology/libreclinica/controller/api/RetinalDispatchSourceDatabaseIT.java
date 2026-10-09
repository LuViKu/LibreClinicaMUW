/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRunResult;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalRunRefused;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.ScanSource;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;

/**
 * DR-039 — what the dispatch records on the job, and what the job page reads
 * back: a refusal's own message (not "returned null"), the scan's format and
 * device even when nothing ran, and the eye a DICOM resolved.
 */
@SuppressWarnings("null")
class RetinalDispatchSourceDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ScanSource CIRRUS = new ScanSource("dicom", "Carl Zeiss Meditec",
            "CIRRUS HD-OCT 5000", "standard-assumed", "OS", List.of());
    private static final ScanSource SPECTRALIS = new ScanSource("dicom", "Heidelberg Engineering",
            "SPECTRALIS", "swapped", "OD", List.of("fluid", "ga"));

    /** A job on a fresh SE_V2_DAY30 visit of Default Study, reading a stored DICOM. */
    private static long job(String eye) throws Exception {
        int n = SEQ.incrementAndGet();
        try (Connection c = DATA_SOURCE.getConnection()) {
            int ss = LifecycleFixtures.insertStudySubject(c, "RD-" + n + "-" + System.nanoTime() % 100000, 1, 1);
            int event = LifecycleFixtures.insertOne(c,
                    "INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal, "
                            + "date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "start_time_flag, end_time_flag) VALUES (2, " + ss + ", 1, now(), 1, 1, now(), "
                            + "1, false, false) RETURNING study_event_id");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_job (study_event_id, task, e2e_path, eye_laterality, status, "
                            + "enqueued_at, scan_index) VALUES (?, 'fluid', ?, ?, 'remote_pending', now(), 0) "
                            + "RETURNING job_id")) {
                ps.setInt(1, event);
                ps.setString(2, "/var/lib/libreclinica/ingest/dicom/" + java.util.UUID.randomUUID() + ".dcm");
                ps.setString(3, eye);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private static RetinalInferenceApiController controller(RemoteRetinalInferenceClient remote,
                                                            RetinalArtifactStorageService store) {
        return new RetinalInferenceApiController(DATA_SOURCE, mock(SiteVisibilityFilter.class),
                mock(RetinalInferenceClient.class), remote, store, mock(RetinalMetricComputer.class),
                new RetinalJobStatusBroadcaster());
    }

    private static Map<String, String> row(long jobId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, status_message, source_format, device_manufacturer, device_model, "
                             + "spacing_order, eye_laterality FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, String> m = new java.util.HashMap<>();
                for (String k : List.of("status", "status_message", "source_format", "device_manufacturer",
                        "device_model", "spacing_order", "eye_laterality")) {
                    m.put(k, rs.getString(k));
                }
                return m;
            }
        }
    }

    private static MockHttpSession admin() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        s.setAttribute("study", study);
        return s;
    }

    private static JsonNode detail(long jobId) throws Exception {
        RemoteRetinalInferenceClient remote = mock(RemoteRetinalInferenceClient.class);
        String body = MockMvcBuilders.standaloneSetup(new RetinalResultsApiController(DATA_SOURCE,
                                new SiteVisibilityFilter(DATA_SOURCE), mock(RetinalArtifactStorageService.class),
                                null, remote, new RetinalJobStatusBroadcaster(),
                                mock(RetinalInferenceApiController.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/api/v1/retinal-jobs/" + jobId).session(admin()))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    @Test
    void aTaskNotValidatedForTheDeviceFailsWithThatMessageAndKeepsTheDevice() throws Exception {
        long jobId = job("OU");
        String message = CIRRUS.unsupportedDeviceMessage("fluid");
        RemoteRetinalInferenceClient remote = mock(RemoteRetinalInferenceClient.class);
        when(remote.runRemote(anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new RetinalRunRefused("unsupported_device", message, CIRRUS));

        controller(remote, mock(RetinalArtifactStorageService.class))
                .handleRemote(jobId, "fluid", "/x.dcm", "OU", 0, null);

        Map<String, String> r = row(jobId);
        assertEquals("failed", r.get("status"));
        assertEquals(message, r.get("status_message"), "the reason, not 'returned null'");
        assertEquals("dicom", r.get("source_format"));
        assertEquals("Carl Zeiss Meditec", r.get("device_manufacturer"));
        assertEquals("CIRRUS HD-OCT 5000", r.get("device_model"));
        assertEquals("standard-assumed", r.get("spacing_order"));

        JsonNode d = detail(jobId);
        assertEquals("failed", d.get("status").asText(), d.toString());
        assertEquals(message, d.get("statusMessage").asText());
        assertEquals("dicom", d.get("sourceFormat").asText());
        assertEquals("Carl Zeiss Meditec", d.get("deviceManufacturer").asText());
        assertEquals("CIRRUS HD-OCT 5000", d.get("deviceModel").asText());
        assertEquals("standard-assumed", d.get("spacingOrder").asText());
    }

    @Test
    void aSidecarRefusalWithoutADeviceStillNamesItsReason() throws Exception {
        long jobId = job("OU");
        RemoteRetinalInferenceClient remote = mock(RemoteRetinalInferenceClient.class);
        when(remote.runRemote(anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new RetinalRunRefused("laterality_missing",
                        "The volume names no single eye; send laterality OD or OS.", null));

        controller(remote, mock(RetinalArtifactStorageService.class))
                .handleRemote(jobId, "fluid", "/x.dcm", "OU", 0, null);

        Map<String, String> r = row(jobId);
        assertEquals("failed", r.get("status"));
        assertEquals("The volume names no single eye; send laterality OD or OS.", r.get("status_message"));
        assertNull(r.get("source_format"));
    }

    @Test
    void aFinishedDicomJobRecordsItsSourceAndTheResolvedEye() throws Exception {
        long jobId = job("OU");
        RemoteRetinalInferenceClient remote = mock(RemoteRetinalInferenceClient.class);
        when(remote.runRemote(anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(new RemoteRunResult("fluid-1.3.0", 0.5, "mm³", Map.of(), 0.9, List.of(),
                        "fluid", "OD", null, "k", null, SPECTRALIS));
        RetinalArtifactStorageService store = mock(RetinalArtifactStorageService.class);
        Path dir = Files.createTempDirectory("dispatch-source-it-");
        when(store.persist(anyLong(), any())).thenReturn(dir);

        controller(remote, store).handleRemote(jobId, "fluid", "/x.dcm", "OU", 0, null);

        Map<String, String> r = row(jobId);
        assertEquals("done", r.get("status"));
        assertEquals("dicom", r.get("source_format"));
        assertEquals("Heidelberg Engineering", r.get("device_manufacturer"));
        assertEquals("swapped", r.get("spacing_order"));
        assertEquals("OD", r.get("eye_laterality"), "a job with no single eye takes the file's");
    }

    @Test
    void anEyeTheJobAlreadyHasIsNotOverwritten() throws Exception {
        long jobId = job("OS");
        RemoteRetinalInferenceClient remote = mock(RemoteRetinalInferenceClient.class);
        when(remote.runRemote(anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(new RemoteRunResult("fluid-1.3.0", 0.5, "mm³", Map.of(), 0.9, List.of(),
                        "fluid", "OD", null, "k", null, SPECTRALIS));
        RetinalArtifactStorageService store = mock(RetinalArtifactStorageService.class);
        when(store.persist(anyLong(), any())).thenReturn(Files.createTempDirectory("dispatch-source-it-"));

        controller(remote, store).handleRemote(jobId, "fluid", "/x.dcm", "OS", 0, null);

        assertEquals("OS", row(jobId).get("eye_laterality"));
    }

    @Test
    void anOutageStaysAnOutage() throws Exception {
        long jobId = job("OD");
        RemoteRetinalInferenceClient remote = mock(RemoteRetinalInferenceClient.class);
        when(remote.runRemote(anyLong(), anyString(), anyString(), any(), anyInt())).thenReturn(null);

        controller(remote, mock(RetinalArtifactStorageService.class))
                .handleRemote(jobId, "fluid", "/nowhere/x.dcm", "OD", 0, null);

        Map<String, String> r = row(jobId);
        assertEquals("failed", r.get("status"));
        assertEquals(RetinalJobAccess.SCAN_FILE_MISSING, r.get("status_message"));
        assertNull(r.get("source_format"));
    }
}
