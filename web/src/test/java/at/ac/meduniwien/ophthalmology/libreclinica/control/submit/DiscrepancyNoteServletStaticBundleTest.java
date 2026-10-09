/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * SecureController's resource bundles are static and are only set while a
 * request is processed. A servlet that read them in a field initialiser threw
 * a NullPointerException when it was the first one instantiated after a
 * start; Tomcat then marked it unavailable (404 until restart).
 *
 * <p>The sequence below is the production one: the container instantiates the
 * servlet while the statics are still null, and only then does
 * {@code SecureController.process} fill them in.
 */
class DiscrepancyNoteServletStaticBundleTest {

    private ResourceBundle savedExc;
    private ResourceBundle savedPage;

    @BeforeEach
    void bundlesNotYetInitialised() {
        savedExc = SecureController.resexception;
        savedPage = SecureController.respage;
        SecureController.resexception = null;
        SecureController.respage = null;
    }

    @AfterEach
    void restore() {
        SecureController.resexception = savedExc;
        SecureController.respage = savedPage;
    }

    private static void processRequestSetsTheBundles() {
        Locale en = Locale.ENGLISH;
        ResourceBundleProvider.updateLocale(en);
        SecureController.resexception = ResourceBundleProvider.getExceptionsBundle(en);
        SecureController.respage = ResourceBundleProvider.getPageMessagesBundle(en);
    }

    private static final class ViewProbe extends ViewDiscrepancyNoteServlet {
        private static final long serialVersionUID = 1L;

        void mayProceedFor(UserAccountBean user) throws InsufficientPermissionException {
            this.request = new MockHttpServletRequest("GET", "/LibreClinica/ViewDiscrepancyNote");
            this.response = new MockHttpServletResponse();
            this.ub = user;
            mayProceed();
        }
    }

    private static final class CreateProbe extends CreateDiscrepancyNoteServlet {
        private static final long serialVersionUID = 1L;

        void mayProceedFor(UserAccountBean user) throws InsufficientPermissionException {
            this.request = new MockHttpServletRequest("GET", "/LibreClinica/CreateDiscrepancyNote");
            this.response = new MockHttpServletResponse();
            this.ub = user;
            mayProceed();
        }
    }

    @Test
    void viewDiscrepancyNoteServletCanBeInstantiatedBeforeTheBundlesExistAndStillAnswers() {
        ViewProbe servlet = assertDoesNotThrow(ViewProbe::new);
        processRequestSetsTheBundles();

        InsufficientPermissionException denied = assertThrows(InsufficientPermissionException.class,
                () -> servlet.mayProceedFor(new UserAccountBean()));
        assertEquals(ResourceBundleProvider.getExceptionsBundle(Locale.ENGLISH)
                .getString("no_permission_to_create_discrepancy_note"), denied.message);
    }

    @Test
    void createDiscrepancyNoteServletCanBeInstantiatedBeforeTheBundlesExist() {
        assertDoesNotThrow(CreateProbe::new);
    }
}
