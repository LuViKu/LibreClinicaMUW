/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

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
                long elapsed = System.currentTimeMillis() - sysTimeBegin;
                String name = sanitizedName + "_sas.sas";
                fileId = extractService.createFile(name, runDir, "", dataset,
                        elapsed, ExportFormatBean.TXTFILE, true, submittedBy);
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
    private enum Fmt { ODM, CSV, TSV, EXCEL, SAS, SPSS }

    private static Fmt normaliseFormat(String raw) {
        if (raw == null) return Fmt.ODM;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "odm", "xml" -> Fmt.ODM;
            case "csv" -> Fmt.CSV;
            case "tsv", "tab", "txt" -> Fmt.TSV;
            case "excel", "xls", "xlsx" -> Fmt.EXCEL;
            case "sas" -> Fmt.SAS;
            case "spss" -> Fmt.SPSS;
            default -> Fmt.ODM;
        };
    }
}
