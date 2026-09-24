/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 * For details see: https://libreclinica.org/license
 * LibreClinica, copyright (C) 2020-2026
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.retinal.RetinalInferenceJobStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DR-035 — retinal inference jobs follow the file.
 *
 * <p>A job used to copy its visit ids at enqueue time and never look at the
 * ingest row again. Remove the scan from the visit and the jobs stayed, so
 * the old visit's results tab kept showing metrics from a scan no longer filed
 * there; file it somewhere else and nothing started, because an inbox bind
 * never enqueued anything. This is the reverse of that: the results belong to
 * the scan, the visit's imaging plan says what work the scan still owes.
 *
 * <ul>
 *   <li>{@link #detach}: on unbind, every job of the file loses its visit.
 *       Jobs not yet running become {@code cancelled}; running or finished
 *       ones keep going and keep their results, unattached.</li>
 *   <li>{@link #ensure}: on bind (and on a plan catch-up), existing jobs are
 *       attached to the visit — finished results are re-pointed, not
 *       re-computed — and the plan's tasks that have no live job are enqueued
 *       and dispatched, exactly as the OCT portal would have.</li>
 * </ul>
 *
 * <p>The plan decides only what <em>new</em> work starts. Results that already
 * exist follow the scan whatever the plan says now: hiding computed metrics
 * because a configuration changed is not something a clinical system should do
 * quietly.
 *
 * <p>Only OCT volumes ({@code kind = 'e2e'}) are inferred on; every other kind
 * is a no-op here. The study's inference switch gates the enqueueing, as it
 * gates the portal's.
 */
public final class RetinalJobFollower {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalJobFollower.class);

    private final DataSource dataSource;
    private final RemoteRetinalInferenceClient remoteClient;
    private final RetinalInferenceApiController inferenceController;

    /**
     * @param remoteClient        nullable — without both dispatch pieces the
     *                            follower detaches and attaches but never
     *                            starts a job (see {@link #ensure})
     * @param inferenceController nullable — same
     */
    public RetinalJobFollower(DataSource dataSource, RemoteRetinalInferenceClient remoteClient,
                              RetinalInferenceApiController inferenceController) {
        this.dataSource = dataSource;
        this.remoteClient = remoteClient;
        this.inferenceController = inferenceController;
    }

    /** Detach-only use, where nothing is ever dispatched. */
    public RetinalJobFollower(DataSource dataSource) {
        this(dataSource, null, null);
    }

    /* ------------------------------------------------------------------ */
    /* Pure logic                                                          */
    /* ------------------------------------------------------------------ */

    /** What is already there for one task of the scan. */
    public record ExistingJob(long jobId, String task, String status) {}

    /** One thing to do for one task. */
    public enum Action { ATTACH, REVIVE, ENQUEUE }

    public record Step(Action action, String task, Long jobId) {}

    /**
     * The work a bind owes, given what the plan wants and what exists.
     *
     * <p>Every existing job that is not cancelled is attached, planned or not —
     * its result belongs to the scan. A cancelled job whose task the plan wants
     * is revived rather than duplicated (the dedup key is scan + task, so a
     * second row could not exist anyway). A planned task with no job at all is
     * enqueued. A cancelled job for a task the plan no longer wants stays
     * cancelled.
     */
    public static List<Step> plan(List<String> wantedTasks, List<ExistingJob> existing) {
        List<Step> steps = new ArrayList<>();
        Map<String, ExistingJob> byTask = new LinkedHashMap<>();
        for (ExistingJob j : existing) byTask.putIfAbsent(j.task(), j);
        String cancelled = RetinalInferenceJobStatus.CANCELLED.dbValue();
        for (ExistingJob j : byTask.values()) {
            if (!cancelled.equals(j.status())) steps.add(new Step(Action.ATTACH, j.task(), j.jobId()));
        }
        for (String task : wantedTasks) {
            ExistingJob j = byTask.get(task);
            if (j == null) {
                steps.add(new Step(Action.ENQUEUE, task, null));
            } else if (cancelled.equals(j.status())) {
                steps.add(new Step(Action.REVIVE, task, j.jobId()));
            }
        }
        return steps;
    }

    /* ------------------------------------------------------------------ */
    /* detach                                                              */
    /* ------------------------------------------------------------------ */

    public record Detached(int detached, int cancelled) {
        public boolean nothing() {
            return detached == 0 && cancelled == 0;
        }
    }

    /**
     * Take every job of the file off its visit; cancel those not yet running.
     *
     * <p>Runs before the ingest row is unbound, so a failure here leaves a
     * bound file — the state the operator was already correcting — rather than
     * an unbound file whose jobs still claim a visit.
     */
    public Detached detach(long ingestItemId, IngestBindService.Actor actor) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            int cancelled;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE retinal_inference_job SET status = ?, "
                            + "status_message = 'scan removed from its visit before this job ran' "
                            + " WHERE ingest_item_id = ? AND status IN ('queued','remote_pending','parked')")) {
                ps.setString(1, RetinalInferenceJobStatus.CANCELLED.dbValue());
                ps.setLong(2, ingestItemId);
                cancelled = ps.executeUpdate();
            }
            int detached;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE retinal_inference_job SET event_crf_id = NULL, study_event_id = NULL "
                            + " WHERE ingest_item_id = ? "
                            + "   AND (event_crf_id IS NOT NULL OR study_event_id IS NOT NULL)")) {
                ps.setLong(1, ingestItemId);
                detached = ps.executeUpdate();
            }
            Detached out = new Detached(detached, cancelled);
            if (!out.nothing()) {
                audit(AuditTypeIds.RETINAL_JOBS_DETACHED, ingestItemId, actor,
                        "retinal jobs detached from the visit", "attached",
                        "detached=" + detached + ";cancelled=" + cancelled);
                LOG.info("ingest_item {}: {} retinal job(s) detached, {} cancelled",
                        ingestItemId, detached, cancelled);
            }
            return out;
        }
    }

    /* ------------------------------------------------------------------ */
    /* ensure                                                              */
    /* ------------------------------------------------------------------ */

    public record Ensured(int attached, int revived, int enqueued, String skippedBecause) {
        public static Ensured skipped(String why) {
            return new Ensured(0, 0, 0, why);
        }

        public int started() {
            return revived + enqueued;
        }
    }

    /** What {@link #ensure} reads about the scan. */
    private record Scan(long ingestItemId, String kind, String storedPath, String sha256,
                        int scanIndex, String laterality, Integer modalityId,
                        Integer boundSubjectId, Integer boundStudyEventId, Integer boundEventCrfId) {}

    /**
     * Attach what exists and start what the plan still wants, for a BOUND scan.
     *
     * @param dryRun count without writing or dispatching — the editor's
     *               "apply to filed scans: N scans, M new jobs" line
     */
    public Ensured ensure(long ingestItemId, IngestBindService.Actor actor, boolean dryRun)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Scan scan = readScan(c, ingestItemId);
            if (scan == null) return Ensured.skipped("no such ingest_item");
            if (!"e2e".equalsIgnoreCase(scan.kind())) return Ensured.skipped("not an OCT volume");
            if (scan.boundStudyEventId() == null && scan.boundEventCrfId() == null) {
                return Ensured.skipped("not bound to a visit");
            }

            Integer studyId = VisitImagingPlan.studyOfBinding(c, scan.boundEventCrfId(), scan.boundStudyEventId());
            if (studyId == null) return Ensured.skipped("visit has no study");

            Integer modalityId = scan.modalityId();
            if (modalityId == null) {
                modalityId = VisitImagingPlan.e2eModalityOf(c, studyId);
                if (modalityId != null && !dryRun) stampModality(c, ingestItemId, modalityId);
            }

            List<String> wanted = List.of();
            boolean inferenceOn = new StudySettingService(dataSource)
                    .isEnabled(studyId, StudySettingService.INFERENCE_ENABLED);
            if (inferenceOn) {
                wanted = VisitImagingPlan.tasksFor(c, scan.boundEventCrfId(), scan.boundStudyEventId(), modalityId)
                        .tasks();
            }

            // A follower built without the dispatch pieces attaches only. It must
            // not enqueue: a 'queued' row nobody hands to the GPU host is drained
            // by the local placeholder worker, and a placeholder result on a
            // real visit is worse than no result.
            boolean canStart = canDispatch();
            List<Step> steps = plan(wanted, existingJobs(c, scan));
            int attached = 0;
            int revived = 0;
            int enqueued = 0;
            int notStarted = 0;
            List<Map<String, Object>> toDispatch = new ArrayList<>();
            for (Step s : steps) {
                switch (s.action()) {
                    case ATTACH -> {
                        attached++;
                        if (!dryRun) attach(c, s.jobId(), scan);
                    }
                    case REVIVE -> {
                        if (!canStart) { notStarted++; continue; }
                        revived++;
                        if (!dryRun) {
                            revive(c, s.jobId(), scan, initialStatus());
                            toDispatch.add(jobInfo(s.jobId(), s.task()));
                        }
                    }
                    case ENQUEUE -> {
                        if (!canStart) { notStarted++; continue; }
                        enqueued++;
                        if (!dryRun) {
                            long id = insertJob(c, scan, s.task(), initialStatus());
                            toDispatch.add(jobInfo(id, s.task()));
                        }
                    }
                }
            }
            String why = null;
            if (!inferenceOn) why = "inference switched off for the study";
            else if (notStarted > 0) why = notStarted + " task(s) not started: no inference dispatcher wired";
            Ensured out = new Ensured(attached, revived, enqueued, why);
            if (notStarted > 0) {
                LOG.warn("ingest_item {}: {} planned task(s) not started — this follower has no dispatcher",
                        ingestItemId, notStarted);
            }
            if (dryRun) return out;

            if (attached + revived + enqueued > 0) {
                audit(AuditTypeIds.RETINAL_JOBS_FOLLOWED_FILE, ingestItemId, actor,
                        "retinal jobs followed the scan to its visit", "unattached",
                        "attached=" + attached + ";revived=" + revived + ";enqueued=" + enqueued
                                + ";tasks=" + String.join(",", wanted));
                LOG.info("ingest_item {}: retinal jobs followed the scan — attached={} revived={} enqueued={} (plan: {})",
                        ingestItemId, attached, revived, enqueued, wanted);
            }
            if (!toDispatch.isEmpty()) dispatchAsync(scan, toDispatch, actor);
            return out;
        }
    }

    /**
     * Every BOUND OCT volume at a visit of this definition, ensured.
     *
     * <p>The editor's catch-up after a plan change. Each scan is handled on its
     * own so one bad row does not stop the rest; the counts say what happened.
     */
    public record CatchUp(int scans, int attached, int revived, int enqueued, int failed) {}

    public CatchUp catchUp(int sedId, IngestBindService.Actor actor, boolean dryRun) throws SQLException {
        List<Long> items = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ii.ingest_item_id FROM ingest_item ii "
                             + "  JOIN study_event se ON se.study_event_id = ii.bound_study_event_id "
                             + " WHERE ii.status = 'BOUND' AND ii.kind = 'e2e' "
                             + "   AND se.study_event_definition_id = ? "
                             + " ORDER BY ii.ingest_item_id")) {
            ps.setInt(1, sedId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(rs.getLong(1));
            }
        }
        int attached = 0;
        int revived = 0;
        int enqueued = 0;
        int failed = 0;
        for (long id : items) {
            try {
                Ensured e = ensure(id, actor, dryRun);
                attached += e.attached();
                revived += e.revived();
                enqueued += e.enqueued();
            } catch (SQLException | RuntimeException ex) {
                failed++;
                LOG.warn("plan catch-up: ingest_item {} failed: {}", id, ex.getMessage());
            }
        }
        return new CatchUp(items.size(), attached, revived, enqueued, failed);
    }

    /* ------------------------------------------------------------------ */
    /* SQL                                                                 */
    /* ------------------------------------------------------------------ */

    private static Scan readScan(Connection c, long ingestItemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT kind, stored_path, sha256, scan_index, laterality, imaging_modality_id, "
                        + "       bound_study_subject_id, bound_study_event_id, bound_event_crf_id "
                        + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Scan(ingestItemId, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), intOrNull(rs, 6),
                        intOrNull(rs, 7), intOrNull(rs, 8), intOrNull(rs, 9));
            }
        }
    }

    /**
     * The scan's jobs: by ingest item, and — for rows the portal wrote before
     * {@code ingest_item_id} existed — by content hash and scan index.
     */
    private static List<ExistingJob> existingJobs(Connection c, Scan scan) throws SQLException {
        List<ExistingJob> out = new ArrayList<>();
        String sql = "SELECT job_id, task, status FROM retinal_inference_job "
                + " WHERE ingest_item_id = ? "
                + (scan.sha256() == null || scan.sha256().isBlank()
                        ? "" : "    OR (e2e_sha256 = ? AND scan_index = ?) ")
                + " ORDER BY job_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, scan.ingestItemId());
            if (scan.sha256() != null && !scan.sha256().isBlank()) {
                ps.setString(2, scan.sha256());
                ps.setInt(3, scan.scanIndex());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ExistingJob(rs.getLong(1), rs.getString(2),
                            rs.getString(3) == null ? "" : rs.getString(3).toLowerCase(Locale.ROOT)));
                }
            }
        }
        return out;
    }

    private static void attach(Connection c, long jobId, Scan scan) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE retinal_inference_job SET event_crf_id = ?, study_event_id = ?, ingest_item_id = ? "
                        + " WHERE job_id = ?")) {
            setIntOrNull(ps, 1, scan.boundEventCrfId());
            setIntOrNull(ps, 2, scan.boundStudyEventId());
            ps.setLong(3, scan.ingestItemId());
            ps.setLong(4, jobId);
            ps.executeUpdate();
        }
    }

    private static void revive(Connection c, long jobId, Scan scan, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE retinal_inference_job SET event_crf_id = ?, study_event_id = ?, ingest_item_id = ?, "
                        + "status = ?, status_message = NULL, enqueued_at = ? "
                        + " WHERE job_id = ?")) {
            setIntOrNull(ps, 1, scan.boundEventCrfId());
            setIntOrNull(ps, 2, scan.boundStudyEventId());
            ps.setLong(3, scan.ingestItemId());
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setLong(6, jobId);
            ps.executeUpdate();
        }
    }

    private static long insertJob(Connection c, Scan scan, String task, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO retinal_inference_job ("
                        + "event_crf_id, study_event_id, task, e2e_path, eye_laterality, status, scan_index, "
                        + "enqueued_at, e2e_sha256, ingest_item_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            setIntOrNull(ps, 1, scan.boundEventCrfId());
            setIntOrNull(ps, 2, scan.boundStudyEventId());
            ps.setString(3, task);
            ps.setString(4, scan.storedPath());
            ps.setString(5, scan.laterality());
            ps.setString(6, status);
            ps.setInt(7, scan.scanIndex());
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            if (scan.sha256() == null || scan.sha256().isBlank()) ps.setNull(9, java.sql.Types.VARCHAR);
            else ps.setString(9, scan.sha256());
            ps.setLong(10, scan.ingestItemId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("retinal_inference_job INSERT returned no PK");
            }
        }
    }

    private static void stampModality(Connection c, long ingestItemId, int modalityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ingest_item SET imaging_modality_id = ? WHERE ingest_item_id = ? AND imaging_modality_id IS NULL")) {
            ps.setInt(1, modalityId);
            ps.setLong(2, ingestItemId);
            ps.executeUpdate();
        }
    }

    /* ------------------------------------------------------------------ */
    /* dispatch                                                            */
    /* ------------------------------------------------------------------ */

    /** The dispatch pieces are wired at all — remote or the local queue. */
    private boolean canDispatch() {
        return remoteClient != null && inferenceController != null;
    }

    private boolean remoteConfigured() {
        return canDispatch() && remoteClient.isConfigured();
    }

    /** Mirrors the portal: remote when the GPU host is configured, else the local worker's queue. */
    private String initialStatus() {
        return remoteConfigured()
                ? RetinalInferenceJobStatus.REMOTE_PENDING.dbValue()
                : RetinalInferenceJobStatus.QUEUED.dbValue();
    }

    /**
     * The portal's post-commit pipeline for jobs started here: preprocess the
     * volume once (a scan parked at upload was never preprocessed), then hand
     * each job to the GPU host. Off the request thread, soft-failing — a job
     * left at {@code remote_pending} is what the retry button is for.
     */
    private void dispatchAsync(Scan scan, List<Map<String, Object>> jobs, IngestBindService.Actor actor) {
        if (!remoteConfigured()) return;
        final int actorId = actor.userId() == null ? 0 : actor.userId();
        CompletableFuture.runAsync(() -> {
            Path path = Path.of(scan.storedPath());
            long primary = ((Number) jobs.get(0).get("jobId")).longValue();
            try {
                byte[] bytes = Files.readAllBytes(path);
                String name = path.getFileName().toString();
                String uuid = name.toLowerCase(Locale.ROOT).endsWith(".e2e")
                        ? name.substring(0, name.length() - 4) : name;
                remoteClient.preprocessUpload(primary, name, bytes, scan.laterality(), uuid, scan.scanIndex());
            } catch (IOException | RuntimeException ex) {
                LOG.warn("follow-file preprocess failed for ingest_item {} (job {}): {}",
                        scan.ingestItemId(), primary, ex.getMessage());
            }
            for (Map<String, Object> info : jobs) {
                long jobId = ((Number) info.get("jobId")).longValue();
                String task = String.valueOf(info.get("task"));
                try {
                    inferenceController.handleRemote(jobId, task, scan.storedPath(), scan.laterality(),
                            scan.scanIndex(), scan.boundEventCrfId(), actorId);
                } catch (RuntimeException ex) {
                    LOG.warn("follow-file remote dispatch failed for job {} ({}): {}", jobId, task, ex.getMessage());
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* small helpers                                                       */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> jobInfo(long jobId, String task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("task", task);
        return m;
    }

    private void audit(int type, long ingestItemId, IngestBindService.Actor actor,
                       String label, String oldValue, String newValue) {
        try {
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), type,
                    actor.user(), actor.study(), null, label,
                    "ingest_item", (int) ingestItemId, "retinal_jobs", oldValue, newValue);
        } catch (RuntimeException e) {
            LOG.warn("could not audit {} of ingest_item {}: {}", label, ingestItemId, e.getMessage());
        }
    }

    private static Integer intOrNull(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else ps.setInt(idx, v);
    }
}
