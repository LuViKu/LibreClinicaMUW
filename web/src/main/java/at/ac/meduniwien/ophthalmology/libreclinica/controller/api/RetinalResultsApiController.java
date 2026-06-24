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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.SegmentationEnvelopeLoader;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Duration;

/**
 * Phase E.7 — Retinal inference read-side API.
 *
 * <p>The Wave 4 SPA viewer consumes four endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/retinal-jobs/{jobId}} — fat DTO joining
 *       {@code retinal_inference_job} + {@code retinal_inference_result}
 *       with shorthand companion-file URLs.</li>
 *   <li>{@code GET /api/v1/event-crfs/{eventCrfId}/retinal-jobs} —
 *       per-event-CRF summary list, ordered by enqueue time desc.</li>
 *   <li>{@code GET /api/v1/study-subjects/{studySubjectId}/retinal-jobs}
 *       — per-subject summary list across all event-CRFs.</li>
 *   <li>{@code GET /api/v1/retinal-jobs/{jobId}/artifacts/{name}} —
 *       streams a single seg or companion file with the right content
 *       type. Path-traversal guarded.</li>
 * </ul>
 *
 * <p>Authorization mirrors {@link RetinalInferenceApiController}:
 * session-bound userBean + study, a {@link SiteVisibilityFilter} check
 * against the job's owning study. 401 / 403 / 404 mirror the rest of
 * the API.
 *
 * <p>Companion files ({@code bscan.dcm}, {@code fundus.png},
 * {@code geometry.json}) live under
 * {@code <bscanStorePath>/<e2eUuid>/} — the preprocess sidecar's
 * persistence path; they are looked up by basename-of(e2e_path) minus
 * the {@code .e2e} suffix. Segmentation artifacts live under the
 * result row's {@code bscan_masks_dir}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Retinal results",
     description = "Read-side endpoints for the SPA viewer (Wave 4).")
public class RetinalResultsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalResultsApiController.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Companion files the preprocess sidecar writes per e2eUuid.
     * Order matters — kept stable so the SPA can iterate without
     * sorting client-side.
     */
    private static final List<String> COMPANION_NAMES =
            List.of("bscan.dcm", "fundus.png", "geometry.json");

    /**
     * Path-traversal guard for {@code GET …/artifacts/{name}}.
     * Allows only alphanumerics + a few common punctuation chars seg
     * runners actually emit ({@code .}, {@code _}, {@code -}, {@code (},
     * {@code )}, {@code #}, space).
     */
    private static final java.util.regex.Pattern SAFE_ARTIFACT_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.()# -]+");

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final RetinalArtifactStorageService artifactStore;
    private final StudySubjectFinder studySubjectFinder;
    private final RemoteRetinalInferenceClient remoteClient;
    private final RetinalJobStatusBroadcaster broadcaster;
    /**
     * Dispatcher for the post-bind remote run. The bind endpoint only
     * flips {@code parked → remote_pending}; the actual GPU sidecar
     * call lives on {@link RetinalInferenceApiController#handleRemote
     * RetinalInferenceApiController.handleRemote}, which was previously
     * private to the upload path. Cross-controller wiring is the
     * minimal-diff fix for the 2026-06-18 smoke gap where bound parked
     * jobs sat in {@code remote_pending} forever (the local DB-poll
     * worker filters that status out by design). Nullable so the
     * IT-friendly back-compat ctor stays valid.
     */
    private final RetinalInferenceApiController inferenceController;

    @Autowired
    public RetinalResultsApiController(@Qualifier("dataSource") DataSource dataSource,
                                       SiteVisibilityFilter siteVisibilityFilter,
                                       RetinalArtifactStorageService artifactStore,
                                       StudySubjectFinder studySubjectFinder,
                                       RemoteRetinalInferenceClient remoteClient,
                                       RetinalJobStatusBroadcaster broadcaster,
                                       RetinalInferenceApiController inferenceController) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.artifactStore = artifactStore;
        this.studySubjectFinder = studySubjectFinder;
        this.remoteClient = remoteClient;
        this.broadcaster = broadcaster;
        this.inferenceController = inferenceController;
    }

    /**
     * Wave 1B (2026-06-18): IT-friendly back-compat constructor used by
     * the Wave-3 happy-path IT and its sibling sliced tests. The bind +
     * search + SSE-broadcast paths are exercised via the
     * full-arg constructor; tests that exercise only the read-side
     * endpoints can keep the existing 3-arg ctor and pass null for the
     * new collaborators (the call paths guard for null).
     */
    public RetinalResultsApiController(DataSource dataSource,
                                       SiteVisibilityFilter siteVisibilityFilter,
                                       RetinalArtifactStorageService artifactStore) {
        this(dataSource, siteVisibilityFilter, artifactStore, null, null, null, null);
    }

    /* ====================================================================== */
    /* DTOs                                                                   */
    /* ====================================================================== */

    public record PrimaryMetric(BigDecimal value, String unit) { }

    public record RetinalJobDetailDto(
            long jobId,
            int eventCrfId,
            String task,
            String laterality,
            String status,
            String modelVersion,
            String enqueuedAt,
            String completedAt,
            String e2eUuid,
            PrimaryMetric primaryMetric,
            Map<String, Object> outputPayload,
            Double confidence,
            List<String> artifactNames,
            List<String> companionNames,
            String fundusUrl,
            String geometryUrl,
            String bscanDcmUrl,
            /**
             * nAMD treat-and-extend Slice 6 (2026-06-20) — the
             * subject's arm assignment in the active study, derived
             * from study_group.name. Honour-system gating: when set
             * to "AI_HIDDEN" the SPA hides the AI panels (KPIs,
             * en-face overlay, comparison) so an Arm B physician
             * doesn't see the quantification by accident. Null when
             * the subject isn't in either AI_SHOWN or AI_HIDDEN
             * (non-T-and-E studies pass through unchanged).
             */
            String subjectArm) { }

    public record RetinalJobSummaryDto(
            long jobId,
            String task,
            String laterality,
            String status,
            String modelVersion,
            String completedAt,
            /**
             * 2026-06-23 — visit date (ISO yyyy-MM-dd) sourced from
             * study_event.date_start. Distinct from completedAt
             * (the upload-pipeline timestamp) — when historical scans
             * are uploaded today the visit date is what reads
             * clinically and what the nAMD workspace + trend charts
             * key off. Null when the job has no study_event binding
             * (parked / partially-bound — visible only in the
             * cross-study parked admin view).
             */
            String visitDate,
            /**
             * 2026-06-23 user-feedback round — OCT acquisition date
             * (ISO yyyy-MM-dd) pulled from the .e2e header by the
             * retinal-preprocess sidecar. This is the device's
             * native scan-time stamp; the SPA's nAMD composable
             * prefers this over visit_date / completed_at so a stack
             * of historical scans uploaded today plot against the
             * real acquisition date. Null when the original device
             * left the field blank or the preprocess sidecar is
             * older than this header.
             */
            String acquisitionDate,
            /**
             * 2026-06-24 user-feedback round — the study_event the
             * job's event_crf is attached to. Surfaces here so the
             * SPA can look up the BCVA timeline row keyed by event
             * (per-visit BCVA values write into a sibling event_crf
             * on the same event). Null when the job is parked
             * (event_crf_id NULL); the BCVA lookup short-circuits
             * to "no BCVA known" for those.
             */
            Integer studyEventId,
            PrimaryMetric primaryMetric) { }

    /**
     * Wave 2A — longitudinal trends row. One point per completed job
     * for a (subject, task) pair. {@code outputPayload} carries the raw
     * {@code retinal_inference_result.output_payload} JSON so the SPA's
     * BiomarkerTrendsChart can render per-biomarker datasets (e.g. IRF
     * / SRF / PED / total for {@code fluid}) without an extra round-
     * trip.
     */
    public record RetinalTrendsPointDto(
            long jobId,
            String completedAt,
            /**
             * 2026-06-23 — clinical-relevance date for the trend X-axis.
             * Sourced from study_event.date_start (the visit date) so a
             * batch of historical scans uploaded today plot at their
             * acquisition / visit dates instead of all collapsing onto
             * the upload day. Null only when neither binding path
             * yielded a study_event (shouldn't happen for done jobs).
             */
            String visitDate,
            String eyeLaterality,
            java.math.BigDecimal primaryMetricValue,
            String primaryMetricUnit,
            Map<String, Object> outputPayload) { }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}                                              */
    /* ====================================================================== */

    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getJob(@PathVariable("jobId") long jobId,
                                    HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        String e2eUuid = e2eUuidFromPath(row.e2ePath);
        List<String> artifactNames = listArtifactNames(row.bscanMasksDir);
        // 2026-06-19 — thread scan_index so multi-volume uploads
        // discover their companions under scan-N/.
        List<String> companions = listCompanionNames(e2eUuid, row.scanIndex);

        String fundusUrl   = companions.contains("fundus.png")
                ? "/pages/api/v1/retinal-jobs/" + jobId + "/artifacts/fundus.png" : null;
        String geometryUrl = companions.contains("geometry.json")
                ? "/pages/api/v1/retinal-jobs/" + jobId + "/artifacts/geometry.json" : null;
        String bscanDcmUrl = companions.contains("bscan.dcm")
                ? "/pages/api/v1/retinal-jobs/" + jobId + "/artifacts/bscan.dcm" : null;

        Map<String, Object> outputPayload = parsePayload(row.outputPayloadJson);
        PrimaryMetric pm = primaryMetric(row.primaryMetricValue, row.primaryMetricUnit);

        // nAMD Slice 6 — resolve the subject's arm via groupAssignments
        // so the SPA can honour-system-gate the AI panels for Arm B.
        String subjectArm = null;
        try (Connection c = dataSource.getConnection()) {
            subjectArm = resolveSubjectArm(c, row.eventCrfId);
        } catch (SQLException sqlEx) {
            LOG.warn("subjectArm lookup failed for job {} (ecrf={}): {}",
                    jobId, row.eventCrfId, sqlEx.getMessage());
        }

        RetinalJobDetailDto dto = new RetinalJobDetailDto(
                row.jobId,
                row.eventCrfId,
                row.task,
                row.eyeLaterality,
                row.status,
                row.modelVersion,
                toIso(row.enqueuedAt),
                toIso(row.completedAt),
                e2eUuid,
                pm,
                outputPayload,
                row.confidence,
                artifactNames,
                companions,
                fundusUrl,
                geometryUrl,
                bscanDcmUrl,
                subjectArm);
        return ResponseEntity.ok(dto);
    }

    /**
     * nAMD Slice 6 — derive the subject's arm from its
     * {@code subject_group_map} membership. Walks
     * event_crf → study_event → study_subject → subject_group_map
     * → study_group + group_class, then returns the first study_group
     * name that matches {@code "AI_SHOWN"} or {@code "AI_HIDDEN"}.
     * Returns null when the subject isn't in either (non-T-and-E
     * studies pass through unchanged) so the SPA falls back to the
     * "no gating" rendering path.
     *
     * <p>The match is case-insensitive on the group name to absorb
     * institutional capitalisation variants.
     */
    private static String resolveSubjectArm(Connection c, int eventCrfId) throws SQLException {
        String sql = "SELECT sg.name "
                + "  FROM event_crf ec "
                + "  JOIN study_event ev ON ev.study_event_id = ec.study_event_id "
                + "  JOIN subject_group_map sgm "
                + "    ON sgm.study_subject_id = ev.study_subject_id "
                + "   AND sgm.status_id = 1 "
                + "  JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                + " WHERE ec.event_crf_id = ? "
                + "   AND UPPER(sg.name) IN ('AI_SHOWN', 'AI_HIDDEN') "
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String name = rs.getString(1);
                return name == null ? null : name.toUpperCase();
            }
        }
    }

    /* ====================================================================== */
    /* GET /event-crfs/{eventCrfId}/retinal-jobs                              */
    /* ====================================================================== */

    @GetMapping(path = "/event-crfs/{eventCrfId:[0-9]+}/retinal-jobs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listByEventCrf(@PathVariable("eventCrfId") int eventCrfId,
                                            HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        // Visibility: derive the job's owning study via the event_crf →
        // study_event → study_subject path. Empty list is fine — the SPA
        // simply renders an empty viewer. But the visibility check has
        // to gate on at least one matching job; we do the check once on
        // the event_crf itself, then list all the jobs for it.
        Integer studyId;
        try (Connection c = dataSource.getConnection()) {
            studyId = fetchStudyIdForEventCrf(c, eventCrfId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve study for event_crf {}: {}", eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for event_crf: " + sqlEx.getMessage()));
        }
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No event_crf with id " + eventCrfId));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(studyId, session,
                "event_crf " + eventCrfId + " belongs to a different study");
        if (visGuard != null) return visGuard;

        List<RetinalJobSummaryDto> out = new ArrayList<>();
        String sql = "SELECT j.job_id, j.task, j.eye_laterality, j.status, j.model_version, "
                + "       j.completed_at, "
                + "       r.primary_metric_value, r.primary_metric_unit "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + " WHERE j.event_crf_id = ? "
                + " ORDER BY j.enqueued_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(summaryFromRow(rs));
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list retinal jobs for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list retinal jobs: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/retinal-jobs                      */
    /* ====================================================================== */

    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/retinal-jobs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listByStudySubject(@PathVariable("studySubjectId") int studySubjectId,
                                                HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        Integer studyId;
        try (Connection c = dataSource.getConnection()) {
            studyId = fetchStudyIdForStudySubject(c, studySubjectId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve study for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for study_subject: " + sqlEx.getMessage()));
        }
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No study_subject with id " + studySubjectId));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(studyId, session,
                "study_subject " + studySubjectId + " belongs to a different study");
        if (visGuard != null) return visGuard;

        List<RetinalJobSummaryDto> out = new ArrayList<>();
        // 2026-06-23 — surfaces both CRF-bound + planned-visit-bound
        // jobs via the COALESCE on study_event_id (see earlier
        // changelog). Also exports visit_date (date(study_event.date_start))
        // so the nAMD workspace + per-subject job list can show the
        // clinical date instead of the upload timestamp.
        // 2026-06-23 — ORDER BY repeats the date expression instead of
        // referencing the `visit_date` alias inside COALESCE: Postgres
        // resolves bare aliases in ORDER BY but NOT inside compound
        // expressions, so `COALESCE(j.acquisition_date, visit_date)`
        // throws `column "visit_date" does not exist`. The duplicate
        // is the only safe way to keep both columns ordered by the
        // effective scan-time stamp.
        String sql = "SELECT j.job_id, j.task, j.eye_laterality, j.status, j.model_version, "
                + "       j.completed_at, "
                + "       date(se.date_start) AS visit_date, "
                + "       j.acquisition_date, "
                // 2026-06-24 user-feedback round — surface study_event_id so
                // the SPA can join into the BCVA timeline by event (the
                // BCVA values live on a sibling event_crf on the same
                // event).
                + "       se.study_event_id, "
                + "       r.primary_metric_value, r.primary_metric_unit "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = COALESCE(ec.study_event_id, j.study_event_id) "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + " WHERE se.study_subject_id = ? "
                + " ORDER BY COALESCE(j.acquisition_date, date(se.date_start)) ASC NULLS LAST, j.enqueued_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(summaryFromRow(rs));
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list retinal jobs for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list retinal jobs: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/bcva-timeline                      */
    /* 2026-06-24 user-feedback round — BCVA values per visit                 */
    /* ====================================================================== */

    /**
     * Per-subject BCVA timeline. Returns one row per study_event for
     * which the subject has at least one populated BCVA item. Each
     * row carries the per-eye trio {@code (decimal, partial, letters)};
     * which subset is populated depends on which BCVA preset the
     * study used (decimal preset → decimal + partial; legacy letters
     * preset → letters).
     *
     * <p>Backs the nAMD module's trend chart + Bericht history table:
     * the SPA converts decimal+partial → letters via the shared
     * {@code bcvaConversion.ts} utility when the row carries the
     * decimal-flavoured fields; the letters field is consumed
     * directly for legacy studies. The raw form (canonical
     * {@code 1,0p-2} / {@code 0,8+2}) is reconstructed SPA-side
     * for tooltip / audit display.
     */
    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/bcva-timeline",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listBcvaTimeline(@PathVariable("studySubjectId") int studySubjectId,
                                              HttpSession session) {
        ResponseEntity<?> denied = guardSession(session);
        if (denied != null) return denied;
        Integer subjectStudyId;
        try (Connection c = dataSource.getConnection()) {
            subjectStudyId = fetchStudyIdForStudySubject(c, studySubjectId);
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for subject: " + sqlEx.getMessage()));
        }
        if (subjectStudyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "study_subject " + studySubjectId + " not found"));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(subjectStudyId, session,
                "study_subject " + studySubjectId + " is outside your site visibility");
        if (visGuard != null) return visGuard;

        // Pivot in Java — one SELECT, group by study_event_id, fold
        // each (eye, oid) row into the per-eye trio.
        // 2026-06-24 — covers both OID families: SPA-side BCVA preset
        // (OD_BCVA_*, OS_BCVA_*) AND institutional Ophthalmology Visit
        // CRF (VA_O*_ETDRS / VA_O*_LOGMAR). A row may come from either
        // (or both, if a multi-section CRF has all of them).
        String sql = "SELECT se.study_event_id, "
                + "       date(se.date_start) AS event_date, "
                + "       i.name AS oid, "
                + "       idata.value AS value "
                + "  FROM item_data idata "
                + "  JOIN event_crf ec ON ec.event_crf_id = idata.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN item i ON i.item_id = idata.item_id "
                + " WHERE ec.study_subject_id = ? "
                + "   AND COALESCE(idata.deleted, false) = false "
                + "   AND idata.value IS NOT NULL AND idata.value <> '' "
                + "   AND i.name IN ('OD_BCVA_DECIMAL','OS_BCVA_DECIMAL', "
                + "                   'OD_BCVA_PARTIAL','OS_BCVA_PARTIAL', "
                + "                   'OD_BCVA_LETTERS','OS_BCVA_LETTERS', "
                + "                   'VA_OD_ETDRS','VA_OS_ETDRS', "
                + "                   'VA_OD_LOGMAR','VA_OS_LOGMAR') "
                + " ORDER BY se.date_start ASC, se.study_event_id ASC";
        // Per-event accumulator: { studyEventId → { eventDate, od:{}, os:{} } }
        Map<Integer, Map<String, Object>> byEvent = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sev = rs.getInt("study_event_id");
                    Map<String, Object> row = byEvent.computeIfAbsent(sev, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("studyEventId", k);
                        try {
                            java.sql.Date ed = rs.getDate("event_date");
                            m.put("eventDate", ed == null ? null : ed.toString());
                        } catch (SQLException ignored) {
                            m.put("eventDate", null);
                        }
                        Map<String, Object> od = new LinkedHashMap<>();
                        od.put("decimal", null); od.put("partial", null); od.put("letters", null);
                        Map<String, Object> os = new LinkedHashMap<>();
                        os.put("decimal", null); os.put("partial", null); os.put("letters", null);
                        m.put("od", od);
                        m.put("os", os);
                        return m;
                    });
                    String oid = rs.getString("oid");
                    String value = rs.getString("value");
                    // 2026-06-24 — both OID conventions encode the eye
                    // in a prefix: SPA-side uses `OD_*` / `OS_*`,
                    // institutional uses `VA_OD_*` / `VA_OS_*` (and
                    // `REFRACT_OD_*` / `REFRACT_OS_*`). Eye detection
                    // tolerates both.
                    String eyeKey = (oid.startsWith("OD_") || oid.contains("_OD_") || oid.startsWith("VA_OD") )
                            ? "od" : "os";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> eyeRow = (Map<String, Object>) row.get(eyeKey);
                    if (oid.endsWith("_DECIMAL")) {
                        eyeRow.put("decimal", parseDoubleOrNull(value));
                    } else if (oid.endsWith("_PARTIAL")) {
                        eyeRow.put("partial", parseIntOrNull(value));
                    } else if (oid.endsWith("_LETTERS") || oid.endsWith("_ETDRS")) {
                        eyeRow.put("letters", parseIntOrNull(value));
                    } else if (oid.endsWith("_LOGMAR")) {
                        // logMAR → decimal: decimal = 10^(-logMAR).
                        // Surfaces in the response only when there's no
                        // direct decimal write (a CRF-Decimal entry
                        // wins because it lands earlier in the loop).
                        Double logmar = parseDoubleOrNull(value);
                        if (logmar != null && eyeRow.get("decimal") == null) {
                            eyeRow.put("decimal", Math.pow(10.0, -logmar));
                        }
                    }
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list BCVA timeline for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list BCVA timeline: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(new ArrayList<>(byEvent.values()));
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return null; }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) {
            // Some legacy rows might store as a real; try a tolerant
            // path before giving up.
            Double d = parseDoubleOrNull(s);
            return d == null ? null : (int) Math.round(d);
        }
    }

    /* ====================================================================== */
    /* GET /study-subjects/{studySubjectId}/retinal-trends — Wave 2A          */
    /* ====================================================================== */

    /**
     * Permitted values of the {@code task} query parameter on the
     * trends endpoint. Mirrors the Wave 2 task discriminator —
     * {@code fluid} / {@code onl} / {@code pr} / {@code ga}. Anything
     * else returns 400.
     */
    private static final Set<String> TREND_TASKS = Set.of("fluid", "onl", "pr", "ga");

    /**
     * Wave 2A — longitudinal biomarker timeseries for a single subject,
     * scoped to one inference task. Backs the SPA's
     * BiomarkerTrendsChart embedded in SubjectRetinalTab.
     *
     * <p>Only {@code status='done'} jobs are returned (the SPA renders
     * one trend point per completed job, ordered by completion time);
     * incomplete / failed jobs are skipped to keep the chart axis
     * clean.
     *
     * <p>Site visibility re-uses the same path as the per-subject job
     * list — gate on the subject's owning study via
     * {@link #fetchStudyIdForStudySubject(Connection, int)} +
     * {@link #guardStudyVisibility(Integer, HttpSession, String)}.
     */
    @GetMapping(path = "/study-subjects/{studySubjectId:[0-9]+}/retinal-trends",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> trendsForSubject(@PathVariable("studySubjectId") int studySubjectId,
                                              @RequestParam("task") String task,
                                              HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (task == null || !TREND_TASKS.contains(task)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "task must be one of " + TREND_TASKS));
        }

        Integer studyId;
        try (Connection c = dataSource.getConnection()) {
            studyId = fetchStudyIdForStudySubject(c, studySubjectId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve study for study_subject {}: {}",
                    studySubjectId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve study for study_subject: " + sqlEx.getMessage()));
        }
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No study_subject with id " + studySubjectId));
        }
        ResponseEntity<?> visGuard = guardStudyVisibility(studyId, session,
                "study_subject " + studySubjectId + " belongs to a different study");
        if (visGuard != null) return visGuard;

        List<RetinalTrendsPointDto> out = new ArrayList<>();
        // 2026-06-23 — resolve study_subject via either the event_crf
        // chain or the direct study_event_id binding, mirroring the
        // listSubjectJobs fix. Planned-visit-bound jobs still need to
        // appear in the trends timeseries.
        //
        // visit_date = date(study_event.date_start) — the clinical
        // date the scan was acquired, used as the chart's X axis so a
        // batch of historical uploads doesn't collapse onto today.
        // Orders primarily by visit date so the trend reads left→right
        // chronologically by visit, regardless of upload order.
        String sql = "SELECT j.job_id, j.completed_at, j.eye_laterality, "
                + "       date(se.date_start) AS visit_date, "
                + "       r.primary_metric_value, r.primary_metric_unit, r.output_payload "
                + "  FROM retinal_inference_job j "
                + "  JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  JOIN study_event se ON se.study_event_id = COALESCE(ec.study_event_id, j.study_event_id) "
                + " WHERE se.study_subject_id = ? "
                + "   AND j.task = ? "
                + "   AND j.status = 'done' "
                + " ORDER BY visit_date ASC NULLS LAST, j.completed_at ASC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            ps.setString(2, task);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long jobId = rs.getLong("job_id");
                    Timestamp completedAt = rs.getTimestamp("completed_at");
                    java.sql.Date visitDate = rs.getDate("visit_date");
                    String laterality = rs.getString("eye_laterality");
                    BigDecimal value = rs.getBigDecimal("primary_metric_value");
                    String unit = rs.getString("primary_metric_unit");
                    Map<String, Object> payload = parsePayload(rs.getString("output_payload"));
                    out.add(new RetinalTrendsPointDto(
                            jobId, toIso(completedAt),
                            visitDate == null ? null : visitDate.toString(),
                            laterality,
                            value, unit, payload));
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list retinal trends for study_subject {} (task={}): {}",
                    studySubjectId, task, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list retinal trends: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}/artifacts/{name}                             */
    /* ====================================================================== */

    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}/artifacts/{name:.+}")
    public ResponseEntity<?> streamArtifact(@PathVariable("jobId") long jobId,
                                            @PathVariable("name") String name,
                                            HttpSession session,
                                            HttpServletResponse response) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (name == null || name.isBlank() || !SAFE_ARTIFACT_NAME.matcher(name).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Artifact name '" + name + "' contains disallowed characters"));
        }

        JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        Path target;
        boolean isCompanion = COMPANION_NAMES.contains(name);
        try {
            if (isCompanion) {
                String e2eUuid = e2eUuidFromPath(row.e2ePath);
                // 2026-06-19 — pass the job's scan_index so the
                // resolver looks under scan-N/ for multi-volume uploads
                // (preprocess sidecar layout change observed 2026-06-18).
                target = switch (name) {
                    case "bscan.dcm"     -> artifactStore.resolveBscanDcm(e2eUuid, row.scanIndex);
                    case "fundus.png"    -> artifactStore.resolveFundus(e2eUuid, row.scanIndex);
                    case "geometry.json" -> artifactStore.resolveGeometry(e2eUuid, row.scanIndex);
                    default -> throw new NoSuchFileException(name);
                };
            } else {
                if (row.bscanMasksDir == null || row.bscanMasksDir.isBlank()) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No segmentation directory for job " + jobId));
                }
                Path dir = Paths.get(row.bscanMasksDir).toAbsolutePath().normalize();
                Path resolved = dir.resolve(name).normalize();
                if (!resolved.startsWith(dir) || !Files.isRegularFile(resolved)) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No artifact '" + name + "' for job " + jobId));
                }
                target = resolved;
            }
        } catch (NoSuchFileException nfe) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No artifact '" + name + "' for job " + jobId));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", iae.getMessage()));
        } catch (IOException ioEx) {
            LOG.error("Failed to resolve artifact '{}' for job {}: {}",
                    name, jobId, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve artifact: " + ioEx.getMessage()));
        }

        // Stream the bytes directly to the response — the application's
        // configured HttpMessageConverter chain doesn't include
        // ResourceHttpMessageConverter, so a ResponseEntity<Resource> would
        // be picked up by Jackson and serialised as JSON (then fail on the
        // ChannelInputStream property). Direct streaming sidesteps the
        // converter selection entirely.
        MediaType mediaType = mediaTypeForName(name);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(mediaType.toString());
        long size;
        try {
            size = Files.size(target);
            response.setContentLengthLong(size);
        } catch (IOException sizeEx) {
            LOG.warn("Could not stat {} for Content-Length: {}", target, sizeEx.getMessage());
        }
        if ("fundus.png".equals(name) || "geometry.json".equals(name)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL,
                    CacheControl.maxAge(Duration.ofHours(1)).cachePrivate().getHeaderValue());
        }
        try {
            Files.copy(target, response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException copyEx) {
            LOG.error("Failed to stream artifact '{}' for job {}: {}",
                    name, jobId, copyEx.getMessage());
            // Headers were already sent — best we can do is abort the body.
        }
        return null;
    }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}/segmentation                                  */
    /*                                                                        */
    /* 2026-06-22 — task-agnostic segmentation envelope. The SPA's B-scan      */
    /* viewer no longer consumes per-slice PNGs; it fetches this one binary    */
    /* envelope and decodes it on a 2D canvas overlay. Shape + dtype + kind    */
    /* + labels travel as response headers so the client doesn't need to      */
    /* parse npy/csv per task — it just sees a typed byte array.              */
    /*                                                                        */
    /* Per-task kinds: fluid = "volume" uint8 (z, rows, cols); ga = binary_2d; */
    /* onl/pr = surface_y float32 (z, cols). Only fluid is wired in this     */
    /* push; ga/onl/pr surface 501 Not Implemented until their loaders land. */
    /* ====================================================================== */
    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}/segmentation")
    public ResponseEntity<?> streamSegmentation(@PathVariable("jobId") long jobId,
                                                HttpSession session,
                                                HttpServletResponse response) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        if (row.bscanMasksDir == null || row.bscanMasksDir.isBlank()) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No segmentation directory for job " + jobId));
        }
        Path dir = Paths.get(row.bscanMasksDir).toAbsolutePath().normalize();

        SegmentationEnvelopeLoader.SegmentationEnvelope env;
        try {
            env = SegmentationEnvelopeLoader.load(row.task, dir);
        } catch (IOException ioEx) {
            LOG.error("Failed to load segmentation envelope for job {} (task={}): {}",
                    jobId, row.task, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to load segmentation envelope: " + ioEx.getMessage()));
        }
        if (env == null) {
            return ResponseEntity.status(501).body(Map.of(
                    "message", "Segmentation envelope for task '" + row.task
                            + "' is not implemented yet"));
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("X-MUW-Seg-Kind", env.kind());
        response.setHeader("X-MUW-Seg-Dtype", env.dtype());
        response.setHeader("X-MUW-Seg-Shape", joinInts(env.shape()));
        response.setHeader("X-MUW-Seg-Task", env.task());
        if (env.labels() != null && !env.labels().isEmpty()) {
            response.setHeader("X-MUW-Seg-Labels", String.join(",", env.labels()));
        }
        // Expose the X-MUW-Seg-* headers to the SPA fetch — without
        // this CORS hides them client-side (same-origin in dev /
        // single-domain in prod, but the headers also need to be in
        // Access-Control-Expose-Headers when the SPA reads them via
        // fetch().headers.get(...)).
        response.setHeader("Access-Control-Expose-Headers",
                "X-MUW-Seg-Kind, X-MUW-Seg-Dtype, X-MUW-Seg-Shape, "
                        + "X-MUW-Seg-Labels, X-MUW-Seg-Task");
        response.setContentLengthLong(env.data().length);
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                CacheControl.maxAge(Duration.ofHours(1)).cachePrivate().getHeaderValue());
        try {
            response.getOutputStream().write(env.data());
            response.getOutputStream().flush();
        } catch (IOException copyEx) {
            LOG.error("Failed to stream segmentation envelope for job {}: {}",
                    jobId, copyEx.getMessage());
        }
        return null;
    }

    private static String joinInts(int[] dims) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dims.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(dims[i]);
        }
        return sb.toString();
    }

    /* ====================================================================== */
    /* PATCH /retinal-jobs/{jobId}/bind — Wave 1B park-bind                   */
    /* ====================================================================== */

    /**
     * Request body for {@link #bindParkedJob(long, BindRequest, HttpSession)}.
     */
    public record BindRequest(int eventCrfId) { }

    /**
     * Wave 1B — bind a previously parked retinal_inference_job (commit
     * via the public OCT-upload portal with no scheduled visit) to a
     * concrete event_crf. The job's status flips from {@code parked} to
     * {@code remote_pending} (when the remote GPU sidecar is
     * configured) or {@code queued} so the worker / sidecar picks it up
     * immediately.
     *
     * <p>409 Conflict when the job is not currently {@code parked}; 403
     * when the requested event_crf is outside the session user's
     * site-visibility scope; 404 when either the job or the event_crf
     * is missing.
     *
     * <p>One audit_log_event row of type
     * {@link AuditTypeIds#RETINAL_PARK_BIND} is emitted on success;
     * old_value carries {@code "parked"} and new_value carries the new
     * status so the timeline shows the bind + the transition in a
     * single row.
     *
     * <p>2026-06-20 (B2 bulk-bind): per-job logic moved into
     * {@link #performBind(long, EventCRFBean, StudySubjectBean, int,
     * UserAccountBean, StudyBean)} so {@link #bulkBindParkedJobs}
     * can reuse it. This endpoint preserves its single-bind contract
     * by translating the {@link BindOutcomeStatus} enum back into the
     * legacy 200 / 404 / 409 / 403 HTTP codes the SPA already handles.
     */
    @PatchMapping(path = "/retinal-jobs/{jobId:[0-9]+}/bind",
                  produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bindParkedJob(@PathVariable("jobId") long jobId,
                                           @RequestBody BindRequest body,
                                           HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (body == null || body.eventCrfId() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eventCrfId is required"));
        }
        int eventCrfId = body.eventCrfId();

        // ---- resolve event_crf + visibility once --------------------
        BindContext ctx;
        try {
            ctx = resolveBindContext(eventCrfId, session);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve bind context for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve event_crf: " + sqlEx.getMessage()));
        }
        if (ctx.notFound()) {
            return ResponseEntity.status(404).body(Map.of("message", ctx.errorMessage()));
        }
        if (ctx.forbidden()) {
            return ResponseEntity.status(403).body(Map.of("message", ctx.errorMessage()));
        }

        // ---- delegate per-job work to the shared helper -------------
        BindOutcome outcome = performBind(jobId, ctx.eventCrf(), ctx.studySubject(),
                eventCrfId, ctx.currentUser(), ctx.currentStudy());
        return switch (outcome.status()) {
            case BOUND -> ResponseEntity.ok(Map.of(
                    "jobId", jobId,
                    "status", outcome.newStatus()));
            case ALREADY_BOUND, INVALID_STATE ->
                    ResponseEntity.status(409).body(Map.of("message", outcome.message()));
            case FORBIDDEN ->
                    ResponseEntity.status(403).body(Map.of("message", outcome.message()));
            case NOT_FOUND ->
                    ResponseEntity.status(404).body(Map.of("message", outcome.message()));
            case ERROR ->
                    ResponseEntity.internalServerError().body(Map.of("message", outcome.message()));
        };
    }

    /* ====================================================================== */
    /* POST /retinal-jobs/bulk-bind — B2 bulk park-bind                       */
    /* ====================================================================== */

    /**
     * Request body for {@link #bulkBindParkedJobs(BulkBindRequest, HttpSession)}.
     * One {@code eventCrfId} is applied to every {@code jobId} in the
     * batch — the common case is one OCT upload session that emitted
     * multiple scans (OD + OS, or repeat acquisitions) that all bind to
     * the same visit.
     */
    public record BulkBindRequest(List<Long> jobIds, int eventCrfId) { }

    /**
     * Status of one job within a bulk-bind batch. Names mirror the SPA's
     * toast strings ({@code bulkSummaryBound} / {@code bulkSummaryAlreadyBound}
     * etc.) so the JSON keys are self-explanatory at the wire.
     */
    public enum BindOutcomeStatus {
        /** The job was parked, the bind succeeded, audit + broadcast fired. */
        BOUND,
        /**
         * The job already had an {@code event_crf_id} (either the same
         * target or a different one). Skipped — no audit emitted.
         */
        ALREADY_BOUND,
        /**
         * The event_crf isn't visible to the current operator. For
         * single-bind this is 403; for bulk we surface it per-row so a
         * mixed selection still returns the rows the operator CAN see.
         */
        FORBIDDEN,
        /**
         * The job is in a state that can never transition to bound
         * (e.g. {@code cancelled}). Distinct from {@link #ALREADY_BOUND}
         * so the operator knows a re-upload is required.
         */
        INVALID_STATE,
        /** No row with the supplied {@code jobId} exists. */
        NOT_FOUND,
        /** Internal DB error during the per-job transition. */
        ERROR;
    }

    /**
     * Internal per-job outcome carrier used by both endpoints.
     *
     * @param jobId      the job the outcome describes
     * @param status     classification, see {@link BindOutcomeStatus}
     * @param newStatus  on {@link BindOutcomeStatus#BOUND}, the new
     *                   {@code retinal_inference_job.status} ({@code queued}
     *                   or {@code remote_pending}); null otherwise
     * @param message    operator-facing reason for skip / failure; null
     *                   on {@link BindOutcomeStatus#BOUND}
     */
    public record BindOutcome(long jobId,
                              BindOutcomeStatus status,
                              String newStatus,
                              String message) { }

    /**
     * B2 bulk-bind: assign one event_crf to every job in {@code jobIds}.
     * Each row is processed in its own transaction — partial success is
     * normal (e.g. one of the four scans in an upload session was already
     * triaged by another operator).
     *
     * <p>Always returns 200 with a per-row results array + summary so
     * the SPA can render a single "3 zugewiesen, 1 bereits gebunden"
     * toast. The exception is input validation (400 on empty
     * {@code jobIds} or non-positive {@code eventCrfId}).
     *
     * <p>Authz mirrors {@link #bindParkedJob}: session-bound user, site
     * visibility on the target event_crf. When the event_crf is
     * out-of-scope the whole batch returns 200 with every row marked
     * {@link BindOutcomeStatus#FORBIDDEN} — the bulk endpoint is more
     * lenient than single-bind so a stale UI selection doesn't fail
     * the operator's whole queue.
     */
    @org.springframework.web.bind.annotation.PostMapping(
            path = "/retinal-jobs/bulk-bind",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bulkBindParkedJobs(@RequestBody BulkBindRequest body,
                                                HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (body == null || body.jobIds() == null || body.jobIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "jobIds must be a non-empty array"));
        }
        if (body.eventCrfId() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eventCrfId is required"));
        }
        for (Long id : body.jobIds()) {
            if (id == null || id <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "jobIds must contain positive integers"));
            }
        }
        int eventCrfId = body.eventCrfId();

        // ---- resolve event_crf + visibility once for the whole batch ----
        BindContext ctx;
        try {
            ctx = resolveBindContext(eventCrfId, session);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to resolve bulk-bind context for event_crf {}: {}",
                    eventCrfId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to resolve event_crf: " + sqlEx.getMessage()));
        }
        if (ctx.notFound()) {
            return ResponseEntity.status(404).body(Map.of("message", ctx.errorMessage()));
        }

        // Whole-batch FORBIDDEN: surface per-row so the SPA's summary
        // toast still renders. Single-bind keeps its 403 contract; bulk
        // is lenient by design (see endpoint javadoc).
        boolean batchForbidden = ctx.forbidden();

        // ---- loop per job ------------------------------------------
        List<BindOutcome> results = new ArrayList<>();
        int bound = 0, alreadyBound = 0, forbidden = 0, invalidState = 0, notFound = 0, error = 0;
        for (Long jobIdBoxed : body.jobIds()) {
            long jobId = jobIdBoxed;
            BindOutcome out;
            if (batchForbidden) {
                out = new BindOutcome(jobId, BindOutcomeStatus.FORBIDDEN,
                        null, ctx.errorMessage());
            } else {
                out = performBind(jobId, ctx.eventCrf(), ctx.studySubject(),
                        eventCrfId, ctx.currentUser(), ctx.currentStudy());
            }
            results.add(out);
            switch (out.status()) {
                case BOUND -> bound++;
                case ALREADY_BOUND -> alreadyBound++;
                case FORBIDDEN -> forbidden++;
                case INVALID_STATE -> invalidState++;
                case NOT_FOUND -> notFound++;
                case ERROR -> error++;
            }
        }

        // Marshal — keep response shape stable per the spec.
        List<Map<String, Object>> rows = new ArrayList<>(results.size());
        for (BindOutcome o : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", o.jobId());
            row.put("status", o.status().name());
            if (o.newStatus() != null) row.put("newStatus", o.newStatus());
            if (o.message() != null) row.put("message", o.message());
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("bound", bound);
        summary.put("alreadyBound", alreadyBound);
        summary.put("forbidden", forbidden);
        summary.put("invalidState", invalidState);
        summary.put("notFound", notFound);
        summary.put("error", error);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", rows);
        response.put("summary", summary);
        LOG.info("Retinal bulk-bind: event_crf {} got {} jobs ({} bound, {} already, {} forbidden, "
                        + "{} invalid, {} missing, {} errored)",
                eventCrfId, body.jobIds().size(), bound, alreadyBound, forbidden,
                invalidState, notFound, error);
        return ResponseEntity.ok(response);
    }

    /* ====================================================================== */
    /* shared bind helpers                                                    */
    /* ====================================================================== */

    /**
     * Pre-resolved per-batch context for the bind helpers: the loaded
     * event_crf + study_subject + the operator's identity. Built once
     * by {@link #resolveBindContext(int, HttpSession)} and threaded
     * through every per-job {@link #performBind} call so the same
     * lookups don't repeat per row.
     *
     * <p>{@code errorMessage} is populated only when {@code notFound} or
     * {@code forbidden} is set; on the happy path it's null.
     */
    private record BindContext(EventCRFBean eventCrf,
                               StudySubjectBean studySubject,
                               UserAccountBean currentUser,
                               StudyBean currentStudy,
                               boolean notFound,
                               boolean forbidden,
                               String errorMessage) {
        static BindContext notFound(String message) {
            return new BindContext(null, null, null, null, true, false, message);
        }
        static BindContext forbidden(String message,
                                     EventCRFBean ecb,
                                     StudySubjectBean ss,
                                     UserAccountBean user,
                                     StudyBean study) {
            return new BindContext(ecb, ss, user, study, false, true, message);
        }
        static BindContext ok(EventCRFBean ecb,
                              StudySubjectBean ss,
                              UserAccountBean user,
                              StudyBean study) {
            return new BindContext(ecb, ss, user, study, false, false, null);
        }
    }

    /**
     * Resolve the event_crf + study chain + the session operator and
     * decide whether the operator can bind anything to this event_crf.
     * Shared by single-bind and bulk-bind so the lookup runs once even
     * when the bulk request carries 30 jobs.
     */
    private BindContext resolveBindContext(int eventCrfId, HttpSession session) throws SQLException {
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return BindContext.notFound("No event_crf with id " + eventCrfId);
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        if (ss == null || ss.getStudyId() == 0) {
            return BindContext.notFound(
                    "event_crf " + eventCrfId + " has no resolvable study");
        }
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return BindContext.forbidden(
                    "event_crf " + eventCrfId + " belongs to a different study",
                    ecb, ss, currentUser, currentStudy);
        }
        return BindContext.ok(ecb, ss, currentUser, currentStudy);
    }

    /**
     * Per-job bind: load the {@code retinal_inference_job} row, validate
     * its state, flip status + event_crf, emit audit, broadcast, and
     * (when the remote sidecar is configured) hand off to
     * {@link RetinalInferenceApiController#handleRemote}.
     *
     * <p>Each call runs in its own short-lived JDBC transaction (the
     * single UPDATE auto-commits) so a 30-job bulk-bind never holds a
     * long-running lock. Soft-fails for the remote dispatch are
     * intentional — the row already carries {@code remote_pending} and
     * the operator can re-trigger from the SPA's retry affordance.
     */
    private BindOutcome performBind(long jobId,
                                    EventCRFBean ecb,
                                    StudySubjectBean ss,
                                    int eventCrfId,
                                    UserAccountBean currentUser,
                                    StudyBean currentStudy) {
        // ---- load the parked job ------------------------------------
        ParkedJob job;
        try (Connection c = dataSource.getConnection()) {
            job = fetchParkedJob(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {} for bind: {}",
                    jobId, sqlEx.getMessage());
            return new BindOutcome(jobId, BindOutcomeStatus.ERROR, null,
                    "Failed to fetch retinal job: " + sqlEx.getMessage());
        }
        if (job == null) {
            return new BindOutcome(jobId, BindOutcomeStatus.NOT_FOUND, null,
                    "No retinal_inference_job with id " + jobId);
        }
        if ("cancelled".equals(job.status)) {
            return new BindOutcome(jobId, BindOutcomeStatus.INVALID_STATE, null,
                    "Job is cancelled and cannot be bound (jobId=" + jobId + ")");
        }
        if (!"parked".equals(job.status)) {
            return new BindOutcome(jobId, BindOutcomeStatus.ALREADY_BOUND, null,
                    "Job is not parked (status=" + job.status + ")");
        }

        // ---- flip the job ------------------------------------------
        String newStatus = (remoteClient != null && remoteClient.isConfigured())
                ? "remote_pending"
                : "queued";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE retinal_inference_job "
                             + "   SET event_crf_id = ?, status = ?, "
                             + "       screened_at = NULL, segmenting_at = NULL "
                             + " WHERE job_id = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, newStatus);
            ps.setLong(3, jobId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return new BindOutcome(jobId, BindOutcomeStatus.NOT_FOUND, null,
                        "No retinal_inference_job with id " + jobId);
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to bind retinal job {} to event_crf {}: {}",
                    jobId, eventCrfId, sqlEx.getMessage());
            return new BindOutcome(jobId, BindOutcomeStatus.ERROR, null,
                    "Failed to bind retinal job: " + sqlEx.getMessage());
        }

        // ---- emit audit + broadcast --------------------------------
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_PARK_BIND,
                currentUser, currentStudy, ss,
                "Retinal job park-bind — eventCrfId=" + eventCrfId,
                /* auditTable */ "retinal_inference_job",
                /* entityId   */ (int) jobId,
                /* columnName */ "status",
                /* oldValue   */ "parked",
                /* newValue   */ newStatus);

        if (broadcaster != null) {
            broadcaster.publish(jobId, newStatus);
        }

        // ---- post-bind remote dispatch (soft-fail) ------------------
        String finalStatus = newStatus;
        if ("remote_pending".equals(newStatus) && inferenceController != null) {
            JobRunHandle handle = fetchJobRunHandle(jobId);
            if (handle != null) {
                try {
                    inferenceController.handleRemote(
                            jobId, handle.task, handle.e2ePath, handle.eyeLaterality,
                            handle.scanIndex, eventCrfId);
                } catch (Exception remoteEx) {
                    LOG.warn("Remote dispatch threw for bound job {}: {}", jobId, remoteEx.getMessage());
                }
                finalStatus = readJobStatus(jobId, newStatus);
            }
        }

        LOG.info("Retinal park-bind: job {} -> event_crf {} (status={})",
                jobId, eventCrfId, finalStatus);
        return new BindOutcome(jobId, BindOutcomeStatus.BOUND, finalStatus, null);
    }

    /* ====================================================================== */
    /* POST /retinal-jobs/{jobId}/retry — operator re-dispatch of failed job  */
    /* ====================================================================== */

    /**
     * Re-dispatch a {@code failed} retinal_inference_job to the remote GPU
     * sidecar without re-uploading the .e2e. Resets the row to
     * {@code remote_pending} (clears status_message, completed_at,
     * screened_at, segmenting_at) and fires
     * {@link RetinalInferenceApiController#handleRemote} on a background
     * thread so the SPA returns immediately; the SSE channel surfaces the
     * post-dispatch status.
     *
     * <p>409 Conflict when the job is not currently {@code failed}; 403
     * when its event_crf is outside the session user's site-visibility
     * scope; 404 when the job has no event_crf (parked jobs use the
     * separate {@code /bind} endpoint) or the row is missing.
     *
     * <p>One audit_log_event row of type
     * {@link AuditTypeIds#RETINAL_JOB_RETRY} is emitted on success;
     * old_value carries the prior status_message (truncated to 500
     * chars), new_value carries {@code "remote_pending"}.
     */
    @org.springframework.web.bind.annotation.PostMapping(
            path = "/retinal-jobs/{jobId:[0-9]+}/retry",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retryJob(@PathVariable("jobId") long jobId,
                                      HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        // ---- load the failed job + its run handle in one shot --------
        FailedJob job;
        try (Connection c = dataSource.getConnection()) {
            job = fetchFailedJob(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {} for retry: {}",
                    jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        // 2026-06-23 — retry accepts BOTH 'failed' and 'remote_pending'.
        // The latter unsticks jobs whose initial dispatch was skipped
        // (e.g. planned-visit binding before the dispatch gate was
        // relaxed) — same end state regardless of which way they got
        // there, same fix.
        if (!"failed".equals(job.status) && !"remote_pending".equals(job.status)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Job is not failed or remote_pending (status=" + job.status + ")"));
        }
        // ---- visibility check via the job's binding ---------------
        // 2026-06-23 — accept either binding path. Planned-visit jobs
        // have event_crf_id=null + a valid study_event_id; resolve the
        // owning study_subject via whichever is set.
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = null;
        if (job.eventCrfId != null) {
            EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
            EventCRFBean ecb = eventCrfDAO.findByPK(job.eventCrfId);
            if (ecb == null || ecb.getId() == 0) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No event_crf with id " + job.eventCrfId));
            }
            ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        } else if (job.studyEventId != null) {
            Integer subjectId = fetchStudySubjectIdForStudyEvent(job.studyEventId);
            if (subjectId == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No study_event with id " + job.studyEventId));
            }
            ss = (StudySubjectBean) ssDAO.findByPK(subjectId);
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Job " + jobId + " has no event_crf nor study_event — use /bind instead"));
        }
        if (ss == null || ss.getStudyId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Job " + jobId + " has no resolvable study"));
        }
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Job " + jobId + " belongs to a different study"));
        }
        if (remoteClient == null || !remoteClient.isConfigured()) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Remote GPU sidecar not configured — retry unavailable"));
        }
        if (inferenceController == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Retry temporarily unavailable"));
        }

        // ---- reset the row + emit audit + broadcast ------------------
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE retinal_inference_job "
                             + "   SET status = 'remote_pending', "
                             + "       status_message = NULL, "
                             + "       completed_at = NULL, "
                             + "       screened_at = NULL, "
                             + "       segmenting_at = NULL "
                             + " WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No retinal_inference_job with id " + jobId));
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to reset retinal job {} for retry: {}",
                    jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to reset retinal job: " + sqlEx.getMessage()));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        String priorMessage = job.statusMessage != null && job.statusMessage.length() > 500
                ? job.statusMessage.substring(0, 500)
                : (job.statusMessage != null ? job.statusMessage : "");
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_JOB_RETRY,
                currentUser, currentStudy, ss,
                "Retinal job retry — manual re-dispatch from `failed`",
                /* auditTable */ "retinal_inference_job",
                /* entityId   */ (int) jobId,
                /* columnName */ "status",
                /* oldValue   */ priorMessage,
                /* newValue   */ "remote_pending");

        if (broadcaster != null) {
            broadcaster.publish(jobId, "remote_pending");
        }

        // ---- fire handleRemote async; SSE surfaces the final state --
        final FailedJob handle = job;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                inferenceController.handleRemote(
                        jobId, handle.task, handle.e2ePath, handle.eyeLaterality,
                        handle.scanIndex, handle.eventCrfId);
            } catch (Exception remoteEx) {
                LOG.warn("Remote dispatch threw for retry of job {}: {}",
                        jobId, remoteEx.getMessage());
            }
        });

        LOG.info("Retinal retry: job {} reset to remote_pending (was failed: {})",
                jobId, priorMessage.isEmpty() ? "(no prior message)" : priorMessage);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobId", jobId);
        resp.put("status", "remote_pending");
        return ResponseEntity.accepted().body(resp);
    }

    /* ====================================================================== */
    /* POST /retinal-jobs/{jobId}/rerun-as — re-dispatch as a different task */
    /* ====================================================================== */

    /** Tasks the operator can pick from the rerun-as dropdown. Mirrors the
     *  runner profiles + the FUNDUS overlay's recognised task discriminator. */
    private static final java.util.Set<String> ALLOWED_RERUN_TASKS =
            java.util.Set.of("fluid", "ga", "onl", "pr");

    /**
     * Re-dispatch the same uploaded .e2e (referenced by {@code sourceJobId})
     * as a DIFFERENT inference task. Inserts a NEW {@code retinal_inference_job}
     * row scoped to the new task — the original job + its results stay intact
     * so the audit trail records each task as its own first-class run.
     *
     * <p>The new row reuses {@code event_crf_id}, {@code e2e_path},
     * {@code e2e_sha256}, {@code eye_laterality}, and {@code scan_index} from
     * the source. The unique key on
     * {@code (e2e_sha256, scan_index, task)} (widened 2026-06-22) keeps the
     * (scan slot × task) pair from being duplicated; if a job for the
     * requested task already exists on the same scan, returns 409 with the
     * existing job_id so the SPA can navigate the operator there.
     *
     * <p>Body: {@code {"task": "ga"}} (or fluid / onl / pr).
     * Response: {@code {"jobId": <new>, "task": "<task>", "status": "remote_pending"}}.
     */
    @org.springframework.web.bind.annotation.PostMapping(
            path = "/retinal-jobs/{jobId:[0-9]+}/rerun-as",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rerunAs(@PathVariable("jobId") long sourceJobId,
                                     @RequestBody Map<String, String> body,
                                     HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        String newTask = body == null ? null : body.get("task");
        if (newTask != null) newTask = newTask.trim().toLowerCase(java.util.Locale.ROOT);
        if (newTask == null || !ALLOWED_RERUN_TASKS.contains(newTask)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "task must be one of " + ALLOWED_RERUN_TASKS));
        }

        // ---- load the source job's immutable metadata ----------------
        FailedJob source;
        String sourceSha256;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, status_message, event_crf_id, task, "
                             + "       e2e_path, eye_laterality, scan_index, e2e_sha256 "
                             + "  FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, sourceJobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of(
                            "message", "No retinal_inference_job with id " + sourceJobId));
                }
                source = new FailedJob();
                source.status = rs.getString("status");
                source.statusMessage = rs.getString("status_message");
                int ecId = rs.getInt("event_crf_id");
                source.eventCrfId = rs.wasNull() ? null : ecId;
                source.task = rs.getString("task");
                source.e2ePath = rs.getString("e2e_path");
                source.eyeLaterality = rs.getString("eye_laterality");
                source.scanIndex = rs.getInt("scan_index");
                sourceSha256 = rs.getString("e2e_sha256");
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch source retinal job {} for rerun-as: {}",
                    sourceJobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch source job: " + sqlEx.getMessage()));
        }

        if (source.eventCrfId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Source job " + sourceJobId + " has no event_crf — "
                            + "park it to a visit first via /bind"));
        }
        if (newTask.equals(source.task)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Source job is already task=" + newTask
                            + "; use /retry to re-dispatch the same task"));
        }
        if (sourceSha256 == null || sourceSha256.isBlank()) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Source job is missing e2e_sha256 — predates the "
                            + "dedup gate; cannot rerun-as safely"));
        }

        // ---- visibility check via the source job's event_crf --------
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(source.eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No event_crf with id " + source.eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        if (ss == null || ss.getStudyId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "event_crf " + source.eventCrfId + " has no resolvable study"));
        }
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Source job " + sourceJobId + " belongs to a different study"));
        }
        if (remoteClient == null || !remoteClient.isConfigured()) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Remote GPU sidecar not configured — rerun-as unavailable"));
        }
        if (inferenceController == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Rerun-as temporarily unavailable"));
        }

        // ---- dedup gate: prefer surfacing an existing twin row over a 500 -
        long existingTwinJobId = -1;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT job_id FROM retinal_inference_job "
                             + "WHERE e2e_sha256 = ? AND scan_index = ? AND task = ?")) {
            ps.setString(1, sourceSha256);
            ps.setInt(2, source.scanIndex);
            ps.setString(3, newTask);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) existingTwinJobId = rs.getLong(1);
            }
        } catch (SQLException sqlEx) {
            // Non-fatal — fall through to the INSERT and let the unique
            // constraint catch any race.
            LOG.warn("rerun-as dedup probe failed for job {}: {}", sourceJobId, sqlEx.getMessage());
        }
        if (existingTwinJobId > 0) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "A job already exists for this scan + task — "
                            + "navigate there instead",
                    "existingJobId", existingTwinJobId));
        }

        // ---- insert the new job row ---------------------------------
        long newJobId;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job ("
                             + "event_crf_id, task, e2e_path, eye_laterality, status, "
                             + "scan_index, enqueued_at, e2e_sha256"
                             + ") VALUES (?, ?, ?, ?, 'remote_pending', ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, source.eventCrfId);
            ps.setString(2, newTask);
            ps.setString(3, source.e2ePath);
            ps.setString(4, source.eyeLaterality);
            ps.setInt(5, source.scanIndex);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setString(7, sourceSha256);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("rerun-as INSERT returned no PK");
                }
                newJobId = keys.getLong(1);
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to insert rerun-as job (source={}, task={}): {}",
                    sourceJobId, newTask, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to enqueue rerun-as job: " + sqlEx.getMessage()));
        }

        // ---- audit (reuses the RETRY type id — the action shape is
        //      the same: operator-initiated re-dispatch of an existing
        //      scan slot) -------------------------------------------
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_JOB_RETRY,
                currentUser, currentStudy, ss,
                "Retinal job rerun-as " + newTask + " (source job " + sourceJobId + ")",
                /* auditTable */ "retinal_inference_job",
                /* entityId   */ (int) newJobId,
                /* columnName */ "task",
                /* oldValue   */ source.task,
                /* newValue   */ newTask);

        if (broadcaster != null) {
            broadcaster.publish(newJobId, "remote_pending");
        }

        // ---- fire handleRemote async; SSE surfaces the final state --
        final String taskForDispatch = newTask;
        final Integer ecfId = source.eventCrfId;
        final String e2ePath = source.e2ePath;
        final String lat = source.eyeLaterality;
        final int scanIdx = source.scanIndex;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                inferenceController.handleRemote(
                        newJobId, taskForDispatch, e2ePath, lat, scanIdx, ecfId);
            } catch (Exception remoteEx) {
                LOG.warn("Remote dispatch threw for rerun-as job {}: {}",
                        newJobId, remoteEx.getMessage());
            }
        });

        LOG.info("Retinal rerun-as: source={} → new job {} (task {}→{})",
                sourceJobId, newJobId, source.task, newTask);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobId", newJobId);
        resp.put("task", newTask);
        resp.put("status", "remote_pending");
        return ResponseEntity.accepted().body(resp);
    }

    /** Slim row carrier for the failed-job retry handoff. */
    private static final class FailedJob {
        String status;
        String statusMessage;
        Integer eventCrfId;
        Integer studyEventId;
        String task;
        String e2ePath;
        String eyeLaterality;
        int scanIndex;
    }

    /**
     * Read the columns {@code retryJob} needs in one SELECT: current
     * status + status_message (for the audit row), plus the immutable
     * upload metadata {@code handleRemote} dispatches on. Returns
     * {@code null} if the row is missing.
     */
    private FailedJob fetchFailedJob(Connection c, long jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status, status_message, event_crf_id, study_event_id, "
                        + "       task, e2e_path, eye_laterality, scan_index "
                        + "  FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                FailedJob f = new FailedJob();
                f.status = rs.getString("status");
                f.statusMessage = rs.getString("status_message");
                int ecId = rs.getInt("event_crf_id");
                f.eventCrfId = rs.wasNull() ? null : ecId;
                int seId = rs.getInt("study_event_id");
                f.studyEventId = rs.wasNull() ? null : seId;
                f.task = rs.getString("task");
                f.e2ePath = rs.getString("e2e_path");
                f.eyeLaterality = rs.getString("eye_laterality");
                f.scanIndex = rs.getInt("scan_index");
                return f;
            }
        }
    }

    /** Slim row carrier for the bind→remote handoff. */
    private static final class JobRunHandle {
        String task;
        String e2ePath;
        String eyeLaterality;
        int scanIndex;
    }

    /**
     * Read the columns {@code handleRemote} needs to dispatch the job
     * to the GPU sidecar. The bind UPDATE has already flipped the row
     * to {@code remote_pending}; this lookup just pulls the immutable
     * upload metadata. Returns {@code null} if the row vanished
     * mid-flight — defensive but unreachable from the normal path.
     */
    private JobRunHandle fetchJobRunHandle(long jobId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT task, e2e_path, eye_laterality, scan_index "
                             + "FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JobRunHandle h = new JobRunHandle();
                h.task = rs.getString("task");
                h.e2ePath = rs.getString("e2e_path");
                h.eyeLaterality = rs.getString("eye_laterality");
                h.scanIndex = rs.getInt("scan_index");
                return h;
            }
        } catch (SQLException sqlEx) {
            LOG.warn("Failed to fetch run-handle for bound job {}: {}", jobId, sqlEx.getMessage());
            return null;
        }
    }

    /**
     * Re-read {@code retinal_inference_job.status} after the dispatch
     * so the bind response reflects the post-{@code handleRemote}
     * terminal state ({@code done} on success, {@code queued} on remote
     * failure, or unchanged {@code remote_pending} if the dispatch
     * couldn't fire). Falls back to the supplied default on read error.
     */
    private String readJobStatus(long jobId, String fallback) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("status");
            }
        } catch (SQLException ignored) { /* best-effort */ }
        return fallback;
    }

    /* ====================================================================== */
    /* GET /study-subjects/search — Wave 1B patient search                    */
    /* ====================================================================== */

    /**
     * Wave 1B — staff-portal label prefix search. Backs the Wave 2B
     * "Patient suchen" modal. Always filtered to the session user's
     * site-visibility scope; never leaks subjects from studies the
     * user can't see.
     *
     * @param q     label prefix (case-insensitive); blank → empty list
     * @param limit hard ceiling on rows returned; clamped to [1, 50]
     */
    @GetMapping(path = "/study-subjects/search",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchSubjects(@RequestParam("q") String q,
                                            @RequestParam(value = "limit", defaultValue = "10") int limit,
                                            HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        if (studySubjectFinder == null) {
            // Defensive: legacy 3-arg ctor (read-only IT path). The
            // production wiring always populates the finder.
            return ResponseEntity.ok(List.of());
        }

        int clamped = Math.max(1, Math.min(50, limit));
        String prefix = (q == null) ? "" : q.trim();
        if (prefix.isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);

        List<StudySubjectMatch> matches = studySubjectFinder.findByLabelPrefix(prefix, clamped);
        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (StudySubjectMatch m : matches) {
            if (!visibleStudyIds.contains(m.studyId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studySubjectId", m.studySubjectId());
            row.put("label", m.subjectLabel());
            row.put("studyId", m.studyId());
            row.put("studyName", m.studyName());
            row.put("siteName", m.siteName());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* GET /retinal-jobs?status=PARKED — cross-study admin browser            */
    /* ====================================================================== */

    /**
     * Cross-study list of parked retinal-inference jobs awaiting a bind.
     *
     * <p>Parked jobs have {@code event_crf_id IS NULL} (see migration
     * {@code lc-muw-2026-06-18-retinal-job-event-crf-nullable.xml}) and
     * therefore no transitive path to a study_subject; the per-subject
     * {@link #listByStudySubject(int, HttpSession) /study-subjects/{id}/retinal-jobs}
     * endpoint cannot surface them. This endpoint backs the
     * Administrator-only "Geparkte Scans" admin view that operators use
     * to triage public-portal uploads that never resolved.
     *
     * <p>Patient metadata is recovered from the
     * {@code audit_log_event_type_id = OCT_UPLOAD_PUBLIC (115)} row
     * emitted at park-commit time — {@code old_value} carries
     * {@code patientId=…[;laterality=…][;studySubjectId=…]}.
     *
     * <p>Role gate: sysadmin only. Mirrors the
     * {@code /system/audit-log} convention used elsewhere in the
     * MUW build — the single sysadmin role is responsible for cross-
     * study cleanup. Widen to Data Manager if institutional policy
     * later splits the responsibility.
     *
     * @param status MUST equal {@code "PARKED"} (case-insensitive) —
     *               the endpoint is scoped to that one filter today.
     *               Any other value returns 400.
     */
    @GetMapping(path = "/retinal-jobs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listParkedJobs(@RequestParam(value = "status",
                                                          defaultValue = "PARKED") String status,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!currentUser.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Parked-jobs admin view is sysadmin-only"));
        }
        if (status == null || !"PARKED".equalsIgnoreCase(status.trim())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "status must be PARKED (only filter supported)"));
        }

        List<ParkedJobAdminDto> out = new ArrayList<>();
        String sql = "SELECT j.job_id, j.task, j.eye_laterality, j.enqueued_at, "
                + "       ( SELECT a.old_value "
                + "           FROM audit_log_event a "
                + "          WHERE a.audit_table = 'retinal_inference_job' "
                + "            AND a.entity_id = j.job_id::integer "
                + "            AND a.audit_log_event_type_id = ? "
                + "          ORDER BY a.audit_date DESC "
                + "          LIMIT 1 ) AS audit_meta "
                + "  FROM retinal_inference_job j "
                + " WHERE j.status = 'parked' "
                + " ORDER BY j.enqueued_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, AuditTypeIds.OCT_UPLOAD_PUBLIC);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> meta = parseAuditMeta(rs.getString("audit_meta"));
                    Integer candidate = null;
                    String csid = meta.get("studySubjectId");
                    if (csid != null) {
                        try { candidate = Integer.valueOf(csid); }
                        catch (NumberFormatException ignored) { /* leave null */ }
                    }
                    out.add(new ParkedJobAdminDto(
                            rs.getLong("job_id"),
                            rs.getString("task"),
                            meta.get("patientId"),
                            rs.getString("eye_laterality"),
                            toIso(rs.getTimestamp("enqueued_at")),
                            candidate));
                }
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to list parked retinal jobs: {}", sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list parked retinal jobs: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * One row in the {@link #listParkedJobs} response.
     *
     * @param jobId                 the {@code retinal_inference_job.job_id}; required
     * @param task                  the inference task tag (e.g. {@code fluid}, {@code onl}); may be empty for legacy rows
     * @param patientId             parsed PatientId from the OCT-UPLOAD audit row's
     *                              {@code old_value}; may be null when the audit row is missing
     * @param laterality            {@code OD} / {@code OS}; mirrors the job-row column
     * @param enqueuedAt            ISO-8601 timestamp when the job was committed as parked
     * @param candidateStudySubjectId study_subject_id the resolve picked at upload time, when known —
     *                                only present for parks that came from {@code novisit}
     *                                or {@code ambiguous} states; null for true {@code nopatient} parks
     */
    public record ParkedJobAdminDto(long jobId,
                                    String task,
                                    String patientId,
                                    String laterality,
                                    String enqueuedAt,
                                    Integer candidateStudySubjectId) { }

    /**
     * Parse the {@code old_value} string from an OCT-UPLOAD audit row into
     * its key=value pairs. Defensive against missing rows and malformed
     * values — anything that doesn't match {@code key=value} is skipped,
     * an empty input returns an empty map.
     */
    private static Map<String, String> parseAuditMeta(String oldValue) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (oldValue == null || oldValue.isBlank()) return meta;
        for (String token : oldValue.split(";")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) continue;
            meta.put(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
        }
        return meta;
    }

    /* ====================================================================== */
    /* helpers                                                                */
    /* ====================================================================== */

    /** Inline state-only row carrier — bridges JDBC ResultSet to DTO assembly. */
    private static final class JobRow {
        long jobId;
        int eventCrfId;
        String task;
        String e2ePath;
        String eyeLaterality;
        String status;
        Timestamp enqueuedAt;
        Timestamp completedAt;
        String modelVersion;
        // result-side (nullable for jobs without a result row)
        String outputPayloadJson;
        BigDecimal primaryMetricValue;
        String primaryMetricUnit;
        String bscanMasksDir;
        Double confidence;
        // visibility — derived via the event_crf → study_event → study_subject chain
        Integer studyId;
        // 2026-06-19 — scan_index from retinal_inference_job. Needed by
        // the artifact resolver to pick the right scan-N/ subdirectory
        // for multi-volume .e2e uploads.
        int scanIndex;
    }

    /** Slim row used by the bind endpoint — only the bits the flip needs. */
    private static final class ParkedJob {
        long jobId;
        String status;
    }

    private ParkedJob fetchParkedJob(Connection c, long jobId) throws SQLException {
        String sql = "SELECT job_id, status FROM retinal_inference_job WHERE job_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ParkedJob job = new ParkedJob();
                job.jobId = rs.getLong("job_id");
                job.status = rs.getString("status");
                return job;
            }
        }
    }

    private JobRow fetchJobDetail(Connection c, long jobId) throws SQLException {
        // 2026-06-23 — study_event lookup now uses COALESCE on the
        // two binding paths so planned-visit-bound jobs (no event_crf
        // yet) still resolve a studyId. Without this the visibility
        // guard rejects the job with "belongs to a different study"
        // because studyId comes back null.
        String sql = "SELECT j.job_id, j.event_crf_id, j.task, j.e2e_path, "
                + "       j.eye_laterality, j.status, j.enqueued_at, j.completed_at, j.model_version, "
                + "       j.scan_index, "
                + "       r.output_payload, r.primary_metric_value, r.primary_metric_unit, "
                + "       r.bscan_masks_dir, r.confidence, ss.study_id "
                + "  FROM retinal_inference_job j "
                + "  LEFT JOIN retinal_inference_result r ON r.job_id = j.job_id "
                + "  LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "  LEFT JOIN study_event se ON se.study_event_id = COALESCE(ec.study_event_id, j.study_event_id) "
                + "  LEFT JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + " WHERE j.job_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JobRow row = new JobRow();
                row.jobId        = rs.getLong("job_id");
                row.eventCrfId   = rs.getInt("event_crf_id");
                row.task         = rs.getString("task");
                row.e2ePath      = rs.getString("e2e_path");
                row.eyeLaterality= rs.getString("eye_laterality");
                row.status       = rs.getString("status");
                row.enqueuedAt   = rs.getTimestamp("enqueued_at");
                row.completedAt  = rs.getTimestamp("completed_at");
                row.modelVersion = rs.getString("model_version");
                row.outputPayloadJson = rs.getString("output_payload");
                row.primaryMetricValue = rs.getBigDecimal("primary_metric_value");
                row.primaryMetricUnit  = rs.getString("primary_metric_unit");
                row.bscanMasksDir      = rs.getString("bscan_masks_dir");
                double cf = rs.getDouble("confidence");
                row.confidence = rs.wasNull() ? null : cf;
                int sid = rs.getInt("study_id");
                row.studyId = rs.wasNull() ? null : sid;
                row.scanIndex = rs.getInt("scan_index");
                return row;
            }
        }
    }

    private Integer fetchStudyIdForEventCrf(Connection c, int eventCrfId) throws SQLException {
        String sql = "SELECT ss.study_id "
                + "  FROM event_crf ec "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + " WHERE ec.event_crf_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sid = rs.getInt("study_id");
                return rs.wasNull() ? null : sid;
            }
        }
    }

    /**
     * 2026-06-23 — resolve the owning study_subject_id for the
     * supplied study_event. Used by the retry path when a job was
     * bound to a planned visit (event_crf_id null) so we can run the
     * visibility check + the audit row through the same subject
     * lookup the event_crf path uses.
     */
    private Integer fetchStudySubjectIdForStudyEvent(int studyEventId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_subject_id FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            LOG.warn("fetchStudySubjectIdForStudyEvent({}) failed: {}", studyEventId, e.getMessage());
            return null;
        }
    }

    private Integer fetchStudyIdForStudySubject(Connection c, int studySubjectId) throws SQLException {
        String sql = "SELECT study_id FROM study_subject WHERE study_subject_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sid = rs.getInt("study_id");
                return rs.wasNull() ? null : sid;
            }
        }
    }

    private RetinalJobSummaryDto summaryFromRow(ResultSet rs) throws SQLException {
        long jobId = rs.getLong("job_id");
        String task = rs.getString("task");
        String laterality = rs.getString("eye_laterality");
        String status = rs.getString("status");
        String modelVersion = rs.getString("model_version");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        BigDecimal pv = rs.getBigDecimal("primary_metric_value");
        String pu = rs.getString("primary_metric_unit");
        // 2026-06-23 — visit_date is optional in the SELECT (the
        // event_crf-scoped query at /event-crfs/{id}/retinal-jobs
        // doesn't join study_event); ResultSetMetaData lookup avoids
        // throwing for callers that didn't add the column.
        String visitDate = null;
        try {
            java.sql.Date vd = rs.getDate("visit_date");
            if (vd != null) visitDate = vd.toString();
        } catch (SQLException ignoredColumnAbsent) {
            // visit_date column not in this query — leave null.
        }
        // 2026-06-23 user-feedback round — acquisition_date is on
        // every job row but the legacy event-CRF-scoped query may
        // not project it; same defensive lookup pattern as visit_date.
        String acquisitionDate = null;
        try {
            java.sql.Date ad = rs.getDate("acquisition_date");
            if (ad != null) acquisitionDate = ad.toString();
        } catch (SQLException ignoredColumnAbsent) {
            // acquisition_date column not in this query — leave null.
        }
        // 2026-06-24 user-feedback round — study_event_id surfaces
        // alongside visit_date so the SPA can key into the BCVA
        // timeline by event. Defensive: legacy callers may not
        // project the column.
        Integer studyEventId = null;
        try {
            int sev = rs.getInt("study_event_id");
            if (!rs.wasNull()) studyEventId = sev;
        } catch (SQLException ignoredColumnAbsent) {
            // study_event_id column not in this query — leave null.
        }
        return new RetinalJobSummaryDto(
                jobId, task, laterality, status, modelVersion,
                toIso(completedAt), visitDate, acquisitionDate, studyEventId, primaryMetric(pv, pu));
    }

    /** 401 if no authenticated user, 400 if no active study. */
    private ResponseEntity<?> guardSession(HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — POST /pages/api/v1/me/activeStudy first."
            ));
        }
        return null;
    }

    /** 403 when the supplied study_id is outside the user's site visibility. */
    private ResponseEntity<?> guardStudyVisibility(Integer studyId, HttpSession session, String denyMessage) {
        if (studyId == null) {
            return ResponseEntity.status(403).body(Map.of("message", denyMessage));
        }
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (visibleStudyIds.contains(studyId)) {
            return null;
        }
        // 2026-06-22 user-feedback round 9 — retinal jobs surface
        // through deep links from the upload portal / cross-study
        // search; the job's owning study often differs from the
        // operator's currently-active session study (e.g. an EIAMD
        // upload routed into RIS while the user was browsing GA).
        // Fall back to "does the user have ANY active grant on the
        // owning study?" so the deep link works without forcing a
        // study-switch. Sysadmins pass through their explicit
        // admin grant; the same grant the seed + the
        // 2026-06-22-seed-ris-amd-admin-grants changeset insert
        // for the demo studies.
        if (currentUser != null && currentUser.isSysAdmin()) {
            return null;
        }
        if (currentUser != null && userHasActiveGrantOnStudy(currentUser, studyId)) {
            return null;
        }
        return ResponseEntity.status(403).body(Map.of("message", denyMessage));
    }

    /**
     * 2026-06-22 round 9 helper — true when {@code user} holds an
     * AVAILABLE study_user_role on the named study. Used by the
     * cross-study deep-link relaxation in {@link #guardStudyVisibility}.
     */
    private boolean userHasActiveGrantOnStudy(UserAccountBean user, Integer studyId) {
        if (user == null || studyId == null) return false;
        try {
            UserAccountDAO userDao = new UserAccountDAO(dataSource);
            List<StudyUserRoleBean> grants = userDao.findAllRolesByUserName(user.getName());
            for (StudyUserRoleBean g : grants) {
                if (g == null || g.getStudyId() != studyId) continue;
                if (g.getStatus() != null
                        && g.getStatus().getId() == Status.AVAILABLE.getId()) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("userHasActiveGrantOnStudy lookup failed for user={} study={}: {}",
                    user.getName(), studyId, e.getMessage());
        }
        return false;
    }

    private ResponseEntity<?> guardJobVisibility(JobRow row, HttpSession session) {
        return guardStudyVisibility(row.studyId, session,
                "retinal_inference_job " + row.jobId + " belongs to a different study");
    }

    /** Trim a single trailing ".e2e" — match what the upload controller saves. */
    private static String e2eUuidFromPath(String e2ePath) {
        if (e2ePath == null) return null;
        String base = Paths.get(e2ePath).getFileName().toString();
        if (base.toLowerCase().endsWith(".e2e")) {
            base = base.substring(0, base.length() - 4);
        }
        return base;
    }

    private List<String> listArtifactNames(String dir) {
        if (dir == null || dir.isBlank()) return List.of();
        Path p = Paths.get(dir);
        if (!Files.isDirectory(p)) return List.of();
        try (var stream = Files.list(p)) {
            List<String> names = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                  .map(f -> f.getFileName().toString())
                  .sorted()
                  .forEach(names::add);
            return names;
        } catch (IOException ioEx) {
            LOG.warn("Failed to list artifacts in {}: {}", dir, ioEx.getMessage());
            return List.of();
        }
    }

    private List<String> listCompanionNames(String e2eUuid) {
        return listCompanionNames(e2eUuid, -1);
    }

    private List<String> listCompanionNames(String e2eUuid, int scanIndex) {
        if (e2eUuid == null || e2eUuid.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String name : COMPANION_NAMES) {
            try {
                switch (name) {
                    case "bscan.dcm"     -> artifactStore.resolveBscanDcm(e2eUuid, scanIndex);
                    case "fundus.png"    -> artifactStore.resolveFundus(e2eUuid, scanIndex);
                    case "geometry.json" -> artifactStore.resolveGeometry(e2eUuid, scanIndex);
                    default -> { }
                }
                out.add(name);
            } catch (NoSuchFileException nfe) {
                // companion absent — skip
            } catch (IllegalArgumentException iae) {
                // bad UUID — none of the companions can be resolved.
                return List.of();
            } catch (IOException ioEx) {
                LOG.warn("Failed to resolve companion {} for e2eUuid {}: {}",
                        name, e2eUuid, ioEx.getMessage());
            }
        }
        return out;
    }

    private static PrimaryMetric primaryMetric(BigDecimal value, String unit) {
        if (value == null && (unit == null || unit.isBlank())) return null;
        return new PrimaryMetric(value, unit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception jsonEx) {
            LOG.warn("Failed to parse output_payload JSON: {}", jsonEx.getMessage());
            return Map.of();
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static MediaType mediaTypeForName(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".csv"))  return MediaType.parseMediaType("text/csv");
        if (lower.endsWith(".npy"))  return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".npz"))  return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".dcm"))  return MediaType.parseMediaType("application/dicom");
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /* ====================================================================== */
    /* GET /retinal-jobs/{jobId}/compare-previous — nAMD Slice 4              */
    /* ====================================================================== */

    /**
     * For the given job, find the previous done job for the same
     * subject + task + eye_laterality and return a delta summary so
     * the SPA can render "Vs previous visit (N days ago)" KPI tiles.
     *
     * <p>The "previous" job is selected by max(completed_at) under
     * (study_subject_id, task, eye_laterality) where completed_at is
     * strictly less than the current job's. Returns 200 with
     * {@code previousJobId=null} when no prior visit exists — the
     * SPA renders that as "First visit, no comparison available."
     */
    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}/compare-previous",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> compareToPrevious(@PathVariable("jobId") long jobId,
                                               HttpSession session) {
        ResponseEntity<?> guard = guardSession(session);
        if (guard != null) return guard;

        JobRow current;
        try (Connection c = dataSource.getConnection()) {
            current = fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("compareToPrevious: fetch current failed for job {}: {}",
                    jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to load job: " + sqlEx.getMessage()));
        }
        if (current == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = guardJobVisibility(current, session);
        if (visGuard != null) return visGuard;

        Map<String, Object> currentMetrics = parsePayload(current.outputPayloadJson);
        PreviousJobView previous;
        try (Connection c = dataSource.getConnection()) {
            previous = fetchPreviousJob(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("compareToPrevious: previous-job lookup failed for job {}: {}",
                    jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to load previous job: " + sqlEx.getMessage()));
        }

        Map<String, Double> deltas = new LinkedHashMap<>();
        Map<String, Object> previousMetrics = previous == null
                ? Map.of()
                : parsePayload(previous.outputPayloadJson);
        if (previous != null) {
            for (String key : List.of("irf_mm3", "srf_mm3", "ped_mm3", "total_fluid_volume_mm3")) {
                Double curV = asDouble(currentMetrics.get(key));
                Double prevV = asDouble(previousMetrics.get(key));
                if (curV != null && prevV != null) {
                    deltas.put(key, curV - prevV);
                }
            }
        }
        Integer daysBetween = (previous == null
                || current.completedAt == null
                || previous.completedAt == null)
                ? null
                : (int) java.time.temporal.ChronoUnit.DAYS.between(
                        previous.completedAt.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        current.completedAt.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate());

        return ResponseEntity.ok(new RetinalJobCompareDto(
                current.jobId,
                toIso(current.completedAt),
                currentMetrics,
                previous == null ? null : previous.jobId,
                previous == null ? null : toIso(previous.completedAt),
                previousMetrics,
                daysBetween,
                deltas));
    }

    private PreviousJobView fetchPreviousJob(Connection c, long currentJobId) throws SQLException {
        // 2026-06-23 — subject resolution via COALESCE on the two
        // binding paths so previous-job lookup still works for
        // planned-visit-bound jobs (no event_crf yet).
        String sql = "WITH cur AS ("
                + "  SELECT j.job_id, j.task, j.eye_laterality, j.completed_at, "
                + "         ev.study_subject_id "
                + "    FROM retinal_inference_job j "
                + "    LEFT JOIN event_crf ec ON ec.event_crf_id = j.event_crf_id "
                + "    JOIN study_event ev ON ev.study_event_id = COALESCE(ec.study_event_id, j.study_event_id) "
                + "   WHERE j.job_id = ?) "
                + "SELECT prev.job_id, prev.completed_at, prev_r.output_payload::text "
                + "  FROM retinal_inference_job prev "
                + "  JOIN retinal_inference_result prev_r ON prev_r.job_id = prev.job_id "
                + "  LEFT JOIN event_crf ec2 ON ec2.event_crf_id = prev.event_crf_id "
                + "  JOIN study_event ev2 ON ev2.study_event_id = COALESCE(ec2.study_event_id, prev.study_event_id) "
                + "  JOIN cur ON cur.study_subject_id = ev2.study_subject_id "
                + "   AND cur.task = prev.task "
                + "   AND cur.eye_laterality = prev.eye_laterality "
                + " WHERE prev.status = 'done' "
                + "   AND prev.completed_at < cur.completed_at "
                + " ORDER BY prev.completed_at DESC "
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, currentJobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                PreviousJobView v = new PreviousJobView();
                v.jobId = rs.getLong("job_id");
                v.completedAt = rs.getTimestamp("completed_at");
                v.outputPayloadJson = rs.getString("output_payload");
                return v;
            }
        }
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static final class PreviousJobView {
        long jobId;
        java.sql.Timestamp completedAt;
        String outputPayloadJson;
    }

    /**
     * Slice 4 response — current job metrics + previous-job metrics
     * + per-key deltas + days between visits. {@code previousJobId}
     * is null when no prior visit exists.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RetinalJobCompareDto")
    public record RetinalJobCompareDto(
            long currentJobId,
            String currentCompletedAt,
            Map<String, Object> currentMetrics,
            Long previousJobId,
            String previousCompletedAt,
            Map<String, Object> previousMetrics,
            Integer daysBetween,
            Map<String, Double> deltas) {}
}
