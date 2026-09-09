/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-025 — internal ingest endpoint for the DICOM C-STORE receiver sidecar.
 *
 * <p>The {@code dicom-scp} sidecar (pynetdicom Storage SCP) receives a fundus
 * image the HealthAEye camera pushes, writes the Part-10 object + a rendered
 * preview into the <em>shared</em> ingest store, then POSTs the DICOM metadata
 * plus those (shared-volume) paths here. We INSERT one {@code image_ingest} row
 * ({@code source_kind='dicom'}) in {@code UNBOUND} state for the SPA
 * reconciliation inbox (Slice 2). The Remidio upload page is the sibling
 * {@code source_kind='upload'} ingress into the same queue.
 *
 * <p>This is NOT a browser endpoint. It is whitelisted {@code permitAll} in
 * {@code SecurityConfig} and gated instead by the shared-secret
 * {@code X-MUW-Dicom-Token} header (config {@code core.dicom.ingest.token}); the
 * reverse proxy must never expose it to the public internet. It is idempotent
 * on {@code sop_instance_uid} — a re-sent C-STORE returns the existing row.
 *
 * <p>Camera-provided string values (PatientID, modality, AE title, …) are never
 * written to the log — only the generated row id — so the endpoint is not a
 * log-injection sink.
 */
@RestController
@RequestMapping("/api/v1/internal/dicom-ingest")
@Tag(name = "DICOM ingest (internal)",
     description = "Sidecar → app handoff: enqueue a received fundus image as a dicom_ingest row.")
public class DicomIngestApiController {

    private static final Logger LOG = LoggerFactory.getLogger(DicomIngestApiController.class);

    private static final String TOKEN_HEADER = "X-MUW-Dicom-Token";

    private final DataSource dataSource;

    @Autowired
    public DicomIngestApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Sidecar → app payload: DICOM identity/exam tags + shared-store paths. */
    public record DicomIngestRequest(
            String sopInstanceUid,
            String sopClassUid,
            String studyInstanceUid,
            String seriesInstanceUid,
            String modality,
            String patientId,
            String patientName,
            String accessionNumber,
            String studyDate,       // ISO yyyy-MM-dd, nullable
            String laterality,      // OD / OS / OU, nullable
            String sourceAeTitle,
            String dicomPath,        // path under the shared ingest store
            String previewPngPath    // nullable
    ) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(
            @RequestBody DicomIngestRequest req,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        // Shared-secret gate — mirrors the sidecar's own auth check.
        String expected = cfg("core.dicom.ingest.token", "");
        if (expected.isBlank()) {
            LOG.warn("DICOM ingest rejected: core.dicom.ingest.token is not configured");
            return ResponseEntity.status(503).body(Map.of("message", "DICOM ingest token not configured"));
        }
        if (token == null || !constantTimeEquals(token, expected)) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or missing " + TOKEN_HEADER));
        }

        if (req == null || isBlank(req.sopInstanceUid()) || isBlank(req.dicomPath())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "sopInstanceUid and dicomPath are required"));
        }

        try (Connection c = dataSource.getConnection()) {
            Long existing = findBySopInstanceUid(c, req.sopInstanceUid());
            if (existing != null) {
                // Re-sent C-STORE — idempotent, return the existing row.
                return ResponseEntity.ok(dupBody(existing));
            }
            long id = insert(c, req);
            LOG.info("DICOM ingest: enqueued dicom_ingest_id={}", id);
            return ResponseEntity.status(201).body(Map.of("dicomIngestId", id, "status", "UNBOUND"));
        } catch (SQLException e) {
            // Race on the sop_instance_uid unique index — treat as idempotent.
            if ("23505".equals(e.getSQLState())) {
                try (Connection c = dataSource.getConnection()) {
                    Long existing = findBySopInstanceUid(c, req.sopInstanceUid());
                    if (existing != null) return ResponseEntity.ok(dupBody(existing));
                } catch (SQLException ignored) {
                    // fall through to 500
                }
            }
            LOG.error("DICOM ingest INSERT failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "DICOM ingest failed"));
        }
    }

    private static Map<String, Object> dupBody(long id) {
        return Map.of("dicomIngestId", id, "status", "UNBOUND", "duplicate", true);
    }

    private long insert(Connection c, DicomIngestRequest r) throws SQLException {
        LocalDate studyDate = parseIsoDateOrNull(r.studyDate());
        // source_kind + content_type are literals here — this endpoint is the
        // DICOM ingress. The Remidio upload path INSERTs source_kind='upload'.
        String sql = "INSERT INTO image_ingest ("
                + "source_kind, content_type, "
                + "sop_instance_uid, sop_class_uid, study_instance_uid, series_instance_uid, "
                + "modality, patient_id, patient_name, accession_number, study_date, laterality, "
                + "source_ae_title, stored_path, preview_png_path, received_at, status"
                + ") VALUES ('dicom', 'application/dicom', "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNBOUND')";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.sopInstanceUid());
            ps.setString(2, r.sopClassUid());
            ps.setString(3, r.studyInstanceUid());
            ps.setString(4, r.seriesInstanceUid());
            ps.setString(5, r.modality());
            ps.setString(6, r.patientId());
            ps.setString(7, r.patientName());
            ps.setString(8, r.accessionNumber());
            if (studyDate == null) {
                ps.setNull(9, Types.DATE);
            } else {
                ps.setObject(9, studyDate);
            }
            ps.setString(10, r.laterality());
            ps.setString(11, r.sourceAeTitle());
            ps.setString(12, r.dicomPath());
            ps.setString(13, r.previewPngPath());
            ps.setTimestamp(14, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("dicom_ingest INSERT returned no PK");
            }
        }
    }

    private Long findBySopInstanceUid(Connection c, String sopInstanceUid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT image_ingest_id FROM image_ingest WHERE sop_instance_uid = ?")) {
            ps.setString(1, sopInstanceUid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** ISO yyyy-MM-dd → LocalDate; null / unparseable → null (not fatal — used only for match hints). */
    private static LocalDate parseIsoDateOrNull(String iso) {
        if (isBlank(iso)) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception ex) {
            LOG.warn("DICOM ingest: unparseable studyDate — storing null");
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String cfg(String key, String dflt) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable (e.g. very early boot) — use the default.
        }
        return dflt;
    }
}
