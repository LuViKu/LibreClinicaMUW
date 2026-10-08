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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;

/**
 * The study name in every legacy page header links to
 * {@code /ViewStudy?id=…&viewFull=yes}. The page asked the participant-portal
 * client for the study's registration first, and that client needs a library
 * the WAR does not ship, so the page failed for every study. It is built from
 * the database alone now.
 */
class ViewStudyServletDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void theFullStudyViewIsBuiltWithoutTheParticipantPortal() throws Exception {
        MockServletContext context = new MockServletContext();
        MockHttpSession session = new MockHttpSession(context);
        MockHttpServletRequest request = new MockHttpServletRequest(context, "GET", "/ViewStudy");
        request.setSession(session);
        request.addParameter("id", "1");
        request.addParameter("viewFull", "yes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        StudyBean study = new StudyDAO(DATA_SOURCE).findByPK(1);
        session.setAttribute("study", study);
        UserAccountBean admin = new UserAccountBean();
        admin.setId(1);
        admin.setName("root");
        admin.addUserType(UserType.SYSADMIN);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.COORDINATOR);
        SessionManager sm = mock(SessionManager.class);
        when(sm.getDataSource()).thenReturn(DATA_SOURCE);

        ViewStudyServlet servlet = new ViewStudyServlet();
        ReflectionTestUtils.setField(servlet, "context", context);
        ReflectionTestUtils.setField(servlet, "sm", sm);
        ReflectionTestUtils.setField(servlet, "request", request);
        ReflectionTestUtils.setField(servlet, "response", response);
        ReflectionTestUtils.setField(servlet, "session", session);
        ReflectionTestUtils.setField(servlet, "ub", admin);
        ReflectionTestUtils.setField(servlet, "currentStudy", study);
        ReflectionTestUtils.setField(servlet, "currentRole", role);

        servlet.processRequest();

        assertEquals(Page.VIEW_FULL_STUDY.getFileName(), response.getForwardedUrl());
        assertNotNull(request.getAttribute("studyToView"));
    }
}
