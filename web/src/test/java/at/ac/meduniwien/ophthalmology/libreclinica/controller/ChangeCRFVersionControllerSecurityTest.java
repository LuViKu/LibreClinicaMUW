/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;

/**
 * Changing an event CRF's version: POST only, study director or coordinator,
 * an event CRF of the current study tree, and a version of the same CRF.
 */
class ChangeCRFVersionControllerSecurityTest {

    private static final int STUDY_ID = 10;
    private static final int OWN_EVENT_CRF = 101;
    private static final int FOREIGN_EVENT_CRF = 999;
    private static final int CRF_ID = 30;
    private static final int SAME_CRF_VERSION = 31;
    private static final int OTHER_CRF_VERSION = 41;

    private ChangeCRFVersionController controller;
    private DataSource dataSource;
    private StudyTreeScope scope;

    @BeforeEach
    void setUp() {
        controller = new ChangeCRFVersionController();
        dataSource = mock(DataSource.class);
        scope = mock(StudyTreeScope.class);
        ReflectionTestUtils.setField(controller, "dataSource", dataSource);
        ReflectionTestUtils.setField(controller, "studyTreeScope", scope);

        when(scope.containsEventCrf(any(), eq(OWN_EVENT_CRF))).thenReturn(true);
        when(scope.containsEventCrf(any(), eq(FOREIGN_EVENT_CRF))).thenReturn(false);
        when(scope.crfIdOfEventCrf(OWN_EVENT_CRF)).thenReturn(CRF_ID);
        when(scope.crfIdOfVersion(SAME_CRF_VERSION)).thenReturn(CRF_ID);
        when(scope.crfIdOfVersion(OTHER_CRF_VERSION)).thenReturn(CRF_ID + 1);
    }

    private static MockHttpSession sessionOf(Role role) {
        // StudyUserRoleBean reads the thread's resource bundles on construction.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        UserAccountBean user = new UserAccountBean();
        user.setId(4);
        user.setName("caller");
        StudyUserRoleBean userRole = new StudyUserRoleBean();
        userRole.setRole(role);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        session.setAttribute("userRole", userRole);
        session.setAttribute("study", study);
        return session;
    }

    private MockHttpServletResponse change(Role role, int eventCrfId, int newVersionId) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/pages/managestudy/changeCRFVersion");
        req.setSession(sessionOf(role));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.changeCRFVersionAction(req, resp, CRF_ID, "CRF", 32, "v1", "SS_1", 5,
                eventCrfId, 7, newVersionId);
        return resp;
    }

    @Test
    void aGetCannotChangeTheVersion() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/managestudy/changeCRFVersion").session(sessionOf(Role.COORDINATOR))
                        .param("crfId", String.valueOf(CRF_ID)).param("crfName", "CRF")
                        .param("crfversionId", "32").param("crfVersionName", "v1")
                        .param("studySubjectLabel", "SS_1").param("studySubjectId", "5")
                        .param("eventCRFId", String.valueOf(OWN_EVENT_CRF))
                        .param("eventDefinitionCRFId", "7")
                        .param("newCRFVersionId", String.valueOf(SAME_CRF_VERSION)))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(dataSource);
    }

    @Test
    void aMonitorCannotChangeTheVersion() {
        MockHttpServletResponse resp = change(Role.MONITOR, OWN_EVENT_CRF, SAME_CRF_VERSION);

        verifyNoInteractions(dataSource);
        assertTrue(resp.getRedirectedUrl().contains("/MainMenu"));
    }

    @Test
    void aCoordinatorCannotChangeAnEventCrfOfAnotherStudy() {
        MockHttpServletResponse resp = change(Role.COORDINATOR, FOREIGN_EVENT_CRF, SAME_CRF_VERSION);

        verifyNoInteractions(dataSource);
        assertTrue(resp.getRedirectedUrl().contains("/MainMenu"));
    }

    @Test
    void theNewVersionMustBelongToTheSameCrf() {
        MockHttpServletResponse resp = change(Role.STUDYDIRECTOR, OWN_EVENT_CRF, OTHER_CRF_VERSION);

        verifyNoInteractions(dataSource);
        assertTrue(resp.getRedirectedUrl().contains("/MainMenu"));
    }

    @Test
    void aCoordinatorOfTheStudyGetsThrough() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/pages/managestudy/changeCRFVersion");
        req.setSession(sessionOf(Role.COORDINATOR));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        // The mock DataSource cannot run the update; the handler reports that as
        // a page message. What matters is that the request got past every check.
        controller.changeCRFVersionAction(req, resp, CRF_ID, "CRF", 32, "v1", "SS_1", 5,
                OWN_EVENT_CRF, 7, SAME_CRF_VERSION);

        verify(scope).crfIdOfVersion(SAME_CRF_VERSION);
        assertFalse(resp.getRedirectedUrl() != null && resp.getRedirectedUrl().contains("authentication_failed"));
    }

    @Test
    void theChooseAndConfirmPagesRefuseAnEventCrfOfAnotherStudy() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/pages/managestudy/chooseCRFVersion");
        req.setSession(sessionOf(Role.COORDINATOR));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.chooseCRFVersion(req, resp, CRF_ID, "CRF", 32, "v1", "SS_1", 5, FOREIGN_EVENT_CRF, 7);
        assertTrue(resp.getRedirectedUrl().contains("/MainMenu"));

        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/pages/managestudy/confirmCRFVersionChange");
        req2.setSession(sessionOf(Role.COORDINATOR));
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        controller.confirmCRFVersionChange(req2, resp2, CRF_ID, "CRF", 32, "v1", "SS_1", 5, FOREIGN_EVENT_CRF, 7,
                SAME_CRF_VERSION, "v2", "Visit", "01-Jan-2026", "1", "Confirm");
        assertTrue(resp2.getRedirectedUrl().contains("/MainMenu"));

        verifyNoInteractions(dataSource);
    }
}
