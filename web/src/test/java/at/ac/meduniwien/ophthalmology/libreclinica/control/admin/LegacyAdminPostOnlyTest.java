/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.quartz.JobKey;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerKey;
import org.quartz.impl.StdScheduler;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.XsltTriggerService;

/**
 * The legacy admin actions that change state - remove, restore and unlock a
 * user; remove and restore a study role; pause, resume and delete a job; send
 * the test e-mail - accept POST only. A GET answers 405 before the servlet
 * reads the session, the database, the scheduler or the mail sender. What the
 * user and role POSTs write is covered by {@code LegacyAdminPostOnlyDatabaseIT}.
 */
class LegacyAdminPostOnlyTest {

    private static final String IMPORT_GROUP = "importTrigger";

    private final UserAccountBean admin = LegacyServletHarness.sysAdmin(1, "root");

    private DataSource dataSource;
    private StdScheduler scheduler;
    private JavaMailSenderImpl mailSender;
    private LegacyServletHarness harness;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        scheduler = mock(StdScheduler.class);
        mailSender = mock(JavaMailSenderImpl.class);
        harness = new LegacyServletHarness(dataSource)
                .bean("schedulerFactoryBean", scheduler)
                .bean("mailSender", mailSender)
                .bean("securityManager", mock(SecurityManager.class));
    }

    private MockHttpServletRequest request(String method, String path, String query) {
        MockHttpServletRequest req = harness.request(method, path, admin);
        if (!query.isEmpty()) {
            req.setQueryString(query);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                req.addParameter(kv[0], kv[1]);
            }
        }
        return req;
    }

    static Stream<Arguments> stateChangingRequests() {
        return Stream.of(
                arguments(DeleteUserServlet.class, "/DeleteUser", "action=3&userId=2"),
                arguments(DeleteUserServlet.class, "/DeleteUser", "action=4&userId=2"),
                arguments(UnLockUserServlet.class, "/UnLockUser", "userId=2"),
                arguments(DeleteStudyUserRoleServlet.class, "/DeleteStudyUserRole", "studyId=1&userName=physician&action=3"),
                arguments(DeleteStudyUserRoleServlet.class, "/DeleteStudyUserRole", "studyId=1&userName=physician&action=4"),
                arguments(PauseJobServlet.class, "/PauseJob", "tname=nightly&gname=0"),
                arguments(PauseJobServlet.class, "/PauseJob", "tname=nightly&gname=1&del=y"),
                arguments(SendTestEmailServlet.class, "/SendTestEmail", ""));
    }

    @ParameterizedTest
    @MethodSource("stateChangingRequests")
    void aGetIsRefusedBeforeTheServletRuns(Class<? extends SecureController> type, String path, String query) throws Exception {
        MockHttpServletResponse resp = harness.run(type.getDeclaredConstructor().newInstance(), request("GET", path, query));

        verifyNoInteractions(dataSource, scheduler, mailSender);
        assertNull(resp.getForwardedUrl(), "no page was rendered");
        assertEquals(405, resp.getStatus());
        assertEquals("POST", resp.getHeader("Allow"));
    }

    // ---- the job actions still work on POST ---------------------------------

    @Test
    void aPostPausesAnExportJobTrigger() throws Exception {
        TriggerKey trigger = new TriggerKey("nightly", XsltTriggerService.TRIGGER_GROUP_NAME);
        MockHttpServletResponse resp = harness.run(new PauseJobServlet(), request("POST", "/PauseJob", "tname=nightly&gname=0"));

        verify(scheduler).pauseTrigger(trigger);
        verify(scheduler, never()).deleteJob(JobKey.jobKey("nightly", XsltTriggerService.TRIGGER_GROUP_NAME));
        assertEquals("/ViewJob", resp.getForwardedUrl());
    }

    @Test
    void aPostResumesAPausedImportJobTrigger() throws Exception {
        TriggerKey trigger = new TriggerKey("nightly", IMPORT_GROUP);
        when(scheduler.getTriggerState(trigger)).thenReturn(TriggerState.PAUSED);

        MockHttpServletResponse resp = harness.run(new PauseJobServlet(), request("POST", "/PauseJob", "tname=nightly&gname=1"));

        verify(scheduler).resumeTrigger(trigger);
        assertEquals("/ViewImportJob", resp.getForwardedUrl());
    }

    @Test
    void aPostDeletesAJob() throws Exception {
        harness.run(new PauseJobServlet(), request("POST", "/PauseJob", "tname=nightly&gname=0&del=y"));

        verify(scheduler).deleteJob(JobKey.jobKey("nightly", XsltTriggerService.TRIGGER_GROUP_NAME));
    }

    // ---- servlets that do not opt out keep answering GET ----------------------

    /** A page that only renders: it keeps the default and serves GET. */
    private static final class RendersOnGet extends SecureController {
        private static final long serialVersionUID = 1L;
        boolean processed;

        @Override
        protected void mayProceed() {
        }

        @Override
        protected void processRequest() {
            processed = true;
        }
    }

    @Test
    void aServletThatOnlyRendersStillAnswersGet() throws Exception {
        RendersOnGet page = new RendersOnGet();
        MockHttpServletResponse resp = harness.run(page, request("GET", "/SomePage", ""));

        assertTrue(page.processed);
        assertEquals(200, resp.getStatus());
    }
}
