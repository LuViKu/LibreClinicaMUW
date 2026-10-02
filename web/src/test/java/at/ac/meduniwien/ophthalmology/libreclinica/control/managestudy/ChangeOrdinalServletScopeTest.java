/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;

/**
 * Reordering names event definitions, and the CRFs within one, by id in the
 * request. After the role check, {@code mayProceed} refuses an id outside the
 * session's study tree, for a system administrator too.
 */
class ChangeOrdinalServletScopeTest {

    private static final int STUDY_ID = 10;
    private static final int OWN_DEFINITION = 31;
    private static final int OWN_DEFINITION_2 = 32;
    private static final int FOREIGN_DEFINITION = 997;
    private static final int OWN_CRF = 41;
    private static final int OWN_CRF_2 = 42;
    private static final int FOREIGN_CRF = 996;

    private StudyTreeScope scope;

    @BeforeEach
    void setUp() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        SecureController.respage = ResourceBundleProvider.getPageMessagesBundle(Locale.ENGLISH);
        SecureController.resexception = ResourceBundleProvider.getExceptionsBundle(Locale.ENGLISH);
        scope = mock(StudyTreeScope.class);
        when(scope.containsEventDefinition(any(), eq(OWN_DEFINITION))).thenReturn(true);
        when(scope.containsEventDefinition(any(), eq(OWN_DEFINITION_2))).thenReturn(true);
        when(scope.containsEventDefinitionCrf(any(), eq(OWN_CRF))).thenReturn(true);
        when(scope.containsEventDefinitionCrf(any(), eq(OWN_CRF_2))).thenReturn(true);
    }

    /** Sets the request state by hand, as SecureController.process does before mayProceed. */
    private void check(ChangeOrdinalServlet servlet, MockHttpServletRequest req, UserAccountBean ub, Role role)
            throws InsufficientPermissionException {
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        StudyUserRoleBean userRole = new StudyUserRoleBean();
        userRole.setRole(role);
        ReflectionTestUtils.setField(servlet, "studyTreeScope", scope);
        ReflectionTestUtils.setField(servlet, "request", req);
        ReflectionTestUtils.setField(servlet, "ub", ub);
        ReflectionTestUtils.setField(servlet, "currentStudy", study);
        ReflectionTestUtils.setField(servlet, "currentRole", userRole);
        servlet.mayProceed();
    }

    private void check(ChangeOrdinalServlet servlet, MockHttpServletRequest req, Role role)
            throws InsufficientPermissionException {
        check(servlet, req, new UserAccountBean(), role);
    }

    private static MockHttpServletRequest definitions(int current, int previous) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/ChangeDefinitionOrdinal");
        req.addParameter("current", String.valueOf(current));
        req.addParameter("previous", String.valueOf(previous));
        return req;
    }

    private static MockHttpServletRequest crfs(int definition, int current, int previous) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/ChangeDefinitionCRFOrdinal");
        req.addParameter("id", String.valueOf(definition));
        req.addParameter("current", String.valueOf(current));
        req.addParameter("previous", String.valueOf(previous));
        req.addParameter("currentOrdinal", "2");
        req.addParameter("previousOrdinal", "1");
        return req;
    }

    private void assertRefused(ChangeOrdinalServlet servlet, MockHttpServletRequest req, Role role) {
        InsufficientPermissionException refused =
                assertThrows(InsufficientPermissionException.class, () -> check(servlet, req, role));

        assertEquals(Page.MENU_SERVLET, refused.getGoTo());
        assertTrue(String.valueOf(req.getAttribute(SecureController.PAGE_MESSAGE))
                .contains(SecureController.resexception.getString("not_select_valid_entity_current_study")));
    }

    @Test
    void aDefinitionOfAnotherStudyIsNotMoved() {
        assertRefused(new ChangeDefinitionOrdinalServlet(), definitions(FOREIGN_DEFINITION, OWN_DEFINITION), Role.STUDYDIRECTOR);
        assertRefused(new ChangeDefinitionOrdinalServlet(), definitions(OWN_DEFINITION, FOREIGN_DEFINITION), Role.COORDINATOR);
    }

    @Test
    void theDefinitionsOfTheCurrentStudyAreMoved() throws Exception {
        check(new ChangeDefinitionOrdinalServlet(), definitions(OWN_DEFINITION_2, OWN_DEFINITION), Role.STUDYDIRECTOR);
    }

    @Test
    void aCrfOfAnotherStudyIsNotMoved() {
        assertRefused(new ChangeDefinitionCRFOrdinalServlet(), crfs(OWN_DEFINITION, FOREIGN_CRF, OWN_CRF), Role.STUDYDIRECTOR);
        assertRefused(new ChangeDefinitionCRFOrdinalServlet(), crfs(OWN_DEFINITION, OWN_CRF, FOREIGN_CRF), Role.COORDINATOR);
    }

    @Test
    void theDefinitionWhoseCrfsAreRenumberedMustBeTheCurrentStudysToo() {
        // The definition id picks the rows renumbered when two CRFs share an ordinal.
        assertRefused(new ChangeDefinitionCRFOrdinalServlet(), crfs(FOREIGN_DEFINITION, OWN_CRF_2, OWN_CRF), Role.STUDYDIRECTOR);
    }

    @Test
    void theCrfsOfACurrentStudyDefinitionAreMoved() throws Exception {
        check(new ChangeDefinitionCRFOrdinalServlet(), crfs(OWN_DEFINITION, OWN_CRF_2, OWN_CRF), Role.COORDINATOR);
    }

    @Test
    void aSystemAdministratorIsHeldToTheCurrentStudyToo() {
        UserAccountBean admin = new UserAccountBean();
        admin.addUserType(UserType.SYSADMIN);

        assertThrows(InsufficientPermissionException.class, () -> check(new ChangeDefinitionOrdinalServlet(),
                definitions(FOREIGN_DEFINITION, OWN_DEFINITION), admin, Role.INVALID));
    }

    @Test
    void theRoleCheckStillComesFirst() {
        assertThrows(InsufficientPermissionException.class,
                () -> check(new ChangeDefinitionOrdinalServlet(), definitions(OWN_DEFINITION_2, OWN_DEFINITION), Role.MONITOR));

        verifyNoInteractions(scope);
    }
}
