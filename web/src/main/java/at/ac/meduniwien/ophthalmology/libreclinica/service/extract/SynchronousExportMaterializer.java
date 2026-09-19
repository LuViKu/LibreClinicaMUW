/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AiArmPolicy;
import at.ac.meduniwien.ophthalmology.libreclinica.service.export.BundleExportWriter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.export.CasebookRenderer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.CommaReportBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExportFormatBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.SPSSReportBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Production {@link ExportFileMaterializer} that drives the same
 * {@link GenerateExtractFileService} pipeline Phase 1's synchronous
 * {@code POST /datasets/{id}/export} uses. Wired as a Spring
 * {@code @Component} so {@link ExportJobRunner#resolveMaterializer}
 * finds it ahead of the {@link PlaceholderExportFileMaterializer}
 * fallback at runtime.
 *
 * <p><strong>Why this is a separate class</strong>: the runner needs
 * to re-load the dataset's study + the submitting user from the DB
 * because it executes outside any HTTP request, so the controller's
 * session-scoped {@code (StudyBean, UserAccountBean)} are not
 * available. This class handles that hydration before delegating to
 * the same {@code GenerateExtractFileService} method matrix Phase 1
 * uses.
 *
 * <p><strong>Bean lookup vs new()</strong>:
 * {@link ExportJobRunner#resolveMaterializer(org.springframework.context.ApplicationContext)}
 * does {@code appCtx.getBean(ExportFileMaterializer.class)} — Spring
 * returns the unique {@code @Component}-annotated implementation
 * (this class) ahead of the placeholder, which is instantiated via
 * {@code new} only when no bean is registered.
 */
@Component
@SuppressWarnings("all")
public class SynchronousExportMaterializer implements ExportFileMaterializer {

    private static final Logger LOG = LoggerFactory.getLogger(SynchronousExportMaterializer.class);

    /** Same per-run subdir layout as ExportDatasetServlet + DatasetsApiController. */
    private static final String RUN_DIR_PATTERN =
            "yyyy" + File.separator + "MM" + File.separator + "dd"
            + File.separator + "HHmmssSSS" + File.separator;

    private final DataSource dataSource;
    private final CoreResources coreResources;
    private final RuleSetRuleDao ruleSetRuleDao;

    @Autowired
    public SynchronousExportMaterializer(@Qualifier("dataSource") DataSource dataSource,
                                         CoreResources coreResources,
                                         RuleSetRuleDao ruleSetRuleDao) {
        this.dataSource = dataSource;
        this.coreResources = coreResources;
        this.ruleSetRuleDao = ruleSetRuleDao;
    }

    @Override
    public Result materialize(DatasetBean dataset, String format, int submittedByUserId) throws Exception {
        if (dataset == null || dataset.getId() == 0) {
            throw new IllegalArgumentException("Dataset is null or transient");
        }
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = (StudyBean) studyDao.findByPK(dataset.getStudyId());
        if (study == null || study.getId() == 0) {
            throw new IllegalStateException(
                    "Dataset's study_id=" + dataset.getStudyId() + " no longer exists");
        }
        StudyBean parentStudy = new StudyBean();
        if (study.getParentStudyId() > 0) {
            StudyBean p = (StudyBean) studyDao.findByPK(study.getParentStudyId());
            if (p != null) parentStudy = p;
        }
        UserAccountBean submittedBy = (UserAccountBean)
                new UserAccountDAO(dataSource).findByPK(submittedByUserId);
        if (submittedBy == null || submittedBy.getId() == 0) {
            throw new IllegalStateException(
                    "Submitting user id=" + submittedByUserId + " no longer exists");
        }

        GenerateExtractFileService extractService = new GenerateExtractFileService(
                dataSource, coreResources, ruleSetRuleDao);
        ExtractBean eb = extractService.generateExtractBean(dataset, study, parentStudy);

        String runDir = runDirFor(dataset.getId());
        long sysTimeBegin = System.currentTimeMillis();
        String sanitizedName = dataset.getName() == null
                ? "dataset"
                : dataset.getName().replaceAll(" ", "_");
        dataset.setName(sanitizedName);

        int fileId;
        switch (normaliseFormat(format)) {
            case ODM -> {
                HashMap<String, Integer> answer = extractService.createODMFile(
                        "oc1.3", sysTimeBegin, runDir, dataset, study, "", eb,
                        study.getId(), study.getParentStudyId(), "99", submittedBy);
                fileId = firstValueOrZero(answer);
            }
            case CSV -> {
                long elapsed = System.currentTimeMillis() - sysTimeBegin;
                ExtractBean eb2 = new ExtractBean(dataSource);
                eb2.setDataset(dataset);
                eb2.setStudy(study);
                eb2.setParentStudy(parentStudy);
                eb2 = new DatasetDAO(dataSource).getDatasetData(
                        eb2, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId());
                eb2.getMetadata();
                CommaReportBean answer = new CommaReportBean();
                eb2.computeReport(answer);
                String name = sanitizedName + "_comma.txt";
                fileId = extractService.createFile(name, runDir, answer.toString(), dataset,
                        elapsed, ExportFormatBean.CSVFILE, true, submittedBy);
            }
            case TSV -> {
                HashMap<String, Integer> answer = extractService.createTabFile(
                        eb, sysTimeBegin, runDir, dataset, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId(),
                        "", submittedBy);
                fileId = firstValueOrZero(answer);
            }
            case EXCEL -> {
                // Legacy: Excel branch streams a tab file with .xls
                // Content-Disposition — replicate.
                HashMap<String, Integer> answer = extractService.createTabFile(
                        eb, sysTimeBegin, runDir, dataset, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId(),
                        "", submittedBy);
                fileId = firstValueOrZero(answer);
            }
            case SAS -> {
                // Was: a zero-byte file recorded as a successful export. See
                // GenerateExtractFileService.createSasFile.
                fileId = extractService.createSasFile(dataset, eb, study, sysTimeBegin, runDir, submittedBy);
            }
            case SPSS -> {
                SPSSReportBean answer = new SPSSReportBean();
                ExtractBean eb2 = new DatasetDAO(dataSource).getDatasetData(
                        eb, study.getId(),
                        study.getParentStudyId() > 0 ? study.getParentStudyId() : study.getId());
                eb2.getMetadata();
                eb2.computeReport(answer);
                HashMap<String, Integer> answerMap = extractService.createSPSSFile(
                        dataset, eb2, study, parentStudy, sysTimeBegin, runDir, answer, "", submittedBy);
                fileId = firstValueOrZero(answerMap);
            }
            case BUNDLE -> {
                // P3.8 — every subject's casebook plus every file behind it.
                // Never on the request thread: this is why the job queue
                // exists, and why the SPA polls rather than waits.
                fileId = writeDatasetBundle(dataset, study, submittedBy, runDir,
                        sanitizedName, sysTimeBegin);
            }
            default -> throw new IllegalStateException("Unhandled format: " + format);
        }

        if (fileId <= 0) {
            throw new IllegalStateException("GenerateExtractFileService returned no archived-file id");
        }

        // Re-load the freshly-created archived_dataset_file row so we
        // can hand the runner the canonical metadata (the legacy
        // extract path persists the row internally, but does not
        // surface it on the return path).
        var adf = (at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean)
                new ArchivedDatasetFileDAO(dataSource).findByPK(fileId);
        if (adf == null || adf.getId() == 0) {
            throw new IllegalStateException(
                    "ArchivedDatasetFileDAO.findByPK returned no row for id=" + fileId);
        }
        long fileSize = 0L;
        String ref = adf.getFileReference();
        if (ref != null) {
            File f = new File(ref);
            if (f.isFile()) fileSize = f.length();
        }

        // The runner records its own archived_dataset_file row in
        // ExportJobRunner.runOnce — but the legacy pipeline above
        // already persisted one inside GenerateExtractFileService. To
        // avoid double-bookkeeping, the runner detects the
        // sentinel-prefix "existing:" + id on fileReference and uses
        // that id directly instead of creating a second row.
        LOG.info("SynchronousExportMaterializer: archived_dataset_file_id={} for dataset_id={} format={}",
                fileId, dataset.getId(), format);
        return new Result(adf.getName(), "existing:" + fileId, fileSize);
    }

    /* --------------------------------------------------------------- */
    /* P3.8 — the dataset bundle                                        */
    /* --------------------------------------------------------------- */

    /**
     * One zip for the whole dataset: a folder per subject holding that
     * subject's casebook and files, one manifest at the root.
     *
     * <p>Gated on {@code export.bundle.enabled} here as well as at enqueue
     * time. The queue row outlives the request that created it; a study that
     * switched the export off between the two must not have its imaging leave
     * the platform because a job was already waiting.
     *
     * <p>Blinding is decided per subject. The requester's role on the study
     * says whether they are a treating clinician; each subject's arm says
     * whether that matters for them. An unanswerable question — a role lookup
     * that fails, an arm that cannot be read — withholds, because a file that
     * has left the platform cannot be taken back (DR-028).
     */
    private int writeDatasetBundle(DatasetBean dataset, StudyBean study, UserAccountBean submittedBy,
                                   String runDir, String sanitizedName, long sysTimeBegin)
            throws IOException {
        StudySettingService settings = new StudySettingService(dataSource);
        if (!settings.isEnabled(study.getId(), StudySettingService.EXPORT_BUNDLE_ENABLED)) {
            throw new IllegalStateException(
                    "The multimodal bundle is not enabled for study " + study.getOid());
        }

        List<Integer> subjectIds = new DatasetFilterSubjectResolver(dataSource)
                .resolve(dataset.getId(), study.getId());
        if (subjectIds == null) {
            // No saved filters means "do not restrict" — the same reading the
            // text extracts give it.
            subjectIds = allSubjectIds(study.getId());
        }

        boolean treating = submitterIsTreatingClinician(submittedBy, study);

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        List<BundleExportWriter.DatasetSubject> subjects = new ArrayList<>(subjectIds.size());
        for (Integer id : subjectIds) {
            StudySubjectBean ss = (StudySubjectBean) studySubjectDAO.findByPK(id);
            if (ss == null || ss.getId() == 0) continue;
            SubjectBean subj = (SubjectBean) subjectDAO.findByPK(ss.getSubjectId());

            boolean maskAi = treating && AiArmPolicy.ARM_HIDDEN.equals(armOf(ss.getId()));
            BundleExportWriter.Policy policy = new BundleExportWriter.Policy(maskAi);

            // Rendered before the zip exists, so the casebook can name the
            // entries the zip will contain — under this subject's own folder.
            String prefix = BundleExportWriter.subjectPrefix(ss.getLabel());
            CasebookRenderer.CasebookSnapshot snap =
                    CasebookRenderer.collect(dataSource, ss, subj, study);
            byte[] odm = CasebookRenderer.renderOdm(snap,
                    BundleExportWriter.acquisitionPaths(dataSource, ss.getId(), prefix));
            byte[] csv = CasebookRenderer.renderCsv(snap);
            subjects.add(new BundleExportWriter.DatasetSubject(
                    ss.getId(), ss.getLabel(), odm, csv, policy));
        }

        File dir = new File(runDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create run directory " + runDir);
        }
        File zip = new File(dir, sanitizedName + "_bundle.zip");
        BundleExportWriter.Result written;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) {
            written = BundleExportWriter.writeDataset(out, dataSource, subjects, study.getOid(),
                    submittedBy.getName(), false);
        }
        LOG.info("Dataset bundle: dataset_id={} subjects={} files={} bytes={} omitted={} by user={}",
                dataset.getId(), subjects.size(), written.filesWritten(), written.bytesWritten(),
                written.omitted().size(), submittedBy.getName());

        // Registered the way the text formats are, so the SPA's file table,
        // the download endpoint and the retention sweep all see one more
        // archived file — under the zip format, not a text one.
        ArchivedDatasetFileBean fb = new ArchivedDatasetFileBean();
        fb.setName(zip.getName());
        fb.setFileReference(zip.getAbsolutePath());
        fb.setFileSize((int) Math.min(zip.length(), Integer.MAX_VALUE));
        fb.setRunTime((System.currentTimeMillis() - sysTimeBegin) / 1000.0);
        fb.setDatasetId(dataset.getId());
        fb.setExportFormatBean(ExportFormatBean.ZIPFILE);
        fb.setExportFormatId(ExportFormatBean.ZIPFILE.getId());
        fb.setOwner(submittedBy);
        fb.setOwnerId(submittedBy.getId());
        fb.setDateCreated(new Date());
        ArchivedDatasetFileBean created =
                (ArchivedDatasetFileBean) new ArchivedDatasetFileDAO(dataSource).create(fb);
        return created == null ? 0 : created.getId();
    }

    /** Every live subject of the study and its sites, in label order. */
    private List<Integer> allSubjectIds(int studyId) throws IOException {
        List<Integer> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.study_subject_id FROM study_subject ss "
                             + "  JOIN study s ON s.study_id = ss.study_id "
                             + " WHERE (s.study_id = ? OR s.parent_study_id = ?) "
                             + "   AND ss.status_id NOT IN (5, 7) "
                             + " ORDER BY ss.label, ss.study_subject_id")) {
            ps.setInt(1, studyId);
            ps.setInt(2, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new IOException("could not list the study's subjects: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Whether the requester holds a treating role on the study or its parent.
     * Fails closed: if the grants cannot be read, the requester is treated as
     * a clinician, which withholds rather than reveals.
     */
    private boolean submitterIsTreatingClinician(UserAccountBean user, StudyBean study) {
        try {
            List<StudyUserRoleBean> grants =
                    new UserAccountDAO(dataSource).findAllRolesByUserName(user.getName());
            for (StudyUserRoleBean g : grants) {
                if (g == null || g.getRole() == null) continue;
                boolean onStudy = g.getStudyId() == study.getId()
                        || (study.getParentStudyId() > 0 && g.getStudyId() == study.getParentStudyId());
                if (!onStudy) continue;
                if (g.getStatus() != null && g.getStatus().getId() != Status.AVAILABLE.getId()) continue;
                if (g.getRole().equals(Role.INVESTIGATOR) || g.getRole().equals(Role.COORDINATOR)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOG.warn("role lookup failed for user={} study={} — treating as a clinician: {}",
                    user.getName(), study.getOid(), e.getMessage());
            return true;
        }
    }

    /** The subject's arm, read as hidden when it cannot be read at all. */
    private String armOf(int studySubjectId) {
        try (Connection c = dataSource.getConnection()) {
            return AiArmPolicy.armForSubject(c, studySubjectId);
        } catch (SQLException e) {
            LOG.warn("arm lookup failed for study_subject {} — withholding AI output: {}",
                    studySubjectId, e.getMessage());
            return AiArmPolicy.ARM_HIDDEN;
        }
    }

    /* --------------------------------------------------------------- */
    /* Helpers                                                         */
    /* --------------------------------------------------------------- */

    private String runDirFor(int datasetId) {
        String base = CoreResources.getField("filePath");
        if (base == null || base.isBlank()) base = "";
        if (!base.endsWith(File.separator)) base = base + File.separator;
        return base + "datasets" + File.separator + datasetId
                + File.separator + new SimpleDateFormat(RUN_DIR_PATTERN).format(new Date());
    }

    private static int firstValueOrZero(HashMap<String, Integer> map) {
        if (map == null || map.isEmpty()) return 0;
        for (Integer v : map.values()) {
            if (v != null) return v.intValue();
        }
        return 0;
    }

    /** Format string projection. Mirrors DatasetsApiController.ExportFormatKey. */
    private enum Fmt { ODM, CSV, TSV, EXCEL, SAS, SPSS, BUNDLE }

    private static Fmt normaliseFormat(String raw) {
        if (raw == null) return Fmt.ODM;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "odm", "xml" -> Fmt.ODM;
            case "csv" -> Fmt.CSV;
            case "tsv", "tab", "txt" -> Fmt.TSV;
            case "excel", "xls", "xlsx" -> Fmt.EXCEL;
            case "sas" -> Fmt.SAS;
            case "spss" -> Fmt.SPSS;
            case "bundle" -> Fmt.BUNDLE;
            default -> Fmt.ODM;
        };
    }
}
