/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportJobDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportScheduleDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportScheduleRegistrar;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Async-export REST surface. Promotes Phase 1's synchronous
 * {@code POST /datasets/{id}/export} (which times out on multi-CRF
 * studies above ~1000 subjects) to fire-and-poll, and adds recurring
 * schedules backed by Quartz cron triggers.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code POST   /api/v1/datasets/{id}/exports} — enqueue an
 *       async export. Returns {@code 202 Accepted} +
 *       {@code ExportJobDto} (status='queued'). The synchronous
 *       sibling {@code POST /datasets/{id}/export} from Phase 1 stays
 *       in place for one-click small exports.</li>
 *   <li>{@code GET    /api/v1/exports/{jobId}} — current status of an
 *       async export. {@code downloadUrl} is non-null only when
 *       {@code status='done'}.</li>
 *   <li>{@code GET    /api/v1/exports/{jobId}/download} — convenience
 *       redirect-style stream of the finished file. {@code 410 Gone}
 *       if the {@code archived_dataset_file} or the underlying file on
 *       disk is missing.</li>
 *   <li>{@code GET    /api/v1/exports?status=&format=&page=&pageSize=}
 *       — paged list of the current user's jobs. Sysadmin sees all
 *       jobs.</li>
 *   <li>{@code GET    /api/v1/studies/{studyOid}/export-jobs} — recent
 *       jobs for the active study (last 50). Audit-style view, no
 *       user filter.</li>
 *   <li>{@code POST   /api/v1/datasets/{id}/schedules} — create a
 *       recurring schedule. Cron validated by
 *       {@link org.quartz.CronExpression}.</li>
 *   <li>{@code GET    /api/v1/datasets/{id}/schedules} — list active.</li>
 *   <li>{@code DELETE /api/v1/schedules/{id}} — soft-delete
 *       ({@code active=false}).</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 *
 * <p>Session-bound {@code userBean} required (401 anonymous). The
 * dataset / study scope checks delegate to the same legacy
 * predicate Phase 1's synchronous endpoint uses
 * ({@link DatasetsApiController#roleMayExportData(UserAccountBean, StudyUserRoleBean)}).
 *
 * <h2>Path naming</h2>
 *
 * <p>The plural {@code /datasets/{id}/exports} is deliberate — it
 * does not collide with Phase 1's singular {@code /datasets/{id}/export}
 * (the sync one-shot). REST collection conventions: the singular
 * verb-style endpoint stays for the small-export shortcut, the
 * plural collection endpoint is the canonical async path.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Export Jobs",
     description = "Async data export — fire-and-poll + recurring schedules.")
public class ExportJobsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJobsApiController.class);

    /** Accepted format strings — kept in lock-step with the SPA dropdown + DatasetsApiController.ExportFormatKey. */
    private static final Set<String> SUPPORTED_FORMATS =
            Set.of("odm", "csv", "tsv", "tab", "excel", "xls", "xlsx", "sas", "spss");

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final DataSource dataSource;
    private final ExportScheduleRegistrar registrar;

    @Autowired
    public ExportJobsApiController(@Qualifier("dataSource") DataSource dataSource,
                                   ExportScheduleRegistrar registrar) {
        this.dataSource = dataSource;
        this.registrar = registrar;
    }

    /* ================================================================== */
    /* Export jobs                                                        */
    /* ================================================================== */

    /**
     * Phase 4 async enqueue. Returns 202 + a {@link ExportJobDto} so
     * the SPA can immediately surface the job in the table and poll
     * {@code GET /exports/{jobId}}. Quartz picks the row up on the
     * next 30 s tick (see {@link ExportScheduleRegistrar}).
     */
    @PostMapping("/datasets/{id}/exports")
    @ApiResponse(responseCode = "202",
                 content = @Content(schema = @Schema(implementation = ExportJobDto.class)))
    public ResponseEntity<?> enqueueExport(@PathVariable("id") int datasetId,
                                           @RequestBody(required = false) EnqueueExportRequest body,
                                           HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!DatasetsApiController.roleMayExportData(me, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit exporting data."));
        }
        String format = body == null ? null : body.format();
        if (format == null || format.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'format' is required"));
        }
        format = format.trim().toLowerCase();
        if (!SUPPORTED_FORMATS.contains(format)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Unsupported format '" + format + "' — accepted: " + SUPPORTED_FORMATS));
        }
        DatasetBean ds = loadDataset(datasetId);
        if (ds == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }

        ExportJobDAO jobDao = new ExportJobDAO(dataSource);
        long jobId = jobDao.insertQueued(datasetId, format, me.getId());
        if (jobId <= 0) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to enqueue export job"));
        }
        ExportJobDAO.Row row = jobDao.findById(jobId);
        LOG.info("Enqueue export: dataset_id={} format={} job_id={} by user={}",
                datasetId, format, jobId, me.getName());
        return ResponseEntity.status(202).body(toJobDto(row));
    }

    @GetMapping("/exports/{jobId}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ExportJobDto.class)))
    public ResponseEntity<?> getJob(@PathVariable("jobId") long jobId,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        ExportJobDAO.Row row = new ExportJobDAO(dataSource).findById(jobId);
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No export job with id " + jobId));
        }
        if (!canSeeJob(me, row)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Not authorized to view this export job"));
        }
        return ResponseEntity.ok(toJobDto(row));
    }

    /**
     * Convenience: stream the finished file by job id (no need for
     * the SPA to also resolve the archived_dataset_file id). Returns
     * 410 if the job is in any non-{@code done} state, or if the
     * underlying file is missing from disk.
     */
    @GetMapping("/exports/{jobId}/download")
    public void downloadJob(@PathVariable("jobId") long jobId,
                            HttpSession session,
                            HttpServletResponse response) throws IOException {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
            return;
        }
        ExportJobDAO.Row row = new ExportJobDAO(dataSource).findById(jobId);
        if (row == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No export job with id " + jobId);
            return;
        }
        if (!canSeeJob(me, row)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not authorized");
            return;
        }
        if (!ExportJobDAO.STATUS_DONE.equals(row.status) || row.archivedDatasetFileId == null) {
            response.sendError(HttpServletResponse.SC_GONE,
                    "Export job is not in 'done' state");
            return;
        }
        ArchivedDatasetFileBean adf = (ArchivedDatasetFileBean)
                new ArchivedDatasetFileDAO(dataSource).findByPK(row.archivedDatasetFileId);
        if (adf == null || adf.getId() == 0) {
            response.sendError(HttpServletResponse.SC_GONE,
                    "Archived file metadata missing");
            return;
        }
        File f = new File(adf.getFileReference() == null ? "" : adf.getFileReference());
        if (!f.isFile()) {
            response.sendError(HttpServletResponse.SC_GONE,
                    "Underlying file no longer on disk");
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + safeFilename(adf.getName(), f.getName()) + "\"");
        response.setContentLengthLong(f.length());
        try (InputStream in = Files.newInputStream(f.toPath());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }

    /**
     * Paged list of jobs. Non-sysadmin: jobs the caller submitted.
     * Sysadmin: every job. {@code status} / {@code format} narrow the
     * result set; both are optional.
     */
    @GetMapping("/exports")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ExportJobListResponse.class)))
    public ResponseEntity<?> listJobs(@RequestParam(value = "status", required = false) String status,
                                      @RequestParam(value = "format", required = false) String format,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                      HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        int p = (page == null || page < 0) ? 0 : page;
        int ps = (pageSize == null || pageSize <= 0) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String normFormat = (format == null || format.isBlank()) ? null : format.trim().toLowerCase();
        String normStatus = (status == null || status.isBlank()) ? null : status.trim().toLowerCase();
        Integer requesterFilter = me.isSysAdmin() ? null : me.getId();

        ExportJobDAO dao = new ExportJobDAO(dataSource);
        long total = dao.countFiltered(requesterFilter, normStatus, normFormat);
        List<ExportJobDAO.Row> rows = dao.findFiltered(requesterFilter, normStatus, normFormat, p, ps);
        List<ExportJobDto> out = new ArrayList<>(rows.size());
        for (ExportJobDAO.Row r : rows) out.add(toJobDto(r));
        return ResponseEntity.ok(new ExportJobListResponse(out, total, p, ps));
    }

    @GetMapping("/studies/{studyOid}/export-jobs")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                                                      implementation = ExportJobDto.class)))
    public ResponseEntity<?> listJobsByStudy(@PathVariable("studyOid") String studyOid,
                                             HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = new StudyDAO(dataSource).findByOid(studyOid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        List<ExportJobDAO.Row> rows = new ExportJobDAO(dataSource).findRecentByStudy(study.getId());
        List<ExportJobDto> out = new ArrayList<>(rows.size());
        for (ExportJobDAO.Row r : rows) out.add(toJobDto(r));
        return ResponseEntity.ok(out);
    }

    /* ================================================================== */
    /* Schedules                                                          */
    /* ================================================================== */

    @PostMapping("/datasets/{id}/schedules")
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = ExportScheduleDto.class)))
    public ResponseEntity<?> createSchedule(@PathVariable("id") int datasetId,
                                            @RequestBody(required = false) CreateScheduleRequest body,
                                            HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!DatasetsApiController.roleMayExportData(me, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit exporting data."));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Request body is required"));
        }
        String format = body.format() == null ? "" : body.format().trim().toLowerCase();
        if (format.isEmpty() || !SUPPORTED_FORMATS.contains(format)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Unsupported format '" + format + "' — accepted: " + SUPPORTED_FORMATS));
        }
        String cron = body.cronExpression() == null ? "" : body.cronExpression().trim();
        if (!registrar.isValidCron(cron)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Invalid cron expression: '" + cron + "'"));
        }
        DatasetBean ds = loadDataset(datasetId);
        if (ds == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }

        Instant nextRun = registrar.computeNextFireTime(cron);
        ExportScheduleDAO scheduleDao = new ExportScheduleDAO(dataSource);
        long id = scheduleDao.create(datasetId, format, cron, me.getId(), nextRun);
        if (id <= 0) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist schedule"));
        }
        try {
            registrar.registerSchedule(id, datasetId, format, cron);
        } catch (Exception e) {
            // Row is persisted; runtime registration failed. The boot
            // pass picks it up on the next app restart.
            LOG.warn("Schedule id={} persisted but Quartz registration failed: {}",
                    id, e.getMessage());
        }
        ExportScheduleDAO.Row row = scheduleDao.findById(id);
        LOG.info("Create schedule: dataset_id={} format={} cron='{}' id={} by user={}",
                datasetId, format, cron, id, me.getName());
        return ResponseEntity.status(201).body(toScheduleDto(row));
    }

    @GetMapping("/datasets/{id}/schedules")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                                                      implementation = ExportScheduleDto.class)))
    public ResponseEntity<?> listSchedules(@PathVariable("id") int datasetId,
                                           HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        DatasetBean ds = loadDataset(datasetId);
        if (ds == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }
        List<ExportScheduleDAO.Row> rows =
                new ExportScheduleDAO(dataSource).findByDataset(datasetId, /* includeInactive */ false);
        List<ExportScheduleDto> out = new ArrayList<>(rows.size());
        for (ExportScheduleDAO.Row r : rows) out.add(toScheduleDto(r));
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/schedules/{id}")
    @ApiResponse(responseCode = "204")
    public ResponseEntity<?> deleteSchedule(@PathVariable("id") long scheduleId,
                                            HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        ExportScheduleDAO dao = new ExportScheduleDAO(dataSource);
        ExportScheduleDAO.Row existing = dao.findById(scheduleId);
        if (existing == null) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No schedule with id " + scheduleId));
        }
        dao.deactivate(scheduleId);
        registrar.unregisterSchedule(scheduleId);
        LOG.info("Soft-delete schedule: id={} by user={}", scheduleId, me.getName());
        return ResponseEntity.noContent().build();
    }

    /* ================================================================== */
    /* Helpers                                                            */
    /* ================================================================== */

    private DatasetBean loadDataset(int datasetId) {
        DatasetBean ds = (DatasetBean) new DatasetDAO(dataSource).findByPK(datasetId);
        return (ds == null || ds.getId() == 0) ? null : ds;
    }

    /** Sysadmin sees every job; everybody else only their own. */
    private static boolean canSeeJob(UserAccountBean me, ExportJobDAO.Row row) {
        if (me.isSysAdmin()) return true;
        return row.submittedBy == me.getId();
    }

    static ExportJobDto toJobDto(ExportJobDAO.Row r) {
        if (r == null) return null;
        String downloadUrl = null;
        if (ExportJobDAO.STATUS_DONE.equals(r.status) && r.archivedDatasetFileId != null) {
            // Prefer the by-job download (no need for the SPA to know the
            // archived_dataset_file id), but the legacy
            // /archived-files/{id}/download from Phase 1 also works.
            downloadUrl = "/LibreClinica/pages/api/v1/exports/" + r.id + "/download";
        }
        return new ExportJobDto(
                r.id,
                r.datasetId,
                r.format,
                r.status,
                progressPctFor(r),
                toIso(r.submittedAt),
                toIso(r.startedAt),
                toIso(r.finishedAt),
                r.archivedDatasetFileId,
                r.errorMessage,
                downloadUrl);
    }

    /**
     * Coarse progress used by the SPA progress bar. We don't track
     * sub-step progress server-side (the legacy extract pipeline emits
     * no progress events), so we map states to fixed percentages.
     */
    private static int progressPctFor(ExportJobDAO.Row r) {
        return switch (r.status) {
            case ExportJobDAO.STATUS_QUEUED -> 0;
            case ExportJobDAO.STATUS_RUNNING -> 50;
            case ExportJobDAO.STATUS_DONE -> 100;
            case ExportJobDAO.STATUS_FAILED -> 100;
            default -> 0;
        };
    }

    static ExportScheduleDto toScheduleDto(ExportScheduleDAO.Row r) {
        if (r == null) return null;
        return new ExportScheduleDto(
                r.id,
                r.datasetId,
                r.format,
                r.cronExpression,
                r.active,
                toIso(r.createdAt),
                toIso(r.nextRunAt),
                toIso(r.lastRunAt),
                r.lastRunJobId);
    }

    private static String toIso(Instant t) {
        return t == null ? null : t.toString();
    }

    private static String safeFilename(String stored, String fallback) {
        String pick = (stored == null || stored.isBlank()) ? fallback : stored;
        // Strip CR / LF / quote chars to keep the Content-Disposition
        // header well-formed.
        return pick.replaceAll("[\\r\\n\"]", "_");
    }

    /* ================================================================== */
    /* Request / response body records                                    */
    /* ================================================================== */

    public record EnqueueExportRequest(String format) {}

    public record CreateScheduleRequest(String format, String cronExpression) {}

    /** Wire shape of {@code GET /exports?...}. */
    public record ExportJobListResponse(
            List<ExportJobDto> jobs,
            long total,
            int page,
            int pageSize) {}
}
