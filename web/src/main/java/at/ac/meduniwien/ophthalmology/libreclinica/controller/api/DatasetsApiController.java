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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DatasetItemStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ItemDataType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExportFormatBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.GenerateExtractFileService;

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
import org.springframework.web.bind.annotation.PutMapping;
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

    /**
     * Phase 2 — canonical SPA-side flag names mapped onto the DatasetBean
     * setters. Any key not listed here is silently ignored on POST / PUT,
     * and any flag not present in the request body defaults to
     * {@code false} (POST) or "leave unchanged" (PUT). Keep this list
     * stable: the SPA's {@code InclusionFlags} TypeScript type derives
     * its key set from the same names.
     */
    static final Set<String> FLAG_KEYS = new LinkedHashSet<>(List.of(
            // Subject metadata
            "dob", "gender", "subjectStatus", "uniqueIdentifier", "secondaryId",
            "ageAtEvent", "groupInformation",
            // Event metadata
            "eventLocation", "eventStartDate", "eventEndDate", "eventStartTime",
            "eventEndTime", "eventStatus",
            // CRF / audit
            "crfStatus", "crfVersion", "interviewerName", "interviewerDate",
            "completionDate",
            // Discrepancy notes
            "discNotes"
    ));

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
            // Skip soft-deleted rows from the list view — the wizard
            // restore path can resurface them via the single-fetch
            // endpoint if/when needed.
            if (db.getStatus() != null && db.getStatus().isDeleted()) continue;
            int fileCount = 0;
            try {
                ArrayList<ArchivedDatasetFileBean> files = archivedDao.findByDatasetId(db.getId());
                fileCount = files == null ? 0 : files.size();
            } catch (RuntimeException re) {
                LOG.warn("Failed to count files for dataset id={}: {}", db.getId(), re.getMessage());
            }
            // The list endpoint stays Phase 1 lightweight — Phase 2
            // hydration (event/CRF/item selection) only fires on the
            // single-fetch endpoint to avoid N+1 SQL on the list.
            out.add(new DatasetDto(
                    String.valueOf(db.getId()),
                    db.getId(),
                    db.getName(),
                    db.getDescription(),
                    db.getOwner() == null ? null : db.getOwner().getName(),
                    toIsoUtc(db.getCreatedDate()),
                    toIsoUtc(db.getDateLastRun()),
                    fileCount,
                    db.getStudyId(),
                    db.getStatus() == null ? null : db.getStatus().getName(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    db.getNumRuns(),
                    db.getNumRuns() > 0));
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
        // OdmFileCreation pass synthesises defaults. Use empty strings
        // rather than nulls: DatasetDAO.create binds these positions
        // via setTypeExpected(VARCHAR), and PreparedStatementFactory
        // NPEs on a literal null without a matching nullVars entry.
        adhoc.setODMMetaDataVersionName("");
        adhoc.setODMMetaDataVersionOid("");
        adhoc.setODMPriorStudyOid("");
        adhoc.setODMPriorMetaDataVersionOid("");
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
        // java.sql.Date.toInstant() throws UnsupportedOperationException
        // unconditionally — the JDBC drivers hand us sql.Date for any
        // DATE-typed column. Go via getTime() to handle both
        // java.util.Date and java.sql.Date uniformly.
        return java.time.Instant.ofEpochMilli(d.getTime())
                .atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toInstant().toString();
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

    /* ============================================================== */
    /*  Phase E.6 P3 — filter test surface                            */
    /*  POST /api/v1/datasets/{datasetId}:test-filter                 */
    /* ============================================================== */

    /**
     * Operators the {@link DatasetFilterDto} accepts.
     *
     * <p>{@code in} carries a list of values, {@code between} carries
     * a {@code [low, high]} pair, the unary {@code is-null} +
     * {@code not-null} need no value. Anything else is treated as a
     * scalar comparison.
     */
    static final Set<String> KNOWN_OPS = Set.of(
            "=", "!=", "<", "<=", ">", ">=", "in", "between", "is-null", "not-null");

    private static final Set<String> NUMERIC_ONLY_OPS = Set.of("<", "<=", ">", ">=", "between");
    private static final Set<String> UNARY_OPS = Set.of("is-null", "not-null");

    /** ItemDataType.name() values that admit numeric/date-style ordering operators. */
    private static final Set<String> NUMERIC_OR_DATE_TYPES = Set.of(
            ItemDataType.INTEGER.getName(),
            ItemDataType.REAL.getName(),
            ItemDataType.DATE.getName(),
            ItemDataType.PDATE.getName());

    /**
     * Counts subjects + CRFs that satisfy the supplied predicate
     * list. Does NOT persist anything — this is the wizard's live
     * preview endpoint.
     */
    @PostMapping("/datasets/{datasetId}:test-filter")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = FilterTestResult.class)))
    public ResponseEntity<?> testFilter(@PathVariable("datasetId") String datasetId,
                                        @RequestBody(required = false) TestFilterRequest body,
                                        HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }

        if (body == null || body.filters() == null) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "'filters' is required"));
        }

        // Validate each filter row against the same rules the wizard
        // enforces client-side, so the live preview can't desync from
        // what the persist path would accept later.
        ItemDAO itemDao = new ItemDAO(dataSource);
        Map<String, ItemBean> resolvedItems = new HashMap<>();
        for (int i = 0; i < body.filters().size(); i++) {
            DatasetFilterDto row = body.filters().get(i);
            ResponseEntity<?> err = validateFilterRow(row, i, itemDao, resolvedItems);
            if (err != null) return err;
        }

        try {
            FilterTestResult result = runCounts(currentStudy.getId(), body.filters(), resolvedItems);
            return ResponseEntity.ok(result);
        } catch (SQLException e) {
            LOG.warn("Failed to run :test-filter count for datasetId={} studyId={}",
                    datasetId, currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to run filter count — see server log"));
        }
    }

    /**
     * Returns a 400 ResponseEntity when the row is malformed, or
     * {@code null} when it's accepted. Populates
     * {@code resolvedItems} as a side-effect so the SQL-builder
     * downstream doesn't re-resolve the OID.
     */
    private ResponseEntity<?> validateFilterRow(DatasetFilterDto row, int index,
                                                ItemDAO itemDao,
                                                Map<String, ItemBean> resolvedItems) {
        if (row == null || row.itemOid() == null || row.itemOid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].itemOid is required"));
        }
        if (row.operator() == null || row.operator().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].operator is required"));
        }
        String op = row.operator();
        if (!KNOWN_OPS.contains(op)) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].operator '" + op + "' is not supported"));
        }

        ItemBean item = resolvedItems.computeIfAbsent(row.itemOid(), oid -> {
            ArrayList<ItemBean> hits = itemDao.findByOid(oid);
            return hits.isEmpty() ? null : hits.get(0);
        });
        if (item == null || item.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].itemOid '" + row.itemOid() + "' does not resolve to a known item"));
        }

        // Numeric/date-only operators (<, <=, >, >=, between) must
        // sit on a numeric or date item.
        if (NUMERIC_ONLY_OPS.contains(op)
                && !NUMERIC_OR_DATE_TYPES.contains(item.getDataType().getName())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "filters[" + index + "].operator '" + op
                    + "' requires a numeric or date item; '" + row.itemOid()
                    + "' is " + item.getDataType().getName()));
        }

        // `in` needs a non-empty value list.
        if ("in".equals(op)) {
            if (row.values() == null || row.values().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "filters[" + index + "].values is required for 'in'"));
            }
        }
        // `between` needs exactly two values.
        if ("between".equals(op)) {
            if (row.values() == null || row.values().size() != 2) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "filters[" + index + "].values must hold exactly two entries for 'between'"));
            }
        }
        // Scalar comparisons need a value.
        if (!UNARY_OPS.contains(op) && !"in".equals(op) && !"between".equals(op)) {
            if (row.value() == null) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "filters[" + index + "].value is required for '" + op + "'"));
            }
        }
        return null;
    }

    private FilterTestResult runCounts(int studyId, List<DatasetFilterDto> filters,
                                       Map<String, ItemBean> resolvedItems) throws SQLException {
        int totalSubjects = countTotalSubjects(studyId);
        int totalCrfs = countTotalCrfs(studyId);

        // No filters → match-all.
        if (filters.isEmpty()) {
            return new FilterTestResult(totalSubjects, totalCrfs, totalSubjects, totalCrfs);
        }

        StringBuilder subjectSql = new StringBuilder(
                "SELECT COUNT(DISTINCT ss.study_subject_id) " +
                "FROM study_subject ss WHERE ss.study_id = ? ");
        StringBuilder crfSql = new StringBuilder(
                "SELECT COUNT(DISTINCT ec.event_crf_id) " +
                "FROM event_crf ec " +
                "JOIN study_event se ON se.study_event_id = ec.study_event_id " +
                "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id " +
                "WHERE ss.study_id = ? ");

        // Each filter row appends an EXISTS clause to both queries
        // and contributes (item_id, ...value params...) — collect
        // both parameter lists in lock-step.
        List<Object> subjectParams = new ArrayList<>();
        List<Object> crfParams = new ArrayList<>();
        subjectParams.add(studyId);
        crfParams.add(studyId);

        for (DatasetFilterDto row : filters) {
            ItemBean item = resolvedItems.get(row.itemOid());
            PredicateFragment pf = renderPredicate(row);
            subjectSql.append(" AND EXISTS (SELECT 1 FROM item_data id " +
                    "JOIN event_crf ec2 ON ec2.event_crf_id = id.event_crf_id " +
                    "JOIN study_event se2 ON se2.study_event_id = ec2.study_event_id " +
                    "WHERE se2.study_subject_id = ss.study_subject_id " +
                    "  AND id.item_id = ? AND " + pf.sqlFragment() + ") ");
            crfSql.append(" AND EXISTS (SELECT 1 FROM item_data id " +
                    "WHERE id.event_crf_id = ec.event_crf_id " +
                    "  AND id.item_id = ? AND " + pf.sqlFragment() + ") ");
            subjectParams.add(item.getId());
            subjectParams.addAll(pf.params());
            crfParams.add(item.getId());
            crfParams.addAll(pf.params());
        }

        int matchingSubjects = runCount(subjectSql.toString(), subjectParams);
        int matchingCrfs = runCount(crfSql.toString(), crfParams);
        return new FilterTestResult(matchingSubjects, matchingCrfs, totalSubjects, totalCrfs);
    }

    private int countTotalSubjects(int studyId) throws SQLException {
        return runCount(
                "SELECT COUNT(*) FROM study_subject WHERE study_id = ?",
                List.of(studyId));
    }

    private int countTotalCrfs(int studyId) throws SQLException {
        return runCount(
                "SELECT COUNT(*) FROM event_crf ec " +
                "JOIN study_event se ON se.study_event_id = ec.study_event_id " +
                "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id " +
                "WHERE ss.study_id = ?",
                List.of(studyId));
    }

    private int runCount(String sql, List<Object> params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Renders the predicate fragment for one filter row. The result
     * is a {@code (sqlFragment, params)} pair that gets spliced into
     * the EXISTS clause downstream. The operator is whitelisted
     * upstream by {@link #validateFilterRow}, so the raw splice into
     * the SQL string is safe.
     */
    private PredicateFragment renderPredicate(DatasetFilterDto row) {
        String op = row.operator();
        if ("is-null".equals(op)) {
            return new PredicateFragment(
                    "(id.value IS NULL OR id.value = '')", Collections.emptyList());
        }
        if ("not-null".equals(op)) {
            return new PredicateFragment(
                    "(id.value IS NOT NULL AND id.value <> '')", Collections.emptyList());
        }
        if ("in".equals(op)) {
            String placeholders = String.join(",",
                    Collections.nCopies(row.values().size(), "?"));
            return new PredicateFragment(
                    "id.value IN (" + placeholders + ")",
                    new ArrayList<>(row.values()));
        }
        if ("between".equals(op)) {
            return new PredicateFragment(
                    "id.value BETWEEN ? AND ?",
                    List.of(row.values().get(0), row.values().get(1)));
        }
        return new PredicateFragment(
                "id.value " + op + " ?",
                List.of(row.value()));
    }

    /* ---- Phase 3 records ---- */

    /**
     * Phase 3 wire shape — one predicate row.
     *
     * <p>{@code value} holds the scalar (or {@code null} for unary +
     * list ops); {@code values} holds the list (for {@code in}) or
     * the two-tuple (for {@code between}). Older clients can omit
     * either field — the controller picks the right one based on
     * the operator.
     */
    public record DatasetFilterDto(String itemOid, String operator,
                                   String value, List<String> values) {}

    /** Request body for {@code POST /datasets/{id}:test-filter}. */
    public record TestFilterRequest(List<DatasetFilterDto> filters) {}

    /**
     * Response body for {@code POST /datasets/{id}:test-filter}.
     *
     * <p>The matching counts include the predicate set; the totals
     * are the un-filtered study population. The wizard renders the
     * ratio.
     */
    public record FilterTestResult(int matchingSubjects, int matchingCrfs,
                                   int totalSubjects, int totalCrfs) {}

    /**
     * Internal pair of rendered SQL fragment + the JDBC parameters
     * it needs (in order). Not part of the public wire vocabulary.
     */
    private record PredicateFragment(String sqlFragment, List<Object> params) {}

    /**
     * Stable lowercase canonicalization for operator strings — used
     * in the unit tests to assert the validation path treats
     * mixed-case operator strings consistently.
     */
    static String normalizeOperator(String op) {
        if (op == null) return null;
        return op.toLowerCase(Locale.ROOT);
    }

    /**
     * Visible-for-test helper — exposes the static op classification
     * so the test class doesn't duplicate the predicate tables.
     */
    static boolean operatorIsNumericOnly(String op) {
        return NUMERIC_ONLY_OPS.contains(op);
    }

    static boolean operatorIsUnary(String op) {
        return UNARY_OPS.contains(op);
    }

    static Set<String> dataTypesAcceptingOrderingOps() {
        return new HashSet<>(NUMERIC_OR_DATE_TYPES);
    }

    /* ============================================================== */
    /*  Phase E.6 P2 — create-dataset wizard CRUD                     */
    /* ============================================================== */

    /**
     * GET {@code /api/v1/datasets/{datasetId}} — single dataset for the
     * wizard's edit-mode hydration. Returns the Phase 2 superset DTO
     * including selection + inclusion flags so the wizard can re-enter
     * a previously-saved scope without an extra round-trip.
     */
    @GetMapping("/datasets/{datasetId}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = DatasetDto.class)))
    public ResponseEntity<?> getDataset(@PathVariable("datasetId") int datasetId,
                                        HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        DatasetBean bean = (DatasetBean) datasetDao.findByPK(datasetId);
        if (bean == null || bean.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }
        // Hydrate item / event selection from the legacy SQL fragment.
        // Older datasets created before the algorithm settled may not
        // parse cleanly; log + return what we have so the SPA can at
        // least render metadata + the inclusion flags.
        try {
            datasetDao.initialDatasetData(bean.getId());
        } catch (Exception e) {
            LOG.warn("initialDatasetData failed for id={} — returning partial bean", datasetId, e);
        }
        return ResponseEntity.ok(toWizardDto(bean));
    }

    /**
     * POST {@code /api/v1/studies/{studyOid}/datasets} — create a new
     * saved dataset for the active study. Persists the dataset bean +
     * the SQL fragment that encodes its event + item selection.
     */
    @PostMapping("/studies/{studyOid}/datasets")
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = DatasetDto.class)))
    public ResponseEntity<?> createDataset(@PathVariable("studyOid") String studyOid,
                                           @RequestBody(required = false) CreateDatasetRequest body,
                                           HttpSession session) {
        WizardPreflight pf = wizardPreflight(session, studyOid, /* mutating */ true);
        if (pf.errorResponse != null) return pf.errorResponse;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateWizardShape(body, pf.study, datasetDao, /* selfId */ 0);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        DatasetBean toCreate = new DatasetBean();
        toCreate.setStudyId(pf.study.getId());
        toCreate.setName(body.name().trim());
        toCreate.setDescription(body.description() == null ? "" : body.description().trim());
        toCreate.setStatus(Status.AVAILABLE);
        toCreate.setDatasetItemStatus(DatasetItemStatus.COMPLETED_AND_NONCOMPLETED);
        toCreate.setOwner(pf.me);
        toCreate.setOwnerId(pf.me.getId());
        toCreate.setCreatedDate(new Date());
        toCreate.setNumRuns(0);

        // Resolve OIDs → IDs against the active study to prevent
        // cross-study leakage. validateWizardShape already verified
        // membership, so these lookups should never miss.
        ArrayList<Integer> eventIds = new ArrayList<>();
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        for (String oid : body.eventDefinitionOids()) {
            StudyEventDefinitionBean sed = sedDao.findByOidAndStudy(oid, pf.study.getId(), 0);
            if (sed != null && sed.getId() != 0) eventIds.add(sed.getId());
        }
        toCreate.setEventIds(eventIds);
        toCreate.setItemIds(new ArrayList<>(body.itemIds()));
        applyFlags(toCreate, body.includeFlags());

        // Build the SQL fragment the way the legacy CreateDatasetServlet
        // does — generateQuery() reads eventIds + itemIds straight off
        // the bean.
        toCreate.setSQLStatement(toCreate.generateQuery());

        DatasetBean persisted = datasetDao.create(toCreate);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("DatasetDAO.create returned no row for name={} study={}",
                    body.name(), studyOid);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist dataset"));
        }

        LOG.info("Create dataset: id={} name={} study={} by user={}",
                persisted.getId(), persisted.getName(), studyOid, pf.me.getName());

        return ResponseEntity.status(201).body(toWizardDto(persisted));
    }

    /**
     * PUT {@code /api/v1/datasets/{datasetId}} — edit an existing
     * dataset. Refused (409 Conflict) when {@code num_runs > 0} —
     * re-running a previously-executed dataset whose definition has
     * shifted silently would produce a different result.
     */
    @PutMapping("/datasets/{datasetId}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = DatasetDto.class)))
    public ResponseEntity<?> updateDataset(@PathVariable("datasetId") int datasetId,
                                           @RequestBody(required = false) CreateDatasetRequest body,
                                           HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        DatasetBean target = (DatasetBean) datasetDao.findByPK(datasetId);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Dataset is removed — restore before editing"));
        }
        if (target.getNumRuns() > 0) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Dataset has already been run (" + target.getNumRuns()
                            + " execution(s)) — duplicate it to edit instead"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parentStudy = (StudyBean) studyDao.findByPK(target.getStudyId());
        if (parentStudy == null || parentStudy.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "Dataset's parent study no longer exists"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!roleMayEditExports(me, currentRole, parentStudy)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing datasets on this study"));
        }
        if (!StudyAdminAuthorization.studyAcceptsWrites(parentStudy)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Study is " + parentStudy.getStatus().getName().toLowerCase()
                            + " — writes are refused until it is unlocked"));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors =
                validateWizardShape(body, parentStudy, datasetDao, /* selfId */ datasetId);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        target.setName(body.name().trim());
        target.setDescription(body.description() == null ? "" : body.description().trim());

        ArrayList<Integer> eventIds = new ArrayList<>();
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        for (String oid : body.eventDefinitionOids()) {
            StudyEventDefinitionBean sed = sedDao.findByOidAndStudy(oid, parentStudy.getId(), 0);
            if (sed != null && sed.getId() != 0) eventIds.add(sed.getId());
        }
        target.setEventIds(eventIds);
        target.setItemIds(new ArrayList<>(body.itemIds()));
        applyFlags(target, body.includeFlags());
        target.setSQLStatement(target.generateQuery());
        target.setUpdater(me);
        target.setUpdatedDate(new Date());
        if (target.getDatasetItemStatus() == null) {
            target.setDatasetItemStatus(DatasetItemStatus.COMPLETED_AND_NONCOMPLETED);
        }
        if (target.getStatus() == null) target.setStatus(Status.AVAILABLE);

        datasetDao.updateAll(target);

        LOG.info("Update dataset: id={} name={} study={} by user={}",
                target.getId(), target.getName(), parentStudy.getOid(), me.getName());

        return ResponseEntity.ok(toWizardDto(target));
    }

    /**
     * DELETE {@code /api/v1/datasets/{datasetId}} — soft delete. Flips
     * status_id to {@link Status#DELETED} so the dataset no longer
     * appears in the list view, but the archive of generated files
     * stays linked and downloadable from any operator who still has
     * the file id.
     */
    @DeleteMapping("/datasets/{datasetId}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = DatasetDto.class)))
    public ResponseEntity<?> removeDataset(@PathVariable("datasetId") int datasetId,
                                           HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        DatasetDAO datasetDao = new DatasetDAO(dataSource);
        DatasetBean target = (DatasetBean) datasetDao.findByPK(datasetId);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No dataset with id " + datasetId));
        }
        if (target.getStatus() != null && target.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Dataset is already removed"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean parentStudy = (StudyBean) studyDao.findByPK(target.getStudyId());
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (parentStudy != null && !roleMayEditExports(me, currentRole, parentStudy)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit deleting datasets on this study"));
        }

        target.setStatus(Status.DELETED);
        target.setUpdater(me);
        target.setUpdatedDate(new Date());
        datasetDao.update(target);

        LOG.info("Soft-delete dataset: id={} name={} by user={}",
                target.getId(), target.getName(), me.getName());

        return ResponseEntity.ok(toWizardDto(target));
    }

    /**
     * GET {@code /api/v1/studies/{studyOid}/event-tree} — denormalised
     * event/CRF/version/item tree the wizard's {@code EventCrfItemTreePicker}
     * component fans out into a checkbox tree. Collapses the legacy
     * wizard's ~5 round-trips into one.
     */
    @GetMapping("/studies/{studyOid}/event-tree")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = EventTreeDto.class)))
    public ResponseEntity<?> eventTree(@PathVariable("studyOid") String studyOid,
                                       HttpSession session) {
        WizardPreflight pf = wizardPreflight(session, studyOid, /* mutating */ false);
        if (pf.errorResponse != null) return pf.errorResponse;

        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        EventDefinitionCRFDAO assignmentDao = new EventDefinitionCRFDAO(dataSource);
        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        ItemDAO itemDao = new ItemDAO(dataSource);

        ArrayList<StudyEventDefinitionBean> seds = sedDao.findAllByStudy(pf.study);

        List<EventTreeDto> tree = new ArrayList<>(seds.size());
        for (StudyEventDefinitionBean sed : seds) {
            if (sed.getStatus() != null && sed.getStatus().isDeleted()) continue;

            ArrayList<EventDefinitionCRFBean> assignments =
                    assignmentDao.findAllByDefinition(sed.getId());
            // Deduplicate CRF ids — older deployments occasionally
            // carry stale duplicate event_definition_crf rows.
            Map<Integer, EventTreeDto.CrfNode> crfNodes = new LinkedHashMap<>();
            for (EventDefinitionCRFBean a : assignments) {
                if (a.getStatus() != null && a.getStatus().isDeleted()) continue;
                if (crfNodes.containsKey(a.getCrfId())) continue;
                CRFBean crf = (CRFBean) crfDao.findByPK(a.getCrfId());
                if (crf == null || crf.getId() == 0) continue;

                ArrayList<CRFVersionBean> versions =
                        versionDao.findAllActiveByCRF(crf.getId());
                List<EventTreeDto.VersionNode> versionNodes = new ArrayList<>(versions.size());
                for (CRFVersionBean v : versions) {
                    if (v.getStatus() != null && v.getStatus().isDeleted()) continue;

                    ArrayList<ItemBean> items = itemDao.findAllItemsByVersionId(v.getId());
                    List<EventTreeDto.ItemNode> itemNodes = new ArrayList<>(items.size());
                    for (ItemBean i : items) {
                        // ItemBean has no per-row status flag at the
                        // version level — items live as long as the
                        // version does, so no extra filter here.
                        itemNodes.add(new EventTreeDto.ItemNode(
                                i.getId(),
                                nullToEmpty(i.getOid()),
                                nullToEmpty(i.getName()),
                                i.getDataType() == null ? "" : i.getDataType().getName()));
                    }
                    versionNodes.add(new EventTreeDto.VersionNode(
                            v.getId(),
                            nullToEmpty(v.getOid()),
                            nullToEmpty(v.getName()),
                            itemNodes));
                }

                crfNodes.put(a.getCrfId(), new EventTreeDto.CrfNode(
                        nullToEmpty(crf.getOid()),
                        nullToEmpty(crf.getName()),
                        versionNodes));
            }

            tree.add(new EventTreeDto(
                    nullToEmpty(sed.getOid()),
                    nullToEmpty(sed.getName()),
                    sed.getOrdinal(),
                    sed.isRepeating(),
                    new ArrayList<>(crfNodes.values())));
        }

        return ResponseEntity.ok(tree);
    }

    /* ============================================================== */
    /*  Wizard helpers                                                */
    /* ============================================================== */

    /** Resolved session + study + mutate-gate errors for the wizard endpoints. */
    private static final class WizardPreflight {
        final ResponseEntity<?> errorResponse;
        final UserAccountBean me;
        final StudyBean study;

        private WizardPreflight(ResponseEntity<?> errorResponse, UserAccountBean me, StudyBean study) {
            this.errorResponse = errorResponse;
            this.me = me;
            this.study = study;
        }

        static WizardPreflight error(ResponseEntity<?> r) {
            return new WizardPreflight(r, null, null);
        }

        static WizardPreflight ok(UserAccountBean me, StudyBean study) {
            return new WizardPreflight(null, me, study);
        }
    }

    /**
     * Wizard-endpoint preflight: 401 unauthenticated · 400 missing OID ·
     * 404 unknown study · 403 may-not-edit (mutating only) · 409 study
     * locked (mutating only).
     */
    private WizardPreflight wizardPreflight(HttpSession session, String studyOid, boolean mutating) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return WizardPreflight.error(ResponseEntity.status(401)
                    .body(Map.of("message", "Not authenticated")));
        }
        if (studyOid == null || studyOid.isBlank()) {
            return WizardPreflight.error(ResponseEntity.badRequest()
                    .body(Map.of("message", "studyOid path variable is required")));
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(studyOid);
        if (study == null || study.getId() == 0) {
            return WizardPreflight.error(ResponseEntity.status(404)
                    .body(Map.of("message", "No study with oid '" + studyOid + "'")));
        }
        if (mutating) {
            StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
            if (!roleMayEditExports(me, currentRole, study)) {
                return WizardPreflight.error(ResponseEntity.status(403)
                        .body(Map.of("message",
                                "Your role does not permit managing datasets on this study")));
            }
            if (!StudyAdminAuthorization.studyAcceptsWrites(study)) {
                return WizardPreflight.error(ResponseEntity.status(409)
                        .body(Map.of("message",
                                "Study is " + study.getStatus().getName().toLowerCase()
                                        + " — writes are refused until it is unlocked")));
            }
        }
        return WizardPreflight.ok(me, study);
    }

    /**
     * Dataset-config edit gate. Wider than
     * {@link StudyAdminAuthorization#roleMayEditStudy} — Monitors
     * legitimately compose export configs in the MUW deployment (the
     * legacy {@code CreateDatasetServlet} accepts the same role set).
     */
    static boolean roleMayEditExports(UserAccountBean me,
                                      StudyUserRoleBean currentRole,
                                      StudyBean target) {
        if (me == null) return false;
        if (me.isSysAdmin()) return true;
        if (currentRole == null || currentRole.getRole() == null) return false;
        if (target == null) return false;
        Role r = currentRole.getRole();
        if (r != Role.STUDYDIRECTOR && r != Role.COORDINATOR && r != Role.MONITOR) return false;
        int boundStudyId = currentRole.getStudyId();
        if (boundStudyId == target.getId()) return true;
        return target.getParentStudyId() > 0 && boundStudyId == target.getParentStudyId();
    }

    /** Apply the includeFlags map onto the dataset bean's setters. */
    private static void applyFlags(DatasetBean bean, Map<String, Boolean> flags) {
        Map<String, Boolean> src = flags == null ? Map.of() : flags;
        bean.setShowSubjectDob(boolFlag(src, "dob"));
        bean.setShowSubjectGender(boolFlag(src, "gender"));
        bean.setShowSubjectStatus(boolFlag(src, "subjectStatus"));
        bean.setShowSubjectUniqueIdentifier(boolFlag(src, "uniqueIdentifier"));
        bean.setShowSubjectSecondaryId(boolFlag(src, "secondaryId"));
        bean.setShowSubjectAgeAtEvent(boolFlag(src, "ageAtEvent"));
        bean.setShowSubjectGroupInformation(boolFlag(src, "groupInformation"));

        bean.setShowEventLocation(boolFlag(src, "eventLocation"));
        bean.setShowEventStart(boolFlag(src, "eventStartDate"));
        bean.setShowEventEnd(boolFlag(src, "eventEndDate"));
        bean.setShowEventStartTime(boolFlag(src, "eventStartTime"));
        bean.setShowEventEndTime(boolFlag(src, "eventEndTime"));
        bean.setShowEventStatus(boolFlag(src, "eventStatus"));

        bean.setShowCRFstatus(boolFlag(src, "crfStatus"));
        bean.setShowCRFversion(boolFlag(src, "crfVersion"));
        bean.setShowCRFinterviewerName(boolFlag(src, "interviewerName"));
        bean.setShowCRFinterviewerDate(boolFlag(src, "interviewerDate"));
        bean.setShowCRFcompletionDate(boolFlag(src, "completionDate"));
        // discNotes flag is not persisted via DatasetBean (the dataset
        // table reserves SHOW_DISC_INFO but the legacy create path
        // hard-codes it false). The wizard still tracks the toggle so
        // the operator's intent is captured; downstream Phase 4
        // (async export) is where the actual disc-note inclusion lands.
    }

    private static boolean boolFlag(Map<String, Boolean> src, String key) {
        Boolean v = src.get(key);
        return v != null && v;
    }

    /**
     * Compose the Phase 2 DTO for the single-fetch + create/update
     * echo paths. Populates BOTH the Phase 1 (list) fields AND the
     * Phase 2 (wizard) fields off the bean.
     */
    private DatasetDto toWizardDto(DatasetBean bean) {
        // Resolve event-definition ids → OIDs + items → CRF-versions.
        List<String> eventDefinitionOids = new ArrayList<>();
        List<Integer> crfVersionIds = new ArrayList<>();
        List<Integer> itemIds = new ArrayList<>();
        if (bean.getEventIds() != null && !bean.getEventIds().isEmpty()) {
            StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
            for (Integer id : bean.getEventIds()) {
                if (id == null) continue;
                StudyEventDefinitionBean sed = (StudyEventDefinitionBean) sedDao.findByPK(id);
                if (sed != null && sed.getOid() != null) eventDefinitionOids.add(sed.getOid());
            }
        }
        if (bean.getItemIds() != null) {
            for (Integer id : bean.getItemIds()) if (id != null) itemIds.add(id);
        }
        if (!itemIds.isEmpty()) {
            ItemDAO itemDao = new ItemDAO(dataSource);
            Set<Integer> versionSet = new LinkedHashSet<>();
            for (Integer itemId : itemIds) {
                ArrayList<Integer> vIds = itemDao.findAllVersionsByItemId(itemId);
                if (vIds != null) versionSet.addAll(vIds);
            }
            crfVersionIds.addAll(versionSet);
        }

        Map<String, Boolean> includeFlags = new LinkedHashMap<>();
        includeFlags.put("dob", bean.isShowSubjectDob());
        includeFlags.put("gender", bean.isShowSubjectGender());
        includeFlags.put("subjectStatus", bean.isShowSubjectStatus());
        includeFlags.put("uniqueIdentifier", bean.isShowSubjectUniqueIdentifier());
        includeFlags.put("secondaryId", bean.isShowSubjectSecondaryId());
        includeFlags.put("ageAtEvent", bean.isShowSubjectAgeAtEvent());
        includeFlags.put("groupInformation", bean.isShowSubjectGroupInformation());

        includeFlags.put("eventLocation", bean.isShowEventLocation());
        includeFlags.put("eventStartDate", bean.isShowEventStart());
        includeFlags.put("eventEndDate", bean.isShowEventEnd());
        includeFlags.put("eventStartTime", bean.isShowEventStartTime());
        includeFlags.put("eventEndTime", bean.isShowEventEndTime());
        includeFlags.put("eventStatus", bean.isShowEventStatus());

        includeFlags.put("crfStatus", bean.isShowCRFstatus());
        includeFlags.put("crfVersion", bean.isShowCRFversion());
        includeFlags.put("interviewerName", bean.isShowCRFinterviewerName());
        includeFlags.put("interviewerDate", bean.isShowCRFinterviewerDate());
        includeFlags.put("completionDate", bean.isShowCRFcompletionDate());
        // discNotes is wizard-only state; surface false on read so the
        // SPA's defaulting logic doesn't flip unexpectedly.
        includeFlags.put("discNotes", false);

        // File count is best-effort — the wizard hydration doesn't
        // strictly need it but the SPA's DatasetListView shares the DTO
        // and will reuse the count without a follow-up call.
        int fileCount = 0;
        try {
            ArchivedDatasetFileDAO archivedDao = new ArchivedDatasetFileDAO(dataSource);
            ArrayList<ArchivedDatasetFileBean> files = archivedDao.findByDatasetId(bean.getId());
            fileCount = files == null ? 0 : files.size();
        } catch (RuntimeException re) {
            LOG.warn("Failed to count files for dataset id={}: {}", bean.getId(), re.getMessage());
        }

        return new DatasetDto(
                String.valueOf(bean.getId()),
                bean.getId(),
                nullToEmpty(bean.getName()),
                nullToEmpty(bean.getDescription()),
                bean.getOwner() == null ? null : bean.getOwner().getName(),
                toIsoUtc(bean.getCreatedDate()),
                toIsoUtc(bean.getDateLastRun()),
                fileCount,
                bean.getStudyId(),
                bean.getStatus() == null ? "" : bean.getStatus().getName(),
                eventDefinitionOids,
                crfVersionIds,
                itemIds,
                includeFlags,
                bean.getNumRuns(),
                bean.getNumRuns() > 0);
    }

    /* ---------------- Wizard validation ---------------- */

    static List<SubjectsApiController.ValidationErrorBody.FieldError> validateWizardShape(
            CreateDatasetRequest body, StudyBean study, DatasetDAO datasetDao, int selfId) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty()) {
            out.add(wizardFieldError("name", "Dataset name is required"));
        } else if (name.length() > 2000) {
            out.add(wizardFieldError("name", "Dataset name must be 2000 characters or fewer"));
        } else if (study != null && study.getId() > 0) {
            // Name uniqueness within the study (case-insensitive,
            // ignoring soft-deleted datasets so a deleted name can be
            // reused).
            DatasetBean dup = datasetDao.findByNameAndStudy(name, study);
            if (dup != null && dup.getId() != 0 && dup.getId() != selfId
                    && (dup.getStatus() == null || !dup.getStatus().isDeleted())) {
                out.add(wizardFieldError("name",
                        "A dataset named '" + name + "' already exists in this study"));
            }
        }
        if (body.description() != null && body.description().length() > 4000) {
            out.add(wizardFieldError("description",
                    "Description must be 4000 characters or fewer"));
        }
        if (body.eventDefinitionOids() == null || body.eventDefinitionOids().isEmpty()) {
            out.add(wizardFieldError("eventDefinitionOids",
                    "Select at least one event definition"));
        } else {
            Set<String> seen = new HashSet<>();
            for (String oid : body.eventDefinitionOids()) {
                if (oid == null || oid.isBlank()) {
                    out.add(wizardFieldError("eventDefinitionOids", "Event OID cannot be blank"));
                } else if (!seen.add(oid)) {
                    out.add(wizardFieldError("eventDefinitionOids",
                            "Duplicate event OID: " + oid));
                }
            }
        }
        if (body.crfVersionIds() == null || body.crfVersionIds().isEmpty()) {
            out.add(wizardFieldError("crfVersionIds",
                    "Select at least one CRF version"));
        }
        if (body.itemIds() == null || body.itemIds().isEmpty()) {
            out.add(wizardFieldError("itemIds", "Select at least one item"));
        }
        return out;
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError wizardFieldError(
            String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
