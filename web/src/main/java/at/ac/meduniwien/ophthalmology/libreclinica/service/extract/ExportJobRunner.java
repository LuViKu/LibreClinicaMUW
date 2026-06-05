/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import javax.sql.DataSource;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExportFormatBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportJobDAO;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>The async export worker. Registered as a Quartz {@link Job} on a
 * 30-second cron by {@link ExportScheduleRegistrar}. Each tick claims
 * one queued row (multi-instance safe via
 * {@code SELECT … FOR UPDATE SKIP LOCKED}), runs the underlying
 * extract, writes the {@code archived_dataset_file} row, and stamps
 * the job {@code done} (or {@code failed} + {@code error_message} on
 * exception).
 *
 * <h2>Phase 1 / Phase 4 split</h2>
 *
 * <p>Phase 1 ships the synchronous extract pipeline
 * ({@link GenerateExtractFileService} call signatures + the
 * {@code archived_dataset_file} bookkeeping). Phase 4 wraps that in
 * an async envelope. To keep this PR self-contained in the absence
 * of Phase 1's controller landing first, the runner delegates the
 * actual extract through {@link ExportFileMaterializer} — a small
 * seam Phase 1 provides the production implementation for
 * (Phase 1 ships {@code SynchronousExportMaterializer}). The
 * fallback here records a placeholder so the queued → done
 * transition is observable end-to-end without Phase 1 merged.
 *
 * <h2>Quartz wiring</h2>
 *
 * <p>The class must be stateless and have a public no-arg
 * constructor — Quartz instantiates jobs reflectively. Spring beans
 * are resolved at execute() time via
 * {@code context.getScheduler().getContext().get("applicationContext")}
 * — the {@code applicationContextSchedulerContextKey} set in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.QuartzConfig}.
 * Same pattern as the existing
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.web.job.ExampleSpringJob}.
 *
 * <h2>What this is NOT</h2>
 *
 * <p>This does not pre-empt or retry running jobs. A job that takes
 * longer than 30 s simply blocks its row in `running` state and the
 * next tick picks the next queued row (SKIP LOCKED guarantees no
 * double-pickup). If the JVM crashes mid-run the row stays in
 * `running` forever — a follow-up retention sweep (Phase 6) should
 * scavenge those.
 */
public class ExportJobRunner implements Job {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJobRunner.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        ApplicationContext appCtx;
        try {
            appCtx = (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
        } catch (Exception e) {
            LOG.error("ExportJobRunner: cannot resolve ApplicationContext from Quartz scheduler context", e);
            return;
        }
        if (appCtx == null) {
            LOG.error("ExportJobRunner: ApplicationContext not found under 'applicationContext' key");
            return;
        }

        DataSource dataSource = appCtx.getBean("dataSource", DataSource.class);
        ExportFileMaterializer materializer = resolveMaterializer(appCtx);

        runOnce(dataSource, materializer);
    }

    /**
     * Package-visible for the unit test — drains exactly one queued
     * row. Returns {@code true} if a job was processed, {@code false}
     * if the queue was empty.
     */
    public static boolean runOnce(DataSource dataSource, ExportFileMaterializer materializer) {
        ExportJobDAO jobDao = new ExportJobDAO(dataSource);
        ExportJobDAO.Row claimed = jobDao.claimNextQueued();
        if (claimed == null) return false;

        LOG.info("ExportJobRunner: picked up job_id={} dataset_id={} format={}",
                claimed.id, claimed.datasetId, claimed.format);

        try {
            DatasetDAO datasetDao = new DatasetDAO(dataSource);
            DatasetBean ds = (DatasetBean) datasetDao.findByPK(claimed.datasetId);
            if (ds == null || ds.getId() == 0) {
                jobDao.markFailed(claimed.id,
                        "Dataset " + claimed.datasetId + " no longer exists");
                return true;
            }

            long t0 = System.currentTimeMillis();
            ExportFileMaterializer.Result result =
                    materializer.materialize(ds, claimed.format, claimed.submittedBy);
            long elapsedMs = System.currentTimeMillis() - t0;

            int archivedFileId = resolveOrCreateArchivedFile(
                    dataSource, ds, claimed, result, elapsedMs);
            if (archivedFileId <= 0) {
                jobDao.markFailed(claimed.id,
                        "Archived-dataset-file insert returned no id");
                return true;
            }
            jobDao.markDone(claimed.id, archivedFileId);
            LOG.info("ExportJobRunner: completed job_id={} archived_dataset_file_id={} in {} ms",
                    claimed.id, archivedFileId, elapsedMs);
            return true;
        } catch (Throwable t) { // NOSONAR — Quartz can swallow Errors; record everything.
            LOG.error("ExportJobRunner: job_id=" + claimed.id + " failed", t);
            jobDao.markFailed(claimed.id, t.getClass().getSimpleName() + ": " + t.getMessage());
            return true;
        }
    }

    /**
     * Two paths:
     * <ol>
     *   <li>Production materializer ({@link SynchronousExportMaterializer})
     *       already persisted an {@code archived_dataset_file} row inside
     *       {@code GenerateExtractFileService}. It signals this by setting
     *       {@code fileReference="existing:<id>"} on the Result — we
     *       reuse that id instead of inserting a duplicate row.</li>
     *   <li>Placeholder / custom materializers return a synthetic
     *       reference — we create the {@code archived_dataset_file} row
     *       ourselves so the SPA's per-dataset file list still shows it.</li>
     * </ol>
     */
    private static int resolveOrCreateArchivedFile(DataSource dataSource,
                                                   DatasetBean ds,
                                                   ExportJobDAO.Row claimed,
                                                   ExportFileMaterializer.Result result,
                                                   long elapsedMs) {
        String ref = result.fileReference();
        if (ref != null && ref.startsWith("existing:")) {
            try {
                return Integer.parseInt(ref.substring("existing:".length()));
            } catch (NumberFormatException nfe) {
                LOG.warn("Unparseable 'existing:' sentinel '{}' — falling back to fresh insert", ref);
            }
        }
        ArchivedDatasetFileBean adf = new ArchivedDatasetFileBean();
        adf.setName(result.name());
        adf.setDatasetId(ds.getId());
        adf.setExportFormatId(formatIdFor(claimed.format));
        adf.setFileReference(ref);
        adf.setFileSize((int) Math.min(result.fileSize(), Integer.MAX_VALUE));
        adf.setRunTime(elapsedMs / 1000.0);
        adf.setOwnerId(claimed.submittedBy);
        new ArchivedDatasetFileDAO(dataSource).create(adf);
        return adf.getId();
    }

    private static ExportFileMaterializer resolveMaterializer(ApplicationContext appCtx) {
        try {
            return appCtx.getBean(ExportFileMaterializer.class);
        } catch (Exception e) {
            // Phase 1 ships the production materializer. Until it lands the
            // PlaceholderExportFileMaterializer below makes the queued → done
            // transition observable end-to-end (smoke test friendly).
            LOG.warn("No ExportFileMaterializer bean found — falling back to placeholder ({}). "
                    + "Phase 1's GenerateExtractFileService wiring will provide the real one.",
                    e.getMessage());
            return new PlaceholderExportFileMaterializer();
        }
    }

    private static int formatIdFor(String format) {
        if (format == null) return ExportFormatBean.TXTFILE.getExportFormatId();
        switch (format.toLowerCase()) {
            case "csv":
            case "tab":
            case "txt": return ExportFormatBean.TXTFILE.getExportFormatId();
            case "excel":
            case "xls":
            case "xlsx": return ExportFormatBean.EXCELFILE.getExportFormatId();
            case "pdf": return ExportFormatBean.PDFFILE.getExportFormatId();
            case "odm":
            case "xml": return ExportFormatBean.XMLFILE.getExportFormatId();
            default: return ExportFormatBean.TXTFILE.getExportFormatId();
        }
    }
}
