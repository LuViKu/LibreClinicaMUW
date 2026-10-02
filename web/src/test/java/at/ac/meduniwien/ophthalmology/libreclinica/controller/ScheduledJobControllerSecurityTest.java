/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.table.sdv.SDVUtil;

/**
 * The export-job list and cancel endpoints: administrators only, cancel by
 * POST only, return view from the allow-list.
 */
class ScheduledJobControllerSecurityTest {

    private ScheduledJobController controller;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        controller = new ScheduledJobController();
        scheduler = mock(Scheduler.class);
        ReflectionTestUtils.setField(controller, "scheduler", scheduler);
        ReflectionTestUtils.setField(controller, "sdvUtil", new SDVUtil());
    }

    private static MockHttpSession sessionOf(UserType type) {
        UserAccountBean user = new UserAccountBean();
        user.setId(3);
        user.setName("caller");
        user.addUserType(type);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        return session;
    }

    private static MockHttpServletRequest post(UserType type) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/pages/cancelScheduledJob");
        req.setSession(sessionOf(type));
        return req;
    }

    @Test
    void anOrdinaryUserCannotCancelAJob() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.cancelScheduledJob(post(UserType.USER), resp, "job", "group", "trigger", "tgroup",
                "listCurrentScheduledJobs");

        verifyNoInteractions(scheduler);
        assertTrue(Objects.requireNonNull(resp.getRedirectedUrl()).contains("/MainMenu"));
    }

    @Test
    void anOrdinaryUserCannotListJobs() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.listScheduledJobsData(post(UserType.USER), resp);

        assertEquals(403, resp.getStatus());
        verifyNoInteractions(scheduler);
    }

    @Test
    void anAdministratorCancelsAndReturnsToTheJobList() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.cancelScheduledJob(post(UserType.SYSADMIN), resp, "job", "group", "trigger", "tgroup",
                "../WEB-INF/jsp/login/login.jsp");

        verify(scheduler).getTrigger(new TriggerKey("trigger", "tgroup"));
        assertEquals("/pages/listCurrentScheduledJobs", resp.getForwardedUrl());
    }

    @Test
    void aGetCannotCancelAJob() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/cancelScheduledJob")
                        .session(sessionOf(UserType.SYSADMIN))
                        .param("theJobName", "job")
                        .param("theJobGroupName", "group")
                        .param("theTriggerName", "trigger")
                        .param("theTriggerGroupName", "tgroup")
                        .param("redirection", "listCurrentScheduledJobs"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(scheduler);
    }
}
