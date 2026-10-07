/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Starting an export ({@code /pages/extract}) schedules a job that writes the
 * export files, so it takes a POST: the legacy export page posts each format as
 * a small form, and a GET answers 405 before the controller runs.
 */
class ExtractControllerMethodTest {

    private Scheduler scheduler;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        ExtractController controller = new ExtractController();
        scheduler = mock(Scheduler.class);
        ReflectionTestUtils.setField(controller, "scheduler", scheduler);
        mvc = ProductionMvc.standalone(controller).build();
    }

    /** A session whose role may not export, so a request that reaches the controller is sent home. */
    private static MockHttpSession sessionWithoutExportRole() {
        UserAccountBean user = new UserAccountBean();
        user.setId(3);
        user.setName("caller");
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.INVALID);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        session.setAttribute("userRole", role);
        return session;
    }

    @Test
    void aGetDoesNotStartAnExport() throws Exception {
        mvc.perform(get("/extract").session(sessionWithoutExportRole()).param("id", "1").param("datasetId", "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));

        verifyNoInteractions(scheduler);
    }

    @Test
    void aPostReachesTheController() throws Exception {
        mvc.perform(post("/extract").session(sessionWithoutExportRole()).param("id", "1").param("datasetId", "1"))
                .andExpect(redirectedUrl("/MainMenu?message=authentication_failed"));

        verifyNoInteractions(scheduler);
    }
}
