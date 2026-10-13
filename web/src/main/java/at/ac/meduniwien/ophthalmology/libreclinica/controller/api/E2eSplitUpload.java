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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.retinal.RetinalInferenceJobStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.ImageFingerprint;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestResolutionService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.E2eSidecarClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * DR-041 — one upload of a multimodal Spectralis {@code .e2e}, split on the
 * server into one {@code ingest_item} per series.
 *
 * <p>The file is streamed to the E2E upload directory once, with its SHA-256
 * taken on the way. The converter sidecar then lists the file's series
 * ({@link E2eSidecarClient#describe}); each series becomes a row of kind
 * {@code e2e} that shares the stored path and the digest and is told apart by
 * {@code e2e_series_key}. Only OCT volumes get retinal-inference jobs, through
 * the visit's imaging plan and the {@code /preprocess} path with the series'
 * {@code scan_index}.
 *
 * <p>Binding: a header patient id that differs from the folder label, an
 * explicit {@code park}, or a label that resolves to no single subject parks
 * every item. Otherwise {@code studyEventId} binds every item to that visit,
 * and without one each series resolves its own visit from label and date; a
 * series with no visit on its date is parked on its own.
 */
final class E2eSplitUpload {

    private static final Logger LOG = LoggerFactory.getLogger(E2eSplitUpload.class);

    static final String MESSAGE_DUPLICATE = "Diese .e2e-Datei wurde bereits hochgeladen.";

    private static final Pattern SERIES_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
    private static final Set<String> HEADER_STATES = Set.of("empty", "matches", "differs");
    private static final Set<String> SERIES_KINDS =
            Set.of("OCT", "OCT_LINE", "IR", "BAF", "BR", "IRAF", "QAF", "ICGA", "FA", "OTHER");

    private final DataSource dataSource;
    private final PublicOctUploadController oct;
    private final E2eSidecarClient sidecar;
    private final RemoteRetinalInferenceClient remoteClient;
    private final RetinalInferenceApiController inferenceController;

    E2eSplitUpload(DataSource dataSource, PublicOctUploadController oct, E2eSidecarClient sidecar,
                   RemoteRetinalInferenceClient remoteClient, RetinalInferenceApiController inferenceController) {
        this.dataSource = dataSource;
        this.oct = oct;
        this.sidecar = sidecar;
        this.remoteClient = remoteClient;
        this.inferenceController = inferenceController;
    }

    /* ------------------------------------------------------------------ */
    /* preflight                                                           */
    /* ------------------------------------------------------------------ */

    /** The items already made from a file with this digest, ids only. */
    static List<Long> existingItems(DataSource dataSource, String sha256) {
        List<Long> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ingest_item_id FROM ingest_item "
                             + " WHERE sha256 = ? AND e2e_series_key IS NOT NULL ORDER BY ingest_item_id")) {
            ps.setString(1, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            LOG.warn("split duplicate lookup failed: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * Preflight for a split file: is it already here? Only items the caller may
     * name are listed ({@code visible} null = all); {@code exists} is true when
     * any item exists, so a hidden duplicate still stops the upload.
     */
    static Map<String, Object> preflight(DataSource dataSource, String sha256, LongPredicate visible) {
        List<Long> all = existingItems(dataSource, sha256);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exists", !all.isEmpty());
        body.put("split", true);
        body.put("existingIngestItemIds", visibleOnly(all, visible));
        return body;
    }

    private static List<Long> visibleOnly(List<Long> ids, LongPredicate visible) {
        if (visible == null) return ids;
        List<Long> out = new ArrayList<>();
        for (long id : ids) if (visible.test(id)) out.add(id);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* commit                                                              */
    /* ------------------------------------------------------------------ */

    /** What one series turned into. */
    private record Planned(E2eSidecarClient.Series series, String key, String kind, LocalDate date,
                           String laterality, Integer scanIndex, String pixelSha256, boolean park,
                           Integer studyEventId, Integer eventCrfId, Integer subjectId,
                           IngestTwins.Twin twin, Integer modalityId, List<String> tasks) {}

    /**
     * @param resolveScope studies the label may resolve against, null for all
     * @param visible      which existing items a duplicate answer may name, null for all
     * @param source       the uploader's source for a tokened request, else null
     */
    ResponseEntity<?> commit(MultipartFile file, String patientId, Integer studyEventId, Integer eventCrfId,
                             boolean park, Set<Integer> resolveScope, LongPredicate visible,
                             IngestSourceService.Source source) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file part is required"));
        }
        String pid = patientId == null ? "" : patientId.trim();
        if (pid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "patientId is required"));
        }
        if (eventCrfId != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eventCrfId is not supported with split=true; send studyEventId or park"));
        }
        if (park && studyEventId != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "park and studyEventId are mutually exclusive"));
        }

        Set<Integer> atLocation = DeviceScope.atLocation(dataSource, source);
        Integer pickedSubject = null;
        if (studyEventId != null) {
            pickedSubject = oct.resolveStudySubjectFromStudyEvent(studyEventId);
            if (pickedSubject == null || !oct.subjectAtLocation(atLocation, pickedSubject)) {
                return ResponseEntity.status(404).body(Map.of("message", "No study_event with id " + studyEventId));
            }
        }

        // ---- 1. store once, digest on the way ----
        Path saved;
        String sha256;
        try {
            Path dir = Paths.get(PublicOctUploadController.uploadsDir());
            Files.createDirectories(dir);
            saved = dir.resolve(UUID.randomUUID() + ".e2e");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = file.getInputStream(); var dis = new DigestInputStream(in, md)) {
                Files.copy(dis, saved);
            }
            sha256 = HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.error("Failed to persist a split E2E upload: {}", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to persist E2E"));
        }

        // ---- 2. already split? ----
        List<Long> existing = existingItems(dataSource, sha256);
        if (!existing.isEmpty()) {
            discard(saved);
            return duplicate(visibleOnly(existing, visible));
        }

        // ---- 3. describe ----
        E2eSidecarClient.Description described;
        try {
            described = sidecar.describe(saved.getFileName().toString(), pid);
        } catch (E2eSidecarClient.SidecarException e) {
            discard(saved);
            return ResponseEntity.status(e.httpStatus()).body(Map.of("message", e.getMessage()));
        }
        String problem = validate(described);
        if (problem != null) {
            discard(saved);
            LOG.warn("Split E2E upload refused: {}", problem);
            return ResponseEntity.status(502).body(Map.of("message", "The image converter's answer was not usable: " + problem));
        }
        if (described.series().isEmpty()) {
            discard(saved);
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "message", "Die .e2e-Datei enthaelt keine Bildserie."));
        }

        // ---- 4. plan: who binds where ----
        boolean differs = "differs".equals(described.headerPatient());
        boolean allParked = park || differs;
        Integer labelSubject = pickedSubject;
        if (studyEventId == null && !allParked) {
            // The label alone must name exactly one subject, or nobody is guessed at.
            IngestResolutionService.Resolution byLabel = oct.resolution().resolve(pid, (LocalDate) null, resolveScope);
            if (byLabel.single().isPresent()) {
                labelSubject = byLabel.single().get().studySubjectId();
            } else {
                allParked = true;
            }
        } else if (studyEventId == null) {
            labelSubject = oct.resolution().resolve(pid, (LocalDate) null, resolveScope).single()
                    .map(IngestResolutionService.ResolveCandidate::studySubjectId).orElse(null);
        }

        Map<Integer, String> fingerprints = Map.of();
        boolean anyOct = described.series().stream().anyMatch(s -> "OCT".equals(s.seriesModality()));
        if (anyOct) {
            try {
                fingerprints = ImageFingerprint.ofE2e(saved);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Split E2E upload - could not fingerprint the volumes: {}", e.getClass().getSimpleName());
            }
        }

        List<Planned> plan = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            Map<LocalDate, IngestResolutionService.Resolution> byDate = new HashMap<>();
            for (E2eSidecarClient.Series s : described.series()) {
                String kind = s.seriesModality();
                LocalDate date = dateOf(s.acquiredAt());
                String lat = lateralityOf(s.laterality());
                boolean hasVolume = ("OCT".equals(kind) || "OCT_LINE".equals(kind)) && s.octVolumeIndex() != null;
                Integer scanIndex = hasVolume ? s.octVolumeIndex() : null;
                String pixel = "OCT".equals(kind) && scanIndex != null ? fingerprints.get(scanIndex) : null;

                IngestTwins.Twin twin = IngestTwins.find(c, pixel);
                boolean parkThis = allParked || twin != null;
                Integer seriesEvent = null;
                Integer seriesCrf = null;
                Integer seriesSubject = labelSubject;
                if (!parkThis) {
                    if (studyEventId != null) {
                        seriesEvent = studyEventId;
                    } else {
                        IngestResolutionService.Resolution r = byDate.computeIfAbsent(date,
                                d -> oct.resolution().resolve(pid, d, resolveScope));
                        if (r.isSuggested() && r.single().isPresent()
                                && r.single().get().matchingEvent() != null) {
                            var ev = r.single().get().matchingEvent();
                            seriesEvent = ev.studyEventId();
                            seriesCrf = ev.eventCrfId();
                            seriesSubject = r.single().get().studySubjectId();
                        } else {
                            parkThis = true;
                        }
                    }
                }

                Integer modalityId = null;
                List<String> tasks = List.of();
                if (!parkThis) {
                    Integer studyId = VisitImagingPlan.studyOfBinding(c, seriesCrf, seriesEvent);
                    if (studyId != null) {
                        modalityId = VisitImagingPlan.modalityByE2eSeries(c, studyId, kind);
                        if (modalityId == null && "OCT".equals(kind)) {
                            modalityId = VisitImagingPlan.e2eModalityOf(c, studyId);
                        }
                        if ("OCT".equals(kind) && scanIndex != null && lat != null) {
                            if (!new StudySettingService(dataSource)
                                    .isEnabled(studyId, StudySettingService.INFERENCE_ENABLED)) {
                                LOG.info("Split E2E upload - inference is switched off for study {}", studyId);
                            } else {
                                tasks = VisitImagingPlan.tasksFor(c, seriesCrf, seriesEvent, modalityId).tasks();
                            }
                        }
                    }
                }
                plan.add(new Planned(s, s.seriesKey(), kind, date, lat, scanIndex, pixel, parkThis,
                        seriesEvent, seriesCrf, seriesSubject, twin, modalityId, tasks));
            }
        } catch (SQLException e) {
            discard(saved);
            LOG.error("Split E2E upload - planning failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to plan the upload"));
        }

        // ---- 5. write: items and jobs in one transaction ----
        boolean remoteConfigured = remoteClient != null && remoteClient.isConfigured();
        boolean dispatchToRemote = remoteConfigured && inferenceController != null;
        String jobStatus = dispatchToRemote ? "remote_pending" : RetinalInferenceJobStatus.QUEUED.dbValue();
        String absolutePath = saved.toString();
        long size = PublicOctUploadController.fileSize(saved);
        String originalName = file.getOriginalFilename();

        List<Long> itemIds = new ArrayList<>();
        List<List<Map<String, Object>>> jobsPerItem = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (Planned p : plan) {
                    DeviceScope.Origin origin = DeviceScope.Origin.of(c, source);
                    var item = IngestItemRepository
                            .newItem(IngestArtifactStore.Kind.E2E, "portal-oct", absolutePath)
                            .originalFilename(originalName)
                            .contentType("application/octet-stream")
                            .digest(sha256, size)
                            .pixelSha256(p.pixelSha256())
                            .scanIndex(p.scanIndex())
                            .e2eSeriesKey(p.key())
                            .seriesModality(p.kind())
                            .seriesFrames(p.series().frames())
                            // The analysable test: an .e2e row is an OCT volume unless it says it is not.
                            .octVolume("OCT".equals(p.kind()))
                            .laterality(p.laterality())
                            .acquisitionDate(p.date())
                            .acquisitionDateSource(p.date() == null ? null : IngestItemRepository.ACQ_SOURCE_FILE)
                            .patientId(pid)
                            .imagingModalityId(p.modalityId())
                            .candidateStudySubjectId(p.subjectId())
                            .originLocationId(origin.locationId())
                            .ingestSourceId(origin.ingestSourceId());
                    if (!p.park()) {
                        ImageIngestBinding.EventTarget target =
                                PublicOctUploadController.resolveBindTarget(c, p.eventCrfId(), p.studyEventId());
                        if (target != null) {
                            item.boundTo(target.studySubjectId(), target.studyEventId(), target.eventCrfId(),
                                    IngestBindService.POLICY_VISIT_PICKED);
                        }
                    }
                    long id = item.insert(c);
                    itemIds.add(id);
                    List<Map<String, Object>> jobs = new ArrayList<>();
                    if (!p.park()) {
                        for (String task : p.tasks()) {
                            long jid = oct.insertJob(c, p.eventCrfId(), p.studyEventId(), task, absolutePath,
                                    p.laterality(), jobStatus, p.scanIndex(), sha256, id);
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("jobId", jid);
                            info.put("task", task);
                            info.put("status", jobStatus);
                            jobs.add(info);
                        }
                    }
                    jobsPerItem.add(jobs);
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            discard(saved);
            if ("23505".equals(e.getSQLState())) {
                return duplicate(visibleOnly(existingItems(dataSource, sha256), visible));
            }
            LOG.error("Split E2E upload - could not write the items: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to file the upload"));
        } catch (RuntimeException e) {
            discard(saved);
            LOG.error("Split E2E upload - could not write the items: {}", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to file the upload"));
        }

        // ---- 6. audit, held-back trail, response ----
        List<Map<String, Object>> items = new ArrayList<>();
        boolean anyBound = false;
        for (int i = 0; i < plan.size(); i++) {
            Planned p = plan.get(i);
            long id = itemIds.get(i);
            List<Map<String, Object>> jobs = jobsPerItem.get(i);
            String status = p.park() ? "UNBOUND" : "BOUND";
            anyBound |= !p.park();
            if (jobs.isEmpty()) {
                oct.writePublicOctUploadAuditRow("ingest_item", id, p.subjectId(), pid, p.laterality(), status);
            } else {
                for (Map<String, Object> j : jobs) {
                    oct.writePublicOctUploadAuditRow(((Number) j.get("jobId")).longValue(), p.subjectId(), pid,
                            p.laterality(), jobStatus);
                }
            }
            if (p.twin() != null) IngestTwins.writeHeldAudit(dataSource, id, p.twin(), pid, null, "portal-oct");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ingestItemId", id);
            m.put("seriesKey", p.key());
            m.put("seriesModality", p.kind());
            m.put("laterality", p.laterality());
            m.put("acquisitionDate", p.date() == null ? null : p.date().toString());
            m.put("frames", p.series().frames());
            m.put("imagingModalityId", p.modalityId());
            m.put("status", status);
            m.put("jobs", jobs);
            if (p.twin() != null) {
                m.put("heldBack", true);
                m.put("sameImageAs", p.twin().ingestItemId());
            }
            items.add(m);
        }
        LOG.info("Split E2E upload - {} item(s) from one file, headerPatient={}, bound={}",
                plan.size(), described.headerPatient(), anyBound);

        // ---- 7. start what the plans want, off the request thread ----
        List<Planned> withJobs = new ArrayList<>();
        List<List<Map<String, Object>>> jobsForRun = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            if (!jobsPerItem.get(i).isEmpty()) {
                withJobs.add(plan.get(i));
                jobsForRun.add(jobsPerItem.get(i));
            }
        }
        if (!withJobs.isEmpty()) {
            final Path path = saved;
            final String name = originalName;
            CompletableFuture.runAsync(() -> {
                try {
                    runJobs(withJobs, jobsForRun, path, name, dispatchToRemote);
                } catch (Exception ex) {
                    LOG.warn("Split E2E post-commit pipeline threw: {}", ex.getClass().getSimpleName());
                }
            });
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sha256", sha256);
        body.put("headerPatient", described.headerPatient());
        body.put("parked", !anyBound);
        body.put("items", items);
        return ResponseEntity.status(201).body(body);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private ResponseEntity<?> duplicate(List<Long> ids) {
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("message", MESSAGE_DUPLICATE);
        dup.put("duplicate", true);
        dup.put("existingIngestItemIds", ids);
        return ResponseEntity.status(409).body(dup);
    }

    private static void discard(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // an orphan file is harmless; ops can clear it
        }
    }

    /** A reason the sidecar's answer cannot be filed, or null. */
    static String validate(E2eSidecarClient.Description d) {
        if (d == null || d.headerPatient() == null || !HEADER_STATES.contains(d.headerPatient())) {
            return "headerPatient";
        }
        Set<String> keys = new HashSet<>();
        for (E2eSidecarClient.Series s : d.series()) {
            if (s == null || s.seriesKey() == null || !SERIES_KEY.matcher(s.seriesKey()).matches()) {
                return "seriesKey";
            }
            if (!keys.add(s.seriesKey())) return "duplicate seriesKey";
            if (s.seriesModality() == null || !SERIES_KINDS.contains(s.seriesModality())) {
                return "seriesModality";
            }
        }
        return null;
    }

    /** The date part of an ISO local date-time (or date); null when absent or unreadable. */
    static LocalDate dateOf(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try {
            return LocalDate.parse(iso.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lateralityOf(String raw) {
        if (raw == null) return null;
        String l = raw.trim().toUpperCase(Locale.ROOT);
        return "OD".equals(l) || "OS".equals(l) ? l : null;
    }

    /**
     * Preprocess each OCT volume that has jobs (the sidecar converts one
     * {@code scan_index} per call, streaming the stored file), then hand the
     * jobs to the GPU host. Soft-failing, as the per-volume path is.
     */
    private void runJobs(List<Planned> planned, List<List<Map<String, Object>>> jobs, Path saved,
                         String originalName, boolean dispatchToRemote) {
        String uuid = RetinalJobAccess.artifactKey(saved.toString());
        for (int i = 0; i < planned.size(); i++) {
            Planned p = planned.get(i);
            List<Map<String, Object>> js = jobs.get(i);
            long primary = ((Number) js.get(0).get("jobId")).longValue();
            if (remoteClient != null) {
                try {
                    var prep = remoteClient.preprocessUploadFile(primary,
                            originalName != null ? originalName : saved.getFileName().toString(),
                            saved, p.laterality(), uuid, p.scanIndex());
                    if (prep == null) {
                        LOG.warn("Preprocess returned nothing for split upload (primary job {})", primary);
                    }
                } catch (Exception e) {
                    LOG.warn("Preprocess threw for split upload (primary job {}): {}", primary,
                            e.getClass().getSimpleName());
                }
            }
            if (dispatchToRemote && inferenceController != null) {
                for (Map<String, Object> info : js) {
                    long jid = ((Number) info.get("jobId")).longValue();
                    try {
                        inferenceController.handleRemote(jid, String.valueOf(info.get("task")), saved.toString(),
                                p.laterality(), p.scanIndex(), p.eventCrfId());
                    } catch (Exception e) {
                        LOG.warn("Remote dispatch threw for split upload job {}: {}", jid,
                                e.getClass().getSimpleName());
                    }
                }
            }
        }
    }
}
