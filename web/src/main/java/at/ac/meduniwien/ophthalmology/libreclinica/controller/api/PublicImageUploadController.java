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
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest req) {
        if (req == null || isBlank(req.patientId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "patientId is required"));
        }
        List<StudySubjectMatch> matches = studySubjectFinder.findByLabelAcrossStudies(req.patientId().trim());
        LocalDate date = parseIsoDateOrNull(req.studyDate());

        List<ResolveCandidate> candidates = new ArrayList<>();
        for (StudySubjectMatch m : matches) {
            EventCandidate ev = date != null
                    ? studySubjectFinder.findEventOnDate(m.studySubjectId(), date).orElse(null)
                    : null;
            candidates.add(new ResolveCandidate(m.studyId(), m.studyName(), m.studyOid(),
                    m.studySubjectId(), m.subjectLabel(), m.siteName(), ev));
        }
        String state;
        if (matches.isEmpty()) {
            state = "nopatient";
        } else if (matches.size() > 1) {
            state = "ambiguous";
        } else {
            state = candidates.get(0).matchingEvent() != null ? "suggested" : "novisit";
        }
        return ResponseEntity.ok(new ResolveResponse(req.patientId(), candidates, state));
    }

    // ----- /commit : the multipart image upload -----

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> commit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "patientId", required = false) String patientId,
            @RequestParam(value = "laterality", required = false) String laterality,
            @RequestParam(value = "studyDate", required = false) String studyDate) {

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
            long id = insert(c, saved.toString(), file.getOriginalFilename(), contentType,
                    blankToNull(patientId), lat, sd);
            LOG.info("public image upload: enqueued image_ingest_id={}", id);
            return ResponseEntity.status(201).body(Map.of("imageIngestId", id, "status", "UNBOUND"));
        } catch (SQLException e) {
            deleteQuietly(saved);
            LOG.error("public image upload: INSERT failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "image upload failed"));
        }
    }

    private long insert(Connection c, String storedPath, String originalFilename, String contentType,
                        String patientId, String laterality, LocalDate studyDate) throws SQLException {
        // The uploaded JPEG/PNG is itself viewable, so preview_png_path = stored_path.
        String sql = "INSERT INTO image_ingest ("
                + "source_kind, stored_path, preview_png_path, original_filename, content_type, "
                + "patient_id, laterality, study_date, received_at, status"
                + ") VALUES ('upload', ?, ?, ?, ?, ?, ?, ?, ?, 'UNBOUND')";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, storedPath);
            ps.setString(2, storedPath);
            ps.setString(3, originalFilename);
            ps.setString(4, contentType);
            ps.setString(5, patientId);
            ps.setString(6, laterality);
            if (studyDate == null) {
                ps.setNull(7, Types.DATE);
            } else {
                ps.setObject(7, studyDate);
            }
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("image_ingest INSERT returned no PK");
            }
        }
    }

    // ----- helpers -----

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
