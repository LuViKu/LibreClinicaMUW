/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.tags.Tag;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;

/**
 * DR-029 — the same uploader, behind a login.
 *
 * <p>A data manager filing a folder of exports at their desk should not have
 * to use a page built for a phone at a camera: they have a session, so the
 * upload is attributed to them, the public throttle does not apply, and the
 * visits they may file into are the ones their site visibility allows rather
 * than a configured portal scope. Everything else — what a file is, where it
 * goes, what binding it does — is {@link IngestUploadService}, shared with
 * {@link PublicUploadController}.
 *
 * <p>Role-gated like the inbox ({@link IngestBindAuthorization}): whoever may
 * reconcile a file may also bring one in.
 */
@RestController
@RequestMapping("/api/v1/ingest/upload")
@Tag(name = "Ingest upload",
     description = "Authenticated upload of OCT (.e2e), DICOM and JPEG/PNG exports into the ingest queue (DR-029).")
public class IngestUploadApiController {

    // Method names double as OpenAPI operationIds (springdoc). They are
    // deliberately distinct from the older controllers' so adding this one
    // does not renumber the ids the SPA's api.ts already carries.

    private static final Logger LOG = LoggerFactory.getLogger(IngestUploadApiController.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final StudySubjectFinder studySubjectFinder;
    private final PublicOctUploadController oct;
    private final IngestUploadService uploads;

    private StudyResourceAccess access;

    @Autowired
    public IngestUploadApiController(@Qualifier("dataSource") DataSource dataSource,
                                     SiteVisibilityFilter siteVisibilityFilter,
                                     StudySubjectFinder studySubjectFinder,
                                     PublicOctUploadController oct) {
        this(dataSource, siteVisibilityFilter, studySubjectFinder, oct, new IngestUploadService(dataSource));
    }

    IngestUploadApiController(DataSource dataSource, SiteVisibilityFilter siteVisibilityFilter,
                              StudySubjectFinder studySubjectFinder, PublicOctUploadController oct,
                              IngestUploadService uploads) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.studySubjectFinder = studySubjectFinder;
        this.oct = oct;
        this.uploads = uploads;
    }

    private StudyResourceAccess access() {
        if (access == null) access = new StudyResourceAccess(dataSource, siteVisibilityFilter);
        return access;
    }

    /* ---------------- resolve / preflight ---------------- */

    /** Per-row resolution inside the session's visibility; the OCT page's request and answer shapes. */
    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolveStaffUpload(@RequestBody PublicOctUploadController.ResolveRequest request,
                                     HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        if (request == null || request.scans() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "scans[] is required"));
        }
        Set<Integer> visible = access().visibleStudyIds(session);
        IngestResolutionService resolution = new IngestResolutionService(studySubjectFinder);
        List<PublicOctUploadController.ResolveResponseScan> out = new ArrayList<>(request.scans().size());
        for (PublicOctUploadController.ResolveRequestScan scan : request.scans()) {
            if (scan == null) {
                out.add(new PublicOctUploadController.ResolveResponseScan(
                        null, List.of(), IngestResolutionService.STATE_NO_PATIENT));
                continue;
            }
            String pid = scan.patientId() == null ? "" : scan.patientId().trim();
            IngestResolutionService.Resolution r = resolution.resolve(
                    pid, UploadRoute.parseIsoDateOrNull(scan.scanDate()), visible);
            List<PublicOctUploadController.ResolveCandidate> candidates = new ArrayList<>(r.candidates().size());
            for (IngestResolutionService.ResolveCandidate c : r.candidates()) {
                candidates.add(new PublicOctUploadController.ResolveCandidate(
                        c.studyId(), c.studyName(), c.studyOid(), c.studySubjectId(), c.subjectLabel(),
                        c.siteName(), c.matchingEvent()));
            }
            out.add(new PublicOctUploadController.ResolveResponseScan(pid, candidates, r.state()));
        }
        return ResponseEntity.ok(Map.of("scans", out));
    }

    @GetMapping(value = "/preflight", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preflightStaffUpload(@RequestParam("sha256") String sha256,
                                       @RequestParam(value = "scanIndex", required = false) Integer scanIndex,
                                       HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return ResponseEntity.badRequest().body(Map.of("message", "sha256 must be 64 hex characters"));
        }
        return ResponseEntity.ok(uploads.preflight(sha256, scanIndex));
    }

    /* ---------------- the upload ---------------- */

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> commitStaffUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "patientId", required = false) String patientId,
            @RequestParam(value = "scanDate", required = false) String scanDate,
            @RequestParam(value = "studyDate", required = false) String studyDate,
            @RequestParam(value = "laterality", required = false) String laterality,
            @RequestParam(value = "scanIndex", defaultValue = "0") int scanIndex,
            @RequestParam(value = "eventCrfId", required = false) Integer eventCrfId,
            @RequestParam(value = "studyEventId", required = false) Integer studyEventId,
            @RequestParam(value = "park", defaultValue = "false") boolean park,
            @RequestParam(value = "device", required = false) String device,
            HttpSession session) {

        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file is required"));
        }
        FileKindSniffer.Sniffed sniffed = UploadRoute.sniff(file);
        if (sniffed == null) {
            return ResponseEntity.badRequest().body(Map.of("message", UploadRoute.UNSUPPORTED_MESSAGE));
        }
        String date = UploadRoute.isBlank(scanDate) ? studyDate : scanDate;

        // Whatever the kind, the visit has to be one this session may see.
        Integer visit = studyEventId;
        if (visit == null && eventCrfId != null) {
            visit = UploadRoute.studyEventIdForEventCrf(dataSource, eventCrfId);
            if (visit == null) {
                return ResponseEntity.status(404).body(Map.of("message", "No event_crf with id " + eventCrfId));
            }
        }
        if (visit != null) {
            ResponseEntity<?> vis = guardVisit(visit, session);
            if (vis != null) return vis;
        }
        IngestBindService.Actor actor = actor(session);

        if (sniffed.kind() == IngestArtifactStore.Kind.E2E) {
            String pid = patientId;
            if (UploadRoute.isBlank(pid) && visit != null) {
                pid = UploadRoute.subjectLabelForStudyEvent(dataSource, visit);
            }
            ResponseEntity<?> r = oct.commit(file, pid == null ? "" : pid, date == null ? "" : date,
                    laterality == null ? "" : laterality, scanIndex, eventCrfId, studyEventId, park,
                    false, 0);
            attributeOctUpload(r, actor);
            return UploadRoute.asE2e(r);
        }

        IngestUploadService.Outcome outcome = uploads.commit(new IngestUploadService.Upload(
                sniffed, file, patientId, UploadRoute.parseIsoDateOrNull(date), laterality, visit, device,
                IngestUploadService.Channel.STAFF, actor, access().visibleStudyIds(session)));
        if (outcome instanceof IngestUploadService.Created c) {
            LOG.info("staff upload: ingest_item {} ({}) {} by user {}",
                    c.ingestItemId(), c.format(), c.status(), actor.userId());
        }
        return UploadRoute.respond(outcome);
    }

    /* ---------------- undo ---------------- */

    @DeleteMapping(value = "/items/{ingestItemId:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> undoStaffUploadItem(@PathVariable("ingestItemId") long ingestItemId, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        return UploadRoute.respond(uploads.undo(ingestItemId, actor(session)));
    }

    @DeleteMapping(value = "/jobs/{jobId:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> undoStaffUploadJob(@PathVariable("jobId") long jobId, HttpSession session) {
        ResponseEntity<?> guard = guards(session);
        if (guard != null) return guard;
        return oct.undo(jobId);
    }

    /* ---------------- guards / helpers ---------------- */

    /** Authenticated, with an active study, in a role that may reconcile. */
    private ResponseEntity<?> guards(HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (role != null && role.getRole() != null) ? role.getRole().getId() : 0;
        if (!IngestBindAuthorization.roleMayReconcile(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message", "Your role may not upload files"));
        }
        return null;
    }

    /** The visit has to exist and belong to a study this session may see. */
    private ResponseEntity<?> guardVisit(int studyEventId, HttpSession session) {
        Integer studyId;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.study_id FROM study_event se "
                             + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                             + " WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (var rs = ps.executeQuery()) {
                studyId = rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            LOG.warn("visit lookup failed for study_event {}: {}", studyEventId, e.getMessage());
            studyId = null;
        }
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of("message", "No study_event with id " + studyEventId));
        }
        return access().guardStudyVisibility(studyId, session,
                "the chosen visit belongs to a study you cannot access");
    }

    private static IngestBindService.Actor actor(HttpSession session) {
        return new IngestBindService.Actor(
                (UserAccountBean) session.getAttribute("userBean"),
                (StudyBean) session.getAttribute("study"));
    }

    /**
     * The OCT route was written for a form with nobody logged in, so its row
     * and audit trail carry no person. Here there is one: the row records who
     * filed it and the trail says who uploaded it. Best-effort — the scan is
     * in, which is what matters.
     */
    private void attributeOctUpload(ResponseEntity<?> r, IngestBindService.Actor actor) {
        if (!r.getStatusCode().is2xxSuccessful() || actor.isSystem()) return;
        if (!(r.getBody() instanceof Map<?, ?> body)) return;
        Object idRaw = body.get("ingestItemId");
        if (!(idRaw instanceof Number n)) return;
        long id = n.longValue();
        String status = String.valueOf(body.get("status"));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ingest_item SET bound_by_user_id = ? "
                             + " WHERE ingest_item_id = ? AND status = 'BOUND' AND bound_by_user_id IS NULL")) {
            ps.setInt(1, actor.userId());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not attribute OCT upload ingest_item {} to its uploader: {}", id, e.getMessage());
        }
        try {
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), AuditTypeIds.OCT_UPLOAD_PUBLIC,
                    actor.user(), actor.study(), null, "OCT scan uploaded by a logged-in user",
                    "ingest_item", (int) id, "status", "", status);
        } catch (RuntimeException e) {
            LOG.warn("could not audit the staff OCT upload of ingest_item {}: {}", id, e.getMessage());
        }
    }
}
