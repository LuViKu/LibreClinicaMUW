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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-025 — public image-upload portal for the Remidio FOP (the upload ingress).
 *
 * <p>The Remidio FOP is an iPhone + browser with no DICOM export, so the
 * operator uploads the captured fundus JPEG/PNG through this page. Mirrors the
 * {@code PublicOctUploadController} / {@code PublicBcvaEntryController} posture:
 * no LibreClinica account, whitelisted {@code permitAll} in SecurityConfig, and
 * the institutional reverse proxy is the only access gate (must NOT be exposed
 * to the public internet). The uploaded image lands in {@code image_ingest}
 * ({@code source_kind='upload'}, {@code UNBOUND}) for the SPA reconciliation
 * inbox — the operator links it to a subject/event/CRF there. Any PatientID the
 * operator types is stored as a match hint; nothing binds here.
 *
 * <p>Two endpoints: {@code POST /resolve} (optional patient lookup for on-page
 * confirmation, reusing {@link StudySubjectFinder}) and {@code POST /commit}
 * (the multipart upload). Operator-typed strings are never logged.
 */
@RestController
@RequestMapping("/api/v1/public/image-upload")
@Tag(name = "Public image upload",
     description = "Remidio FOP fundus JPEG/PNG upload → image_ingest (source_kind='upload').")
public class PublicImageUploadController {

    private static final Logger LOG = LoggerFactory.getLogger(PublicImageUploadController.class);

    /** Filesystem fallback when {@code core.dicom.ingest.storePath} is unset. */
    public static final String DEFAULT_STORE_PATH = "/var/lib/libreclinica/dicom-ingest";
    private static final long MAX_BYTES = 64L * 1024 * 1024;   // 64 MB — a fundus JPEG is a few MB
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png");
    private static final Set<String> LATERALITIES = Set.of("OD", "OS", "OU");

    private final DataSource dataSource;
    private final StudySubjectFinder studySubjectFinder;

    @Autowired
    public PublicImageUploadController(@Qualifier("dataSource") DataSource dataSource,
                                       StudySubjectFinder studySubjectFinder) {
        this.dataSource = dataSource;
        this.studySubjectFinder = studySubjectFinder;
    }

    // ----- /resolve : optional patient confirmation (reuses StudySubjectFinder) -----

    public record ResolveRequest(String patientId, String studyDate) {}

    public record ResolveCandidate(int studyId, String studyName, String studyOid,
                                    int studySubjectId, String subjectLabel, String siteName,
                                    EventCandidate matchingEvent) {}

    public record ResolveResponse(String patientId, List<ResolveCandidate> candidates, String state) {}

    /**
     * Label-prefix subject lookup for the Remidio page's patient-search dialog
     * — the sibling of {@code /public/oct-upload/patients/search}. See
     * {@link PublicSubjectSearch} for the narrowing relative to the staff
     * endpoint.
     */
    @GetMapping(value = "/patients/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchPatientsPublic(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return PublicSubjectSearch.search(studySubjectFinder, q, limit,
                StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.PORTAL_KEY));
    }

    /**
     * One visit on the picker, and nothing more than the operator needs to
     * recognise it.
     *
     * <p>Deliberately narrower than the DICOM worklist's entry, which carries
     * sex and date of birth: this page is unauthenticated, so the projection is
     * the label, the visit and the study.
     */
    public record PortalVisit(int studyEventId, String subjectLabel, String eventLabel,
                              String studyName, String time) {}

    /**
     * The visits scheduled for one day, so the operator can pick the patient
     * from a list instead of typing a label — the same thing the camera's own
     * worklist screen shows.
     *
     * <p><strong>Off by default.</strong> A list of today's patients on a page
     * that needs no login is a disclosure in its own right, even though it
     * shows only pseudonymised labels. It ships behind
     * {@code core.ingest.portal.todaysVisits} so it cannot be enabled by
     * accident, and honours the portal's study scope when it is.
     *
     * @return 404 when the feature is off — the path should not announce itself
     */
    @GetMapping(value = "/visits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> visits(@RequestParam(value = "date", required = false) String date) {
        if (!todaysVisitsEnabled()) {
            return ResponseEntity.status(404).body(Map.of("message", "not found"));
        }
        LocalDate day = parseIsoDateOrNull(date);
        if (day == null) day = LocalDate.now();

        try {
            List<PortalVisit> out = new ArrayList<>();
            for (ScheduledVisitQuery.ScheduledVisit v : ScheduledVisitQuery.query(
                    dataSource, day, day,
                    StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.PORTAL_KEY),
                    MAX_VISITS)) {
                out.add(new PortalVisit(v.studyEventId(), v.subjectLabel(), v.eventLabel(),
                        v.studyName(), v.time() == null ? null : v.time().toString()));
            }
            // Count only — the labels are patient data.
            LOG.info("public image upload: served {} visits for one day", out.size());
            return ResponseEntity.ok(Map.of("date", day.toString(), "visits", out));
        } catch (SQLException e) {
            LOG.error("public image upload: visit list failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not load visits"));
        }
    }

    /** Hard cap: a picker is for choosing, not for exporting a day's schedule. */
    private static final int MAX_VISITS = 200;

    private static boolean todaysVisitsEnabled() {
        try {
            return "true".equalsIgnoreCase(
                    String.valueOf(CoreResources.getField("core.ingest.portal.todaysVisits")).trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest req) {
        if (req == null || isBlank(req.patientId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "patientId is required"));
        }
        // P3.0 — one shared implementation, and the portal's study scope
        // applied. This used to resolve across every study while the sibling
        // search endpoint was scoped, so a portal configured for one study
        // would refuse to *search* for another study's subject but would
        // happily *resolve* one and name their study and site back to an
        // unauthenticated caller.
        IngestResolutionService.Resolution r = new IngestResolutionService(studySubjectFinder)
                .resolve(req.patientId(), parseIsoDateOrNull(req.studyDate()),
                        StudyScopeConfig.studyIdsFor(dataSource, StudyScopeConfig.PORTAL_KEY));

        List<ResolveCandidate> candidates = new ArrayList<>(r.candidates().size());
        for (IngestResolutionService.ResolveCandidate c : r.candidates()) {
            candidates.add(new ResolveCandidate(c.studyId(), c.studyName(), c.studyOid(),
                    c.studySubjectId(), c.subjectLabel(), c.siteName(), c.matchingEvent()));
        }
        return ResponseEntity.ok(new ResolveResponse(req.patientId(), candidates, r.state()));
    }

    // ----- /commit : the multipart image upload -----

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> commit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "patientId", required = false) String patientId,
            @RequestParam(value = "laterality", required = false) String laterality,
            @RequestParam(value = "studyDate", required = false) String studyDate,
            @RequestParam(value = "studyEventId", required = false) Integer studyEventId,
            @RequestParam(value = "device", required = false) String device) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file is required"));
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest().body(Map.of("message", "only JPEG or PNG images are accepted"));
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.status(413).body(Map.of("message", "image exceeds the 64 MB limit"));
        }

        String lat = normalizeLaterality(laterality);
        LocalDate sd = parseIsoDateOrNull(studyDate);

        Path saved;
        try {
            Path dir = Paths.get(storeDir(), "uploads");
            Files.createDirectories(dir);
            saved = dir.resolve(UUID.randomUUID() + extFor(contentType, file.getOriginalFilename()));
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, saved, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("public image upload: could not store the file: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not store the image"));
        }

        try (Connection c = dataSource.getConnection()) {
            // When the operator picked a visit on the form, file the image
            // straight against it instead of queueing it for reconciliation.
            // The event id alone cannot be trusted on an unauthenticated form,
            // so the visit must be live, scheduled or in data entry, AND on the
            // acquisition date submitted — otherwise this is a 400, not a
            // silent fall back to UNBOUND (the operator asked for a binding and
            // deserves to know it did not happen).
            ImageIngestBinding.EventTarget target = null;
            if (studyEventId != null) {
                target = ImageIngestBinding.resolveEventTargetForPortal(c, studyEventId, sd);
                if (target == null) {
                    deleteQuietly(saved);
                    return ResponseEntity.badRequest().body(Map.of("message",
                            "that visit is not scheduled for the submitted date"));
                }
            }
            String dev = normaliseDevice(device);
            long id = insert(c, saved.toString(), file.getOriginalFilename(), contentType,
                    blankToNull(patientId), lat, sd, dev, target);
            if (target != null) {
                ImageIngestBinding.writeSystemBindAudit(dataSource, id, "portal", target.studyEventId());
                // The image on the visit is the evidence that this camera was
                // used on it, so tick the visit's checklist box for this
                // device. Nobody is logged in here — the write is attributed to
                // the system service account, not to a person.
                ImageIngestBinding.tickPerformed(dataSource, id, target, "upload", dev, null);
            }
            LOG.info("public image upload: enqueued image_ingest_id={} status={}",
                    id, target == null ? "UNBOUND" : "BOUND");
            return ResponseEntity.status(201).body(Map.of(
                    "imageIngestId", id, "status", target == null ? "UNBOUND" : "BOUND"));
        } catch (SQLException e) {
            deleteQuietly(saved);
            LOG.error("public image upload: INSERT failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "image upload failed"));
        }
    }

    private long insert(Connection c, String storedPath, String originalFilename, String contentType,
                        String patientId, String laterality, LocalDate studyDate, String device,
                        ImageIngestBinding.EventTarget target) throws SQLException {
        // The uploaded JPEG/PNG is itself viewable, so preview_png_path = stored_path.
        // A visit-picked upload lands BOUND with match_policy='portal' and no
        // bound_by_user_id — the form has no user; the audit row carries the trail.
        String sql = "INSERT INTO image_ingest ("
                + "source_kind, device, stored_path, preview_png_path, original_filename, content_type, "
                + "patient_id, laterality, study_date, received_at, status, "
                + "match_policy, bound_study_subject_id, bound_study_event_id, "
                + "bound_event_crf_id, bound_at"
                + ") VALUES ('upload', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, device);
            ps.setString(2, storedPath);
            ps.setString(3, storedPath);
            ps.setString(4, originalFilename);
            ps.setString(5, contentType);
            ps.setString(6, patientId);
            ps.setString(7, laterality);
            if (studyDate == null) {
                ps.setNull(8, Types.DATE);
            } else {
                ps.setObject(8, studyDate);
            }
            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(9, now);
            ps.setString(10, target == null ? "UNBOUND" : "BOUND");
            if (target == null) {
                ps.setNull(11, Types.VARCHAR);
                ps.setNull(12, Types.INTEGER);
                ps.setNull(13, Types.INTEGER);
                ps.setNull(14, Types.INTEGER);
                ps.setNull(15, Types.TIMESTAMP);
            } else {
                ps.setString(11, "portal");
                ps.setInt(12, target.studySubjectId());
                ps.setInt(13, target.studyEventId());
                if (target.eventCrfId() == null) {
                    ps.setNull(14, Types.INTEGER);
                } else {
                    ps.setInt(14, target.eventCrfId());
                }
                ps.setTimestamp(15, now);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("image_ingest INSERT returned no PK");
            }
        }
    }

    // ----- helpers -----

    /**
     * Which camera an upload came from.
     *
     * <p>This page is the Remidio InstaFOP's route into the platform — the
     * device exports a JPEG and has no DICOM — so that is the default. The
     * parameter exists so a second file-export camera can share the page
     * without the two becoming indistinguishable in the record.
     *
     * <p>Lower-cased: the value is matched against the performed-item map, and
     * a device that spells itself differently on different days is still one
     * device.
     */
    static final String DEFAULT_DEVICE = "remidio";

    private static String normaliseDevice(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_DEVICE;
        String trimmed = raw.trim();
        // Bounded by the column width; the value is configuration-like, not
        // free text, and is never logged.
        if (trimmed.length() > 64) trimmed = trimmed.substring(0, 64);
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static String storeDir() {
        try {
            String raw = CoreResources.getField("core.dicom.ingest.storePath");
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable — use the default.
        }
        return DEFAULT_STORE_PATH;
    }

    private static String extFor(String contentType, String originalName) {
        if (contentType.contains("png")) return ".png";
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return ".jpg";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot > 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot).toLowerCase();
                if (ext.length() <= 5 && ext.chars().allMatch(ch -> ch == '.' || Character.isLetterOrDigit(ch))) {
                    return ext;
                }
            }
        }
        return ".img";
    }

    private static String normalizeLaterality(String laterality) {
        if (isBlank(laterality)) return null;
        String up = laterality.trim().toUpperCase();
        return LATERALITIES.contains(up) ? up : null;
    }

    private static LocalDate parseIsoDateOrNull(String iso) {
        if (isBlank(iso)) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
