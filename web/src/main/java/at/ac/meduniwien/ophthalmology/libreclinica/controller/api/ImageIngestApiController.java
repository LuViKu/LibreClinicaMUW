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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-025 — reconciliation inbox for inbound fundus images.
 *
 * <p>Both ingress paths (the Optomed C-STORE sidecar and the Remidio upload
 * page) land an {@code image_ingest} row in {@code UNBOUND} state. Here staff
 * (Data Manager / Investigator / CRC / Admin) list those rows, view the
 * preview, and bind each to a subject/event/CRF — or dismiss it. Modelled on
 * the retinal parked-job flow; reuses {@link StudySubjectFinder} (for a
 * one-click match suggestion), {@link SiteVisibilityFilter}, and the
 * {@code writeAuditEvent} helper (IMAGE_BIND / IMAGE_DISMISS).
 *
 * <p>Authenticated (behind {@code .anyRequest().hasRole("USER")}), role-gated
 * via {@link ImageBindAuthorization}, and every bind target is checked against
 * the caller's site visibility.
 */
@RestController
@RequestMapping("/api/v1/image-ingest")
@Tag(name = "Image reconciliation inbox",
     description = "List / preview / bind / dismiss UNBOUND fundus images (DR-025).")
public class ImageIngestApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ImageIngestApiController.class);

    public static final String DEFAULT_STORE_PATH = "/var/lib/libreclinica/dicom-ingest";
    private static final int INBOX_LIMIT = 200;
    private static final IngestArtifactStore ARTIFACT_STORE = new IngestArtifactStore();

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final StudySubjectFinder studySubjectFinder;

    @Autowired
    public ImageIngestApiController(@Qualifier("dataSource") DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter,
                                    StudySubjectFinder studySubjectFinder) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.studySubjectFinder = studySubjectFinder;
    }

    // ----- DTOs -----

    /** One-click bind hint when the row's PatientID resolves to exactly one visible subject. */
    public record Suggestion(String state, int studySubjectId, String subjectLabel,
                             int studyId, String studyName,
                             Integer studyEventId, Integer eventCrfId) {}

    public record InboxRow(long id, String sourceKind, String patientId, String laterality,
                           String studyDate, String modality, String originalFilename,
                           String receivedAt, String previewUrl, boolean hasPreview,
                           Suggestion suggestion) {}

    public record BindRequest(Integer studySubjectId, Integer studyEventId, Integer eventCrfId) {}

    public record DismissRequest(String reason) {}

    // ----- GET /inbox -----

    @GetMapping(value = "/inbox", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> inbox(HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;
        ResponseEntity<?> roleGuard = guardReconcileRole(session);
        if (roleGuard != null) return roleGuard;

        Set<Integer> visible = visibleStudyIds(session);
        List<InboxRow> rows = new ArrayList<>();
        String sql = "SELECT image_ingest_id, source_kind, patient_id, laterality, study_date, "
                + "modality, original_filename, received_at, preview_png_path "
                + "FROM image_ingest WHERE status = 'UNBOUND' ORDER BY received_at DESC LIMIT " + INBOX_LIMIT;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("image_ingest_id");
                String patientId = rs.getString("patient_id");
                String studyDate = rs.getString("study_date");
                Timestamp received = rs.getTimestamp("received_at");
                rows.add(new InboxRow(
                        id,
                        rs.getString("source_kind"),
                        patientId,
                        rs.getString("laterality"),
                        studyDate,
                        rs.getString("modality"),
                        rs.getString("original_filename"),
                        received != null ? received.toInstant().toString() : null,
                        "/pages/api/v1/image-ingest/" + id + "/preview",
                        rs.getString("preview_png_path") != null,
                        buildSuggestion(patientId, studyDate, visible)));
            }
        } catch (SQLException e) {
            LOG.error("image inbox list failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not list the inbox"));
        }
        return ResponseEntity.ok(Map.of("images", rows));
    }

    /**
     * The one-click suggestion an operator sees beside an unbound image.
     *
     * <p>P3.0 — reads through the shared resolution service, so the inbox, the
     * OCT portal and the image portal now answer the same question the same
     * way. They previously differed on whether a single subject with no visit
     * that day counts as a suggestion; it does not, and all three agree now.
     *
     * <p>Still returns the subject for the {@code novisit} state, because
     * knowing who it is saves the operator the search even when the visit
     * remains theirs to pick. Nothing is returned for an ambiguous label: two
     * subjects share it, and guessing is how an image reaches the wrong chart.
     */
    private Suggestion buildSuggestion(String patientId, String studyDate, Set<Integer> visible) {
        IngestResolutionService.Resolution r;
        try {
            r = new IngestResolutionService(studySubjectFinder)
                    .resolve(patientId, studyDate, visible);
        } catch (RuntimeException lookupFailed) {
            LOG.warn("inbox suggestion lookup failed: {}", lookupFailed.getMessage());
            return null;
        }
        var candidate = r.single().orElse(null);
        if (candidate == null) return null;
        EventCandidate ev = candidate.matchingEvent();
        return new Suggestion(r.state(), candidate.studySubjectId(), candidate.subjectLabel(),
                candidate.studyId(), candidate.studyName(),
                ev == null ? null : ev.studyEventId(),
                ev == null ? null : ev.eventCrfId());
    }

    // ----- GET /{id}/preview -----

    @GetMapping("/{id:[0-9]+}/preview")
    public ResponseEntity<?> preview(@PathVariable("id") long id, HttpSession session,
                                     HttpServletResponse response) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;
        ResponseEntity<?> roleGuard = guardReconcileRole(session);
        if (roleGuard != null) return roleGuard;

        String previewPath;
        String contentType;
        Integer boundSubjectId = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT preview_png_path, content_type, bound_study_subject_id "
                     + "FROM image_ingest WHERE image_ingest_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("message", "no image_ingest " + id));
                }
                previewPath = rs.getString("preview_png_path");
                contentType = rs.getString("content_type");
                int ss = rs.getInt("bound_study_subject_id");
                if (!rs.wasNull()) boundSubjectId = ss;
            }
        } catch (SQLException e) {
            LOG.error("image preview lookup failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "preview lookup failed"));
        }
        // An UNBOUND image belongs to no study yet — the reconciliation inbox is
        // cross-study by design (reconcile role gate above). Once BOUND, the image
        // is that subject's data: enforce the same site/study visibility as
        // RetinalResultsApiController.streamArtifact does for OCT artifacts, so
        // ids can't be enumerated across studies.
        if (boundSubjectId != null) {
            ResponseEntity<?> visGuard = guardStudyVisibility(subjectStudyId(boundSubjectId), session,
                    "This image belongs to a study you cannot access");
            if (visGuard != null) return visGuard;
        }
        if (previewPath == null || previewPath.isBlank()) {
            return ResponseEntity.status(404).body(Map.of("message", "no preview for image " + id));
        }
        // P3.0 — confinement now runs through the shared artifact store, which
        // resolves symlinks before comparing. The previous check compared path
        // prefixes literally, so a link inside the store pointing anywhere on
        // the host satisfied it. The path itself comes from a row an
        // unauthenticated ingress can write.
        Path target = ARTIFACT_STORE.resolveConfined(previewPath).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("message", "preview file missing for image " + id));
        }

        // Stream directly — the converter chain has no ResourceHttpMessageConverter
        // (same reason as RetinalResultsApiController.streamArtifact).
        MediaType mt = mediaTypeForPath(previewPath, contentType);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(mt.toString());
        try {
            response.setContentLengthLong(Files.size(target));
        } catch (IOException ignored) {
            // Content-Length is optional.
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                CacheControl.maxAge(Duration.ofHours(1)).cachePrivate().getHeaderValue());
        try {
            Files.copy(target, response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException e) {
            LOG.error("failed to stream preview for image {}: {}", id, e.getMessage());
        }
        return null;
    }

    // ----- POST /{id}/bind -----

    @PostMapping(value = "/{id:[0-9]+}/bind", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bind(@PathVariable("id") long id,
                                  @RequestBody BindRequest req, HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;
        ResponseEntity<?> roleGuard = guardReconcileRole(session);
        if (roleGuard != null) return roleGuard;
        if (req == null || req.studySubjectId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "studySubjectId is required"));
        }

        Integer studyId = subjectStudyId(req.studySubjectId());
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of("message", "no study_subject " + req.studySubjectId()));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(studyId, session,
                "the chosen subject belongs to a study you cannot access");
        if (visGuard != null) return visGuard;

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        try (Connection c = dataSource.getConnection()) {
            int n;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE image_ingest SET status='BOUND', match_policy='manual', "
                            + "bound_study_subject_id=?, bound_study_event_id=?, bound_event_crf_id=?, "
                            + "bound_by_user_id=?, bound_at=? WHERE image_ingest_id=? AND status='UNBOUND'")) {
                ps.setInt(1, req.studySubjectId());
                if (req.studyEventId() == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, req.studyEventId());
                if (req.eventCrfId() == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, req.eventCrfId());
                ps.setInt(4, user.getId());
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.setLong(6, id);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                return ResponseEntity.status(409).body(Map.of("message", "image " + id + " is not UNBOUND (already reconciled)"));
            }
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), AuditTypeIds.IMAGE_BIND,
                    user, study, null, "fundus image bound", "image_ingest", (int) id, "status", "UNBOUND", "BOUND");
            // The image on the visit is the evidence that this camera was used
            // on it, so tick the visit's checklist box for this device. The
            // operator who bound it is the author of that value.
            if (req.studyEventId() != null && req.eventCrfId() != null) {
                Device dev = readDevice(c, id);
                if (dev != null) {
                    ImageIngestBinding.tickPerformed(dataSource, id,
                            new ImageIngestBinding.EventTarget(
                                    req.studySubjectId(), req.studyEventId(), req.eventCrfId()),
                            dev.sourceKind(), dev.deviceKey(), user.getId());
                }
            }
            LOG.info("image_ingest {} bound to study_subject {}", id, req.studySubjectId());
            return ResponseEntity.ok(Map.of("imageIngestId", id, "status", "BOUND"));
        } catch (SQLException e) {
            LOG.error("image bind failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "bind failed"));
        }
    }

    // ----- POST /{id}/dismiss -----

    @PostMapping(value = "/{id:[0-9]+}/dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> dismiss(@PathVariable("id") long id,
                                     @RequestBody(required = false) DismissRequest req, HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;
        ResponseEntity<?> roleGuard = guardReconcileRole(session);
        if (roleGuard != null) return roleGuard;

        String reason = (req != null && req.reason() != null && !req.reason().isBlank())
                ? req.reason().trim() : "dismissed";
        if (reason.length() > 500) reason = reason.substring(0, 500);

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        try (Connection c = dataSource.getConnection()) {
            int n;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE image_ingest SET status='DISMISSED', status_message=?, "
                            + "bound_by_user_id=?, bound_at=? WHERE image_ingest_id=? AND status='UNBOUND'")) {
                ps.setString(1, reason);
                ps.setInt(2, user.getId());
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setLong(4, id);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                return ResponseEntity.status(409).body(Map.of("message", "image " + id + " is not UNBOUND"));
            }
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), AuditTypeIds.IMAGE_DISMISS,
                    user, study, null, "fundus image dismissed", "image_ingest", (int) id, "status", "UNBOUND", "DISMISSED");
            return ResponseEntity.ok(Map.of("imageIngestId", id, "status", "DISMISSED"));
        } catch (SQLException e) {
            LOG.error("image dismiss failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "dismiss failed"));
        }
    }

    // ----- guards / helpers -----

    /** Which ingress an image came through, and which camera sent it. */
    private record Device(String sourceKind, String deviceKey) {}

    /**
     * Reads the device off the image row. Falls back to the DICOM calling AE
     * title for rows written before {@code device} existed, so images already
     * sitting in the inbox still tick the right box when they are reconciled.
     */
    private static Device readDevice(Connection c, long imageIngestId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_kind, COALESCE(device, source_ae_title) AS device_key "
                        + "FROM image_ingest WHERE image_ingest_id = ?")) {
            ps.setLong(1, imageIngestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Device(rs.getString("source_kind"), rs.getString("device_key"));
            }
        } catch (SQLException e) {
            LOG.warn("could not read the device of image {}: {}", imageIngestId, e.getMessage());
            return null;
        }
    }

    private ResponseEntity<?> guardSession(HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = (StudyBean) session.getAttribute("study");
        if (study == null || study.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No active study bound to the session"));
        }
        return null;
    }

    private ResponseEntity<?> guardReconcileRole(HttpSession session) {
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (role != null && role.getRole() != null) ? role.getRole().getId() : 0;
        if (!ImageBindAuthorization.roleMayReconcile(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message", "Your role may not reconcile fundus images"));
        }
        return null;
    }

    private Set<Integer> visibleStudyIds(HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        StudyBean study = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        return siteVisibilityFilter.visibleStudyIds(user, study, role);
    }

    private ResponseEntity<?> guardStudyVisibility(Integer studyId, HttpSession session, String denyMessage) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user != null && user.isSysAdmin()) return null;
        if (visibleStudyIds(session).contains(studyId)) return null;
        return ResponseEntity.status(403).body(Map.of("message", denyMessage));
    }

    private Integer subjectStudyId(int studySubjectId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_id FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("study_id") : null;
            }
        } catch (SQLException e) {
            LOG.warn("subjectStudyId lookup failed for {}: {}", studySubjectId, e.getMessage());
            return null;
        }
    }

    private static MediaType mediaTypeForPath(String path, String contentType) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

}
