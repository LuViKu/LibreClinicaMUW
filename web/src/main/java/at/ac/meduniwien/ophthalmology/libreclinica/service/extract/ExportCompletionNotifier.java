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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportJobDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportScheduleDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;

/**
 * Mails a scheduled export's contact address when a run finishes.
 *
 * <p>The legacy scheduled export ({@code /CreateJobExport}) required a
 * contact address and mailed it after every run. An {@code export_schedule}
 * may name one ({@code notify_email}); this is that mail. It names the
 * dataset, the study and the format, says whether the run succeeded or
 * failed, and where to find the file. It carries neither the file nor the
 * error text: an extract's error message can quote a data value, and mail
 * leaves the platform.
 *
 * <p>Only runs that came from a schedule with an address are mailed. An
 * export someone started by hand is followed in the SPA by whoever started
 * it, and a cancelled run is not mailed: someone chose to stop it. Nor is a
 * run of a schedule that was paused or deleted while the run was queued or
 * running: whoever stopped the schedule stopped its mail with it. The run
 * itself still finishes, and its file is listed with the dataset's files.
 *
 * <p>Never throws. The run's outcome is already recorded, and a mail server
 * that is down must not turn a finished export into a failed one. A failed
 * send is logged here, and {@link OpenClinicaMailSender} writes its
 * OPERATION_FAILED audit row.
 */
@Service
public class ExportCompletionNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(ExportCompletionNotifier.class);

    private final OpenClinicaMailSender mailSender;
    private final DataSource dataSource;

    @Autowired
    public ExportCompletionNotifier(@Qualifier("openClinicaMailSender") OpenClinicaMailSender mailSender,
                                    @Qualifier("dataSource") DataSource dataSource) {
        this.mailSender = mailSender;
        this.dataSource = dataSource;
    }

    /**
     * Mail the contact address of the schedule {@code jobId} came from, if
     * the job came from a schedule that still runs (neither deleted nor
     * paused), the schedule names an address, and the job is done or failed.
     */
    public void notifyFinished(long jobId) {
        try {
            ExportJobDAO.Row job = new ExportJobDAO(dataSource).findById(jobId);
            if (job == null || job.scheduleId == null) return;
            boolean done = ExportJobDAO.STATUS_DONE.equals(job.status);
            if (!done && !ExportJobDAO.STATUS_FAILED.equals(job.status)) return;
            ExportScheduleDAO.Row schedule = new ExportScheduleDAO(dataSource).findById(job.scheduleId);
            if (schedule == null || !schedule.active || !schedule.enabled) return;
            if (schedule.notifyEmail == null || schedule.notifyEmail.isBlank()) return;

            DatasetBean dataset = (DatasetBean) new DatasetDAO(dataSource).findByPK(job.datasetId);
            String datasetName = dataset == null || dataset.getId() == 0
                    ? "dataset " + job.datasetId : dataset.getName();
            String studyName = "";
            if (dataset != null && dataset.getId() != 0) {
                StudyBean study = (StudyBean) new StudyDAO(dataSource).findByPK(dataset.getStudyId());
                if (study != null && study.getId() != 0) studyName = study.getName();
            }

            String subject = "[LibreClinica] Scheduled export " + (done ? "finished" : "failed")
                    + ": " + datasetName;
            String body = (done
                    ? "<p>A scheduled export has finished.</p>"
                    : "<p>A scheduled export has failed.</p>")
                    + "<ul>"
                    + "<li>Dataset: " + escape(datasetName) + "</li>"
                    + "<li>Study: " + escape(studyName) + "</li>"
                    + "<li>Format: " + escape(job.format) + "</li>"
                    + "<li>Schedule: " + escape(schedule.cronExpression) + "</li>"
                    + "</ul>"
                    + (done
                    ? "<p>The file is in LibreClinica, under Data Export, in the dataset's files.</p>"
                    : "<p>The reason is shown in LibreClinica under Data Export. "
                            + "The schedule keeps running.</p>");
            mailSender.sendEmail(schedule.notifyEmail.trim(), subject, body, Boolean.TRUE);
            LOG.info("Completion mail sent for export_job id={} (export_schedule id={})", jobId, schedule.id);
        } catch (OpenClinicaSystemException e) {
            LOG.warn("Completion mail for export_job id={} failed: {}", jobId, e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Completion mail for export_job id={} failed: {}", jobId, e.toString());
        }
    }

    /** Minimal HTML escape for names that end up in the mail body. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
