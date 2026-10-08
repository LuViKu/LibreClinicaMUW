/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.job.JobInterruptedException;
import at.ac.meduniwien.ophthalmology.libreclinica.job.JobTerminationMonitor;

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
 * <h2>Cancellation</h2>
 *
 * <p>A running job can be asked to stop ({@link #requestCancel(long)}).
 * The worker gives its thread a {@link JobTerminationMonitor}, the
 * cooperative mechanism the legacy extract jobs use: the ODM extract and
 * the dataset bundle check it at their checkpoints and throw
 * {@link JobInterruptedException}, and the job ends {@code cancelled}.
 * No file is registered for it, as a file is registered only once the
 * export is complete, and the part the ODM extract or the bundle had
 * written is removed from disk. The tabular formats have no checkpoint and
 * finish.
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
@SuppressWarnings("all")
public class ExportJobRunner implements Job {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJobRunner.class);

    /** The monitor of each job a worker of this JVM is running, by export_job id. */
    private static final Map<Long, JobTerminationMonitor> RUNNING = new ConcurrentHashMap<>();

    /**
     * Ask the worker running {@code jobId} to stop at its next checkpoint.
     * Returns {@code false} when no worker of this JVM is running it.
     */
    public static boolean requestCancel(long jobId) {
        JobTerminationMonitor monitor = RUNNING.get(jobId);
        if (monitor == null) return false;
        monitor.terminate();
        return true;
    }

    /** True while a worker of this JVM runs {@code jobId} and has been asked to stop. */
    public static boolean isCancelRequested(long jobId) {
        JobTerminationMonitor monitor = RUNNING.get(jobId);
        return monitor != null && monitor.isTerminated();
    }

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

        runOnce(dataSource, materializer, resolveNotifier(appCtx));
    }

    /**
     * Drains exactly one queued row, sending no completion mail. Returns
     * {@code true} if a job was processed, {@code false} if the queue was
     * empty.
     */
    public static boolean runOnce(DataSource dataSource, ExportFileMaterializer materializer) {
        return runOnce(dataSource, materializer, null);
    }

    /**
     * Drains exactly one queued row. When the row came from an
     * {@code export_schedule}, {@code notifier} mails the schedule's
     * contact address once the run is done or failed; a null notifier
     * sends nothing. Returns {@code true} if a job was processed,
     * {@code false} if the queue was empty.
     */
    public static boolean runOnce(DataSource dataSource, ExportFileMaterializer materializer,
                                  ExportCompletionNotifier notifier) {
        ExportJobDAO jobDao = new ExportJobDAO(dataSource);
        // The extract's checkpoints read this thread's monitor;
        // requestCancel reaches the same one through RUNNING. It is in
        // RUNNING before the claim commits: a cancel that sees the job
        // running must find the monitor, not answer that no worker of this
        // server runs it.
        long[] registered = {-1L};
        ExportJobDAO.Row claimed = jobDao.claimNextQueued(id -> {
            RUNNING.put(id, JobTerminationMonitor.createInstance("export_job " + id));
            registered[0] = id;
        });
        if (claimed == null) {
            if (registered[0] >= 0) RUNNING.remove(registered[0]);
            JobTerminationMonitor.clear();
            return false;
        }
        // A Quartz worker has no request to take a locale from, and the extract
        // reads the per-thread resource bundles (ResourceBundleProvider.getResBundle
        // NPEs when none is bound), so every queued export died. Bind the
        // requesting user's locale for the run and put the thread back afterwards:
        // Quartz pools its threads.
        Locale previousLocale = ResourceBundleProvider.getLocale();
        ResourceBundleProvider.updateLocale(resolveLocale(dataSource, claimed.submittedBy));
        try {
            process(dataSource, materializer, jobDao, claimed);
        } finally {
            if (previousLocale == null) {
                ResourceBundleProvider.localeMap.remove(Thread.currentThread());
            } else {
                ResourceBundleProvider.updateLocale(previousLocale);
            }
            RUNNING.remove(claimed.id);
            // Quartz pools its threads: the next job must not inherit this monitor.
            JobTerminationMonitor.clear();
            // After the outcome is recorded: the mail reports it, it cannot change it.
            if (notifier != null && claimed.scheduleId != null) {
                notifier.notifyFinished(claimed.id);
            }
        }
        return true;
    }

    /**
     * The locale the requesting user stored on their profile
     * ({@code user_account.locale}); English (the application default,
     * as in LocaleResolver) when none is stored or it cannot be read.
     */
    static Locale resolveLocale(DataSource dataSource, int userId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT locale FROM user_account WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isBlank()) {
                        Locale l = Locale.forLanguageTag(v.trim().replace('_', '-'));
                        if (!l.getLanguage().isEmpty()) return l;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("No stored locale for user_id={}: {}", userId, e.getMessage());
        }
        return Locale.ENGLISH;
    }

    private static void process(DataSource dataSource, ExportFileMaterializer materializer,
                                ExportJobDAO jobDao, ExportJobDAO.Row claimed) {
        LOG.info("ExportJobRunner: picked up job_id={} dataset_id={} format={}",
                claimed.id, claimed.datasetId, claimed.format);

        try {
            DatasetDAO datasetDao = new DatasetDAO(dataSource);
            DatasetBean ds = (DatasetBean) datasetDao.findByPK(claimed.datasetId);
            if (ds == null || ds.getId() == 0) {
                jobDao.markFailed(claimed.id,
                        "Dataset " + claimed.datasetId + " no longer exists");
                return;
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
                return;
            }
            jobDao.markDone(claimed.id, archivedFileId);
            LOG.info("ExportJobRunner: completed job_id={} archived_dataset_file_id={} in {} ms",
                    claimed.id, archivedFileId, elapsedMs);
        } catch (JobInterruptedException cancelled) {
            // Stopped at a checkpoint, before any file was registered.
            LOG.info("ExportJobRunner: job_id={} cancelled", claimed.id);
            jobDao.markCancelled(claimed.id, "Cancelled");
        } catch (Throwable t) { // NOSONAR — Quartz can swallow Errors; record everything.
            LOG.error("ExportJobRunner: job_id=" + claimed.id + " failed", t);
            jobDao.markFailed(claimed.id, t.getClass().getSimpleName() + ": " + t.getMessage());
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

    private static ExportCompletionNotifier resolveNotifier(ApplicationContext appCtx) {
        try {
            return appCtx.getBean(ExportCompletionNotifier.class);
        } catch (Exception e) {
            LOG.warn("No ExportCompletionNotifier bean; scheduled exports send no completion mail ({})",
                    e.getMessage());
            return null;
        }
    }

    /**
     * The {@code export_format} row a format is registered under. The
     * constants carry that id as their term id; their
     * {@code getExportFormatId()} is never set and answers 0, which no
     * {@code export_format} row has.
     */
    private static int formatIdFor(String format) {
        if (format == null) return ExportFormatBean.TXTFILE.getId();
        switch (format.toLowerCase()) {
            // as SynchronousExportMaterializer registers its CSV file
            case "csv": return ExportFormatBean.CSVFILE.getId();
            case "tsv":
            case "tab":
            case "txt": return ExportFormatBean.TXTFILE.getId();
            case "excel":
            case "xls":
            case "xlsx": return ExportFormatBean.EXCELFILE.getId();
            case "pdf": return ExportFormatBean.PDFFILE.getId();
            case "odm":
            case "xml": return ExportFormatBean.XMLFILE.getId();
            case "bundle": return ExportFormatBean.ZIPFILE.getId();
            default: return ExportFormatBean.TXTFILE.getId();
        }
    }
}
