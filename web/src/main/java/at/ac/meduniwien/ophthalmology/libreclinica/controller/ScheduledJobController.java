/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;


import static at.ac.meduniwien.ophthalmology.libreclinica.core.util.ClassCastHelper.asArrayList;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractPropertyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.XsltTriggerService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.table.sdv.SDVUtil;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 *
 * @author jnyayapathi
 *  Controller for listing all the scheduled jobs. Also an interface for canceling the jobs which are running.
 */
@Controller("ScheduledJobController")
public class ScheduledJobController {
    private final static Logger logger = LoggerFactory.getLogger(ScheduledJobController.class);

    public final static String SCHEDULED_TABLE_ATTRIBUTE = "scheduledTableAttribute";

    public static final String EP_BEAN = "epBean";

    @Autowired
    @Qualifier("sdvUtil")
    private SDVUtil sdvUtil;

    @Autowired
    private Scheduler scheduler;

    @RequestMapping("/listCurrentScheduledJobs")
    public ModelMap listScheduledJobs(HttpServletRequest request, HttpServletResponse response) throws SchedulerException{
        // Phase B.4 jmesa PR 7c (cohort 5c): the factory.createTable().render()
        // call is gone. The JSP shell now includes a vanilla-JS fragment that
        // fetches /pages/listCurrentScheduledJobsData asynchronously.
        Locale locale = LocaleResolver.getLocale(request);
        ResourceBundleProvider.updateLocale(locale);
        ModelMap gridMap = new ModelMap();

        request.setAttribute("imagePathPrefix", "../");

        ArrayList<String> pageMessages = asArrayList(request.getAttribute("pageMessages"), String.class);
        if (pageMessages == null) {
            pageMessages = new ArrayList<String>();
        }
        request.setAttribute("pageMessages", pageMessages);
        return gridMap;
    }

    /**
     * DataTables-protocol JSON endpoint backing the Quartz-jobs table.
     * Phase B.4 jmesa PR 7c (cohort 5c) — replaces the
     * {@code ScheduledJobTableFactory.createTable().render()} blob the
     * {@code /listCurrentScheduledJobs} mapping used to emit.
     *
     * <p>Each row is a snapshot of the current scheduler state. The
     * client polls this endpoint via the vanilla-JS fragment in
     * {@code listCurrentScheduledJobs.jsp}, so "Currently Executing"
     * vs "Scheduled" transitions become visible on the next page load.
     */
    @RequestMapping("/listCurrentScheduledJobsData")
    @ResponseBody
    public void listScheduledJobsData(HttpServletRequest request, HttpServletResponse response)
            throws SchedulerException, IOException {
        Locale locale = LocaleResolver.getLocale(request);
        ResourceBundleProvider.updateLocale(locale);

        List<JobExecutionContext> listCurrentJobs = scheduler.getCurrentlyExecutingJobs();
        List<JobKey> currentJobList = listCurrentJobs.stream()
                .map(job -> job.getTrigger().getJobKey())
                .collect(Collectors.toList());

        List<String> triggerGroups = scheduler.getTriggerGroupNames();
        List<SimpleTrigger> simpleTriggers = new ArrayList<>();
        for (String triggerGroup : triggerGroups) {
            Set<TriggerKey> triggerKeys = scheduler.getTriggerKeys(
                    GroupMatcher.triggerGroupEquals(triggerGroup));
            for (TriggerKey triggerKey : triggerKeys) {
                TriggerState state = scheduler.getTriggerState(triggerKey);
                if (state != TriggerState.PAUSED) {
                    simpleTriggers.add((SimpleTrigger) scheduler.getTrigger(triggerKey));
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SimpleTrigger st : simpleTriggers) {
            JobKey jobKey = st.getJobKey();
            boolean isExecuting = currentJobList.contains(jobKey);

            ExtractPropertyBean epBean = null;
            if (st.getJobDataMap() != null) {
                epBean = (ExtractPropertyBean) st.getJobDataMap().get(EP_BEAN);
            }
            if (epBean == null) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("datasetId", epBean.getDatasetName());
            row.put("fireTime", st.getStartTime() != null ? longFormat(locale).format(st.getStartTime()) : "");
            row.put("exportFileName",
                    epBean.getExportFileName() != null && epBean.getExportFileName().length > 0
                            ? epBean.getExportFileName()[0] : "");
            row.put("jobStatus", isExecuting ? "Currently Executing" : "Scheduled");
            row.put("isExecuting", isExecuting);
            // jobKey + triggerKey carried through so the cancel-job
            // form on the client side can post them back unchanged.
            row.put("jobName", jobKey.getName());
            row.put("jobGroup", jobKey.getGroup());
            row.put("triggerName", st.getKey().getName());
            row.put("triggerGroup", st.getKey().getGroup());
            rows.add(row);
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(column("datasetId",      "Dataset"));
        columns.add(column("fireTime",       "Fire Time"));
        columns.add(column("exportFileName", "Export File Name"));
        columns.add(column("jobStatus",      "Job Status"));
        columns.add(column("action",         "Action"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draw", parseDraw(request));
        payload.put("recordsTotal", rows.size());
        payload.put("recordsFiltered", rows.size());
        payload.put("data", rows);
        payload.put("columns", columns);

        response.setContentType("application/json;charset=UTF-8");
        try (OutputStream out = response.getOutputStream()) {
            new ObjectMapper().writeValue(out, payload);
        }
    }

    private static Map<String, Object> column(String key, String title) {
        Map<String, Object> c = new HashMap<>();
        c.put("key", key);
        c.put("title", title);
        return c;
    }

    private static int parseDraw(HttpServletRequest request) {
        String s = request.getParameter("draw");
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException nfe) { return 0; }
    }

    @RequestMapping("/cancelScheduledJob")
    public String cancelScheduledJob(HttpServletRequest request, HttpServletResponse response,
            @RequestParam("theJobName") String theJobName,
            @RequestParam("theJobGroupName") String theJobGroupName,
            @RequestParam("theTriggerName") String triggerName,
            @RequestParam("theTriggerGroupName") String triggerGroupName,
            @RequestParam("redirection") String redirection, ModelMap model) throws SchedulerException {

    	JobKey jobKey = new JobKey(theJobName, theJobGroupName);
    	TriggerKey triggerKey = new TriggerKey(triggerName, triggerGroupName);
        scheduler.getJobDetail(jobKey);
        logger.debug("About to pause the job-->" + theJobName + "Job Group Name -->" + theJobGroupName);

        SimpleTrigger oldTrigger = (SimpleTrigger) scheduler.getTrigger(triggerKey);
        if (oldTrigger != null) {
            
            Date startTime = new Date(oldTrigger.getStartTime().getTime() + oldTrigger.getRepeatInterval());
            if (triggerGroupName.equals(ExtractController.TRIGGER_GROUP_NAME)) {
                interruptQuartzJob(scheduler, theJobName, theJobGroupName);
            }

            scheduler.pauseJob(jobKey);

            SimpleTrigger newTrigger = newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .startAt(startTime)
                .withSchedule(simpleSchedule()
                    .withRepeatCount(oldTrigger.getRepeatCount())
                    .withIntervalInMilliseconds(oldTrigger.getRepeatInterval())
                    .withMisfireHandlingInstructionNextWithRemainingCount())
                .withDescription(oldTrigger.getDescription())
                .usingJobData(oldTrigger.getJobDataMap())
                .build();

            scheduler.unscheduleJob(triggerKey);// these are the jobs which are from extract data and are not not required to be rescheduled.

            ArrayList<String> pageMessages = new ArrayList<>();

            if (triggerGroupName.equals(ExtractController.TRIGGER_GROUP_NAME)) {
                scheduler.rescheduleJob(triggerKey, newTrigger);
                pageMessages.add("The Job " + theJobName + " has been cancelled");
            } else if (triggerGroupName.equals(XsltTriggerService.TRIGGER_GROUP_NAME)) {
                JobDetailFactoryBean jobDetailBean = new JobDetailFactoryBean();
                jobDetailBean.setGroup(XsltTriggerService.TRIGGER_GROUP_NAME);
                jobDetailBean.setName(newTrigger.getKey().getName());
                jobDetailBean.setJobClass(at.ac.meduniwien.ophthalmology.libreclinica.job.XsltStatefulJob.class);
                jobDetailBean.setJobDataMap(newTrigger.getJobDataMap());
                jobDetailBean.setDurability(true); // need durability?
                jobDetailBean.afterPropertiesSet();

                scheduler.deleteJob(jobKey);
                scheduler.scheduleJob(jobDetailBean.getObject(), newTrigger);
                pageMessages.add("The Job " + theJobName + " has been rescheduled");
            }

            request.setAttribute("pageMessages", pageMessages);

            logger.debug("jobDetails>" + scheduler.getJobDetail(jobKey));
        }
        
        sdvUtil.forwardRequestFromController(request, response, "/pages/" + redirection);
        
        return null;
    }

    private void interruptQuartzJob(Scheduler scheduler, String jobName, String jobGroup) throws SchedulerException {
        scheduler.interrupt(JobKey.jobKey(jobName, jobGroup));
    }

    private String longFormatString() {
        return "EEE MMM dd HH:mm:ss zzz yyyy";
    }

    private SimpleDateFormat longFormat(Locale locale) {
        return new SimpleDateFormat(longFormatString(), locale);
    }

}
