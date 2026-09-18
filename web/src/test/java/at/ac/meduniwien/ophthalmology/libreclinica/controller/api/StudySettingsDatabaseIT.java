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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudyBindings;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * P3.5 — a study says what it does, and which items it means.
 *
 * <p>Two things are pinned here, and the second is the one that matters.
 *
 * <p>The resolution chain: a site's own answer beats its study's, and an
 * absent row means "as before" rather than "off". That last part is what lets
 * this ship without a configuration change — every deployment keeps behaving
 * exactly as it did until somebody sets something.
 *
 * <p>And the blinding. A trial that renames its arms must still be blinded.
 * The lookups translate whatever a study calls its groups into one fixed pair
 * of tokens, so the seven masking call sites keep comparing against a constant
 * and none of them can be wrong for a study that chose its own vocabulary.
 * Handing the raw name outward instead is how a renamed group silently
 * unblinds a trial — which nothing would report, and analysis would find.
 */
@SuppressWarnings("null")
class StudySettingsDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY_ID = 1;
    private static final int SITE_ID = 9401;
    private static final int SUBJECT_ID = 9402;
    private static final int STUDY_SUBJECT_ID = 9403;
    private static final int GROUP_CLASS_ID = 9404;
    private static final int GROUP_ID = 9405;

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE FROM subject_group_map WHERE study_subject_id = " + STUDY_SUBJECT_ID);
        exec("DELETE FROM study_group WHERE study_group_id = " + GROUP_ID);
        exec("DELETE FROM study_group_class WHERE study_group_class_id = " + GROUP_CLASS_ID);
        exec("DELETE FROM study_subject WHERE study_subject_id = " + STUDY_SUBJECT_ID);
        exec("DELETE FROM subject WHERE subject_id = " + SUBJECT_ID);
        exec("DELETE FROM study_setting WHERE study_id IN (" + STUDY_ID + ", " + SITE_ID + ")");
        exec("DELETE FROM study_item_binding WHERE study_id IN (" + STUDY_ID + ", " + SITE_ID + ")");
        exec("DELETE FROM study WHERE study_id = " + SITE_ID);
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private StudySettingService settings() {
        return new StudySettingService(DATA_SOURCE);
    }

    private StudyBindings bindings() {
        return new StudyBindings(DATA_SOURCE);
    }

    /** A site under study 1, so inheritance has something to inherit from. */
    private void seedSite() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study (study_id, name, unique_identifier, oc_oid, type_id, "
                             + "status_id, owner_id, date_created, parent_study_id) "
                             + "VALUES (?, 'IT Site', 'it-settings-site', 'S_ITSITE', 1, 1, 1, NOW(), ?) "
                             + "ON CONFLICT (study_id) DO NOTHING")) {
            ps.setInt(1, SITE_ID);
            ps.setInt(2, STUDY_ID);
            ps.executeUpdate();
        }
    }

    /* ---------------- the resolution chain ---------------- */

    @Test
    void anAbsentSettingMeansAsBefore() {
        // Nothing is set, so the code default answers — not "off". A
        // deployment that upgrades into this must not lose a surface.
        assertEquals("false", settings().resolve(STUDY_ID, StudySettingService.PORTAL_TODAYS_VISITS));
        assertEquals("true", settings().resolve(STUDY_ID, StudySettingService.INFERENCE_ENABLED));
    }

    @Test
    void aStudysOwnAnswerBeatsTheDefault() throws Exception {
        settings().put(STUDY_ID, StudySettingService.INFERENCE_ENABLED, "false", 1);
        assertFalse(settings().isEnabled(STUDY_ID, StudySettingService.INFERENCE_ENABLED));
    }

    @Test
    void aSiteInheritsItsStudyUntilItSaysOtherwise() throws Exception {
        seedSite();
        settings().put(STUDY_ID, StudySettingService.INGEST_IMAGE_ENABLED, "true", 1);
        assertTrue(settings().isEnabled(SITE_ID, StudySettingService.INGEST_IMAGE_ENABLED),
                "a site with no answer of its own uses its study's");

        settings().put(SITE_ID, StudySettingService.INGEST_IMAGE_ENABLED, "false", 1);
        assertFalse(settings().isEnabled(SITE_ID, StudySettingService.INGEST_IMAGE_ENABLED),
                "and its own answer wins when it has one");
        assertTrue(settings().isEnabled(STUDY_ID, StudySettingService.INGEST_IMAGE_ENABLED),
                "without changing the study's");
    }

    /**
     * Clearing restores "as before" rather than storing a value that happens
     * to match today's default — the two differ the moment the default does.
     */
    @Test
    void clearingASettingIsNotTheSameAsSettingItToTheDefault() throws Exception {
        settings().put(STUDY_ID, StudySettingService.INFERENCE_ENABLED, "false", 1);
        settings().put(STUDY_ID, StudySettingService.INFERENCE_ENABLED, null, 1);
        assertEquals("true", settings().resolve(STUDY_ID, StudySettingService.INFERENCE_ENABLED));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM study_setting WHERE study_id = ? AND setting_key = ?")) {
            ps.setInt(1, STUDY_ID);
            ps.setString(2, StudySettingService.INFERENCE_ENABLED);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "the row is gone, not set to the default");
            }
        }
    }

    /* ---------------- item bindings ---------------- */

    @Test
    void aBindingFallsBackToTheLiteralTheCodeAlwaysUsed() {
        // This is what lets the shared controllers keep their literals for one
        // release: a study that has said nothing behaves exactly as before.
        assertEquals("F_NAMD_VISIT",
                bindings().oidFor(STUDY_ID, StudyBindings.VISIT_CRF, "F_NAMD_VISIT"));
    }

    @Test
    void aStudyCanNameItsOwnItem() throws Exception {
        bindings().put(STUDY_ID, StudyBindings.VISIT_CRF, "F_MY_OWN_VISIT", 1);
        assertEquals("F_MY_OWN_VISIT",
                bindings().oidFor(STUDY_ID, StudyBindings.VISIT_CRF, "F_NAMD_VISIT"));
    }

    @Test
    void aSiteInheritsItsStudysBindings() throws Exception {
        seedSite();
        bindings().put(STUDY_ID, StudyBindings.RETINAL_CRT_OD, "I_PARENT_CRT_OD", 1);
        assertEquals("I_PARENT_CRT_OD",
                bindings().oidFor(SITE_ID, StudyBindings.RETINAL_CRT_OD, "fallback"));
        assertTrue(bindings().forStudy(SITE_ID).containsKey(StudyBindings.RETINAL_CRT_OD));
    }

    /* ---------------- the blinding that must not break ---------------- */

    /**
     * A study that calls its arms something else is still blinded.
     *
     * <p>The lookup translates the study's own group name into the canonical
     * token, so every masking call site keeps comparing against one constant.
     * If the raw name were handed outward instead, a renamed group would read
     * as "not the hidden arm" and a treating physician would see the AI's
     * answer — silently, with nothing to notice.
     */
    @Test
    void aStudyThatRenamesItsArmsIsStillBlinded() throws Exception {
        seedRandomisedSubject("KEIN_KI");
        settings().put(STUDY_ID, "ai.arm.hiddenGroup", "KEIN_KI", 1);
        settings().put(STUDY_ID, "ai.arm.shownGroup", "MIT_KI", 1);

        try (Connection c = DATA_SOURCE.getConnection()) {
            String arm = AiArmPolicy.armForSubject(c, STUDY_SUBJECT_ID);
            assertEquals(AiArmPolicy.ARM_HIDDEN, arm,
                    "the study's own name must come back as the canonical token");
            assertTrue(AiArmPolicy.maskAiFor(arm, sessionAs(Role.INVESTIGATOR)),
                    "a treating clinician on that subject must still be blinded");
            assertFalse(AiArmPolicy.maskAiFor(arm, sessionAs(Role.STUDYDIRECTOR)),
                    "and a data manager must still see it");
        }
    }

    @Test
    void aStudyUsingTheDefaultNamesIsUnaffected() throws Exception {
        seedRandomisedSubject(AiArmPolicy.ARM_HIDDEN);
        try (Connection c = DATA_SOURCE.getConnection()) {
            assertEquals(AiArmPolicy.ARM_HIDDEN, AiArmPolicy.armForSubject(c, STUDY_SUBJECT_ID));
        }
    }

    @Test
    void aSubjectInNeitherArmIsNotMasked() throws Exception {
        seedRandomisedSubject("SOME_OTHER_COHORT");
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Not an arm of this study: a non-AI grouping must not be read as
            // a blinding decision either way.
            assertNull(AiArmPolicy.armForSubject(c, STUDY_SUBJECT_ID));
        }
    }

    /** A subject randomised into a group of the given name. */
    private void seedRandomisedSubject(String groupName) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject (subject_id, date_of_birth, dob_collected, gender, "
                            + "unique_identifier, status_id, date_created, owner_id) "
                            + "VALUES (?, '1970-01-01', true, 'f', 'it-arm-pid', 1, NOW(), 1) "
                            + "ON CONFLICT (subject_id) DO NOTHING")) {
                ps.setInt(1, SUBJECT_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_subject (study_subject_id, label, subject_id, study_id, "
                            + "status_id, enrollment_date, date_created, owner_id, oc_oid) "
                            + "VALUES (?, 'IT-ARM-1', ?, ?, 1, NOW(), NOW(), 1, 'SS_ITARM1') "
                            + "ON CONFLICT (study_subject_id) DO NOTHING")) {
                ps.setInt(1, STUDY_SUBJECT_ID);
                ps.setInt(2, SUBJECT_ID);
                ps.setInt(3, STUDY_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group_class (study_group_class_id, name, study_id, "
                            + "group_class_type_id, status_id, owner_id, date_created) "
                            + "VALUES (?, 'IT Arm', ?, 3, 1, 1, NOW()) "
                            + "ON CONFLICT (study_group_class_id) DO NOTHING")) {
                ps.setInt(1, GROUP_CLASS_ID);
                ps.setInt(2, STUDY_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_group (study_group_id, name, study_group_class_id, "
                            + "allocation_weight) "
                            + "VALUES (?, ?, ?, 1) "
                            + "ON CONFLICT (study_group_id) DO NOTHING")) {
                ps.setInt(1, GROUP_ID);
                ps.setString(2, groupName);
                ps.setInt(3, GROUP_CLASS_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject_group_map (study_subject_id, study_group_class_id, "
                            + "study_group_id, status_id, owner_id, date_created) "
                            + "VALUES (?, ?, ?, 1, 1, NOW())")) {
                ps.setInt(1, STUDY_SUBJECT_ID);
                ps.setInt(2, GROUP_CLASS_ID);
                ps.setInt(3, GROUP_ID);
                ps.executeUpdate();
            }
        }
    }

    private static MockHttpSession sessionAs(Role role) {
        MockHttpSession s = new MockHttpSession();
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }
}
