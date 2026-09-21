/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P2-1 — sites inherit their parent study's enrolled modules.
 *
 * <p>Enrollment is recorded on the study a data manager administers, which is
 * the parent. The people entering data are scoped to a site, with a different
 * study id — so without inheritance the nAMD workspace was absent for everyone
 * working at a site, which is where the patients are.
 */
class StudyModuleEnrollmentInheritanceDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Seeded parent study with a NAMD enrollment row. */
    private static final int PARENT_STUDY_ID = 1;

    private int siteId;

    @AfterEach
    void dropSite() throws Exception {
        if (siteId > 0) {
            exec("DELETE FROM study_module_enrollment WHERE study_id = " + siteId);
            exec("DELETE FROM study WHERE study_id = " + siteId);
            siteId = 0;
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** A site of the seeded parent study. */
    private int createSite() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study (parent_study_id, unique_identifier, name, status_id, "
                             + "date_created, owner_id, oc_oid) "
                             + "VALUES (?, ?, 'Inheritance IT site', 1, NOW(), 1, ?) "
                             + "RETURNING study_id")) {
            String suffix = String.valueOf(System.nanoTime() % 100000);
            ps.setInt(1, PARENT_STUDY_ID);
            ps.setString(2, "inh-it-" + suffix);
            ps.setString(3, "S_INHIT" + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void aSiteSeesItsParentsModules() throws Exception {
        siteId = createSite();

        List<String> parentModules = StudyModuleEnrollmentApiController
                .loadEnrolledModuleIds(DATA_SOURCE, PARENT_STUDY_ID, 0);
        assertTrue(parentModules.contains("NAMD"),
                "fixture assumption: the seeded parent study is enrolled in NAMD");

        // Without the parent, the site has nothing of its own.
        assertTrue(StudyModuleEnrollmentApiController
                        .loadEnrolledModuleIds(DATA_SOURCE, siteId, 0).isEmpty(),
                "the site has no enrollment rows of its own");

        // With it, the site's users get the study's modules.
        assertTrue(StudyModuleEnrollmentApiController
                        .loadEnrolledModuleIds(DATA_SOURCE, siteId, PARENT_STUDY_ID)
                        .contains("NAMD"),
                "a site must inherit its parent's modules");
    }

    @Test
    void aSitesOwnEnrollmentIsAddedToTheInheritedOnes() throws Exception {
        siteId = createSite();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_module_enrollment (study_id, module_id, enrolled_by) "
                             + "VALUES (?, 'RETINAL', 1)")) {
            ps.setInt(1, siteId);
            ps.executeUpdate();
        }

        List<String> effective = StudyModuleEnrollmentApiController
                .loadEnrolledModuleIds(DATA_SOURCE, siteId, PARENT_STUDY_ID);
        assertTrue(effective.contains("RETINAL"), "the site's own module");
        assertTrue(effective.contains("NAMD"), "and the parent's");
    }

    /** A module enrolled on both must not be listed twice. */
    @Test
    void aModuleEnrolledOnBothAppearsOnce() throws Exception {
        siteId = createSite();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_module_enrollment (study_id, module_id, enrolled_by) "
                             + "VALUES (?, 'NAMD', 1)")) {
            ps.setInt(1, siteId);
            ps.executeUpdate();
        }

        List<String> effective = StudyModuleEnrollmentApiController
                .loadEnrolledModuleIds(DATA_SOURCE, siteId, PARENT_STUDY_ID);
        assertEquals(1, effective.stream().filter("NAMD"::equals).count());
    }

    /** A parent must not pick up a module enrolled only on one of its sites. */
    @Test
    void inheritanceDoesNotRunUpwards() throws Exception {
        siteId = createSite();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_module_enrollment (study_id, module_id, enrolled_by) "
                             + "VALUES (?, 'IMAGING', 1)")) {
            ps.setInt(1, siteId);
            ps.executeUpdate();
        }

        assertFalse(StudyModuleEnrollmentApiController
                        .loadEnrolledModuleIds(DATA_SOURCE, PARENT_STUDY_ID, 0)
                        .contains("IMAGING"),
                "a site's module must not activate for the whole study");
    }
}
