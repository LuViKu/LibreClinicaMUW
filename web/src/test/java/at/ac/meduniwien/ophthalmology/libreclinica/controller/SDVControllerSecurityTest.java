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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ModelMap;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;
import at.ac.meduniwien.ophthalmology.libreclinica.web.table.sdv.SDVUtil;

/**
 * The legacy SDV handlers: POST only, SDV roles only, records of the current
 * study tree only, and a return view from the allow-list.
 */
class SDVControllerSecurityTest {

    private static final int STUDY_ID = 10;
    private static final int OWN_EVENT_CRF = 101;
    private static final int FOREIGN_EVENT_CRF = 999;
    private static final int OWN_SUBJECT = 201;
    private static final int FOREIGN_SUBJECT = 998;

    private SDVController controller;
    private SDVUtil sdvUtil;
    private StudyTreeScope scope;

    @BeforeEach
    void setUp() {
        controller = new SDVController();
        sdvUtil = mock(SDVUtil.class);
        scope = mock(StudyTreeScope.class);
        ReflectionTestUtils.setField(controller, "sdvUtil", sdvUtil);
        ReflectionTestUtils.setField(controller, "studyTreeScope", scope);
        ReflectionTestUtils.setField(controller, "dataSource", mock(DataSource.class));

        when(scope.containsEventCrf(any(), eq(OWN_EVENT_CRF))).thenReturn(true);
        when(scope.containsEventCrf(any(), eq(FOREIGN_EVENT_CRF))).thenReturn(false);
        when(scope.containsStudySubject(any(), eq(OWN_SUBJECT))).thenReturn(true);
        when(scope.containsStudySubject(any(), eq(FOREIGN_SUBJECT))).thenReturn(false);
        when(sdvUtil.setSDVerified(anyList(), anyInt(), anyBoolean())).thenReturn(true);
        when(sdvUtil.setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean())).thenReturn(true);
        when(sdvUtil.getListOfSdvEventCRFIds(any())).thenCallRealMethod();
        when(sdvUtil.getListOfStudySubjectIds(any())).thenCallRealMethod();
    }

    private static MockHttpServletRequest session(Role role) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/pages/x");
        req.setSession(httpSession(role));
        return req;
    }

    private static MockHttpSession httpSession(Role role) {
        // StudyUserRoleBean reads the thread's resource bundles on construction.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean user = new UserAccountBean();
        user.setId(7);
        user.setName("caller");
        StudyUserRoleBean userRole = new StudyUserRoleBean();
        userRole.setRole(role);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        session.setAttribute("userBean", user);
        session.setAttribute("userRole", userRole);
        session.setAttribute("study", study);
        return session;
    }

    private static boolean redirectedToMainMenu(MockHttpServletResponse resp) {
        String url = resp.getRedirectedUrl();
        return url != null && url.contains("/MainMenu");
    }

    // ---- role check on every mutating handler -------------------------------

    @Test
    void aDataEntryPersonCannotRemoveSdv() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.changeSDVHandler(session(Role.RESEARCHASSISTANT), resp, OWN_EVENT_CRF, "viewAllSubjectSDVtmp", new ModelMap());

        verify(sdvUtil, never()).setSDVerified(anyList(), anyInt(), anyBoolean());
        assertTrue(redirectedToMainMenu(resp));
    }

    @Test
    void aDataEntryPersonCannotVerifyASubject() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.sdvStudySubjectHandler(session(Role.RESEARCHASSISTANT), resp, OWN_SUBJECT, "viewSubjectAggregate", new ModelMap());

        verify(sdvUtil, never()).setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean());
        assertTrue(redirectedToMainMenu(resp));
    }

    @Test
    void aDataEntryPersonCannotUnverifyASubject() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.unSdvStudySubjectHandler(session(Role.RESEARCHASSISTANT), resp, OWN_SUBJECT, "viewSubjectAggregate", new ModelMap());

        verify(sdvUtil, never()).setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean());
    }

    @Test
    void aDataEntryPersonCannotBulkVerify() {
        MockHttpServletRequest req = session(Role.RESEARCHASSISTANT);
        req.addParameter("sdvCheck_" + OWN_EVENT_CRF, "on");
        controller.sdvAllSubjectsFormHandler(req, new MockHttpServletResponse(), STUDY_ID, "viewAllSubjectSDVtmp", new ModelMap());

        MockHttpServletRequest req2 = session(Role.RESEARCHASSISTANT);
        req2.addParameter("sdvCheck_" + OWN_SUBJECT, "on");
        controller.sdvStudySubjectsHandler(req2, new MockHttpServletResponse(), STUDY_ID, "viewSubjectAggregate", new ModelMap());

        verify(sdvUtil, never()).setSDVerified(anyList(), anyInt(), anyBoolean());
        verify(sdvUtil, never()).setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean());
    }

    @Test
    void aSessionWithoutARoleIsRefused() {
        MockHttpServletRequest req = session(Role.MONITOR);
        Objects.requireNonNull(req.getSession()).removeAttribute("userRole");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.sdvOneCRFFormHandler(req, resp, OWN_EVENT_CRF, "viewAllSubjectSDVtmp", new ModelMap());

        verify(sdvUtil, never()).setSDVerified(anyList(), anyInt(), anyBoolean());
        assertTrue(redirectedToMainMenu(resp));
    }

    // ---- study scope ---------------------------------------------------------

    @Test
    void aMonitorCannotTouchAnEventCrfOfAnotherStudy() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.sdvOneCRFFormHandler(session(Role.MONITOR), resp, FOREIGN_EVENT_CRF, "viewAllSubjectSDVtmp", new ModelMap());
        controller.changeSDVHandler(session(Role.MONITOR), new MockHttpServletResponse(), FOREIGN_EVENT_CRF, "viewAllSubjectSDVtmp", new ModelMap());

        verify(sdvUtil, never()).setSDVerified(anyList(), anyInt(), anyBoolean());
        assertTrue(redirectedToMainMenu(resp));
    }

    @Test
    void oneForeignIdSpoilsTheWholeBulkRequest() {
        MockHttpServletRequest req = session(Role.MONITOR);
        req.addParameter("sdvCheck_" + OWN_EVENT_CRF, "on");
        req.addParameter("sdvCheck_" + FOREIGN_EVENT_CRF, "on");
        controller.sdvAllSubjectsFormHandler(req, new MockHttpServletResponse(), STUDY_ID, "viewAllSubjectSDVtmp", new ModelMap());

        MockHttpServletRequest req2 = session(Role.MONITOR);
        req2.addParameter("sdvCheck_" + OWN_SUBJECT, "on");
        req2.addParameter("sdvCheck_" + FOREIGN_SUBJECT, "on");
        controller.sdvStudySubjectsHandler(req2, new MockHttpServletResponse(), STUDY_ID, "viewSubjectAggregate", new ModelMap());

        verify(sdvUtil, never()).setSDVerified(anyList(), anyInt(), anyBoolean());
        verify(sdvUtil, never()).setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean());
    }

    @Test
    void aMonitorCannotTouchASubjectOfAnotherStudy() {
        controller.sdvStudySubjectHandler(session(Role.MONITOR), new MockHttpServletResponse(), FOREIGN_SUBJECT, "viewSubjectAggregate", new ModelMap());
        controller.unSdvStudySubjectHandler(session(Role.MONITOR), new MockHttpServletResponse(), FOREIGN_SUBJECT, "viewSubjectAggregate", new ModelMap());

        verify(sdvUtil, never()).setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean());
    }

    @Test
    void aMonitorVerifiesAnEventCrfOfTheCurrentStudy() {
        MockHttpServletRequest req = session(Role.MONITOR);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.sdvOneCRFFormHandler(req, resp, OWN_EVENT_CRF, "viewAllSubjectSDVform", new ModelMap());

        verify(sdvUtil).setSDVerified(List.of(OWN_EVENT_CRF), 7, true);
        verify(sdvUtil).forwardToView(req, resp, "viewAllSubjectSDVform", SDVUtil.VIEW_SDV_BY_EVENT_CRF);
    }

    @Test
    void aCoordinatorBulkVerifiesSubjectsOfTheCurrentStudy() {
        MockHttpServletRequest req = session(Role.COORDINATOR);
        req.addParameter("sdvCheck_" + OWN_SUBJECT, "on");
        controller.sdvStudySubjectsHandler(req, new MockHttpServletResponse(), STUDY_ID, "viewSubjectAggregate", new ModelMap());

        verify(sdvUtil).setSDVStatusForStudySubjects(List.of(OWN_SUBJECT), 7, true);
    }

    @Test
    void theTableDataOfAnotherStudyIsRefused() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.viewEventCrfSdvData(session(Role.MONITOR), resp, STUDY_ID + 1);
        assertEquals(403, resp.getStatus());

        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        controller.viewSubjectAggregateData(session(Role.MONITOR), resp2, STUDY_ID + 1);
        assertEquals(403, resp2.getStatus());
    }

    @Test
    void thePagesRefuseDataEntryPersonsAndOtherStudies() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.viewSubjectHandler(session(Role.RESEARCHASSISTANT), resp, OWN_SUBJECT, STUDY_ID);
        assertTrue(redirectedToMainMenu(resp));

        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        controller.viewAllSubjectFormHandler(session(Role.MONITOR), resp2, STUDY_ID + 1);
        assertTrue(redirectedToMainMenu(resp2));
    }

    // ---- POST only -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/handleSDVPost", "/handleSDVGet", "/handleSDVRemove",
            "/sdvStudySubject", "/unSdvStudySubject", "/sdvStudySubjects"})
    void aGetCannotChangeSdvState(String path) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get(path)
                        .session(httpSession(Role.MONITOR))
                        .param("crfId", String.valueOf(OWN_EVENT_CRF))
                        .param("theStudySubjectId", String.valueOf(OWN_SUBJECT))
                        .param("studyId", String.valueOf(STUDY_ID))
                        .param("redirection", "viewAllSubjectSDVtmp"))
                .andExpect(status().isMethodNotAllowed());

        verify(sdvUtil, never()).setSDVerified(anyList(), anyInt(), anyBoolean());
        verify(sdvUtil, never()).setSDVStatusForStudySubjects(anyList(), anyInt(), anyBoolean());
    }

    // ---- allow-listed return view ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"viewAllSubjectSDVtmp", "viewAllSubjectSDV", "viewAllSubjectSDVform",
            "viewSubjectAggregate", "listCurrentScheduledJobs"})
    void knownViewsAreHonoured(String view) {
        assertEquals("/pages/" + view, SDVUtil.returnViewPath(view, SDVUtil.VIEW_SDV_BY_EVENT_CRF));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../WEB-INF/jsp/admin/listUserAccounts.jsp", "ListUserAccounts",
            "viewAllSubjectSDVtmp/../../MainMenu", "", " viewSubjectAggregate"})
    void anythingElseLandsOnTheFallback(String view) {
        assertEquals("/pages/viewSubjectAggregate", SDVUtil.returnViewPath(view, SDVUtil.VIEW_SDV_BY_SUBJECT));
        assertEquals("/pages/viewSubjectAggregate", SDVUtil.returnViewPath(null, SDVUtil.VIEW_SDV_BY_SUBJECT));
    }

    @Test
    void aForgedRedirectionIsReplacedBeforeTheForward() {
        MockHttpServletRequest req = session(Role.MONITOR);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        SDVUtil real = new SDVUtil();
        real.setDataSource(mock(DataSource.class));
        ReflectionTestUtils.setField(controller, "sdvUtil", real);

        // Empty selection: nothing to change, straight back to the table.
        controller.sdvAllSubjectsFormHandler(req, resp, STUDY_ID, "../WEB-INF/jsp/login/login.jsp", new ModelMap());

        assertEquals("/pages/viewAllSubjectSDVtmp", resp.getForwardedUrl());
    }
}
