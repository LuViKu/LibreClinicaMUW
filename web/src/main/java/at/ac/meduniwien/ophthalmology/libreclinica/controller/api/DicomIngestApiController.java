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
import java.time.LocalDate;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.PerformedItemAutoTicker;

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
 * plus those (shared-volume) paths here. We INSERT one {@code ingest_item} row
 * ({@code source_kind='dicom'}) in {@code UNBOUND} state for the SPA
 * reconciliation inbox (Slice 2). The Remidio upload page is the sibling
 * {@code source_kind='upload'} ingress into the same queue. A study that carries
 * our Modality Worklist accession ({@code LC<study_event_id>}, issued by
 * {@link DicomWorklistApiController} via the sidecar) is instead landed already
 * {@code BOUND} to that visit ({@code match_policy='worklist'}).
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
            Inserted ins = insert(c, req);
            if (ins.boundStudyEventId() != null) {
                // Nobody clicked "bind" — the worklist accession did. Leave a
                // trail so a bound image can always be explained.
                ImageIngestBinding.writeSystemBindAudit(
                        dataSource, ins.id(), "worklist", ins.boundStudyEventId());
                // The image on the visit is the evidence that this camera was
                // used on it. No operator is involved in a worklist bind, so
                // the tick is attributed to the system service account.
                ImageIngestBinding.tickPerformed(
                        dataSource, ins.id(), ins.target(), "dicom", ins.deviceKey(),
                        req.laterality(), null);
            }
            LOG.info("DICOM ingest: ingest_item_id={} status={}", ins.id(), ins.status());
            return ResponseEntity.status(201).body(Map.of("imageIngestId", ins.id(), "status", ins.status()));
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
        return Map.of("imageIngestId", id, "duplicate", true);
    }

    private record Inserted(long id, String status, Integer boundStudyEventId,
                            ImageIngestBinding.EventTarget target, String deviceKey) {}

    /**
     * Which camera sent this. The calling AE title is the device's own
     * identifier and is what the performed-item map is keyed on; lower-cased so
     * a device that presents itself inconsistently is still one device.
     */
    private static String deviceKeyOf(DicomIngestRequest r) {
        return PerformedItemAutoTicker.normaliseDeviceKey(r.sourceAeTitle());
    }

    /**
     * Resolve a worklist accession to its visit. Shared with the upload portal
     * via {@link ImageIngestBinding}; foreign or stale accessions resolve to
     * null and the image lands UNBOUND for the inbox.
     */
    private ImageIngestBinding.EventTarget resolveWorklistTarget(Connection c, String accession)
            throws SQLException {
        Integer studyEventId = ImageIngestBinding.studyEventIdFromAccession(accession);
        return studyEventId == null ? null
                : ImageIngestBinding.resolveEventTarget(c, studyEventId);
    }

    private Inserted insert(Connection c, DicomIngestRequest r) throws SQLException {
        LocalDate studyDate = parseIsoDateOrNull(r.studyDate());
        // A study answering one of our worklist items comes back with our accession
        // → land it BOUND to that visit. Anything else lands UNBOUND for the inbox.
        ImageIngestBinding.EventTarget target = resolveWorklistTarget(c, r.accessionNumber());
        String status = target != null ? "BOUND" : "UNBOUND";
        // P3.1 — the statement lives in IngestItemRepository, shared with the
        // upload portal. source_kind and content_type are fixed here because
        // this endpoint IS the DICOM ingress.
        //
        // `device` is the one column that always answers "which camera",
        // whichever ingress an image came through. For DICOM that is the
        // calling AE title; source_ae_title keeps the raw DICOM value.
        var item = IngestItemRepository
                .newItem(IngestArtifactStore.Kind.DICOM, "dicom", r.dicomPath())
                .contentType("application/dicom")
                .device(deviceKeyOf(r))
                .previewPngPath(r.previewPngPath())
                .sopInstanceUid(r.sopInstanceUid())
                .sopClassUid(r.sopClassUid())
                .studyInstanceUid(r.studyInstanceUid())
                .seriesInstanceUid(r.seriesInstanceUid())
                .modality(r.modality())
                .sourceAeTitle(r.sourceAeTitle())
                // P3.4 — a camera that identifies itself classifies its own
                // images: no operator has to say which acquisition this is.
                .imagingModalityId(modalityForAeTitle(c, deviceKeyOf(r), target))
                .patientId(r.patientId())
                .patientName(r.patientName())
                .accessionNumber(r.accessionNumber())
                // StudyDate off the DICOM header — the file's own account of
                // when it was taken, not anything an operator typed.
                .acquisitionDate(studyDate)
                .acquisitionDateSource(studyDate == null
                        ? null : IngestItemRepository.ACQ_SOURCE_FILE)
                .laterality(r.laterality());
        if (target != null) {
            item.boundTo(target.studySubjectId(), target.studyEventId(), target.eventCrfId(), "worklist");
        }
        long id = item.insert(c);
        return new Inserted(id, status,
                target == null ? null : target.studyEventId(),
                target, deviceKeyOf(r));
    }

    /**
     * The study's modality whose {@code auto_match_ae_title} is this camera.
     *
     * <p>Scoped to the study the worklist bound the image to; an unbound image
     * belongs to no study yet, so nothing is claimed about it. Site studies
     * inherit their parent's catalogue.
     *
     * @return null when the image is unbound, no catalogue row matches, or the
     *         lookup fails — in every case the ticker falls back to the device
     */
    private static Integer modalityForAeTitle(Connection c, String aeTitle,
                                              ImageIngestBinding.EventTarget target) {
        if (aeTitle == null || aeTitle.isBlank() || target == null) return null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT im.imaging_modality_id "
                        + "  FROM imaging_modality im "
                        + "  JOIN study_subject ss ON ss.study_subject_id = ? "
                        + " WHERE im.status_id = 1 "
                        + "   AND lower(im.auto_match_ae_title) = lower(?) "
                        + "   AND im.study_id IN (ss.study_id, "
                        + "         COALESCE((SELECT parent_study_id FROM study "
                        + "                    WHERE study_id = ss.study_id), -1)) "
                        + " ORDER BY im.imaging_modality_id LIMIT 1")) {
            ps.setInt(1, target.studySubjectId());
            ps.setString(2, aeTitle);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.valueOf(rs.getInt(1)) : null;
            }
        } catch (SQLException e) {
            LOG.warn("modality auto-match lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private Long findBySopInstanceUid(Connection c, String sopInstanceUid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ingest_item_id FROM ingest_item WHERE sop_instance_uid = ?")) {
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
