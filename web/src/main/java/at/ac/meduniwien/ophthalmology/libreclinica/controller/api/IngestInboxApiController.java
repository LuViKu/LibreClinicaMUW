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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * P3.2 — one inbox for everything that arrives.
 *
 * <p>The platform had two queues for the same activity. A file arrives from a
 * device, somebody says whose visit it belongs to, and it becomes study data —
 * but an OCT volume went to the retinal pipeline's "parked" list and a fundus
 * photo to the image inbox, each with its own list endpoint, bind API and SPA
 * view. An operator had to know which queue a file had landed in before they
 * could go looking for it, and neither queue could show them that the same
 * patient had both waiting.
 *
 * <p>This is the single surface. {@code kind} narrows it when somebody wants
 * only photographs or only scans; nothing requires them to.
 *
 * <p>Three things it does that the image inbox did not:
 *
 * <ul>
 *   <li><strong>Filters.</strong> The old inbox returned the newest 200 rows
 *       and nothing else. With OCT volumes in the same queue that is a
 *       scrolling exercise, so kind, source, device, a label substring and a
 *       candidate subject all narrow it.</li>
 *   <li><strong>Unbind.</strong> A mis-bind was previously fixed by editing the
 *       row, which left the CRF tick the bind had caused — see
 *       {@link IngestBindService}.</li>
 *   <li><strong>Bulk bind.</strong> A visit produces several files at once;
 *       binding them one at a time is the same decision typed repeatedly.</li>
 * </ul>
 *
 * <p>Authenticated, role-gated via {@link IngestBindAuthorization}, and every
 * bind target is checked against the caller's site visibility. The preview
 * stream uses the STRICT visibility form: it serves patient imagery.
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "Ingest reconciliation inbox",
     description = "List / preview / bind / unbind / dismiss inbound files of any kind (P3.2).")
public class IngestInboxApiController {

    private static final Logger LOG = LoggerFactory.getLogger(IngestInboxApiController.class);

    /** Default and ceiling for one page of the inbox. */
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    /** How many files one bulk call may bind. */
    private static final int MAX_BULK = 100;

    private static final IngestArtifactStore ARTIFACT_STORE = new IngestArtifactStore();

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final StudySubjectFinder studySubjectFinder;

    private StudyResourceAccess access;
    private IngestBindService binds;

    @Autowired
    public IngestInboxApiController(@Qualifier("dataSource") DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter,
                                    StudySubjectFinder studySubjectFinder) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.studySubjectFinder = studySubjectFinder;
    }

    private StudyResourceAccess access() {
        if (access == null) access = new StudyResourceAccess(dataSource, siteVisibilityFilter);
        return access;
    }

    private IngestBindService binds() {
        if (binds == null) binds = new IngestBindService(dataSource);
        return binds;
    }

    // ----- DTOs -----

    /** The one-click hint shown beside an unreconciled file. */
    public record Suggestion(String state, int studySubjectId, String subjectLabel,
                             int studyId, String studyName,
                             Integer studyEventId, Integer eventCrfId) {}

    /**
     * @param acquisitionDateSource where {@code acquisitionDate} came from —
     *        "file" when it was read out of the file, "operator" when somebody
     *        typed it, "unknown" for rows that predate the distinction. The
     *        inbox shows an unverified date differently, because a date the
     *        uploader supplied on the workbench is the day they searched
     *        visits by and matches the visit whatever the file says.
     */
    public record InboxRow(long id, String kind, String sourceKind, String device,
                           String patientId, String laterality, String acquisitionDate,
                           String acquisitionDateSource,
                           String modality, String originalFilename, Long byteSize,
                           Integer scanIndex, String receivedAt,
                           String previewUrl, boolean hasPreview,
                           Suggestion suggestion) {}

    /**
     * @param acknowledgeDateMismatch the operator has been shown that the
     *        file's own acquisition date disagrees with the visit's date and
     *        wants to file it there anyway. Absent or false, a mismatch comes
     *        back as 409 rather than being applied.
     */
    public record BindRequest(Integer studySubjectId, Integer studyEventId, Integer eventCrfId,
                              String modalityCode, String laterality,
                              Boolean acknowledgeDateMismatch) {}

    public record BulkBindRequest(List<Long> ids, Integer studySubjectId,
                                  Integer studyEventId, Integer eventCrfId,
                                  Boolean acknowledgeDateMismatch) {}

    public record DismissRequest(String reason) {}

    // ----- GET /inbox -----

    /**
     * @param kind   narrow to one sort of file — e2e, dicom, image, other
     * @param source narrow to one ingress — dicom, upload, portal-oct, api
     * @param q      a substring of the patient id or the original filename, for
     *               an operator who knows roughly what they are looking for
     * @param status defaults to UNBOUND, which is the working queue; BOUND and
     *               DISMISSED are available so an operator can find something
     *               they filed by mistake and unbind it
     */
    @GetMapping(value = "/inbox", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> inbox(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "device", required = false) String device,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "candidateStudySubjectId", required = false) Integer candidate,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpSession session) {

        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        String wantedStatus = normaliseStatus(status);
        if (wantedStatus == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "status must be UNBOUND, BOUND or DISMISSED"));
        }
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        StringBuilder sql = new StringBuilder(
                "SELECT ingest_item_id, kind, source_kind, device, patient_id, laterality, "
                        + "acquisition_date, acquisition_date_source, modality, original_filename, byte_size, scan_index, "
                        + "received_at, preview_png_path "
                        + "  FROM ingest_item WHERE status = ?");
        List<Object> args = new ArrayList<>();
        args.add(wantedStatus);
        if (notBlank(kind)) {
            sql.append(" AND lower(kind) = lower(?)");
            args.add(kind.trim());
        }
        if (notBlank(source)) {
            sql.append(" AND lower(source_kind) = lower(?)");
            args.add(source.trim());
        }
        if (notBlank(device)) {
            sql.append(" AND lower(COALESCE(device, source_ae_title, '')) = lower(?)");
            args.add(device.trim());
        }
        if (candidate != null) {
            sql.append(" AND candidate_study_subject_id = ?");
            args.add(candidate);
        }
        if (notBlank(q)) {
            // Both columns carry operator- or device-supplied text, so the term
            // is bound, never concatenated, and never logged.
            sql.append(" AND (patient_id ILIKE ? OR original_filename ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY received_at DESC LIMIT ").append(max);

        Set<Integer> visible = access().visibleStudyIds(session);
        List<InboxRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(toRow(rs, visible));
            }
        } catch (SQLException e) {
            LOG.error("ingest inbox list failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not list the inbox"));
        }
        return ResponseEntity.ok(Map.of("items", rows, "limit", max, "status", wantedStatus));
    }

    /**
     * How many files are waiting, by kind — so the SPA can label its filter
     * chips without fetching every row to count them.
     */
    @GetMapping(value = "/inbox/counts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> counts(HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        Map<String, Integer> byKind = new LinkedHashMap<>();
        int total = 0;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind, count(*) FROM ingest_item WHERE status = 'UNBOUND' "
                             + "GROUP BY kind ORDER BY kind");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byKind.put(rs.getString(1), rs.getInt(2));
                total += rs.getInt(2);
            }
        } catch (SQLException e) {
            LOG.error("ingest inbox counts failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not count the inbox"));
        }
        return ResponseEntity.ok(Map.of("unbound", total, "byKind", byKind));
    }

    // ----- GET /{id} -----

    @GetMapping(value = "/{id:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> one(@PathVariable("id") long id, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        Set<Integer> visible = access().visibleStudyIds(session);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, kind, source_kind, device, patient_id, laterality, "
                             + "acquisition_date, acquisition_date_source, modality, original_filename, byte_size, scan_index, "
                             + "received_at, preview_png_path, bound_study_subject_id "
                             + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
                }
                int boundSubject = rs.getInt("bound_study_subject_id");
                if (!rs.wasNull()) {
                    // Once bound, the file is that subject's data and the
                    // cross-study reach the inbox needs no longer applies.
                    ResponseEntity<?> vis = access().guardStudyVisibility(
                            subjectStudyId(boundSubject), session,
                            "This file belongs to a study you cannot access");
                    if (vis != null) return vis;
                }
                return ResponseEntity.ok(toRow(rs, visible));
            }
        } catch (SQLException e) {
            LOG.error("ingest item lookup failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "lookup failed"));
        }
    }

    // ----- GET /{id}/preview -----

    /**
     * The images filed against one visit: every BOUND row whose binding names
     * this study event, in the inbox's own row shape so the visit page and
     * the inbox render a file the same way.
     *
     * <p>Gated by study visibility, not by the reconcile role: a bound image
     * is the subject's data, and whoever may open the subject's visit (a
     * Monitor included) may see what was captured at it. Added 2026-09-24,
     * when the visit page showed a scan's AI metrics but nowhere the scans
     * and photographs themselves.
     */
    @GetMapping(value = "/by-event/{studyEventId:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> byEvent(@PathVariable("studyEventId") int studyEventId, HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;

        Integer studyId;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.study_id FROM study_event se "
                             + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                             + " WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("message", "no study_event " + studyEventId));
                }
                studyId = rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.error("visit lookup failed for study_event {}: {}", studyEventId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not resolve the visit"));
        }
        ResponseEntity<?> vis = access().guardStudyVisibility(studyId, session,
                "This visit belongs to a study you cannot access");
        if (vis != null) return vis;

        Set<Integer> visible = access().visibleStudyIds(session);
        List<InboxRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id, kind, source_kind, device, patient_id, laterality, "
                             + "acquisition_date, acquisition_date_source, modality, original_filename, byte_size, scan_index, "
                             + "received_at, preview_png_path "
                             + "  FROM ingest_item WHERE bound_study_event_id = ? AND status = 'BOUND' "
                             + " ORDER BY laterality NULLS LAST, received_at")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(toRow(rs, visible));
            }
        } catch (SQLException e) {
            LOG.error("ingest by-event list failed for study_event {}: {}", studyEventId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not list the visit's images"));
        }
        return ResponseEntity.ok(Map.of("items", rows, "studyEventId", studyEventId));
    }

    @GetMapping("/{id:[0-9]+}/preview")
    public ResponseEntity<?> preview(@PathVariable("id") long id, HttpSession session,
                                     HttpServletResponse response) {
        // The reconcile-role gate applies to UNBOUND files only (below). A
        // bound file is a subject's data, and the visit page — which a
        // Monitor may open — shows its preview; the study-visibility check
        // is what protects it there.
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;

        String previewPath;
        String contentType;
        Integer boundSubjectId = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT preview_png_path, content_type, bound_study_subject_id "
                             + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
                }
                previewPath = rs.getString("preview_png_path");
                contentType = rs.getString("content_type");
                int ss = rs.getInt("bound_study_subject_id");
                if (!rs.wasNull()) boundSubjectId = ss;
            }
        } catch (SQLException e) {
            LOG.error("preview lookup failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "preview lookup failed"));
        }

        // An UNBOUND file belongs to no study yet — the inbox is cross-study by
        // design, gated by role. Once BOUND it is a subject's data, and the
        // same site visibility applies as to any other artifact of theirs.
        if (boundSubjectId != null) {
            ResponseEntity<?> vis = access().guardStudyVisibility(subjectStudyId(boundSubjectId), session,
                    "This file belongs to a study you cannot access");
            if (vis != null) return vis;
        } else {
            ResponseEntity<?> role = guards(session);
            if (role != null) return role;
        }
        if (previewPath == null || previewPath.isBlank()) {
            return ResponseEntity.status(404).body(Map.of("message", "no preview for file " + id));
        }
        // The path comes out of a row an unauthenticated ingress can write, so
        // it is resolved against the known roots with symlinks followed.
        Path target = ARTIFACT_STORE.resolveConfined(previewPath).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("message", "preview file missing for file " + id));
        }

        // Streamed directly: the converter chain has no ResourceHttpMessageConverter.
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
            LOG.error("failed to stream preview for file {}: {}", id, e.getMessage());
        }
        return null;
    }

    // ----- POST /{id}/bind -----

    @PostMapping(value = "/{id:[0-9]+}/bind", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bind(@PathVariable("id") long id,
                                  @RequestBody BindRequest req, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        if (req == null || req.studySubjectId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "studySubjectId is required"));
        }
        ResponseEntity<?> targetGuard = guardBindTarget(req.studySubjectId(), session);
        if (targetGuard != null) return targetGuard;

        // What the file says about itself, before it is filed under a day it
        // may not belong to. A mismatch is refused once and applied on the
        // second ask; see dateMismatchResponse.
        IngestBindService.DateCheck dc = binds().checkVisitDate(id, req.studyEventId());
        if (dc.isMismatch() && !Boolean.TRUE.equals(req.acknowledgeDateMismatch())) {
            return dateMismatchResponse(id, dc);
        }

        IngestBindService.Result r = binds().bind(id, req.studySubjectId(), req.studyEventId(),
                req.eventCrfId(), IngestBindService.POLICY_MANUAL, actor(session), dc);
        return bindResponse(r, id, "BOUND");
    }

    /**
     * 409 for a file whose own acquisition date disagrees with the visit.
     *
     * <p>Not a refusal — a question. The body carries both dates so the SPA can
     * show what disagrees rather than just that something did, and the operator
     * re-sends with {@code acknowledgeDateMismatch} to go ahead. Nothing about
     * the file changes in the meantime.
     *
     * <p>Only a date read out of the file gets here at all
     * ({@link IngestBindService#checkVisitDate}), so this never fires on a date
     * somebody typed into the upload workbench.
     */
    private static ResponseEntity<?> dateMismatchResponse(long id, IngestBindService.DateCheck dc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "date_mismatch");
        body.put("ingestItemId", id);
        body.put("fileDate", String.valueOf(dc.fileDate()));
        body.put("visitDate", String.valueOf(dc.visitDate()));
        body.put("message", "This file says it was acquired on " + dc.fileDate()
                + ", but the visit is dated " + dc.visitDate()
                + ". Re-send with acknowledgeDateMismatch to file it there anyway.");
        return ResponseEntity.status(409).body(body);
    }

    // ----- POST /bulk-bind -----

    /**
     * Bind several files to one visit.
     *
     * <p>A visit produces several files at once — both eyes, several
     * modalities — and they are one decision, not several. Each is applied
     * independently so one already-reconciled row does not discard the rest;
     * the response says which succeeded.
     */
    @PostMapping(value = "/bulk-bind", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bulkBind(@RequestBody BulkBindRequest req, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ids is required"));
        }
        if (req.studySubjectId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "studySubjectId is required"));
        }
        if (req.ids().size() > MAX_BULK) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "at most " + MAX_BULK + " files at a time"));
        }
        ResponseEntity<?> targetGuard = guardBindTarget(req.studySubjectId(), session);
        if (targetGuard != null) return targetGuard;

        IngestBindService.Actor actor = actor(session);
        List<Long> bound = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : req.ids()) {
            if (id == null) continue;
            // Each file is dated on its own, so one scan from the wrong day
            // is skipped with its two dates rather than taking the batch down
            // — the same shape the already-reconciled case uses.
            IngestBindService.DateCheck dc = binds().checkVisitDate(id, req.studyEventId());
            if (dc.isMismatch() && !Boolean.TRUE.equals(req.acknowledgeDateMismatch())) {
                skipped.add(Map.of("id", id, "reason", "DATE_MISMATCH",
                        "fileDate", String.valueOf(dc.fileDate()),
                        "visitDate", String.valueOf(dc.visitDate())));
                continue;
            }
            IngestBindService.Result r = binds().bind(id, req.studySubjectId(), req.studyEventId(),
                    req.eventCrfId(), IngestBindService.POLICY_MANUAL, actor, dc);
            if (r == IngestBindService.Result.OK) {
                bound.add(id);
            } else {
                skipped.add(Map.of("id", id, "reason", r.name()));
            }
        }
        return ResponseEntity.ok(Map.of("bound", bound, "skipped", skipped));
    }

    // ----- POST /{id}/unbind -----

    /**
     * Return a bound file to the inbox.
     *
     * <p>Visibility is checked against the study it is currently bound to: the
     * operator has to be able to see the data they are about to change.
     */
    @PostMapping(value = "/{id:[0-9]+}/unbind", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> unbind(@PathVariable("id") long id, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        Integer boundSubject = boundSubjectOf(id);
        if (boundSubject != null) {
            ResponseEntity<?> vis = access().guardStudyVisibility(subjectStudyId(boundSubject), session,
                    "This file belongs to a study you cannot access");
            if (vis != null) return vis;
        }
        return bindResponse(binds().unbind(id, actor(session)), id, "UNBOUND");
    }

    // ----- POST /{id}/dismiss -----

    @PostMapping(value = "/{id:[0-9]+}/dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> dismiss(@PathVariable("id") long id,
                                     @RequestBody(required = false) DismissRequest req,
                                     HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        String reason = req == null ? null : req.reason();
        return bindResponse(binds().dismiss(id, reason, actor(session)), id, "DISMISSED");
    }

    // ----- guards / helpers -----

    /** Authenticated, with an active study, in a role that may reconcile. */
    private ResponseEntity<?> guards(HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (role != null && role.getRole() != null) ? role.getRole().getId() : 0;
        if (!IngestBindAuthorization.roleMayReconcile(roleId)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Your role may not reconcile inbound files"));
        }
        return null;
    }

    /** The subject has to exist, and be one this session may see. */
    private ResponseEntity<?> guardBindTarget(int studySubjectId, HttpSession session) {
        Integer studyId = subjectStudyId(studySubjectId);
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "no study_subject " + studySubjectId));
        }
        return access().guardStudyVisibility(studyId, session,
                "the chosen subject belongs to a study you cannot access");
    }

    private static IngestBindService.Actor actor(HttpSession session) {
        return new IngestBindService.Actor(
                (UserAccountBean) session.getAttribute("userBean"),
                (StudyBean) session.getAttribute("study"));
    }

    private static ResponseEntity<?> bindResponse(IngestBindService.Result r, long id, String newStatus) {
        return switch (r) {
            case OK -> ResponseEntity.ok(Map.of("ingestItemId", id, "status", newStatus));
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
            case WRONG_STATE -> ResponseEntity.status(409).body(Map.of(
                    "message", "file " + id + " is not in a state this can be applied to"));
            case FAILED -> ResponseEntity.internalServerError().body(Map.of("message", "the change failed"));
        };
    }

    private InboxRow toRow(ResultSet rs, Set<Integer> visible) throws SQLException {
        long id = rs.getLong("ingest_item_id");
        String patientId = rs.getString("patient_id");
        String acquisitionDate = rs.getString("acquisition_date");
        Timestamp received = rs.getTimestamp("received_at");
        long size = rs.getLong("byte_size");
        boolean sizeNull = rs.wasNull();
        int scan = rs.getInt("scan_index");
        boolean scanNull = rs.wasNull();
        return new InboxRow(
                id,
                rs.getString("kind"),
                rs.getString("source_kind"),
                rs.getString("device"),
                patientId,
                rs.getString("laterality"),
                acquisitionDate,
                rs.getString("acquisition_date_source"),
                rs.getString("modality"),
                rs.getString("original_filename"),
                sizeNull ? null : size,
                scanNull ? null : scan,
                received != null ? received.toInstant().toString() : null,
                "/pages/api/v1/ingest/" + id + "/preview",
                rs.getString("preview_png_path") != null,
                buildSuggestion(patientId, acquisitionDate, visible));
    }

    /**
     * The one-click suggestion beside an unreconciled file.
     *
     * <p>Answered by the shared resolution service, so the inbox and the two
     * portals agree on what counts as a match. Nothing is returned for an
     * ambiguous label: two subjects share it, and guessing is how a file
     * reaches the wrong chart.
     */
    private Suggestion buildSuggestion(String patientId, String acquisitionDate, Set<Integer> visible) {
        IngestResolutionService.Resolution r;
        try {
            r = new IngestResolutionService(studySubjectFinder)
                    .resolve(patientId, acquisitionDate, visible);
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

    private Integer boundSubjectOf(long ingestItemId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bound_study_subject_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            LOG.warn("bound-subject lookup failed for {}: {}", ingestItemId, e.getMessage());
            return null;
        }
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

    /** UNBOUND unless asked otherwise; null when the caller asked for nonsense. */
    private static String normaliseStatus(String raw) {
        if (raw == null || raw.isBlank()) return "UNBOUND";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "UNBOUND", "BOUND", "DISMISSED" -> s;
            default -> null;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static MediaType mediaTypeForPath(String path, String contentType) {
        String lower = path.toLowerCase(Locale.ROOT);
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
