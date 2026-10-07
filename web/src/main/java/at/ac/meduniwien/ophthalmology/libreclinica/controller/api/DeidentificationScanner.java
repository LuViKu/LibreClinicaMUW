/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.DicomDescribeClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.E2eDeidentificationVerifier;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.NeutralFilename;

/**
 * Layer 3 of the de-identification check: the files and rows already stored,
 * looked at again with the verifiers the upload used.
 *
 * <p>Layers 1 and 2 stop a file on its way in. This is for what they cannot
 * see: a file stored before the deployment required de-identification, a row
 * written by another path, a store edited by hand, a verifier rule tightened
 * since the file arrived. Once a night (cron
 * {@code libreclinica.ingest.deidentification.scan-cron}, default
 * {@code 0 30 2 * * *}; {@code -} disables), and only when
 * {@link DeidentificationPolicy#isRequired() required}, plus on demand from
 * {@code POST /api/v1/admin/deidentification/scan}.
 *
 * <ul>
 *   <li>every {@code .e2e} under {@code core.retinalInference.e2eUploadsPath}
 *       and every E2E {@code ingest_item}: {@link E2eDeidentificationVerifier},
 *       against the row's label (none for a file no row refers to);</li>
 *   <li>every DICOM {@code ingest_item}: the sidecar's {@code /verify} (the
 *       file is not modified), against the row's label;</li>
 *   <li>every {@code ingest_item}: {@code patient_id} must be the label of a
 *       study subject, {@code original_filename} the neutral name for it, and
 *       {@code patient_name} empty.</li>
 * </ul>
 *
 * <p>A finding is an ERROR log line, an audit row (type 194) and an entry in
 * {@link #last()}, which the sysadmin's System Status reads. All of them hold
 * field names, an {@code ingest_item} id and a file SHA-256 — never a value
 * from a file or a row. An audit row is written once per distinct finding;
 * the log line repeats every night until the cause is gone. A scan that could
 * not check something (sidecar down, file unreadable) says so rather than
 * reporting clean.
 */
@Component
public class DeidentificationScanner {

    private static final Logger LOG = LoggerFactory.getLogger(DeidentificationScanner.class);

    /** The most findings kept in memory for the page; the log and audit trail carry all of them. */
    static final int MAX_KEPT_FINDINGS = 500;
    static final String CRON_PROPERTY = "libreclinica.ingest.deidentification.scan-cron";

    /** One thing a scan found: where, which rules, and the file's SHA-256 when there is a file. */
    public record Finding(String kind, Long ingestItemId, List<String> violations, String sha256) {}

    /** What a scan did. */
    public record Report(Instant startedAt, Instant finishedAt, String trigger,
                         int e2eFilesChecked, int dicomFilesChecked, int rowsChecked,
                         int findingCount, boolean incomplete, List<Finding> findings) {}

    private final DataSource dataSource;
    private final DeidentificationPolicy policy;
    private final Supplier<DicomDescribeClient> dicom;
    private final Supplier<Path> e2eDir;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Report last;

    @Autowired
    public DeidentificationScanner(@Qualifier("dataSource") DataSource dataSource,
                                   DeidentificationPolicy policy) {
        this(dataSource, policy, DicomDescribeClient::new, DeidentificationScanner::configuredE2eDir);
    }

    DeidentificationScanner(DataSource dataSource, DeidentificationPolicy policy,
                            Supplier<DicomDescribeClient> dicom, Supplier<Path> e2eDir) {
        this.dataSource = dataSource;
        this.policy = policy;
        this.dicom = dicom;
        this.e2eDir = e2eDir;
    }

    /** The newest finished scan, or null when none has run since the application started. */
    public Report last() {
        return last;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isRequired() {
        return policy != null && policy.isRequired();
    }

    @Scheduled(cron = "${" + CRON_PROPERTY + ":0 30 2 * * *}")
    public void scheduled() {
        if (!isRequired()) return;
        run("scheduled");
    }

    /** Start a scan on its own thread; false when one is already running. */
    public boolean requestScan() {
        if (running.get()) return false;
        Thread t = new Thread(() -> run("manual"), "deid-scan");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Scan now, on the calling thread. Returns null when a scan is already running. */
    public Report run(String trigger) {
        if (!running.compareAndSet(false, true)) return null;
        try {
            Report r = scan(trigger);
            last = r;
            return r;
        } catch (RuntimeException e) {
            LOG.error("de-identification scan failed: {}", e.getClass().getSimpleName());
            Report r = new Report(Instant.now(), Instant.now(), trigger, 0, 0, 0, 0, true, List.of());
            last = r;
            return r;
        } finally {
            running.set(false);
        }
    }

    private record Row(long id, String kind, String storedPath, String patientId, String originalFilename,
                       String patientName, String sha256) {}

    private Report scan(String trigger) {
        Instant started = Instant.now();
        List<Finding> findings = new ArrayList<>();
        int[] counts = new int[3]; // e2e files, dicom files, rows
        boolean[] incomplete = {false};

        List<Row> rows;
        try {
            rows = loadRows();
        } catch (SQLException e) {
            LOG.error("de-identification scan could not read ingest_item ({})", e.getSQLState());
            return new Report(started, Instant.now(), trigger, 0, 0, 0, 0, true, List.of());
        }

        // ---- DB fields -------------------------------------------------------
        try (Connection c = dataSource.getConnection()) {
            for (Row row : rows) {
                counts[2]++;
                List<String> v = new ArrayList<>();
                String label = row.patientId();
                boolean labelOk = label == null || label.isBlank() || DeidUploadGate.isStudyLabel(c, label.trim());
                if (label != null && !label.isBlank() && !labelOk) v.add("ingest_item.patient_id");
                String name = row.originalFilename();
                if (name != null && !name.isBlank()
                        && !(labelOk && label != null && NeutralFilename.matchesLabel(name, label.trim()))) {
                    v.add("ingest_item.original_filename");
                }
                if (row.patientName() != null && !row.patientName().isBlank()) {
                    v.add("ingest_item.patient_name");
                }
                if (!v.isEmpty()) findings.add(new Finding("row", row.id(), v, row.sha256()));
            }
        } catch (SQLException e) {
            LOG.error("de-identification scan could not check ingest_item fields ({})", e.getSQLState());
            incomplete[0] = true;
        }

        // ---- E2E files: the rows' paths, then whatever else is in the store ---
        Map<String, List<Row>> e2eByPath = new LinkedHashMap<>();
        List<Row> dicomRows = new ArrayList<>();
        for (Row row : rows) {
            if (row.storedPath() == null || row.storedPath().isBlank()) continue;
            if ("e2e".equalsIgnoreCase(row.kind())) {
                e2eByPath.computeIfAbsent(canonical(Path.of(row.storedPath())), _ -> new ArrayList<>()).add(row);
            } else if ("dicom".equalsIgnoreCase(row.kind())) {
                dicomRows.add(row);
            }
        }
        Set<String> orphans = new LinkedHashSet<>();
        Path dir = e2eDir.get();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir, 2)) {
                walk.filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".e2e"))
                    .forEach(p -> {
                        String key = canonical(p);
                        if (!e2eByPath.containsKey(key)) orphans.add(key);
                    });
            } catch (IOException | RuntimeException e) {
                LOG.error("de-identification scan could not list the E2E store: {}", e.getClass().getSimpleName());
                incomplete[0] = true;
            }
        }
        for (Map.Entry<String, List<Row>> e : e2eByPath.entrySet()) {
            Path p = Path.of(e.getKey());
            if (!Files.isRegularFile(p)) continue; // an artefact the retention sweep removed
            counts[0]++;
            List<String> violations = null;
            for (Row row : e.getValue()) {
                List<String> v = E2eDeidentificationVerifier.verify(p, row.patientId()).violations();
                if (v.isEmpty()) { violations = null; break; }
                if (violations == null) violations = v;
            }
            if (violations != null) {
                findings.add(new Finding("e2e", e.getValue().get(0).id(), violations, sha256(p)));
            }
        }
        for (String key : orphans) {
            Path p = Path.of(key);
            counts[0]++;
            List<String> v = E2eDeidentificationVerifier.verify(p, null).violations();
            if (!v.isEmpty()) findings.add(new Finding("e2e-unlisted", null, v, sha256(p)));
        }

        // ---- DICOM files: the sidecar, read-only -----------------------------
        if (!dicomRows.isEmpty()) {
            DicomDescribeClient client = dicom.get();
            boolean sidecarDown = false;
            for (Row row : dicomRows) {
                Path p = Path.of(row.storedPath());
                if (!Files.isRegularFile(p)) continue;
                if (sidecarDown) { incomplete[0] = true; continue; }
                counts[1]++;
                try {
                    DicomDescribeClient.Verification v = client.verify(p, row.patientId());
                    if (!v.ok()) findings.add(new Finding("dicom", row.id(), v.violations(), row.sha256()));
                } catch (DicomDescribeClient.DescribeException e) {
                    if (e.reason() == DicomDescribeClient.DescribeException.Reason.NOT_DICOM) {
                        findings.add(new Finding("dicom", row.id(), List.of("dicom.notDicom"), row.sha256()));
                    } else {
                        // Could not check, which is not the same as clean.
                        LOG.error("de-identification scan: the DICOM verifier is not available ({})", e.reason());
                        sidecarDown = true;
                        incomplete[0] = true;
                        counts[1]--;
                    }
                }
            }
        }

        // ---- report ----------------------------------------------------------
        for (Finding f : findings) {
            LOG.error("de-identification scan finding: {} ingest_item={} rules={} sha256={}",
                    f.kind(), f.ingestItemId(), f.violations(), f.sha256());
            audit(f);
        }
        if (findings.isEmpty()) {
            LOG.info("de-identification scan clean: {} E2E file(s), {} DICOM file(s), {} row(s){}",
                    counts[0], counts[1], counts[2], incomplete[0] ? " — INCOMPLETE, see earlier errors" : "");
        } else {
            LOG.error("de-identification scan: {} finding(s) in {} E2E file(s), {} DICOM file(s), {} row(s)",
                    findings.size(), counts[0], counts[1], counts[2]);
        }
        List<Finding> kept = findings.size() > MAX_KEPT_FINDINGS
                ? List.copyOf(findings.subList(0, MAX_KEPT_FINDINGS)) : List.copyOf(findings);
        return new Report(started, Instant.now(), trigger, counts[0], counts[1], counts[2],
                findings.size(), incomplete[0], kept);
    }

    private List<Row> loadRows() throws SQLException {
        List<Row> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, kind, stored_path, patient_id, original_filename, "
                             + "patient_name, sha256 FROM ingest_item ORDER BY ingest_item_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)));
            }
        }
        return out;
    }

    /** One audit row per distinct finding: later nights repeat the log line, not the row. */
    private void audit(Finding f) {
        String name = "deid_scan " + f.kind() + ": " + String.join(",", f.violations());
        if (name.length() > 250) name = name.substring(0, 250);
        String value = "sha256=" + (f.sha256() == null ? "" : f.sha256());
        int entity = f.ingestItemId() == null ? 0 : (int) (long) f.ingestItemId();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = 'ingest_item' AND entity_id = ? AND entity_name = ? "
                             + "AND new_value = ? LIMIT 1")) {
            ps.setInt(1, AuditTypeIds.DEID_SCAN_FINDING);
            ps.setInt(2, entity);
            ps.setString(3, name);
            ps.setString(4, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        } catch (SQLException e) {
            // Fall through: a duplicate row is better than a missing one.
            LOG.warn("de-identification scan: audit lookup failed ({})", e.getSQLState());
        }
        try {
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), AuditTypeIds.DEID_SCAN_FINDING,
                    null, null, null, "de-identification scan finding", "ingest_item", entity, name, "", value);
        } catch (RuntimeException e) {
            LOG.warn("de-identification scan: could not write the audit row: {}", e.getClass().getSimpleName());
        }
    }

    private static String canonical(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    private static Path configuredE2eDir() {
        String raw = null;
        try {
            raw = CoreResources.getField("core.retinalInference.e2eUploadsPath");
        } catch (Exception ignored) {
            // no CoreResources outside a booted container
        }
        return Path.of(raw == null || raw.isBlank() ? PublicOctUploadController.DEFAULT_UPLOADS_PATH : raw.trim());
    }
}
