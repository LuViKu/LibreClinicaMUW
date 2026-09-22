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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.SegmentationEnvelopeLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3.6 — the files an inference job produced, and the hand corrections made
 * to them.
 *
 * <p>Streaming a mask, streaming the segmentation envelope, and the
 * HEYEX-style layer-surface corrections. These are grouped because they all
 * reach the artifact store rather than the database, and because they are
 * where blinding bites hardest: an artifact stream is the one path that can
 * hand a treating clinician the model's reading of their patient's eye in a
 * study that randomised them not to see it.
 *
 * <p>Paths are unchanged from {@code RetinalResultsApiController}. The split
 * is a move.
 *
 * <p>The split preserves the companion exemption exactly: {@code bscan.dcm},
 * {@code fundus.png} and {@code geometry.json} are renderings of the eye, not
 * of the model's answer, so they stream to a blinded caller while everything
 * else in the same directory does not.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Retinal artifacts",
     description = "Mask and envelope streaming, and layer-surface corrections.")
@SuppressWarnings("null")
public class RetinalJobArtifactsApiController {

    private static final Logger LOG =
            LoggerFactory.getLogger(RetinalJobArtifactsApiController.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final RetinalArtifactStorageService artifactStore;
    private final StudyResourceAccess accessDelegate;
    private final RetinalJobAccess jobsDelegate;

    @Autowired
    public RetinalJobArtifactsApiController(@Qualifier("dataSource") DataSource dataSource,
                                            SiteVisibilityFilter siteVisibilityFilter,
                                            RetinalArtifactStorageService artifactStore) {
        this.dataSource = dataSource;
        this.artifactStore = artifactStore;
        this.accessDelegate = new StudyResourceAccess(dataSource, siteVisibilityFilter);
        this.jobsDelegate = new RetinalJobAccess(dataSource, artifactStore, this.accessDelegate);
    }

    private StudyResourceAccess access() {
        return accessDelegate;
    }

    private RetinalJobAccess jobs() {
        return jobsDelegate;
    }

    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}/artifacts/{name:.+}")
    public ResponseEntity<?> streamArtifact(@PathVariable("jobId") long jobId,
                                            @PathVariable("name") String name,
                                            HttpSession session,
                                            HttpServletResponse response) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;

        if (name == null || name.isBlank() || !RetinalJobAccess.SAFE_ARTIFACT_NAME.matcher(name).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Artifact name '" + name + "' contains disallowed characters"));
        }

        RetinalJobAccess.JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = jobs().fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = jobs().guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        Path target;
        boolean isCompanion = RetinalJobAccess.COMPANION_NAMES.contains(name);
        // Trial blinding — a treating clinician viewing an AI_HIDDEN subject may
        // fetch the raw scan companions (bscan.dcm / fundus.png / geometry.json)
        // but NOT the AI segmentation artifacts (masks / CSVs under
        // bscan_masks_dir).
        if (!isCompanion && AiArmPolicy.maskAiFor(jobs().armForJobRow(row), session)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "AI output is not available for this subject"));
        }
        try {
            if (isCompanion) {
                String e2eUuid = RetinalJobAccess.e2eUuidFromPath(row.e2ePath);
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
        MediaType mediaType = RetinalJobAccess.mediaTypeForName(name);
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
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;

        RetinalJobAccess.JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = jobs().fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("Failed to fetch retinal job {}: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = jobs().guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        // Trial blinding — the segmentation envelope is pure AI output; withhold
        // it from a treating clinician viewing an AI_HIDDEN subject.
        if (AiArmPolicy.maskAiFor(jobs().armForJobRow(row), session)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "AI output is not available for this subject"));
        }

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
        // 2026-06-26 — surface indices the loader served from
        // corrections/. Empty list = no operator corrections active for
        // the job; we still emit the header (as an empty value) so the
        // SPA can distinguish "no header at all" (older backend) from
        // "header present + empty" (no corrections this job).
        response.setHeader("X-MUW-Seg-Corrected",
                env.correctedSurfaceIndices() == null
                        ? ""
                        : env.correctedSurfaceIndices().stream()
                                .map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(",")));
        // Expose the X-MUW-Seg-* headers to the SPA fetch — without
        // this CORS hides them client-side (same-origin in dev /
        // single-domain in prod, but the headers also need to be in
        // Access-Control-Expose-Headers when the SPA reads them via
        // fetch().headers.get(...)).
        response.setHeader("Access-Control-Expose-Headers",
                "X-MUW-Seg-Kind, X-MUW-Seg-Dtype, X-MUW-Seg-Shape, "
                        + "X-MUW-Seg-Labels, X-MUW-Seg-Task, X-MUW-Seg-Corrected");
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
    /* 2026-06-26 — Layer-segmentation correction endpoints                   */
    /*                                                                        */
    /* HEYEX-style hand correction of the AI's IOWA layer surfaces            */
    /* (ILM / RPE / BM / 11 surfaces total). The SPA's fullscreen B-scan      */
    /* overlay edits a per-(slice, layer) curve and POSTs the diff here; we   */
    /* read the original CSV from bscan_masks_dir, splice the operator's      */
    /* per-slice rows over the originals, and write the merged CSV to         */
    /* <bscan_masks_dir>/corrections/<basename>. The SegmentationEnvelopeLoader*/
    /* prefers the corrected file on next read. Original AI output is never   */
    /* overwritten.                                                           */
    /*                                                                        */
    /* Role gate — INVESTIGATOR + STUDYDIRECTOR (sysadmin always). DELETE     */
    /* reverts to the AI output by removing the corrections file + DB row.    */
    /* ====================================================================== */

    /**
     * 2026-06-26 — gate for the layer-correction endpoints. INVESTIGATOR
     * is the clinical lead (signs-off persona); STUDYDIRECTOR is the
     * data-manager persona shown on the design's oct-inference-job mock.
     * Sysadmin always allowed. Coordinators / monitors / others rejected.
     */
    static boolean canCorrectSegmentation(UserAccountBean user, StudyUserRoleBean currentRole) {
        if (user != null && user.isSysAdmin()) return true;
        if (currentRole == null || currentRole.getRole() == null) return false;
        Role r = currentRole.getRole();
        return r.equals(Role.STUDYDIRECTOR) || r.equals(Role.INVESTIGATOR);
    }

    /* ====================================================================== */
    /* Trial blinding — server-side AI masking for the AI_HIDDEN arm          */
    /* ====================================================================== */



    /**
     * Request shape for the save endpoint. {@code perSliceRows} is keyed
     * by stringified B-scan index (so JSON-friendly); each value is the
     * per-A-scan y-pixel value for that slice's layer surface, length
     * MUST equal the original CSV's {@code cols} count.
     */
    public record SaveCorrectionRequest(
            Integer layerIndex,
            String layerLabel,
            Map<String, List<Double>> perSliceRows) {}

    /** Response shape for save + the list endpoint's array members. */
    public record CorrectionDto(
            long correctionId,
            long jobId,
            int layerIndex,
            String layerLabel,
            int editedSliceCount,
            String csvRelpath,
            int editedByUserId,
            String editedByUserName,
            String editedAt) {}

    @PostMapping(path = "/retinal-jobs/{jobId:[0-9]+}/segmentation/corrections",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveCorrection(@PathVariable("jobId") long jobId,
                                            @RequestBody SaveCorrectionRequest req,
                                            HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;

        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!canCorrectSegmentation(currentUser, currentRole)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Layer-segmentation corrections require Investigator or Data-Manager role"));
        }
        if (req == null || req.layerIndex() == null || req.layerLabel() == null
                || req.layerLabel().isBlank() || req.perSliceRows() == null
                || req.perSliceRows().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "layerIndex, layerLabel and a non-empty perSliceRows are required"));
        }

        RetinalJobAccess.JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = jobs().fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            LOG.error("saveCorrection: fetch job {} failed: {}", jobId, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = jobs().guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;
        if (row.bscanMasksDir == null || row.bscanMasksDir.isBlank()) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Job " + jobId + " has no segmentation directory; cannot accept corrections"));
        }

        Path masksDir = Paths.get(row.bscanMasksDir).toAbsolutePath().normalize();

        // Locate the IOWA / BM CSV that corresponds to layerIndex. The
        // file naming is "NNN-(long description) (SHORT).csv"; we walk
        // the dir, parse the NNN prefix, and pick the file whose
        // (zero-based) position in the sorted list matches layerIndex.
        Path originalCsv;
        try {
            originalCsv = findLayerCsv(masksDir, req.layerIndex());
        } catch (IOException ioEx) {
            LOG.error("saveCorrection: enumerate {} failed: {}", masksDir, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to enumerate segmentation directory: " + ioEx.getMessage()));
        }
        if (originalCsv == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No layer CSV found for layerIndex=" + req.layerIndex()
                            + " under " + masksDir));
        }

        // Merge the operator's per-slice rows over the original CSV.
        byte[] mergedBytes;
        try {
            mergedBytes = mergeCorrectionCsv(originalCsv, req.perSliceRows());
        } catch (IllegalArgumentException badShape) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", badShape.getMessage()));
        } catch (IOException ioEx) {
            LOG.error("saveCorrection: read {} failed: {}", originalCsv, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to read original CSV: " + ioEx.getMessage()));
        }

        String relpath;
        try {
            relpath = artifactStore.persistCorrection(masksDir,
                    originalCsv.getFileName().toString(), mergedBytes);
        } catch (IOException ioEx) {
            LOG.error("saveCorrection: persist failed for job {} layer {}: {}",
                    jobId, req.layerIndex(), ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist correction file: " + ioEx.getMessage()));
        }

        // UPSERT the metadata row.
        int editedSliceCount = req.perSliceRows().size();
        long correctionId;
        String previousRelpath;
        try (Connection c = dataSource.getConnection()) {
            previousRelpath = fetchCorrectionRelpath(c, jobId, req.layerIndex());
            correctionId = upsertCorrection(c, jobId, req.layerIndex(),
                    req.layerLabel(), editedSliceCount, relpath,
                    currentUser.getId());
        } catch (SQLException sqlEx) {
            LOG.error("saveCorrection: UPSERT failed for job {} layer {}: {}",
                    jobId, req.layerIndex(), sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist correction row: " + sqlEx.getMessage()));
        }

        // Audit row + STUDY-SUBJECT context for the timeline lookup.
        StudySubjectBean ss = resolveStudySubjectForJob(row);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_SEGMENTATION_CORRECTED,
                currentUser, currentStudy, ss,
                "Layer correction: " + req.layerLabel() + " (" + editedSliceCount + " slices)",
                /* auditTable */ "retinal_inference_correction",
                /* entityId   */ (int) correctionId,
                /* columnName */ "csv_relpath",
                /* oldValue   */ previousRelpath == null ? "" : previousRelpath,
                /* newValue   */ relpath);

        return ResponseEntity.ok(new CorrectionDto(
                correctionId, jobId, req.layerIndex(), req.layerLabel(),
                editedSliceCount, relpath, currentUser.getId(),
                currentUser.getName(), Instant.now().toString()));
    }

    @DeleteMapping(path = "/retinal-jobs/{jobId:[0-9]+}/segmentation/corrections/{layerIndex:[0-9]+}",
                   produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteCorrection(@PathVariable("jobId") long jobId,
                                              @PathVariable("layerIndex") int layerIndex,
                                              HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!canCorrectSegmentation(currentUser, currentRole)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Layer-segmentation corrections require Investigator or Data-Manager role"));
        }

        RetinalJobAccess.JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = jobs().fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = jobs().guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;
        if (row.bscanMasksDir == null || row.bscanMasksDir.isBlank()) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Job " + jobId + " has no segmentation directory"));
        }

        String previousRelpath;
        int affected;
        try (Connection c = dataSource.getConnection()) {
            previousRelpath = fetchCorrectionRelpath(c, jobId, layerIndex);
            if (previousRelpath == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No correction found for job " + jobId + " layer " + layerIndex));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM retinal_inference_correction "
                            + " WHERE job_id = ? AND layer_index = ?")) {
                ps.setLong(1, jobId);
                ps.setInt(2, layerIndex);
                affected = ps.executeUpdate();
            }
        } catch (SQLException sqlEx) {
            LOG.error("deleteCorrection: DELETE failed for job {} layer {}: {}",
                    jobId, layerIndex, sqlEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to delete correction row: " + sqlEx.getMessage()));
        }
        if (affected == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No correction found for job " + jobId + " layer " + layerIndex));
        }

        Path masksDir = Paths.get(row.bscanMasksDir).toAbsolutePath().normalize();
        try {
            artifactStore.deleteCorrection(masksDir,
                    Path.of(previousRelpath).getFileName().toString());
        } catch (IOException ioEx) {
            // The DB row is gone; the file delete is best-effort. Log
            // so an operator can sweep dangling files manually.
            LOG.warn("deleteCorrection: file delete failed for {}: {}",
                    previousRelpath, ioEx.getMessage());
        }

        StudySubjectBean ss = resolveStudySubjectForJob(row);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        EventCrfsApiController.writeAuditEvent(
                auditDAO, AuditTypeIds.RETINAL_SEGMENTATION_CORRECTED,
                currentUser, currentStudy, ss,
                "Layer correction reverted (layerIndex=" + layerIndex + ")",
                "retinal_inference_correction", 0,
                "csv_relpath", previousRelpath, "");
        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "layerIndex", layerIndex,
                "reverted", true));
    }

    @GetMapping(path = "/retinal-jobs/{jobId:[0-9]+}/segmentation/corrections",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listCorrections(@PathVariable("jobId") long jobId,
                                             HttpSession session) {
        ResponseEntity<?> guard = access().guardSession(session);
        if (guard != null) return guard;

        RetinalJobAccess.JobRow row;
        try (Connection c = dataSource.getConnection()) {
            row = jobs().fetchJobDetail(c, jobId);
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to fetch retinal job: " + sqlEx.getMessage()));
        }
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No retinal_inference_job with id " + jobId));
        }
        ResponseEntity<?> visGuard = jobs().guardJobVisibility(row, session);
        if (visGuard != null) return visGuard;

        List<CorrectionDto> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT c.correction_id, c.layer_index, c.layer_label, "
                             + "       c.edited_slice_count, c.csv_relpath, "
                             + "       c.edited_by_user_id, u.user_name, c.edited_at "
                             + "  FROM retinal_inference_correction c "
                             + "  LEFT JOIN user_account u ON u.user_id = c.edited_by_user_id "
                             + " WHERE c.job_id = ? "
                             + " ORDER BY c.layer_index ASC")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CorrectionDto(
                            rs.getLong("correction_id"), jobId,
                            rs.getInt("layer_index"), rs.getString("layer_label"),
                            rs.getInt("edited_slice_count"), rs.getString("csv_relpath"),
                            rs.getInt("edited_by_user_id"), rs.getString("user_name"),
                            RetinalJobAccess.toIso(rs.getTimestamp("edited_at"))));
                }
            }
        } catch (SQLException sqlEx) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to list corrections: " + sqlEx.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /* ── correction helpers ── */

    /**
     * Walk {@code masksDir} for {@code NNN-...(LABEL).csv}, sort by IOWA
     * prefix (BM appended), and return the file at zero-based
     * {@code layerIndex}. Mirrors {@link SegmentationEnvelopeLoader}'s
     * ordering so the SPA's layer-index slot maps to the same CSV.
     */
    private static Path findLayerCsv(Path masksDir, int layerIndex) throws IOException {
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "^(\\d{3})-.+\\(([^)]+)\\)\\.csv$");
        record Entry(int order, boolean iowa, String label, Path path) {}
        List<Entry> entries = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> ds =
                     Files.newDirectoryStream(masksDir, "*.csv")) {
            for (Path p : ds) {
                java.util.regex.Matcher m = pat.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int prefix = Integer.parseInt(m.group(1));
                String label = m.group(2).trim();
                boolean iowa = prefix >= 1 && prefix <= 11 && !"BM".equalsIgnoreCase(label);
                entries.add(new Entry(prefix, iowa, label, p));
            }
        }
        entries.sort((a, b) -> {
            if (a.iowa() != b.iowa()) return a.iowa() ? -1 : 1;
            if (a.iowa()) return Integer.compare(a.order(), b.order());
            return a.label().compareTo(b.label());
        });
        if (layerIndex < 0 || layerIndex >= entries.size()) return null;
        return entries.get(layerIndex).path();
    }

    /**
     * Read the original IOWA CSV, splice the operator-edited slice
     * rows over the originals, and return the merged bytes. Preserves
     * the original header row and the padding sentinel (100) layout
     * so {@link SegmentationEnvelopeLoader} parses the merged CSV the
     * same way it parses the original.
     */
    private static byte[] mergeCorrectionCsv(Path originalCsv,
                                             Map<String, List<Double>> perSliceRows)
            throws IOException {
        List<String> lines = Files.readAllLines(originalCsv, java.nio.charset.StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("Original CSV is empty: " + originalCsv);
        }
        // Parse the 3-element dimensions header (cols, n_bscans, n_rows)
        // when present; otherwise treat every line as a data row.
        String header = lines.get(0);
        String[] headerTokens = header.split(",");
        int dataStartIdx = 0;
        int cols = -1;
        if (headerTokens.length == 3 && lines.size() > 1) {
            try {
                cols = (int) Double.parseDouble(headerTokens[0].trim());
                dataStartIdx = 1;
            } catch (NumberFormatException ignored) {
                // First row isn't a 3-element numeric header — fall through.
            }
        }
        if (cols < 0) {
            // Derive cols from the first data row's width.
            String[] tokens = lines.get(dataStartIdx).split(",");
            cols = tokens.length;
        }
        // Derive the padded width from the FIRST data row so the
        // sentinel layout is preserved exactly.
        int paddedWidth = lines.get(dataStartIdx).split(",").length;
        if (paddedWidth < cols) paddedWidth = cols;

        int nSlices = lines.size() - dataStartIdx;
        // Splice each edited slice.
        for (Map.Entry<String, List<Double>> e : perSliceRows.entrySet()) {
            int z;
            try {
                z = Integer.parseInt(e.getKey().trim());
            } catch (NumberFormatException badZ) {
                throw new IllegalArgumentException("perSliceRows key not an integer: " + e.getKey());
            }
            if (z < 0 || z >= nSlices) {
                throw new IllegalArgumentException(
                        "Slice index " + z + " out of range [0, " + (nSlices - 1) + "]");
            }
            List<Double> values = e.getValue();
            if (values == null || values.size() != cols) {
                throw new IllegalArgumentException(
                        "Slice " + z + ": expected " + cols + " values, got "
                                + (values == null ? "null" : values.size()));
            }
            StringBuilder sb = new StringBuilder(paddedWidth * 8);
            for (int i = 0; i < paddedWidth; i++) {
                if (i > 0) sb.append(',');
                if (i < cols) {
                    sb.append(formatCsvDouble(values.get(i)));
                } else {
                    sb.append("100");
                }
            }
            lines.set(dataStartIdx + z, sb.toString());
        }
        StringBuilder out = new StringBuilder(lines.size() * 256);
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Format a y-pixel value with a sensible precision: integer if the
     * fractional part is < 1e-6, else 4-decimal float. Mirrors what the
     * IOWA converter produces.
     */
    private static String formatCsvDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString((long) Math.rint(v));
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /** DB UPSERT — returns the correction_id (existing or new). */
    private static long upsertCorrection(Connection c, long jobId, int layerIndex,
                                         String layerLabel, int editedSliceCount,
                                         String csvRelpath, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO retinal_inference_correction ("
                        + "job_id, layer_index, layer_label, edited_slice_count, "
                        + "csv_relpath, edited_by_user_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (job_id, layer_index) DO UPDATE "
                        + "SET layer_label = EXCLUDED.layer_label, "
                        + "    edited_slice_count = EXCLUDED.edited_slice_count, "
                        + "    csv_relpath = EXCLUDED.csv_relpath, "
                        + "    edited_by_user_id = EXCLUDED.edited_by_user_id, "
                        + "    edited_at = CURRENT_TIMESTAMP "
                        + "RETURNING correction_id")) {
            ps.setLong(1, jobId);
            ps.setInt(2, layerIndex);
            ps.setString(3, layerLabel);
            ps.setInt(4, editedSliceCount);
            ps.setString(5, csvRelpath);
            ps.setInt(6, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("UPSERT returned no row");
                }
                return rs.getLong(1);
            }
        }
    }

    private static String fetchCorrectionRelpath(Connection c, long jobId, int layerIndex)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT csv_relpath FROM retinal_inference_correction "
                        + " WHERE job_id = ? AND layer_index = ?")) {
            ps.setLong(1, jobId);
            ps.setInt(2, layerIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Resolve the {@link StudySubjectBean} for an audit row from the
     * job's binding (event_crf preferred; falls back to study_event for
     * planned-visit-bound jobs). Returns null when neither path
     * resolves — the audit row's entity_id still points at the
     * correction_id, so the timeline lookup degrades gracefully.
     */
    /** The subject a planned visit belongs to, or null. */
    private Integer studySubjectIdForStudyEvent(int studyEventId) {
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
            LOG.warn("subject lookup failed for study_event {}: {}", studyEventId, e.getMessage());
            return null;
        }
    }

    private StudySubjectBean resolveStudySubjectForJob(RetinalJobAccess.JobRow row) {
        try {
            StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
            if (row.eventCrfId > 0) {
                EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
                EventCRFBean ecb = eventCrfDAO.findByPK(row.eventCrfId);
                if (ecb != null && ecb.getId() > 0 && ecb.getStudySubjectId() > 0) {
                    return (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
                }
            }
            // Planned-visit-bound jobs carry no event_crf — walk the job's
            // own study_event instead.
            //
            // P3.6: this passed a literal 0 before the move, so the fallback
            // could never resolve and a correction on a portal-uploaded scan
            // lost its subject on the audit row. The comment said what the
            // code was meant to do; the argument did not.
            if (row.studyEventId != null && row.studyEventId > 0) {
                Integer ssId = studySubjectIdForStudyEvent(row.studyEventId);
                if (ssId != null) {
                    return (StudySubjectBean) ssDAO.findByPK(ssId);
                }
            }
        } catch (Exception ignored) {
            // Best-effort — audit row still lands without ss context.
        }
        return null;
    }
}
