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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
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

    /** DR-036 — the earliest other row that shows the same picture, so the inbox can say so. */
    private static final String TWIN_COLUMN = "(SELECT MIN(t.ingest_item_id) FROM ingest_item t "
            + " WHERE t.pixel_sha256 = ingest_item.pixel_sha256 "
            + "   AND t.ingest_item_id <> ingest_item.ingest_item_id) AS twin_id";

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final StudySubjectFinder studySubjectFinder;
    /** DR-035 — nullable; without them a bind attaches existing jobs but starts none. */
    private final RemoteRetinalInferenceClient remoteClient;
    private final RetinalInferenceApiController inferenceController;

    private StudyResourceAccess access;
    private RetinalJobFollower follower;
    private IngestBindService binds;
    private IngestItemVisibility visibility;

    @Autowired
    public IngestInboxApiController(@Qualifier("dataSource") DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter,
                                    StudySubjectFinder studySubjectFinder,
                                    RemoteRetinalInferenceClient remoteClient,
                                    RetinalInferenceApiController inferenceController) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.studySubjectFinder = studySubjectFinder;
        this.remoteClient = remoteClient;
        this.inferenceController = inferenceController;
    }

    /** Test seam: no inference dispatcher. */
    public IngestInboxApiController(DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter,
                                    StudySubjectFinder studySubjectFinder) {
        this(dataSource, siteVisibilityFilter, studySubjectFinder, null, null);
    }

    private StudyResourceAccess access() {
        if (access == null) access = new StudyResourceAccess(dataSource, siteVisibilityFilter);
        return access;
    }

    private IngestItemVisibility visibility() {
        if (visibility == null) visibility = new IngestItemVisibility(dataSource, access());
        return visibility;
    }

    /** 404 for an item that does not exist or that the session may not see (no existence oracle). */
    private ResponseEntity<?> guardItem(long id, HttpSession session) {
        if (visibility().canSee(id, session)) return null;
        return ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
    }

    private RetinalJobFollower follower() {
        if (follower == null) follower = new RetinalJobFollower(dataSource, remoteClient, inferenceController);
        return follower;
    }

    private IngestBindService binds() {
        if (binds == null) binds = new IngestBindService(dataSource, follower());
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
                           Suggestion suggestion, Twin twin,
                           boolean analysable, List<ScanAnalysis> analyses) {

        /** The same row with the scan's analyses filled in (the visit page's list). */
        InboxRow withAnalyses(List<ScanAnalysis> list) {
            return new InboxRow(id, kind, sourceKind, device, patientId, laterality, acquisitionDate,
                    acquisitionDateSource, modality, originalFilename, byteSize, scanIndex, receivedAt,
                    previewUrl, hasPreview, suggestion, twin, analysable, list);
        }
    }

    /**
     * A retinal analysis of a scan that is not cancelled, for the visit page:
     * which tasks the scan already has, and where each one's results are.
     */
    public record ScanAnalysis(long jobId, Integer subjectSeq, String task, String status) {}

    /**
     * DR-036 — an earlier file that shows the same picture as this one.
     *
     * @param label the twin's bound subject's label when the caller may see
     *              that study, else the patient id it arrived with, else null
     */
    public record Twin(long ingestItemId, String status, String label, String receivedAt) {}

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

    /** Optional body of {@code /unbind}: dismiss in the same step, with the reason. */
    public record UnbindRequest(Boolean dismiss, String reason) {}

    public record BulkDismissRequest(List<Long> ids, String reason) {}

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
                        + "received_at, preview_png_path, " + TWIN_COLUMN
                        + "  FROM ingest_item WHERE status = ?");
        // Cross-site isolation: only the rows this session may see (IngestItemVisibility).
        sql.append(" AND ").append(visibility().predicate("ingest_item", session));
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
                     "SELECT kind, count(*) FROM ingest_item WHERE status = 'UNBOUND' AND "
                             + visibility().predicate("ingest_item", session)
                             + " GROUP BY kind ORDER BY kind");
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
                             + "received_at, preview_png_path, bound_study_subject_id, " + TWIN_COLUMN
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
                } else if (!visibility().canSee(id, session)) {
                    // Unbound or dismissed: visible by the uploader's study (origin_study_id).
                    return ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
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
        String subjectLabel;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.study_id, ss.label FROM study_event se "
                             + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                             + " WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("message", "no study_event " + studyEventId));
                }
                studyId = rs.getInt(1);
                subjectLabel = rs.getString(2);
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
                             + "received_at, preview_png_path, " + TWIN_COLUMN
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
        // What each scan has been analysed for, so the page can show the
        // results and offer only the tasks it lacks.
        try {
            Map<Long, List<RetinalJobFollower.ExistingJob>> byItem = new LinkedHashMap<>();
            List<Long> jobIds = new ArrayList<>();
            for (InboxRow row : rows) {
                if (!row.analysable()) continue;
                List<RetinalJobFollower.ExistingJob> live = follower().liveJobs(row.id());
                byItem.put(row.id(), live);
                for (RetinalJobFollower.ExistingJob j : live) jobIds.add(j.jobId());
            }
            // Each job's number under the subject, for its canonical address.
            Map<Long, RetinalJobAccess.JobAddress> addresses;
            try (Connection c = dataSource.getConnection()) {
                addresses = RetinalJobAccess.addressesOf(c, jobIds);
            }
            for (int i = 0; i < rows.size(); i++) {
                InboxRow row = rows.get(i);
                List<RetinalJobFollower.ExistingJob> live = byItem.get(row.id());
                if (live == null) continue;
                List<ScanAnalysis> analyses = new ArrayList<>();
                for (RetinalJobFollower.ExistingJob j : live) {
                    RetinalJobAccess.JobAddress a = addresses.get(j.jobId());
                    analyses.add(new ScanAnalysis(j.jobId(), a == null ? null : a.subjectSeq(), j.task(), j.status()));
                }
                rows.set(i, row.withAnalyses(analyses));
            }
        } catch (SQLException e) {
            // The files still list; a row left without analyses (null) offers nothing to start.
            LOG.warn("retinal job lookup failed for study_event {}: {}", studyEventId, e.getMessage());
        }
        // Files that carry this subject's label but are not filed anywhere —
        // after "remove from visit: wrong visit", or a capture the resolver
        // could not place. The visit page says so, so nothing removed from a
        // visit is out of sight; the count is all it needs.
        int pendingForSubject = 0;
        if (subjectLabel != null && !subjectLabel.isBlank()) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT count(*) FROM ingest_item WHERE status = 'UNBOUND' AND lower(patient_id) = lower(?) AND "
                                 + visibility().predicate("ingest_item", session))) {
                ps.setString(1, subjectLabel.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) pendingForSubject = rs.getInt(1);
                }
            } catch (SQLException e) {
                LOG.warn("pending-for-subject count failed for study_event {}: {}", studyEventId, e.getMessage());
            }
        }
        // DR-034 — what the visit expects, against what is there. One row per
        // plan entry; empty when the visit definition has no plan, in which
        // case the page shows nothing about expectations.
        List<Map<String, Object>> plan = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            List<VisitImagingPlan.Entry> entries = VisitImagingPlan.forStudyEvent(c, studyEventId);
            if (!entries.isEmpty()) {
                List<VisitImagingPlan.PresentFile> files = VisitImagingPlan.filesOf(c, studyEventId);
                for (VisitImagingPlan.Coverage cov : VisitImagingPlan.coverage(entries, files)) {
                    VisitImagingPlan.Entry e = cov.entry();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("modalityId", e.modalityId());
                    row.put("code", e.code());
                    row.put("labelDe", e.labelDe());
                    row.put("labelEn", e.labelEn());
                    row.put("device", e.device());
                    row.put("requirement", e.requirement());
                    row.put("laterality", e.laterality());
                    row.put("tasks", e.tasks());
                    row.put("presentOD", cov.presentOD());
                    row.put("presentOS", cov.presentOS());
                    row.put("presentTotal", cov.presentTotal());
                    row.put("satisfied", cov.satisfied());
                    plan.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.warn("imaging plan lookup failed for study_event {}: {}", studyEventId, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("items", rows, "studyEventId", studyEventId,
                "pendingForSubject", pendingForSubject, "plan", plan));
    }

    @SuppressWarnings("resource") // the servlet container owns and closes the response stream/writer
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
            ResponseEntity<?> item = guardItem(id, session);
            if (item != null) return item;
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
        ResponseEntity<?> itemGuard = guardItem(id, session);
        if (itemGuard != null) return itemGuard;

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
            if (!visibility().canSee(id, session)) {
                skipped.add(Map.of("id", id, "reason", IngestBindService.Result.NOT_FOUND.name()));
                continue;
            }
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
    public ResponseEntity<?> unbind(@PathVariable("id") long id,
                                    @RequestBody(required = false) UnbindRequest req,
                                    HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        Integer boundSubject = boundSubjectOf(id);
        if (boundSubject != null) {
            ResponseEntity<?> vis = access().guardStudyVisibility(subjectStudyId(boundSubject), session,
                    "This file belongs to a study you cannot access");
            if (vis != null) return vis;
        }
        ResponseEntity<?> itemGuard = guardItem(id, session);
        if (itemGuard != null) return itemGuard;
        IngestBindService.Actor actor = actor(session);
        IngestBindService.Result r = binds().unbind(id, actor);
        // 2026-09-24 — "remove from visit" on the visit page carries the
        // operator's intent: back to the inbox (wrong visit), or not study
        // data at all. The second is unbind + dismiss as one request, so the
        // file never sits in the inbox unreviewed between two clicks; the
        // trail still shows both steps, each with its actor.
        if (r == IngestBindService.Result.OK && req != null && Boolean.TRUE.equals(req.dismiss())) {
            return bindResponse(binds().dismiss(id, req.reason(), actor), id, "DISMISSED");
        }
        return bindResponse(r, id, "UNBOUND");
    }

    // ----- POST /{id}/restore -----

    /**
     * Bring a dismissed file back into the inbox while the retention window
     * is open. Role-gated like dismiss: a dismissed row is unbound, so it
     * belongs to no study to check visibility against.
     */
    @PostMapping(value = "/{id:[0-9]+}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> restore(@PathVariable("id") long id, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        ResponseEntity<?> itemGuard = guardItem(id, session);
        if (itemGuard != null) return itemGuard;
        return bindResponse(binds().restore(id, actor(session)), id, "UNBOUND");
    }

    // ----- POST /bulk-dismiss -----

    /**
     * Dismiss several files at once — a device flushes its whole memory on
     * first contact, and sixty test exposures are one decision. Applied
     * independently, like bulk-bind; the response says which were refused.
     */
    @PostMapping(value = "/bulk-dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bulkDismiss(@RequestBody BulkDismissRequest req, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "ids is required"));
        }
        if (req.ids().size() > MAX_BULK) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "at most " + MAX_BULK + " files at a time"));
        }
        IngestBindService.Actor actor = actor(session);
        List<Long> dismissed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : req.ids()) {
            if (id == null) continue;
            if (!visibility().canSee(id, session)) {
                skipped.add(Map.of("id", id, "reason", IngestBindService.Result.NOT_FOUND.name()));
                continue;
            }
            IngestBindService.Result r = binds().dismiss(id, req.reason(), actor);
            if (r == IngestBindService.Result.OK) {
                dismissed.add(id);
            } else {
                skipped.add(Map.of("id", id, "reason", r.name()));
            }
        }
        return ResponseEntity.ok(Map.of("dismissed", dismissed, "skipped", skipped));
    }

    // ----- POST /{id}/dismiss -----

    @PostMapping(value = "/{id:[0-9]+}/dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> dismiss(@PathVariable("id") long id,
                                     @RequestBody(required = false) DismissRequest req,
                                     HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;

        ResponseEntity<?> itemGuard = guardItem(id, session);
        if (itemGuard != null) return itemGuard;
        String reason = req == null ? null : req.reason();
        return bindResponse(binds().dismiss(id, reason, actor(session)), id, "DISMISSED");
    }

    // ----- POST /{id}/analyses -----

    public record StartAnalysisRequest(String task) {}

    /**
     * Start one retinal analysis on a filed OCT scan.
     *
     * <p>A bind starts what the visit's imaging plan asks for, and rerun-as
     * needs a job to start from. A filed scan that the plan did not cover had
     * no way to be analysed at all; this is that way, one task at a time.
     *
     * <p>Answers, in this order: 403 for a role that may not enter data; 400
     * for a task that is not offered; 404 for a file the caller cannot see
     * (never 403, so a foreign id says nothing); 409 {@code NOT_BOUND} for a
     * file not filed to a visit, {@code NOT_ANALYSABLE} for a file that is not
     * an OCT volume, the {@link ClinicalRecordGuard} codes for a closed
     * record, {@code SCAN_FILE_MISSING} for a file no longer on disk,
     * {@code INFERENCE_DISABLED} for a study that runs no inference, and
     * {@code ANALYSIS_EXISTS} with {@code existingJobId} for a task the scan
     * already has; 202 with {@code jobId} otherwise.
     */
    @PostMapping(value = "/{id:[0-9]+}/analyses",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startAnalysis(@PathVariable("id") long id,
                                           @RequestBody StartAnalysisRequest req,
                                           HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;
        // A finished job writes its metrics into the visit's CRF, as for a re-run.
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "running retinal analyses");
        if (roleRefusal != null) return roleRefusal;

        String task = req == null || req.task() == null ? null : req.task().trim().toLowerCase(Locale.ROOT);
        if (!RetinalJobFollower.isStartableTask(task)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "task must be one of " + RetinalJobFollower.STARTABLE_TASKS));
        }
        ResponseEntity<?> itemGuard = guardItem(id, session);
        if (itemGuard != null) return itemGuard;

        String kind;
        String sopClassUid;
        String storedPath;
        Integer subjectId;
        Integer studyEventId;
        Integer eventCrfId;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind, sop_class_uid, stored_path, status, bound_study_subject_id, "
                             + "       bound_study_event_id, bound_event_crf_id "
                             + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
                }
                kind = rs.getString("kind");
                sopClassUid = rs.getString("sop_class_uid");
                storedPath = rs.getString("stored_path");
                boolean bound = "BOUND".equals(rs.getString("status"));
                subjectId = intOrNull(rs, "bound_study_subject_id");
                studyEventId = intOrNull(rs, "bound_study_event_id");
                eventCrfId = intOrNull(rs, "bound_event_crf_id");
                if (!bound || subjectId == null || (studyEventId == null && eventCrfId == null)) {
                    return ClinicalRecordGuard.conflict("NOT_BOUND",
                            "This file is not filed to a visit; file it before starting an analysis.");
                }
            }
        } catch (SQLException e) {
            LOG.error("ingest item lookup failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "lookup failed"));
        }
        if (!RetinalJobFollower.isAnalysable(kind, sopClassUid)) {
            return ClinicalRecordGuard.conflict("NOT_ANALYSABLE", "Only OCT volumes can be analysed.");
        }

        StudySubjectBean ss = (StudySubjectBean) new StudySubjectDAO(dataSource).findByPK(subjectId);
        StudyEventBean event = studyEventId == null ? null
                : (StudyEventBean) new StudyEventDAO(dataSource).findByPK(studyEventId);
        EventCRFBean ecb = eventCrfId == null ? null : new EventCRFDAO(dataSource).findByPK(eventCrfId);
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(dataSource,
                (StudyBean) session.getAttribute("study"), ss, event, ecb, "starting a retinal analysis");
        if (closed != null) return closed;

        if (RetinalJobAccess.scanFileMissing(storedPath)) {
            return ClinicalRecordGuard.conflict("SCAN_FILE_MISSING", RetinalJobAccess.SCAN_FILE_MISSING);
        }

        RetinalJobFollower.Started r;
        try {
            r = follower().start(id, task, actor(session));
        } catch (SQLException e) {
            LOG.error("starting task {} on ingest_item {} failed: {}", task, id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "the analysis could not be started"));
        }
        return switch (r.outcome()) {
            case STARTED -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jobId", r.jobId());
                body.put("task", task);
                body.put("status", r.status());
                RetinalJobAccess.putAddress(dataSource, r.jobId(), body);
                yield ResponseEntity.accepted().body(body);
            }
            case DUPLICATE -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "ANALYSIS_EXISTS");
                body.put("message", "This scan already has a " + task + " analysis — open it instead.");
                body.put("existingJobId", r.jobId());
                RetinalJobAccess.putAddress(dataSource, r.jobId(), body);
                yield ResponseEntity.status(409).body(body);
            }
            case INFERENCE_DISABLED -> ClinicalRecordGuard.conflict("INFERENCE_DISABLED",
                    "Retinal analyses are switched off for this study.");
            case NO_DISPATCHER -> ResponseEntity.status(503).body(Map.of(
                    "message", "Starting analyses is unavailable on this server."));
            // The file changed between the checks above and the start.
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("message", "no ingest_item " + id));
            case NOT_BOUND -> ClinicalRecordGuard.conflict("NOT_BOUND",
                    "This file is not filed to a visit; file it before starting an analysis.");
            case NOT_ANALYSABLE -> ClinicalRecordGuard.conflict("NOT_ANALYSABLE",
                    "Only OCT volumes can be analysed.");
        };
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
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
            case REFUSED_LOCKED -> ResponseEntity.status(409).body(Map.of(
                    "message", "file " + id + " is filed against a signed or locked visit — un-sign or unlock it first",
                    "reason", "VISIT_SEALED"));
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
        long twinId = rs.getLong("twin_id");
        Twin twin = rs.wasNull() ? null : describeTwin(twinId, visible);
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
                buildSuggestion(patientId, acquisitionDate, visible),
                twin,
                RetinalJobFollower.isAnalysable(rs.getString("kind"), null),
                // Looked up for the visit page only (byEvent); the inbox has no use for it.
                null);
    }

    /**
     * DR-036 — the twin as the inbox shows it. A twin filed in a study the
     * caller cannot see is named by its number only: the label would say
     * which subject of that study a picture belongs to.
     */
    private Twin describeTwin(long twinId, Set<Integer> visible) {
        IngestTwins.Twin t = IngestTwins.load(dataSource, twinId);
        if (t == null) return null;
        String label = t.label();
        if (t.boundStudySubjectId() != null && visible != null) {
            Integer studyId = subjectStudyId(t.boundStudySubjectId());
            if (studyId == null || !visible.contains(studyId)) label = null;
        }
        return new Twin(t.ingestItemId(), t.status(), label,
                t.receivedAt() == null ? null : t.receivedAt().toString());
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
