/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.json.JsonMapper;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DatasetItemStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportJobDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ExportScheduleDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.job.JobInterruptedException;
import at.ac.meduniwien.ophthalmology.libreclinica.job.JobTerminationMonitor;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportCompletionNotifier;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportFileMaterializer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportJobRunner;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportScheduleRegistrar;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.PlaceholderExportFileMaterializer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.SynchronousExportMaterializer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * Export schedules and queued exports, against a real database and a real
 * (never started) Quartz scheduler.
 *
 * <p>A dataset is acted on only from the study it belongs to. The role check
 * says the caller may export in the active study; it says nothing about the
 * study a dataset id names, so every dataset-scoped endpoint also checks that
 * the dataset is the active study's, as {@code DatasetsApiController} does.
 * Each test shows both sides: the caller in the dataset's study succeeds, the
 * same role bound to another study is refused and changes nothing.
 */
@SuppressWarnings("null")
class ExportSchedulesApiDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY_ID = 1;
    private static final String STUDY_OID = "S_DEFAULTS1";
    /** Only ever a session value: the refusals happen before any lookup of it. */
    private static final int OTHER_STUDY_ID = 2;
    private static final String CRON = "0 0 3 ? * MON";
    private static final String SCHEDULE_GROUP = "exportSchedule";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Scheduler SCHEDULER;

    @TempDir
    static Path FILE_ROOT;

    private Integer datasetId;

    @BeforeAll
    static void pointFilePathAtATempDir() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        Properties live = (Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        live.setProperty("filePath", FILE_ROOT.toString() + File.separator);
    }

    @BeforeAll
    static void createScheduler() throws Exception {
        Properties p = new Properties();
        p.setProperty("org.quartz.scheduler.instanceName", "export-schedules-it");
        p.setProperty("org.quartz.threadPool.threadCount", "1");
        p.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        // Never started: triggers are registered and inspected, nothing fires.
        SCHEDULER = new StdSchedulerFactory(p).getScheduler();
    }

    @AfterAll
    static void shutDownScheduler() throws Exception {
        if (SCHEDULER != null) SCHEDULER.shutdown(false);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (datasetId != null) {
            exec("DELETE FROM export_schedule WHERE dataset_id = " + datasetId);
            exec("DELETE FROM export_job WHERE dataset_id = " + datasetId);
            exec("DELETE FROM archived_dataset_file WHERE dataset_id = " + datasetId);
            exec("DELETE FROM dataset WHERE dataset_id = " + datasetId);
            datasetId = null;
        }
        exec("DELETE FROM study_setting WHERE study_id = " + STUDY_ID);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'study_setting'");
        SCHEDULER.clear();
    }

    /* ---------------- fixtures ---------------- */

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private long count(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private DatasetBean persistDataset() {
        UserAccountBean root = new UserAccountDAO(DATA_SOURCE).findByPK(1);
        DatasetBean ds = new DatasetBean();
        ds.setStudyId(STUDY_ID);
        ds.setName("schedule fixture " + System.nanoTime());
        ds.setDescription("export schedules");
        ds.setStatus(Status.AVAILABLE);
        ds.setDatasetItemStatus(DatasetItemStatus.COMPLETED_AND_NONCOMPLETED);
        ds.setOwner(root);
        ds.setOwnerId(1);
        ds.setCreatedDate(new Date());
        ds.setNumRuns(0);
        ds.setODMMetaDataVersionName("");
        ds.setODMMetaDataVersionOid("");
        ds.setODMPriorStudyOid("");
        ds.setODMPriorMetaDataVersionOid("");
        ds.setEventIds(new ArrayList<>(List.of(1, 2, 3)));
        ds.setItemIds(new ArrayList<>(List.of(1, 2, 3, 4, 5)));
        ds.setShowSubjectUniqueIdentifier(true);
        ds.setSQLStatement(ds.generateQuery());
        DatasetBean persisted = new DatasetDAO(DATA_SOURCE).create(ds);
        assertTrue(persisted.getId() > 0, "the fixture dataset should persist");
        datasetId = persisted.getId();
        return persisted;
    }

    private long scheduleRow(DatasetBean ds) {
        long id = new ExportScheduleDAO(DATA_SOURCE).create(ds.getId(), "odm", CRON, 1, null);
        assertTrue(id > 0, "the fixture schedule should persist");
        return id;
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new ExportJobsApiController(DATA_SOURCE,
                                new ExportScheduleRegistrar(SCHEDULER, DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** A Data Manager (legacy coordinator) of the given study; not a sysadmin. */
    private MockHttpSession dataManagerIn(int studyId) {
        return dataManagerIn(studyId, 1, "root");
    }

    private MockHttpSession dataManagerIn(int studyId, int userId, String userName) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId);
        ub.setName(userName);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(studyId == STUDY_ID ? STUDY_OID : "S_OTHER");
        s.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.COORDINATOR);
        role.setStudyId(studyId);
        role.setUserName(userName);
        role.setUserAccountId(userId);
        s.setAttribute("userRole", role);
        return s;
    }

    private MockHttpSession sysadminIn(int studyId) {
        MockHttpSession s = dataManagerIn(studyId, 1, "root");
        ((UserAccountBean) s.getAttribute("userBean")).addUserType(UserType.SYSADMIN);
        return s;
    }

    private org.springframework.test.web.servlet.ResultActions cancel(long jobId, MockHttpSession session)
            throws Exception {
        return mockMvc().perform(post("/api/v1/exports/" + jobId + "/cancel").session(session));
    }

    private long archivedFilesOf(DatasetBean ds) throws Exception {
        return count("SELECT count(*) FROM archived_dataset_file WHERE dataset_id = " + ds.getId());
    }

    private int scheduleTriggers() throws Exception {
        return SCHEDULER.getTriggerKeys(GroupMatcher.triggerGroupEquals(SCHEDULE_GROUP)).size();
    }

    private Trigger triggerOf(long scheduleId) throws Exception {
        return SCHEDULER.getTrigger(
                TriggerKey.triggerKey("exportScheduleTrigger-" + scheduleId, SCHEDULE_GROUP));
    }

    private long createViaApi(DatasetBean ds) throws Exception {
        String json = mockMvc().perform(post("/api/v1/datasets/" + ds.getId() + "/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"odm\",\"cronExpression\":\"" + CRON + "\"}")
                        .session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(json).get("id").asLong();
    }

    /** The job data a schedule's trigger carries, as registered for it. */
    private JobDataMap jobDataOf(long scheduleId) throws Exception {
        return SCHEDULER.getJobDetail(triggerOf(scheduleId).getJobKey()).getJobDataMap();
    }

    /** Job data as a trigger registered before an edit or pause would still carry it. */
    private static JobDataMap staleJobData(long scheduleId, int datasetId, String format) {
        JobDataMap data = new JobDataMap();
        data.put(ExportScheduleRegistrar.ScheduleFireJob.KEY_SCHEDULE_ID, scheduleId);
        data.put(ExportScheduleRegistrar.ScheduleFireJob.KEY_DATASET_ID, datasetId);
        data.put(ExportScheduleRegistrar.ScheduleFireJob.KEY_FORMAT, format);
        data.put(ExportScheduleRegistrar.ScheduleFireJob.KEY_CRON, CRON);
        return data;
    }

    /**
     * One tick of a schedule, through the Quartz job Quartz would run, with
     * the job data given. Returns the id of the newest export job of the
     * dataset afterwards, or -1 when the tick queued none.
     */
    private long tick(JobDataMap data, int datasetId) throws Exception {
        long before = count("SELECT count(*) FROM export_job WHERE dataset_id = " + datasetId);
        ApplicationContext app = Mockito.mock(ApplicationContext.class);
        Mockito.when(app.getBean("dataSource", DataSource.class)).thenReturn(DATA_SOURCE);
        SchedulerContext schedulerContext = new SchedulerContext();
        schedulerContext.put("applicationContext", app);
        Scheduler scheduler = Mockito.mock(Scheduler.class);
        Mockito.when(scheduler.getContext()).thenReturn(schedulerContext);
        JobExecutionContext ctx = Mockito.mock(JobExecutionContext.class);
        Mockito.when(ctx.getMergedJobDataMap()).thenReturn(data);
        Mockito.when(ctx.getScheduler()).thenReturn(scheduler);

        new ExportScheduleRegistrar.ScheduleFireJob().execute(ctx);

        if (count("SELECT count(*) FROM export_job WHERE dataset_id = " + datasetId) == before) return -1L;
        return count("SELECT max(id) FROM export_job WHERE dataset_id = " + datasetId);
    }

    private org.springframework.test.web.servlet.ResultActions patchSchedule(long id, String body, int studyId)
            throws Exception {
        return mockMvc().perform(patch("/api/v1/schedules/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .session(dataManagerIn(studyId)));
    }

    /* ---------------- study scope ---------------- */

    @Test
    void anExportIsQueuedOnlyFromTheDatasetsStudy() throws Exception {
        DatasetBean ds = persistDataset();
        String url = "/api/v1/datasets/" + ds.getId() + "/exports";

        mockMvc().perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"odm\"}").session(dataManagerIn(OTHER_STUDY_ID)))
                .andExpect(status().isNotFound());
        assertEquals(0, count("SELECT count(*) FROM export_job WHERE dataset_id = " + ds.getId()));

        mockMvc().perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"odm\"}").session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isAccepted());
        assertEquals(1, count("SELECT count(*) FROM export_job WHERE dataset_id = " + ds.getId()));
    }

    @Test
    void aScheduleIsCreatedOnlyFromTheDatasetsStudy() throws Exception {
        DatasetBean ds = persistDataset();
        String url = "/api/v1/datasets/" + ds.getId() + "/schedules";
        String body = "{\"format\":\"odm\",\"cronExpression\":\"" + CRON + "\"}";

        mockMvc().perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(body).session(dataManagerIn(OTHER_STUDY_ID)))
                .andExpect(status().isNotFound());
        assertEquals(0, count("SELECT count(*) FROM export_schedule WHERE dataset_id = " + ds.getId()));
        assertEquals(0, scheduleTriggers(), "nothing may be scheduled for a refused request");

        mockMvc().perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(body).session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cronExpression").value(CRON));
        assertEquals(1, count("SELECT count(*) FROM export_schedule WHERE dataset_id = " + ds.getId()));
        assertEquals(1, scheduleTriggers());
    }

    @Test
    void schedulesAreListedOnlyInTheDatasetsStudy() throws Exception {
        DatasetBean ds = persistDataset();
        long id = scheduleRow(ds);
        String url = "/api/v1/datasets/" + ds.getId() + "/schedules";

        mockMvc().perform(get(url).session(dataManagerIn(OTHER_STUDY_ID)))
                .andExpect(status().isNotFound());

        mockMvc().perform(get(url).session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void aScheduleIsDeletedOnlyFromTheDatasetsStudy() throws Exception {
        DatasetBean ds = persistDataset();
        long id = scheduleRow(ds);

        mockMvc().perform(delete("/api/v1/schedules/" + id).session(dataManagerIn(OTHER_STUDY_ID)))
                .andExpect(status().isNotFound());
        assertTrue(new ExportScheduleDAO(DATA_SOURCE).findById(id).active,
                "a refused delete leaves the schedule running");

        mockMvc().perform(delete("/api/v1/schedules/" + id).session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isNoContent());
        assertTrue(!new ExportScheduleDAO(DATA_SOURCE).findById(id).active);
    }

    @Test
    void aScheduleIsEditedOnlyFromTheDatasetsStudy() throws Exception {
        DatasetBean ds = persistDataset();
        long id = createViaApi(ds);

        patchSchedule(id, "{\"cronExpression\":\"0 30 4 * * ?\"}", OTHER_STUDY_ID)
                .andExpect(status().isNotFound());
        assertEquals(CRON, new ExportScheduleDAO(DATA_SOURCE).findById(id).cronExpression);
        assertEquals(CRON, ((CronTrigger) triggerOf(id)).getCronExpression(),
                "a refused edit leaves the trigger as it was");

        patchSchedule(id, "{\"cronExpression\":\"0 30 4 * * ?\"}", STUDY_ID)
                .andExpect(status().isOk());
    }

    /* ---------------- edit, pause, resume ---------------- */

    @Test
    void anEditReachesTheSchedulerAtOnce() throws Exception {
        DatasetBean ds = persistDataset();
        long id = createViaApi(ds);
        assertEquals(CRON, ((CronTrigger) triggerOf(id)).getCronExpression());

        String newCron = "0 30 4 * * ?";
        patchSchedule(id, "{\"format\":\"csv\",\"cronExpression\":\"" + newCron + "\"}", STUDY_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("csv"))
                .andExpect(jsonPath("$.cronExpression").value(newCron))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());

        ExportScheduleDAO.Row row = new ExportScheduleDAO(DATA_SOURCE).findById(id);
        assertEquals("csv", row.format);
        assertEquals(newCron, row.cronExpression);
        CronTrigger trigger = (CronTrigger) triggerOf(id);
        assertEquals(newCron, trigger.getCronExpression(), "rescheduled without a restart");
        assertEquals("csv", SCHEDULER.getJobDetail(trigger.getJobKey()).getJobDataMap().getString("format"));

        long jobId = tick(jobDataOf(id), ds.getId());
        assertEquals("csv", new ExportJobDAO(DATA_SOURCE).findById(jobId).format,
                "the next tick exports the new format");
        // and so does a tick of a trigger the edit did not replace
        long staleJobId = tick(staleJobData(id, ds.getId(), "odm"), ds.getId());
        assertEquals("csv", new ExportJobDAO(DATA_SOURCE).findById(staleJobId).format,
                "the schedule row, not the trigger's job data, says what to export");
    }

    @Test
    void aPausedScheduleHasNoTriggerAndQueuesNothingUntilResumed() throws Exception {
        DatasetBean ds = persistDataset();
        long id = createViaApi(ds);

        patchSchedule(id, "{\"enabled\":false}", STUDY_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.nextRunAt").value(Matchers.nullValue()));
        assertNull(triggerOf(id), "a paused schedule has no trigger");
        mockMvc().perform(get("/api/v1/datasets/" + ds.getId() + "/schedules").session(dataManagerIn(STUDY_ID)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].enabled").value(false));
        // a tick from a trigger that outlived the pause queues nothing
        assertEquals(-1L, tick(staleJobData(id, ds.getId(), "odm"), ds.getId()));
        assertEquals(0, count("SELECT count(*) FROM export_job WHERE dataset_id = " + ds.getId()));

        patchSchedule(id, "{\"enabled\":true}", STUDY_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
        assertEquals(CRON, ((CronTrigger) triggerOf(id)).getCronExpression(), "resumed with its own cron");
        assertTrue(tick(jobDataOf(id), ds.getId()) > 0, "a resumed schedule queues again");
    }

    @Test
    void aPausedScheduleStaysPausedAcrossARestart() throws Exception {
        DatasetBean ds = persistDataset();
        long running = createViaApi(ds);
        long paused = createViaApi(ds);
        patchSchedule(paused, "{\"enabled\":false}", STUDY_ID).andExpect(status().isOk());

        // A restart with an empty store, except for a trigger that a failed
        // pause left behind in it.
        SCHEDULER.clear();
        new ExportScheduleRegistrar(SCHEDULER, DATA_SOURCE).registerSchedule(paused, ds.getId(), "odm", CRON);

        ApplicationContext root = Mockito.mock(ApplicationContext.class); // no parent: the root context
        new ExportScheduleRegistrar(SCHEDULER, DATA_SOURCE).onApplicationEvent(new ContextRefreshedEvent(root));

        assertNotNull(triggerOf(running), "an enabled schedule is registered at boot");
        assertNull(triggerOf(paused), "a paused one is not, and loses a stale trigger");
    }

    @Test
    void aDeletedScheduleCannotBeEditedBackToLife() throws Exception {
        DatasetBean ds = persistDataset();
        long id = createViaApi(ds);
        mockMvc().perform(delete("/api/v1/schedules/" + id).session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isNoContent());

        patchSchedule(id, "{\"enabled\":true}", STUDY_ID).andExpect(status().isNotFound());
        assertNull(triggerOf(id));
        assertFalse(new ExportScheduleDAO(DATA_SOURCE).findById(id).active);
    }

    /* ---------------- completion mail ---------------- */

    /** Captures what would be mailed. */
    private static final class Outbox extends OpenClinicaMailSender {
        final List<String[]> sent = new ArrayList<>();

        @Override
        public void sendEmail(String to, String subject, String body, Boolean htmlEmail) {
            sent.add(new String[] {to, subject, body});
        }
    }

    @Test
    void aScheduledRunMailsTheScheduleContactWhetherItSucceedsOrFails() throws Exception {
        DatasetBean ds = persistDataset();
        String json = mockMvc().perform(post("/api/v1/datasets/" + ds.getId() + "/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"odm\",\"cronExpression\":\"" + CRON + "\","
                                + "\"notifyEmail\":\"dm-team@example.org\"}")
                        .session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notifyEmail").value("dm-team@example.org"))
                .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(json).get("id").asLong();
        Outbox outbox = new Outbox();
        ExportCompletionNotifier notifier = new ExportCompletionNotifier(outbox, DATA_SOURCE);
        ExportJobDAO jobs = new ExportJobDAO(DATA_SOURCE);

        // a scheduled run that succeeds
        long done = tick(jobDataOf(id), ds.getId());
        assertTrue(ExportJobRunner.runOnce(DATA_SOURCE, new PlaceholderExportFileMaterializer(), notifier));
        assertEquals(ExportJobDAO.STATUS_DONE, jobs.findById(done).status, jobs.findById(done).errorMessage);
        assertEquals(1, outbox.sent.size());
        assertEquals("dm-team@example.org", outbox.sent.get(0)[0]);
        assertTrue(outbox.sent.get(0)[1].contains("finished") && outbox.sent.get(0)[1].contains(ds.getName()),
                outbox.sent.get(0)[1]);

        // a scheduled run that fails: mailed, without the error text
        long failed = tick(jobDataOf(id), ds.getId());
        ExportFileMaterializer failing = (dataset, format, userId) -> {
            throw new IllegalStateException("value 'M-001 hba1c 7.9' is not a number");
        };
        assertTrue(ExportJobRunner.runOnce(DATA_SOURCE, failing, notifier));
        assertEquals(ExportJobDAO.STATUS_FAILED, jobs.findById(failed).status);
        assertEquals(2, outbox.sent.size());
        assertTrue(outbox.sent.get(1)[1].contains("failed"), outbox.sent.get(1)[1]);
        assertFalse(outbox.sent.get(1)[2].contains("M-001"), "an error message can quote data; it stays in the platform");

        // an export someone started by hand is not mailed
        mockMvc().perform(post("/api/v1/datasets/" + ds.getId() + "/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"odm\"}").session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isAccepted());
        assertTrue(ExportJobRunner.runOnce(DATA_SOURCE, new PlaceholderExportFileMaterializer(), notifier));
        assertEquals(2, outbox.sent.size());

        // nor is a run of a schedule whose address was removed
        patchSchedule(id, "{\"notifyEmail\":\"\"}", STUDY_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyEmail").value(Matchers.nullValue()));
        tick(jobDataOf(id), ds.getId());
        assertTrue(ExportJobRunner.runOnce(DATA_SOURCE, new PlaceholderExportFileMaterializer(), notifier));
        assertEquals(2, outbox.sent.size());
    }

    @Test
    void aStudysExportJobsAreListedOnlyInThatStudy() throws Exception {
        DatasetBean ds = persistDataset();
        long jobId = new ExportJobDAO(DATA_SOURCE).insertQueued(ds.getId(), "odm", 1);
        assertTrue(jobId > 0);
        String url = "/api/v1/studies/" + STUDY_OID + "/export-jobs";

        mockMvc().perform(get(url).session(dataManagerIn(OTHER_STUDY_ID)))
                .andExpect(status().isForbidden());

        mockMvc().perform(get(url).session(dataManagerIn(STUDY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(jobId));
    }

    /* ---------------- cancel ---------------- */

    @Test
    void aQueuedExportIsCancelledAndNeverRuns() throws Exception {
        DatasetBean ds = persistDataset();
        long jobId = new ExportJobDAO(DATA_SOURCE).insertQueued(ds.getId(), "odm", 1);

        cancel(jobId, dataManagerIn(STUDY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.errorMessage").value("Cancelled by root"));

        assertFalse(ExportJobRunner.runOnce(DATA_SOURCE, new PlaceholderExportFileMaterializer()),
                "a cancelled job is not in the queue");
        assertEquals("cancelled", new ExportJobDAO(DATA_SOURCE).findById(jobId).status);
        assertEquals(0, archivedFilesOf(ds));
    }

    @Test
    void aRunningExportStopsAtItsNextCheckpointAndRegistersNoFile() throws Exception {
        DatasetBean ds = persistDataset();
        long jobId = new ExportJobDAO(DATA_SOURCE).insertQueued(ds.getId(), "odm", 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExportFileMaterializer withACheckpoint = (dataset, format, userId) -> {
            running.countDown();
            proceed.await(30, TimeUnit.SECONDS);
            JobTerminationMonitor.check(); // as the ODM extract and the bundle writer do
            return new ExportFileMaterializer.Result("never.xml", "/placeholder/never.xml", 0L);
        };
        Thread worker = new Thread(() -> ExportJobRunner.runOnce(DATA_SOURCE, withACheckpoint));
        worker.start();
        assertTrue(running.await(30, TimeUnit.SECONDS), "the worker should have claimed the job");

        try {
            cancel(jobId, dataManagerIn(STUDY_ID))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("running"))
                    .andExpect(jsonPath("$.cancelRequested").value(true));
        } finally {
            proceed.countDown();
            worker.join(30_000);
        }
        assertFalse(worker.isAlive());
        ExportJobDAO.Row job = new ExportJobDAO(DATA_SOURCE).findById(jobId);
        assertEquals("cancelled", job.status, job.errorMessage);
        assertEquals("Cancelled by root", job.errorMessage);
        assertEquals(0, archivedFilesOf(ds), "a cancelled export registers no file");
    }

    @Test
    void aFinishedExportCannotBeCancelled() throws Exception {
        DatasetBean ds = persistDataset();
        long jobId = new ExportJobDAO(DATA_SOURCE).insertQueued(ds.getId(), "odm", 1);
        assertTrue(ExportJobRunner.runOnce(DATA_SOURCE, new PlaceholderExportFileMaterializer()));

        cancel(jobId, dataManagerIn(STUDY_ID)).andExpect(status().isConflict());
        assertEquals(ExportJobDAO.STATUS_DONE, new ExportJobDAO(DATA_SOURCE).findById(jobId).status);
    }

    @Test
    void onlyTheSubmitterOrASysadminCancels() throws Exception {
        DatasetBean ds = persistDataset();
        long jobId = new ExportJobDAO(DATA_SOURCE).insertQueued(ds.getId(), "odm", 1);

        cancel(jobId, dataManagerIn(STUDY_ID, 2, "colleague")).andExpect(status().isForbidden());
        assertEquals(ExportJobDAO.STATUS_QUEUED, new ExportJobDAO(DATA_SOURCE).findById(jobId).status);

        cancel(jobId, sysadminIn(STUDY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void aCancelledBundleLeavesNoFileBehind() throws Exception {
        new StudySettingService(DATA_SOURCE)
                .put(STUDY_ID, StudySettingService.EXPORT_BUNDLE_ENABLED, "true", 1);
        DatasetBean ds = persistDataset();
        RuleSetRuleDao rules = Mockito.mock(RuleSetRuleDao.class);
        Mockito.when(rules.findByRuleSetStudyIdAndStatusAvail(Mockito.anyInt())).thenReturn(new ArrayList<>());
        SynchronousExportMaterializer materializer =
                new SynchronousExportMaterializer(DATA_SOURCE, Mockito.mock(CoreResources.class), rules);

        // This thread's job has been asked to stop, as ExportJobRunner would.
        JobTerminationMonitor.createInstance("cancelled bundle").terminate();
        try {
            assertThrows(JobInterruptedException.class, () -> materializer.materialize(ds, "bundle", 1),
                    "the bundle stops at its first subject");
        } finally {
            // the next test runs on this thread
            JobTerminationMonitor.createInstance("idle");
        }

        assertEquals(0, archivedFilesOf(ds));
        try (Stream<Path> files = Files.walk(FILE_ROOT)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith("_bundle.zip")),
                    "no partial bundle is left on disk");
        }
    }

    /* ---------------- the worker ---------------- */

    /**
     * A materializer that did not register its file itself (the placeholder,
     * or any other than the production one) leaves the registration to the
     * worker, which must name an export format that exists.
     */
    @Test
    void theWorkerRegistersAFileTheMaterializerDidNot() throws Exception {
        DatasetBean ds = persistDataset();
        long jobId = new ExportJobDAO(DATA_SOURCE).insertQueued(ds.getId(), "odm", 1);

        assertTrue(ExportJobRunner.runOnce(DATA_SOURCE, new PlaceholderExportFileMaterializer()));

        ExportJobDAO.Row job = new ExportJobDAO(DATA_SOURCE).findById(jobId);
        assertEquals(ExportJobDAO.STATUS_DONE, job.status, job.errorMessage);
        assertEquals(4, count("SELECT export_format_id FROM archived_dataset_file "
                + " WHERE archived_dataset_file_id = " + job.archivedDatasetFileId),
                "an ODM export is registered as the XML format");
    }
}
