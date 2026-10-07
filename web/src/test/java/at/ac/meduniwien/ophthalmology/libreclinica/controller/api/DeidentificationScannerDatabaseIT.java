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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;

/**
 * Layer 3: the nightly scan of what is already stored. Files and rows are
 * seeded directly (as if they predated the mode, or were written by another
 * path); the sidecar is a stub that calls a file dirty when its name says so.
 */
class DeidentificationScannerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String LABEL = "M-001";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path E2E_DIR;

    @TempDir
    static Path DICOM_DIR;

    private static HttpServer STUB;
    private static final AtomicInteger VERIFY_CALLS = new AtomicInteger();
    private static int sha = 0;

    @BeforeAll
    static void startStub() throws Exception {
        STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        STUB.createContext("/verify", ex -> {
            VERIFY_CALLS.incrementAndGet();
            String path = JSON.readTree(ex.getRequestBody()).path("path").asText();
            String out = path.contains("dirty")
                    ? "{\"ok\":false,\"violations\":[\"PatientName\",\"PrivateTags\"]}"
                    : "{\"ok\":true,\"violations\":[]}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        STUB.start();
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void reset() {
        VERIFY_CALLS.set(0);
    }

    @AfterEach
    void clean() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            for (String sql : new String[] {
                    "DELETE FROM audit_log_event WHERE audit_log_event_type_id = " + AuditTypeIds.DEID_SCAN_FINDING,
                    "DELETE FROM ingest_item WHERE source_kind = 'deid-scan-test'"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.executeUpdate();
                }
            }
        }
        for (Path dir : new Path[] {E2E_DIR, DICOM_DIR}) {
            try (var s = Files.walk(dir)) {
                s.filter(Files::isRegularFile).forEach(p -> p.toFile().delete());
            }
        }
    }

    private DeidentificationScanner scanner(boolean required, String sidecarUrl) {
        return new DeidentificationScanner(DATA_SOURCE, DeidentificationPolicy.of(required),
                () -> new DicomDescribeClient(sidecarUrl, "tok"), () -> E2E_DIR);
    }

    private String stubUrl() {
        return "http://127.0.0.1:" + STUB.getAddress().getPort() + "/describe";
    }

    private Path file(Path dir, String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private long row(IngestArtifactStore.Kind kind, Path stored, String patientId, String filename,
                     String patientName) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            var b = IngestItemRepository.newItem(kind, "deid-scan-test", stored == null ? "" : stored.toString())
                    .digest(String.format("%064x", ++sha), 1)
                    .originalFilename(filename)
                    .patientId(patientId);
            if (patientName != null) b.patientName(patientName);
            return b.insert(c);
        }
    }

    private List<String> auditNames() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT entity_name || ' | ' || new_value FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? ORDER BY audit_id")) {
            ps.setInt(1, AuditTypeIds.DEID_SCAN_FINDING);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new java.util.ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    /** Seeds one of everything: clean and dirty E2E, an unlisted E2E, clean and dirty DICOM, three bad rows. */
    private void seed() throws Exception {
        Path cleanE2e = file(E2E_DIR, "clean.e2e", E2eTestFiles.e2e(E2eTestFiles.patient(LABEL)));
        row(IngestArtifactStore.Kind.E2E, cleanE2e, LABEL, "M-001_20210104_OD.e2e", null);
        Path dirtyE2e = file(E2E_DIR, "dirty.e2e", E2eTestFiles.e2e(E2eTestFiles.patient("Mustermann")));
        row(IngestArtifactStore.Kind.E2E, dirtyE2e, LABEL, "M-001_20210105_OD.e2e", null);
        file(E2E_DIR, "orphan.e2e", E2eTestFiles.e2e(E2eTestFiles.patient("Musterfrau")));

        Path cleanDcm = file(DICOM_DIR, "ok.dcm", new byte[200]);
        row(IngestArtifactStore.Kind.DICOM, cleanDcm, LABEL, "M-001_20210104_OS.dcm", null);
        Path dirtyDcm = file(DICOM_DIR, "dirty.dcm", new byte[200]);
        row(IngestArtifactStore.Kind.DICOM, dirtyDcm, LABEL, "M-001_20210105_OS.dcm", null);

        // a row whose fields hold a person: free-text id, a camera's own filename, a patient name
        row(IngestArtifactStore.Kind.IMAGE, DICOM_DIR.resolve("gone.png"), "Mustermann Max", "Muster_Max_OD.png", "Mustermann^Max");
    }

    @Test
    void everyStoredFileAndRowIsReVerifiedAndEachFindingIsReportedAsNamesOnly() throws Exception {
        seed();

        DeidentificationScanner.Report r = scanner(true, stubUrl()).run("manual");

        assertNotNull(r);
        assertEquals(3, r.e2eFilesChecked(), "two listed files and the one nothing refers to");
        assertEquals(2, r.dicomFilesChecked());
        assertEquals(5, r.rowsChecked());
        assertFalse(r.incomplete());
        List<String> found = r.findings().stream()
                .map(f -> f.kind() + ":" + String.join("+", f.violations())).sorted().collect(Collectors.toList());
        assertEquals(List.of(
                "dicom:PatientName+PrivateTags",
                "e2e-unlisted:e2e.surname",
                "e2e:e2e.surname",
                "row:ingest_item.patient_id+ingest_item.original_filename+ingest_item.patient_name"), found);
        assertEquals(4, r.findingCount());
        r.findings().stream().filter(f -> !f.kind().equals("row"))
                .forEach(f -> assertEquals(64, f.sha256() == null ? 0 : f.sha256().length(), f.kind()));
        assertEquals(2, VERIFY_CALLS.get(), "the sidecar is asked once per stored DICOM file");

        // one audit row per finding, with no value from a file or a row in it
        List<String> audit = auditNames();
        assertEquals(4, audit.size(), audit.toString());
        String blob = String.join("\n", audit) + r.findings();
        for (String secret : List.of("Mustermann", "Musterfrau", "Muster_Max", "Max")) {
            assertFalse(blob.contains(secret), secret + " leaked into " + blob);
        }
    }

    @Test
    void aSecondScanKeepsTheFindingsButDoesNotRepeatTheAuditRows() throws Exception {
        seed();
        DeidentificationScanner s = scanner(true, stubUrl());
        s.run("scheduled");
        DeidentificationScanner.Report again = s.run("scheduled");
        assertEquals(4, again.findingCount(), "a finding stays until its cause is gone");
        assertEquals(4, auditNames().size());
        assertEquals(again, s.last());
    }

    @Test
    void aFixedFileStopsBeingAFinding() throws Exception {
        seed();
        DeidentificationScanner s = scanner(true, stubUrl());
        s.run("manual");
        Files.write(E2E_DIR.resolve("dirty.e2e"), E2eTestFiles.e2e(E2eTestFiles.patient("")));
        Files.write(E2E_DIR.resolve("orphan.e2e"), E2eTestFiles.e2e(E2eTestFiles.patient("")));
        DeidentificationScanner.Report r = s.run("manual");
        assertEquals(2, r.findingCount(), r.findings().toString());
        assertTrue(r.findings().stream().noneMatch(f -> f.kind().startsWith("e2e")));
    }

    @Test
    void aSidecarThatCannotBeReachedMakesTheScanIncompleteNotClean() throws Exception {
        seed();
        DeidentificationScanner.Report r = scanner(true, "http://127.0.0.1:1/describe").run("manual");
        assertTrue(r.incomplete(), "could not check is not the same as clean");
        assertTrue(r.findings().stream().noneMatch(f -> f.kind().equals("dicom")));
        assertEquals(2, r.findings().stream().filter(f -> f.kind().startsWith("e2e")).count());
    }

    @Test
    void aCleanStoreReportsNoFindings() throws Exception {
        Path e2e = file(E2E_DIR, "clean.e2e", E2eTestFiles.e2e(E2eTestFiles.patient(LABEL)));
        row(IngestArtifactStore.Kind.E2E, e2e, LABEL, "M-001_20210104_OD.e2e", null);
        DeidentificationScanner.Report r = scanner(true, stubUrl()).run("manual");
        assertEquals(0, r.findingCount());
        assertFalse(r.incomplete());
        assertEquals(0, auditNames().size());
    }

    @Test
    void theScheduledRunDoesNothingUnlessRequiredAndManualRunsAlways() throws Exception {
        seed();
        DeidentificationScanner off = scanner(false, stubUrl());
        off.scheduled();
        assertNull(off.last(), "internal deployment: the nightly job is a no-op");
        assertEquals(0, VERIFY_CALLS.get());

        DeidentificationScanner on = scanner(true, stubUrl());
        on.scheduled();
        assertNotNull(on.last());
        assertEquals("scheduled", on.last().trigger());
        assertEquals(4, on.last().findingCount());

        // an admin may scan an unrequired deployment before switching the mode on
        assertEquals("manual", off.run("manual").trigger());
    }

    @Test
    void anAdminTriggeredScanRunsOnItsOwnThreadAndOnlyOnce() throws Exception {
        seed();
        DeidentificationScanner s = scanner(true, stubUrl());
        assertTrue(s.requestScan());
        long deadline = System.currentTimeMillis() + 30_000;
        while (s.last() == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertNotNull(s.last());
        assertEquals("manual", s.last().trigger());
    }
}
