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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * The option maps used to be filled in a static initialiser reading
 * SecureController.resadmin, which is null until a request has been
 * processed: loading the class first failed it for good. They are filled on
 * demand now, from processRequest, once the bundle exists.
 */
class CreateStudyServletOptionMapsTest {

    private ResourceBundle savedAdmin;

    @BeforeEach
    void emptyMapsAndNoBundle() {
        savedAdmin = SecureController.resadmin;
        SecureController.resadmin = null;
        CreateStudyServlet.facRecruitStatusMap.clear();
    }

    @AfterEach
    void restore() {
        SecureController.resadmin = savedAdmin;
    }

    @Test
    void loadingTheClassNeedsNoBundleAndTheMapsFillOnceItExists() {
        assertTrue(CreateStudyServlet.facRecruitStatusMap.isEmpty());

        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        SecureController.resadmin = ResourceBundleProvider.getAdminBundle(Locale.ENGLISH);
        CreateStudyServlet.ensureOptionMaps();

        assertFalse(CreateStudyServlet.facRecruitStatusMap.isEmpty());
        assertEquals(SecureController.resadmin.getString("recruiting"),
                CreateStudyServlet.facRecruitStatusMap.get("recruiting"));
        assertFalse(CreateStudyServlet.studyPhaseMap.isEmpty());
    }
}
