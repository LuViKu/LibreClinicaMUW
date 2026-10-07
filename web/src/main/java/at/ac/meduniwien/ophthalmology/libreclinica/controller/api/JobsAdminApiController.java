/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.XsltTriggerService;

import jakarta.servlet.http.HttpSession;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.8 legacy-retirement Slice L5 (2026-06-20) — SPA replacement
 * for the legacy {@code ViewAllJobsServlet} + the
 * {@code ViewJobServlet} / {@code ViewImportJobServlet} family. Returns
 * a flat list of every Quartz trigger registered against the shared
 * scheduler bean ({@code schedulerFactoryBean}, see {@code
 * QuartzConfig}).
 *
 * <p><strong>Scope.</strong> Read-only, with one exception: a legacy
 * scheduled export (group {@code XsltTriggersExportJobs}, made by the
 * retiring {@code /CreateJobExport}) can be deleted, and its row carries
 * what it was set to do. Those jobs are not migrated to SPA schedules: a
 * legacy job's format is an XSLT stylesheet the SPA does not produce, its
 * period ("monthly" is every 28 days) has no cron equivalent, and its
 * stored data may no longer be readable after the package rename. An
 * administrator recreates one on the dataset's schedules and deletes it
 * here. Every other trigger is the application's own, registered at boot
 * where it is defined, and stays read-only; so do pause and pause-all,
 * which have material liability (a misclick can stomp on a long-running
 * export).
 *
 * <p><strong>Authorization.</strong> The endpoint is sysadmin-only —
 * same posture as the L3 admin tooling. Non-sysadmin → 403.
 *
 * <p>The response shape is JSON keys + ISO instants so the SPA can
 * render dates without re-parsing Java's {@link java.util.Date#toString()}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin jobs",
     description = "Sysadmin Quartz trigger listing — SPA replacement for the legacy job-admin JSPs.")
public class JobsAdminApiController {

    private static final Logger LOG = LoggerFactory.getLogger(JobsAdminApiController.class);

    /** Where /CreateJobExport put its jobs; job and trigger share a name. */
    static final String LEGACY_EXPORT_GROUP = new XsltTriggerService().getTriggerGroupNameForExportJobs();

    private final Scheduler scheduler;

    @Autowired
    public JobsAdminApiController(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @GetMapping(value = "/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listJobs(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Sysadmin privilege required."));
        }

        try {
            List<Map<String, Object>> rows = collectTriggerRows();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schedulerName", scheduler.getSchedulerName());
            body.put("isStarted", scheduler.isStarted());
            body.put("isStandby", scheduler.isInStandbyMode());
            body.put("jobs", rows);
            return ResponseEntity.ok(body);
        } catch (SchedulerException e) {
            LOG.warn("Scheduler enumeration failed", e);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Scheduler is not available — try again after the next restart.",
                    "cause", e.getMessage()));
        }
    }

    /**
     * Delete a legacy scheduled export: the trigger and the job
     * {@code /CreateJobExport} made under {@code name}. Only that group can
     * be deleted here (see the class comment). The trigger goes first,
     * which works even for a job whose stored data can no longer be read;
     * a run that is executing finishes, and nothing starts after it.
     */
    @DeleteMapping("/jobs/legacy-exports/{name}")
    public ResponseEntity<?> deleteLegacyExportJob(@PathVariable("name") String name,
                                                   HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Sysadmin privilege required."));
        }
        TriggerKey triggerKey = TriggerKey.triggerKey(name, LEGACY_EXPORT_GROUP);
        JobKey jobKey = JobKey.jobKey(name, LEGACY_EXPORT_GROUP);
        try {
            boolean hadTrigger = scheduler.checkExists(triggerKey);
            if (!hadTrigger && !scheduler.checkExists(jobKey)) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "No legacy export job with that name."));
            }
            if (hadTrigger) {
                scheduler.unscheduleJob(triggerKey);
            }
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
            LOG.info("Deleted legacy export job '{}' ({}) by user={}",
                    name.replaceAll("[\r\n]", "_"), LEGACY_EXPORT_GROUP, ub.getName());
            return ResponseEntity.noContent().build();
        } catch (SchedulerException e) {
            LOG.warn("Deleting a legacy export job failed", e);
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Scheduler is not available — try again after the next restart.",
                    "cause", e.getMessage()));
        }
    }

    /**
     * Walk every group of triggers in the scheduler. Surfacing both
     * the trigger key + the linked job key gives the SPA enough to
     * deep-link or correlate logs without an extra fetch.
     *
     * <p>A trigger whose stored data cannot be read (a class that no longer
     * exists, as after the package rename) is listed with what the store
     * still answers, rather than failing the whole list.
     */
    private List<Map<String, Object>> collectTriggerRows() throws SchedulerException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String group : scheduler.getTriggerGroupNames()) {
            Set<TriggerKey> keys = scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(group));
            for (TriggerKey key : keys) {
                Trigger trigger;
                try {
                    trigger = scheduler.getTrigger(key);
                } catch (SchedulerException | RuntimeException unreadable) {
                    LOG.warn("Trigger {} cannot be read: {}", key, unreadable.getMessage());
                    out.add(unreadableRow(key, group));
                    continue;
                }
                if (trigger == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", key.getName());
                row.put("group", group);
                row.put("description", trigger.getDescription());
                row.put("priority", trigger.getPriority());
                row.put("previousFireTime", trigger.getPreviousFireTime());
                row.put("nextFireTime", trigger.getNextFireTime());
                row.put("finalFireTime", trigger.getFinalFireTime());
                row.put("state", scheduler.getTriggerState(key).name());
                JobKey jobKey = trigger.getJobKey();
                if (jobKey != null) {
                    row.put("jobName", jobKey.getName());
                    row.put("jobGroup", jobKey.getGroup());
                }
                if (LEGACY_EXPORT_GROUP.equals(group)) {
                    row.put("legacyExport", legacyExportDetails(trigger.getJobDataMap()));
                }
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, Object> unreadableRow(TriggerKey key, String group) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", key.getName());
        row.put("group", group);
        row.put("unreadable", true);
        try {
            row.put("state", scheduler.getTriggerState(key).name());
        } catch (SchedulerException | RuntimeException e) {
            row.put("state", "ERROR");
        }
        if (LEGACY_EXPORT_GROUP.equals(group)) {
            row.put("legacyExport", new LinkedHashMap<String, Object>());
        }
        return row;
    }

    /**
     * What a legacy scheduled export was set to do, read from its job data
     * as {@code ViewJobServlet} reads it, so that it can be recreated as a
     * schedule before it is deleted.
     */
    private static Map<String, Object> legacyExportDetails(JobDataMap data) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("jobName", text(data, XsltTriggerService.JOB_NAME));
        d.put("datasetId", data == null ? null : data.get(XsltTriggerService.DATASET_ID));
        d.put("period", text(data, XsltTriggerService.PERIOD));
        d.put("exportFormat", text(data, XsltTriggerService.EXPORT_FORMAT));
        d.put("contactEmail", text(data, XsltTriggerService.EMAIL));
        return d;
    }

    private static String text(JobDataMap data, String key) {
        Object v = data == null ? null : data.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
