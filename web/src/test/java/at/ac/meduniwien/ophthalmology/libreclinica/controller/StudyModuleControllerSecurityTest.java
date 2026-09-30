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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.apache.commons.dbcp.BasicDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.support.SimpleSessionStatus;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.StudyModuleStatusDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.managestudy.StudyModuleStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * Build-study page: the module switches are POST only and, like the module
 * status form, need a build-study role on the study they name.
 */
class StudyModuleControllerSecurityTest {

    private static final int STUDY_ID = 10;
    private static final String STUDY_OID = "S_OWN";

    private StudyModuleController controller;
    private BasicDataSource dataSource;
    private StudyModuleStatusDao statusDao;

    @BeforeEach
    void setUp() {
        controller = new StudyModuleController();
        dataSource = mock(BasicDataSource.class);
        statusDao = mock(StudyModuleStatusDao.class);
        ReflectionTestUtils.setField(controller, "dataSource", dataSource);
        ReflectionTestUtils.setField(controller, "studyModuleStatusDao", statusDao);
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
        study.setOid(STUDY_OID);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", user);
        session.setAttribute("userRole", userRole);
        session.setAttribute("study", study);
        return session;
    }

    private static MockHttpServletRequest request(Role role) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/pages/studymodule");
        req.setSession(sessionOf(role));
        return req;
    }

    @ParameterizedTest
    @ValueSource(strings = {"deactivaterandomization", "reactivaterandomization"})
    void aGetCannotFlipAModule(String action) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/studymodule/" + STUDY_OID + "/" + action).session(sessionOf(Role.COORDINATOR)))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(dataSource);
    }

    @Test
    void aDataEntryPersonCannotFlipAModule() throws Exception {
        assertEquals(StudyModuleController.DENIED, controller.deactivateRandomization(STUDY_OID, request(Role.INVESTIGATOR)));
        assertEquals(StudyModuleController.DENIED, controller.reactivateRandomization(STUDY_OID, request(Role.RESEARCHASSISTANT)));

        verifyNoInteractions(dataSource);
    }

    @Test
    void aCoordinatorCannotFlipAModuleOfAnotherStudy() throws Exception {
        assertEquals(StudyModuleController.DENIED, controller.reactivateRandomization("S_OTHER", request(Role.STUDYDIRECTOR)));

        verifyNoInteractions(dataSource);
    }

    @Test
    void aDataEntryPersonCannotSaveTheModuleStatusOrTheStudyStatus() {
        StudyModuleStatus sms = new StudyModuleStatus();
        sms.setStudyId(STUDY_ID);
        MockHttpServletRequest req = request(Role.RESEARCHASSISTANT);
        req.addParameter("saveStudyStatus", "x");

        String view = controller.processSubmit(sms, new BeanPropertyBindingResult(sms, "studyModuleStatus"),
                new SimpleSessionStatus(), req);
        String view2 = controller.processSubmit(sms, new BeanPropertyBindingResult(sms, "studyModuleStatus"),
                new SimpleSessionStatus(), request(Role.RESEARCHASSISTANT));

        assertEquals(StudyModuleController.DENIED, view);
        assertEquals(StudyModuleController.DENIED, view2);
        verify(statusDao, never()).saveOrUpdate(any());
        verifyNoInteractions(dataSource);
    }

    @Test
    void aStatusObjectOfAnotherStudyIsNotSaved() {
        StudyModuleStatus sms = new StudyModuleStatus();
        sms.setStudyId(STUDY_ID + 1);

        String view = controller.processSubmit(sms, new BeanPropertyBindingResult(sms, "studyModuleStatus"),
                new SimpleSessionStatus(), request(Role.COORDINATOR));

        assertEquals(StudyModuleController.DENIED, view);
        verify(statusDao, never()).saveOrUpdate(any());
    }

    @Test
    void theFormBindsTheModuleStatesButNotTheRowOrStudyId() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpSession session = sessionOf(Role.COORDINATOR);
        StudyModuleStatus sms = new StudyModuleStatus();
        sms.setId(5);
        sms.setStudyId(STUDY_ID);
        session.setAttribute("studyModuleStatus", sms);

        mvc.perform(post("/studymodule").session(session)
                        .param("id", "99")
                        .param("studyId", "77")
                        .param("crf", "3"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<StudyModuleStatus> saved = ArgumentCaptor.forClass(StudyModuleStatus.class);
        verify(statusDao).saveOrUpdate(saved.capture());
        assertEquals(Integer.valueOf(5), saved.getValue().getId());
        assertEquals(STUDY_ID, saved.getValue().getStudyId());
        assertEquals(3, saved.getValue().getCrf());
    }
}
