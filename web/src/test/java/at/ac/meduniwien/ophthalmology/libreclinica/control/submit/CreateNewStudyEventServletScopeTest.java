/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;

/**
 * Scheduling an event names the study subject and the event definition by id
 * in the request, and the form shows both before anything validates them:
 * {@code mayProceed} refuses ids outside the session's study tree.
 */
class CreateNewStudyEventServletScopeTest {

    private static final int STUDY_ID = 10;
    private static final int OWN_SUBJECT = 201;
    private static final int FOREIGN_SUBJECT = 998;
    private static final int OWN_DEFINITION = 31;
    private static final int FOREIGN_DEFINITION = 997;

    private StudyTreeScope scope;

    /** Sets the request state by hand, as SecureController.process does before mayProceed. */
    private static final class Probe extends CreateNewStudyEventServlet {
        private static final long serialVersionUID = 1L;

        void check(MockHttpServletRequest req, Role role, StudyTreeScope scope) throws InsufficientPermissionException {
            ReflectionTestUtils.setField(this, "studyTreeScope", scope);
            StudyBean study = new StudyBean();
            study.setId(STUDY_ID);
            StudyUserRoleBean userRole = new StudyUserRoleBean();
            userRole.setRole(role);
            request = req;
            ub = new UserAccountBean();
            currentStudy = study;
            currentRole = userRole;
            mayProceed();
        }
    }

    @BeforeEach
    void setUp() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        SecureController.respage = ResourceBundleProvider.getPageMessagesBundle(Locale.ENGLISH);
        SecureController.resexception = ResourceBundleProvider.getExceptionsBundle(Locale.ENGLISH);
        scope = mock(StudyTreeScope.class);
        when(scope.containsStudySubject(any(), eq(OWN_SUBJECT))).thenReturn(true);
        when(scope.containsEventDefinition(any(), eq(OWN_DEFINITION))).thenReturn(true);
    }

    private static MockHttpServletRequest request(Integer studySubjectId, Integer definitionId) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/CreateNewStudyEvent");
        if (studySubjectId != null) {
            req.addParameter("studySubjectId", studySubjectId.toString());
        }
        if (definitionId != null) {
            req.addParameter("studyEventDefinition", definitionId.toString());
        }
        return req;
    }

    private void check(MockHttpServletRequest req, Role role) throws InsufficientPermissionException {
        new Probe().check(req, role, scope);
    }

    @Test
    void aSubjectOfAnotherStudyIsRefused() {
        MockHttpServletRequest req = request(FOREIGN_SUBJECT, OWN_DEFINITION);

        InsufficientPermissionException refused =
                assertThrows(InsufficientPermissionException.class, () -> check(req, Role.RESEARCHASSISTANT));

        assertEquals(Page.MENU_SERVLET, refused.getGoTo());
        assertTrue(String.valueOf(req.getAttribute(SecureController.PAGE_MESSAGE))
                .contains(SecureController.respage.getString("required_study_subject_not_belong")));
    }

    @Test
    void anEventDefinitionOfAnotherStudyIsRefused() {
        MockHttpServletRequest req = request(OWN_SUBJECT, FOREIGN_DEFINITION);

        InsufficientPermissionException refused =
                assertThrows(InsufficientPermissionException.class, () -> check(req, Role.INVESTIGATOR));

        assertEquals(Page.MENU_SERVLET, refused.getGoTo());
    }

    @Test
    void aSubjectAndDefinitionOfTheCurrentStudyPass() throws Exception {
        check(request(OWN_SUBJECT, OWN_DEFINITION), Role.RESEARCHASSISTANT);
    }

    @Test
    void thePlainScheduleLinkNeedsNoLookup() throws Exception {
        check(request(null, null), Role.COORDINATOR);

        verifyNoInteractions(scope);
    }

    @Test
    void theRoleCheckStillComesFirst() {
        assertThrows(InsufficientPermissionException.class, () -> check(request(FOREIGN_SUBJECT, null), Role.MONITOR));

        verifyNoInteractions(scope);
    }
}
