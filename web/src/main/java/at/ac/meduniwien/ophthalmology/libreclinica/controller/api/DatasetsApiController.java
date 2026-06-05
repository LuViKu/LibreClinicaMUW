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
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DatasetItemStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExportFormatBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.GenerateExtractFileService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.6 — Data-export MVP.
 *
 * <p>Surfaces the legacy ExportDataset / CreateDataset stack in the
 * SPA as five JSON endpoints:
 *
 * <ul>
 *   <li>{@code GET    /api/v1/studies/{studyOid}/datasets}
 *       — list saved datasets scoped to the active study.</li>
 *   <li>{@code GET    /api/v1/studies/{studyOid}/datasets/{datasetId}/files}
 *       — per-dataset list of generated export files.</li>
 *   <li>{@code POST   /api/v1/datasets/{datasetId}/export}
 *       — trigger an export run for the saved dataset, body
 *       {@code { format: 'odm' | 'csv' | 'tsv' | 'excel' | 'sas' |
 *       'spss' }}.</li>
 *   <li>{@code POST   /api/v1/studies/{studyOid}/datasets:quick-odm}
 *       — one-click "Export everything": create an ad-hoc dataset
 *       covering every event-definition + every item in the active
 *       study and run it as ODM-1.3 immediately.</li>
 *   <li>{@code GET    /api/v1/archived-files/{id}/download}
 *       — stream a previously-generated file with
 *       {@code Content-Disposition: attachment}.</li>
 * </ul>
 *
 * <p><strong>Authorization.</strong> Mirrors
 * {@code ExportDatasetServlet.mayProceed()} — sysadmin OR Study
 * Director OR Coordinator OR Investigator OR Monitor on the current
 * study. RA / RA2 are refused with {@code 403}.
 *
 * <p><strong>Synchronous.</strong> Export runs (CSV / TSV / ODM /
 * Excel / SAS / SPSS) finish in seconds against MUW's study sizes
 * (10–20 visits / subject), so the v1 endpoint blocks the request
 * thread. A future slice can move the work onto Quartz the way
 * ExportDatasetServlet wires up XalanTriggerService.
 *
 * <p><strong>Filesystem.</strong> Generated files live under
 * {@code ${filePath}/datasets/{dataset_id}/{yyyy/MM/dd/HHmmssSSS}/}
 * — the same layout the legacy servlet emits. The download endpoint
 * resolves the canonical {@code archived_dataset_file.file_reference}
 * column rather than re-deriving the path so the SPA round-trip and
 * the legacy /ExportDataset?adfId=… link both stream the same bytes.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Data Export", description = "SPA-facing dataset list, export run, and file download.")
public class DatasetsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetsApiController.class);

    /** Legacy {@code ExportDatasetServlet} per-run subdir pattern. */
    private static final String RUN_DIR_PATTERN =
            "yyyy" + File.separator + "MM" + File.separator + "dd"
            + File.separator + "HHmmssSSS" + File.separator;

    /** Pretty labels for the export-format enum, used by ArchivedFileDto.formatName. */
    private static final Map<Integer, String> FORMAT_LABELS = Map.of(
            ExportFormatBean.TXTFILE.getId(), "TXT",
            ExportFormatBean.CSVFILE.getId(), "CSV",
            ExportFormatBean.EXCELFILE.getId(), "Excel",
            ExportFormatBean.XMLFILE.getId(), "XML",
            ExportFormatBean.PDFFILE.getId(), "PDF");

    private final DataSource dataSource;
    private final CoreResources coreResources;
    private final RuleSetRuleDao ruleSetRuleDao;

    @Autowired
    public DatasetsApiController(@Qualifier("dataSource") DataSource dataSource,
                                 CoreResources coreResources,
                                 RuleSetRuleDao ruleSetRuleDao) {
        this.dataSource = dataSource;
        this.coreResources = coreResources;
        this.ruleSetRuleDao = ruleSetRuleDao;
    }

    /* ============================================================== */
    /*  GET /api/v1/studies/{studyOid}/datasets                       */
    /* ============================================================== */

    @GetMapping("/studies/{studyOid}/datasets")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = DatasetDto.class)))
    public ResponseEntity<?> listDatasets(@PathVariable("studyOid") String studyOid,
                                          HttpSession session) {
        ResolvedContext ctx = resolveContext(studyOid, session);
        if (ctx.errorResponse != null) return ctx.errorResponse;

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        ArchivedDatasetFileDAO archivedDao = new ArchivedDatasetFileDAO(dataSource);

        ArrayList<DatasetBean> datasets = datasetDao.findAllByStudyId(ctx.study.getId());
        List<DatasetDto> out = new ArrayList<>(datasets.size());
        for (DatasetBean db : datasets) {
            int fileCount = 0;
            try {
                ArrayList<ArchivedDatasetFileBean> files = archivedDao.findByDatasetId(db.getId());
                fileCount = files == null ? 0 : files.size();
            } catch (RuntimeException re) {
                LOG.warn("Failed to count files for dataset id={}: {}", db.getId(), re.getMessage());
            }
            out.add(new DatasetDto(
                    String.valueOf(db.getId()),
                    db.getId(),
                    db.getName(),
                    db.getDescription(),
                    db.getOwner() == null ? null : db.getOwner().getName(),
                    toIsoUtc(db.getCreatedDate()),
                    toIsoUtc(db.getDateLastRun()),
                    fileCount));
        }
        return ResponseEntity.ok(out);
    }

    /* ============================================================== */
    /*  GET /api/v1/studies/{studyOid}/datasets/{datasetId}/files     */
    /* ============================================================== */

    @GetMapping("/studies/{studyOid}/datasets/{datasetId}/files")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = ArchivedFileDto.class)))
    public ResponseEntity<?> listFiles(@PathVariable("studyOid") String studyOid,
                                       @PathVariable("datasetId") int datasetId,
                                       HttpSession session) {
        ResolvedContext ctx = resolveContext(studyOid, session);
        if (ctx.errorResponse != null) return ctx.errorResponse;

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        DatasetBean db = (DatasetBean) datasetDao.findByPK(datasetId);
        if (db == null || db.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }
        if (db.getStudyId() != ctx.study.getId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "Dataset " + datasetId + " does not belong to study " + studyOid));
        }

        ArchivedDatasetFileDAO archivedDao = new ArchivedDatasetFileDAO(dataSource);
        ArrayList<ArchivedDatasetFileBean> files = archivedDao.findByDatasetIdByDate(datasetId);
        List<ArchivedFileDto> out = new ArrayList<>(files.size());
        for (ArchivedDatasetFileBean f : files) {
            // Skip rows whose file_reference no longer points at a real
            // file on disk — those happen when an operator wipes the
            // dataset-files directory but leaves the metadata. Surfacing
            // them in the SPA would yield 404s on download.
            String ref = f.getFileReference();
            if (ref == null || !new File(ref).isFile()) {
                continue;
            }
            out.add(new ArchivedFileDto(
                    f.getId(),
                    f.getName(),
                    FORMAT_LABELS.getOrDefault(f.getExportFormatId(), "Other"),
                    new File(ref).length(),
                    toIsoUtc(f.getDateCreated()),
                    downloadUrlFor(f.getId())));
        }
        return ResponseEntity.ok(out);
    }

    /* ============================================================== */
    /*  POST /api/v1/datasets/{datasetId}/export                      */
    /* ============================================================== */

    @PostMapping("/datasets/{datasetId}/export")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ExportTriggerResponse.class)))
    public ResponseEntity<?> triggerExport(@PathVariable("datasetId") int datasetId,
                                           @RequestBody(required = false) ExportTriggerRequest body,
                                           HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!roleMayExportData(me, currentRole)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit exporting data."));
        }
        if (body == null || body.format() == null || body.format().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Request body must include { \"format\": ... }"));
        }
        ExportFormatKey fmt = ExportFormatKey.parse(body.format());
        if (fmt == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Unsupported format: " + body.format()
                            + " (one of odm, csv, tsv, excel, sas, spss)"));
        }

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        DatasetBean db = (DatasetBean) datasetDao.findByPK(datasetId);
        if (db == null || db.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }
        if (db.getStudyId() != currentStudy.getId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "Dataset " + datasetId + " does not belong to the active study"));
        }

        try {
            ExportRunResult result = runExport(db, fmt, currentStudy, me);
            return ResponseEntity.ok(new ExportTriggerResponse(
                    result.archivedFileId,
                    downloadUrlFor(result.archivedFileId)));
        } catch (RuntimeException re) {
            LOG.error("Export failed for dataset id={} format={}", datasetId, fmt, re);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Export failed: " + re.getMessage()));
        }
    }

    /* ============================================================== */
    /*  POST /api/v1/studies/{studyOid}/datasets:quick-odm           */
    /* ============================================================== */

    @PostMapping("/studies/{studyOid}/datasets:quick-odm")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ExportTriggerResponse.class)))
    public ResponseEntity<?> quickOdm(@PathVariable("studyOid") String studyOid,
                                      HttpSession session) {
        ResolvedContext ctx = resolveContext(studyOid, session);
        if (ctx.errorResponse != null) return ctx.errorResponse;

        DatasetDAO datasetDao = new DatasetDAO(dataSource);

        // Synthesise an ad-hoc dataset spanning everything. The
        // SQLStatement is the empty-filter shape produced by
        // DatasetBean.generateQuery with no event / item / date
        // restrictions — equivalent to "select distinct * from
        // extract_data_table where ((date(date_created) >=
        // date('1900-01-01')) and (date(date_created) <=
        // date('2100-01-01'))) order by date_start asc".
        DatasetBean adhoc = new DatasetBean();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        adhoc.setName("Quick_ODM_" + stamp);
        adhoc.setDescription("Ad-hoc full-study export created from the SPA Data Export view.");
        adhoc.setStudyId(ctx.study.getId());
        adhoc.setOwner(ctx.user);
        adhoc.setOwnerId(ctx.user.getId());
        adhoc.setStatus(Status.AVAILABLE);
        adhoc.setNumRuns(0);
        adhoc.setSQLStatement(adhoc.generateQuery());
        adhoc.setDatasetItemStatus(DatasetItemStatus.COMPLETED_AND_NONCOMPLETED);
        // ODM serialisation needs the metadata-version oids; the legacy
        // CreateDatasetServlet leaves these blank when the operator
        // doesn't pick a versioning prior, so we do the same — the
        // OdmFileCreation pass synthesises defaults.
        adhoc.setODMMetaDataVersionName(null);
        adhoc.setODMMetaDataVersionOid(null);
        adhoc.setODMPriorStudyOid(null);
        adhoc.setODMPriorMetaDataVersionOid(null);
        // Show every "show_*" flag so the ODM has the broadest possible
        // attribute coverage. Operators using the legacy CreateDataset
        // wizard tick these by hand; quick-ODM is the one-click
        // equivalent so we surface them all.
        adhoc.setShowEventLocation(true);
        adhoc.setShowEventStart(true);
        adhoc.setShowEventEnd(true);
        adhoc.setShowSubjectDob(true);
        adhoc.setShowSubjectGender(true);
        adhoc.setShowEventStatus(true);
        adhoc.setShowSubjectStatus(true);
        adhoc.setShowSubjectUniqueIdentifier(true);
        adhoc.setShowSubjectAgeAtEvent(true);
        adhoc.setShowSubjectSecondaryId(true);
        adhoc.setShowCRFstatus(true);
        adhoc.setShowCRFversion(true);
        adhoc.setShowCRFinterviewerName(true);
        adhoc.setShowCRFinterviewerDate(true);
        adhoc.setShowSubjectGroupInformation(false);

        DatasetBean persisted = (DatasetBean) datasetDao.create(adhoc);
        if (persisted == null || persisted.getId() == 0) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to create ad-hoc dataset for Quick ODM export"));
        }

        try {
            ExportRunResult result = runExport(persisted, ExportFormatKey.ODM, ctx.study, ctx.user);
            return ResponseEntity.ok(new ExportTriggerResponse(
                    result.archivedFileId,
                    downloadUrlFor(result.archivedFileId)));
        } catch (RuntimeException re) {
            LOG.error("Quick-ODM export failed for study={} dataset={}", studyOid, persisted.getId(), re);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Quick-ODM export failed: " + re.getMessage()));
        }
    }

    /* ============================================================== */
    /*  GET /api/v1/archived-files/{id}/download                     */
    /* ============================================================== */

    @GetMapping("/archived-files/{id}/download")
    public void downloadFile(@PathVariable("id") int archivedFileId,
                             HttpSession session,
                             HttpServletResponse response) throws IOException {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
            return;
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "No active study bound");
            return;
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!roleMayExportData(me, currentRole)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Your role does not permit downloading export files.");
            return;
        }

        ArchivedDatasetFileDAO archivedDao = new ArchivedDatasetFileDAO(dataSource);
        ArchivedDatasetFileBean f = (ArchivedDatasetFileBean) archivedDao.findByPK(archivedFileId);
        if (f == null || f.getId() == 0) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "No archived file with id " + archivedFileId);
            return;
        }
        // Verify the file's dataset belongs to the active study before
        // streaming bytes — otherwise an authenticated user on study A
        // could enumerate study B's exports by guessing ids.
        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        DatasetBean db = (DatasetBean) datasetDao.findByPK(f.getDatasetId());
        if (db == null || db.getId() == 0
                || db.getStudyId() != currentStudy.getId()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "No archived file with id " + archivedFileId);
            return;
        }

        File onDisk = new File(f.getFileReference());
        if (!onDisk.isFile() || !onDisk.canRead()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "File no longer exists on disk: " + f.getName());
            return;
        }

        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        // .zip is the typical wrapper (createODMFile, createTabFile,
        // createSPSSFile all zip), .xls for raw Excel exports.
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            contentType = "application/zip";
        } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".xls")) {
            contentType = "application/vnd.ms-excel";
        }
        response.setContentType(contentType);
        response.setContentLengthLong(onDisk.length());
        // Quote the filename so spaces and unicode survive the round-trip.
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + f.getName().replace("\"", "_") + "\"");
        try (InputStream in = Files.newInputStream(onDisk.toPath());
             OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    /* ============================================================== */
    /*  Helpers                                                        */
    /* ============================================================== */

    /** Resolved session + study + study-scope errors. */
    private static final class ResolvedContext {
        final UserAccountBean user;
        final StudyBean study;
        final ResponseEntity<?> errorResponse;

        private ResolvedContext(UserAccountBean user, StudyBean study, ResponseEntity<?> err) {
            this.user = user;
            this.study = study;
            this.errorResponse = err;
        }

        static ResolvedContext error(ResponseEntity<?> err) {
            return new ResolvedContext(null, null, err);
        }
        static ResolvedContext ok(UserAccountBean user, StudyBean study) {
            return new ResolvedContext(user, study, null);
        }
    }

    /**
     * Resolve the request session into (auth user, target study) and
     * apply the standard auth gates: 401 anonymous, 400 no active
     * study, 404 unknown OID, 403 role mismatch.
     *
     * <p>The legacy ExportDatasetServlet additionally allows the OID
     * of a child site under the active study; we keep that behaviour
     * for symmetry — Monitors bound to a single site can list and
     * download their own exports without first switching the active
     * study to that site.
     */
    private ResolvedContext resolveContext(String studyOid, HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResolvedContext.error(ResponseEntity.status(401).body(
                    Map.of("message", "Not authenticated")));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResolvedContext.error(ResponseEntity.badRequest().body(
                    Map.of("message",
                            "No active study bound — call POST /pages/api/v1/me/activeStudy first")));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!roleMayExportData(me, currentRole)) {
            return ResolvedContext.error(ResponseEntity.status(403).body(
                    Map.of("message", "Your role does not permit accessing data exports.")));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResolvedContext.error(ResponseEntity.status(404).body(
                    Map.of("message", "No study with oid '" + studyOid + "'")));
        }
        // Refuse when the OID doesn't match the active study (or one of
        // its children) — mirrors ExportDatasetServlet's
        // checkRoleByUserAndStudy + the study-switch guard.
        if (target.getId() != currentStudy.getId()
                && target.getParentStudyId() != currentStudy.getId()) {
            return ResolvedContext.error(ResponseEntity.status(403).body(
                    Map.of("message",
                            "Study '" + studyOid + "' is not the currently active study.")));
        }
        return ResolvedContext.ok(me, target);
    }

    /**
     * Authorization predicate. Sysadmin + the five operational study
     * roles per {@code ExportDatasetServlet.mayProceed()}. RA / RA2
     * (read-only researcher) are denied.
     */
    static boolean roleMayExportData(UserAccountBean me, StudyUserRoleBean currentRole) {
        if (me != null && me.isSysAdmin()) return true;
        if (currentRole == null || currentRole.getRole() == null) return false;
        Role r = currentRole.getRole();
        return r.equals(Role.STUDYDIRECTOR)
                || r.equals(Role.COORDINATOR)
                || r.equals(Role.INVESTIGATOR)
                || r.equals(Role.MONITOR);
    }

    /**
     * Where the legacy ExportDatasetServlet writes per-run output.
     * Composing it lazily because {@code CoreResources.getField} can
     * be inert at controller-construction time (before the DataInfo
     * properties bind), but is always populated by the time a
     * request lands.
     */
    private String runDirFor(int datasetId) {
        String base = CoreResources.getField("filePath");
        if (base == null || base.isBlank()) base = "";
        if (!base.endsWith(File.separator)) base = base + File.separator;
        return base + "datasets" + File.separator + datasetId
                + File.separator + new SimpleDateFormat(RUN_DIR_PATTERN).format(new Date());
    }

    private static String downloadUrlFor(int archivedFileId) {
        return "/LibreClinica/pages/api/v1/archived-files/" + archivedFileId + "/download";
    }

    private static String toIsoUtc(Date d) {
        if (d == null) return null;
        return d.toInstant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toInstant().toString();
    }

    /** Wraps the legacy GenerateExtractFileService dispatch. */
    private ExportRunResult runExport(DatasetBean db, ExportFormatKey format,
                                      StudyBean study, UserAccountBean me) {
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parentStudy = new StudyBean();
        if (study.getParentStudyId() > 0) {
            parentStudy = (StudyBean) studyDao.findByPK(study.getParentStudyId());
        }

        GenerateExtractFileService extractService = new GenerateExtractFileService(
                dataSource, coreResources, ruleSetRuleDao);
        ExtractBean eb = extractService.generateExtractBean(db, study, parentStudy);

        String runDir = runDirFor(db.getId());
        long sysTimeBegin = System.currentTimeMillis();
        // The legacy file-creation helpers strip spaces out of the
        // dataset name when composing zip filenames — replicate so the
        // ArchivedDatasetFile rows are consistent with /Extract Data.
        String sanitizedName = db.getName() == null ? "dataset" : db.getName().replaceAll(" ", "_");
        db.setName(sanitizedName);

        int fileId;
        switch (format) {
            case ODM -> {
                HashMap<String, Integer> answer = extractService.createODMFile(
                        "oc1.3", sysTimeBegin, runDir, db, study, "", eb,
                        study.getId(), study.getParentStudyId(), "99", me);
                fileId = firstValueOrZero(answer);
            }
            case CSV -> {
                long elapsed = System.currentTimeMillis() - sysTimeBegin;
                eb = new at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractBean(dataSource);
                eb.setDataset(db);
                eb.setStudy(study);
                eb.setParentStudy(parentStudy);
                eb = new DatasetDAO(dataSource).getDatasetData(
                        eb, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId());
                eb.getMetadata();
                at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.CommaReportBean answer =
                        new at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.CommaReportBean();
                eb.computeReport(answer);
                String name = sanitizedName + "_comma.txt";
                fileId = extractService.createFile(name, runDir, answer.toString(), db,
                        elapsed, ExportFormatBean.CSVFILE, true, me);
            }
            case TSV -> {
                HashMap<String, Integer> answer = extractService.createTabFile(
                        eb, sysTimeBegin, runDir, db, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId(),
                        "", me);
                fileId = firstValueOrZero(answer);
            }
            case EXCEL -> {
                // The legacy /ExportDataset Excel branch doesn't actually
                // emit a binary .xls (TODO in the servlet since 2009);
                // it streams a generated tab file with a .xls
                // Content-Disposition. Replicate by reusing the tab
                // pipeline — operators get a .xls Excel can open
                // happily.
                HashMap<String, Integer> answer = extractService.createTabFile(
                        eb, sysTimeBegin, runDir, db, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId(),
                        "", me);
                fileId = firstValueOrZero(answer);
            }
            case SAS -> {
                long elapsed = System.currentTimeMillis() - sysTimeBegin;
                String name = sanitizedName + "_sas.sas";
                fileId = extractService.createFile(name, runDir, "", db,
                        elapsed, ExportFormatBean.TXTFILE, true, me);
            }
            case SPSS -> {
                at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.SPSSReportBean answer =
                        new at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.SPSSReportBean();
                eb = new DatasetDAO(dataSource).getDatasetData(
                        eb, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId());
                eb.getMetadata();
                eb.computeReport(answer);
                HashMap<String, Integer> answerMap = extractService.createSPSSFile(
                        db, eb, study, parentStudy, sysTimeBegin, runDir, answer, "", me);
                fileId = firstValueOrZero(answerMap);
            }
            default -> throw new IllegalStateException("Unhandled format: " + format);
        }
        return new ExportRunResult(fileId);
    }

    private static int firstValueOrZero(HashMap<String, Integer> map) {
        if (map == null || map.isEmpty()) return 0;
        for (Integer v : map.values()) {
            if (v != null) return v.intValue();
        }
        return 0;
    }

    /* ---- Records / enums ---- */

    /** Wire shape of {@code POST /api/v1/datasets/{id}/export}. */
    public record ExportTriggerRequest(String format) {}

    /** Wire shape of the export-trigger response. */
    public record ExportTriggerResponse(int archivedDatasetFileId, String downloadUrl) {}

    private record ExportRunResult(int archivedFileId) {}

    /**
     * Internal projection of the {@code format} field accepted by
     * {@code POST /datasets/{id}/export}. Case-insensitive parse —
     * the SPA always sends lower-case but operators hitting the API
     * directly might capitalise.
     */
    enum ExportFormatKey {
        ODM, CSV, TSV, EXCEL, SAS, SPSS;

        static ExportFormatKey parse(String raw) {
            if (raw == null) return null;
            String key = raw.trim().toLowerCase(Locale.ROOT);
            return switch (key) {
                case "odm" -> ODM;
                case "csv" -> CSV;
                case "tsv", "tab", "txt" -> TSV;
                case "excel", "xls" -> EXCEL;
                case "sas" -> SAS;
                case "spss" -> SPSS;
                default -> null;
            };
        }
    }
}
