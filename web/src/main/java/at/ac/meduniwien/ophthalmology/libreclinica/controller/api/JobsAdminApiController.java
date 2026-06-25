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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import jakarta.servlet.http.HttpSession;
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
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p><strong>Scope.</strong> Read-only. The legacy pause / pause-all
 * paths are NOT exposed in this slice — they have material liability
 * (a misclick can stomp on a long-running export) and the legacy
 * top-level menu doesn't surface them either. Per-job actions can be
 * added in a follow-up against a tighter set of role gates if
 * operators ask.
 *
 * <p><strong>Authorization.</strong> The endpoint is sysadmin-only —
 * same posture as the L3 admin tooling. Non-sysadmin → 403.
 *
 * <p>The response shape is JSON keys + ISO instants so the SPA can
 * render dates without re-parsing Java's {@link Date#toString()}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin jobs",
     description = "Sysadmin Quartz trigger listing — SPA replacement for the legacy job-admin JSPs.")
public class JobsAdminApiController {

    private static final Logger LOG = LoggerFactory.getLogger(JobsAdminApiController.class);

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
     * Walk every group of triggers in the scheduler. Surfacing both
     * the trigger key + the linked job key gives the SPA enough to
     * deep-link or correlate logs without an extra fetch.
     */
    private List<Map<String, Object>> collectTriggerRows() throws SchedulerException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String group : scheduler.getTriggerGroupNames()) {
            Set<TriggerKey> keys = scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(group));
            for (TriggerKey key : keys) {
                Trigger trigger = scheduler.getTrigger(key);
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
                out.add(row);
            }
        }
        return out;
    }
}
