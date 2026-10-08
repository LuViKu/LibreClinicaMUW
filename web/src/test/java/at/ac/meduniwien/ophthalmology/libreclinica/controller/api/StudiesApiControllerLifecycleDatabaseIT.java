/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Removing and restoring a study through {@code POST /studies/{oid}/disable}
 * and {@code /restore}, checked row by row against what the legacy
 * {@code RemoveStudyServlet} / {@code RestoreStudyServlet} change.
 *
 * <p>The fixture is one top-level study with two sites and one row of
 * every kind the servlets cascade to, plus rows that were removed on
 * their own before the study: a removed site, subject, definition,
 * event CRF and item, the study subject of a removed person, the role of
 * a removed user account and a revoked role, a removed group class and
 * dataset, a removed event-definition CRF and one of a CRF removed from
 * the library, and an item hidden under a live event CRF. The live site
 * has its own group class, dataset and site-level event-definition CRF.
 * The subjects removed on their own keep a live subject map, as every
 * subject removal leaves it. Removal must auto-remove the live rows and
 * leave those alone; restore must bring back exactly what the removal
 * took.
 *
 * <p>The tests run in order: preview, the refusals, a removal that fails
 * part-way, remove, restore.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudiesApiControllerLifecycleDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String OID = "S_CASCADE_IT";
    /** {@code source_retinal_job_id|source_ingest_item_id} of {@code idLive}. */
    private static String PROVENANCE_IDS;

    private static int study;
    private static int siteLive;
    private static int siteRemoved;
    private static int subjLive;
    private static int subjSigned;
    private static int subjRemoved;
    private static int subjOnRemovedSite;
    private static int subjOfRemovedPerson;
    private static int groupClass;
    private static int groupClassOfSite;
    private static int groupClassRemoved;
    private static int mapLive;
    private static int mapOfSiteClass;
    private static int mapOfRemovedSubject;
    private static int mapOfSubjectOnRemovedSite;
    private static int mapOfRemovedPerson;
    private static int defLive;
    private static int defRemoved;
    private static int edc;
    private static int edcRemoved;
    private static int edcOfRemovedCrf;
    private static int edcOfSite;
    private static int evLive;
    private static int evSigned;
    private static int evOfRemovedSubject;
    private static int evOfRemovedDef;
    private static int evOfRemovedPerson;
    private static int ecLive;
    private static int ecRemoved;
    private static int ecOfSignedSubject;
    private static int ecOfRemovedSubject;
    private static int ecOfRemovedPerson;
    private static int idLive;
    private static int idRemoved;
    private static int idOfSignedSubject;
    private static int idOfRemovedSubject;
    private static int idOfRemovedPerson;
    private static int idHiddenUnderLive;
    private static int dataset;
    private static int datasetOfSite;
    private static int datasetRemoved;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            study = insertStudy(c, null, "cascade-it", "Cascade IT", OID, 1);
            siteLive = insertStudy(c, study, "cascade-it-a", "Cascade IT site A", OID + "_A", 1);
            siteRemoved = insertStudy(c, study, "cascade-it-b", "Cascade IT site B", OID + "_B", 5);

            insertUser(c, "casc_multi", 1);
            insertUser(c, "casc_site", 1);
            insertUser(c, "casc_gone", 5);
            // Two live roles on the same (study, user) pair, plus a revoked one.
            insertRole(c, "casc_multi", study, "Investigator", 1);
            insertRole(c, "casc_multi", study, "monitor", 1);
            insertRole(c, "casc_multi", study, "director", 5);
            insertRole(c, "casc_site", siteLive, "Investigator", 1);
            // A removed account's roles are auto-removed together with the account.
            insertRole(c, "casc_gone", study, "Investigator", 7);

            subjLive = insertStudySubject(c, "CASC-1", study, 1);
            subjSigned = insertStudySubject(c, "CASC-2", siteLive, 8);
            subjRemoved = insertStudySubject(c, "CASC-3", study, 5);
            subjOnRemovedSite = insertStudySubject(c, "CASC-4", siteRemoved, 7);
            // Removing a person (the subject record) auto-removes their study
            // subjects and everything under them, as RemoveSubjectServlet does.
            subjOfRemovedPerson = insertStudySubject(c, "CASC-5", study, 7);
            removePerson(c, subjOfRemovedPerson);

            groupClass = insertOne(c, "INSERT INTO study_group_class (name, study_id, owner_id, date_created, "
                    + "group_class_type_id, status_id, subject_assignment) "
                    + "VALUES ('Arm', " + study + ", 1, now(), 1, 1, 'optional') RETURNING study_group_class_id");
            int group = insertOne(c, "INSERT INTO study_group (name, description, study_group_class_id) "
                    + "VALUES ('A', 'arm A', " + groupClass + ") RETURNING study_group_id");
            mapLive = insertMap(c, groupClass, subjLive, group, 1);
            // No subject removal touches the subject's maps: they stay live.
            mapOfRemovedSubject = insertMap(c, groupClass, subjRemoved, group, 1);
            mapOfSubjectOnRemovedSite = insertMap(c, groupClass, subjOnRemovedSite, group, 1);
            mapOfRemovedPerson = insertMap(c, groupClass, subjOfRemovedPerson, group, 1);
            groupClassOfSite = insertGroupClass(c, siteLive, "Site arm");
            mapOfSiteClass = insertMap(c, groupClassOfSite, subjSigned, insertGroup(c, groupClassOfSite, "S"), 1);
            groupClassRemoved = insertGroupClass(c, study, "Removed arm");
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE study_group_class SET status_id = 5 WHERE study_group_class_id = "
                        + groupClassRemoved);
            }

            defLive = insertDefinition(c, study, "SE_CASC_LIVE", 1);
            defRemoved = insertDefinition(c, study, "SE_CASC_GONE", 5);
            edc = insertOne(c, "INSERT INTO event_definition_crf (study_event_definition_id, study_id, crf_id, "
                    + "required_crf, double_entry, default_version_id, status_id, owner_id, date_created, "
                    + "ordinal) VALUES (" + defLive + ", " + study + ", 1, true, false, 1, 1, 1, now(), 1) "
                    + "RETURNING event_definition_crf_id");
            edcRemoved = insertEventDefinitionCrf(c, defLive, study, insertCrf(c, "F_CASC_OTHER", 1), 5, null);
            // RemoveCRFServlet: the CRF removed, its event-definition CRFs auto-removed.
            edcOfRemovedCrf = insertEventDefinitionCrf(c, defLive, study, insertCrf(c, "F_CASC_GONE", 5), 7, null);
            edcOfSite = insertEventDefinitionCrf(c, defLive, siteLive, 1, 1, edc);

            evLive = insertEvent(c, defLive, subjLive, 1);
            evSigned = insertEvent(c, defLive, subjSigned, 1);
            evOfRemovedSubject = insertEvent(c, defLive, subjRemoved, 7);
            evOfRemovedDef = insertEvent(c, defRemoved, subjLive, 7);
            evOfRemovedPerson = insertEvent(c, defLive, subjOfRemovedPerson, 7);

            // One event CRF per (event, version, subject): the second one on evLive needs its own version.
            int secondVersion = insertOne(c, "INSERT INTO crf_version (crf_id, name, description, status_id, "
                    + "date_created, owner_id, oc_oid) VALUES (1, 'v-casc', '', 1, now(), 1, 'F_DEMOGRAPHICS_VCASC') "
                    + "RETURNING crf_version_id");
            ecLive = insertEventCrf(c, evLive, subjLive, 1, 1);
            ecRemoved = insertEventCrf(c, evLive, subjLive, secondVersion, 5);
            ecOfSignedSubject = insertEventCrf(c, evSigned, subjSigned, 1, 1);
            ecOfRemovedSubject = insertEventCrf(c, evOfRemovedSubject, subjRemoved, 1, 7);
            ecOfRemovedPerson = insertEventCrf(c, evOfRemovedPerson, subjOfRemovedPerson, 1, 7);

            idLive = insertItemData(c, ecLive, 1, 1, "modality_baseline");
            idRemoved = insertItemData(c, ecLive, 2, 5, null);
            idOfSignedSubject = insertItemData(c, ecOfSignedSubject, 1, 1, null);
            idOfRemovedSubject = insertItemData(c, ecOfRemovedSubject, 1, 7, null);
            idOfRemovedPerson = insertItemData(c, ecOfRemovedPerson, 1, 7, null);
            // The person's removal wrote the rows back with the status they had loaded.
            setOldStatus(c, "event_crf", "event_crf_id", ecOfRemovedPerson, 1);
            setOldStatus(c, "item_data", "item_data_id", idOfRemovedPerson, 1);
            // Hidden before the study, recorded as available, under a live event CRF.
            idHiddenUnderLive = insertItemData(c, ecLive, 3, 7, null);
            setOldStatus(c, "item_data", "item_data_id", idHiddenUnderLive, 1);
            setProvenanceIds(c, idLive, ecLive, subjLive, 990101L);
            PROVENANCE_IDS = sourceIdsOf(idLive);
            assertTrue(PROVENANCE_IDS.matches("990101\\|\\d+"), PROVENANCE_IDS);

            dataset = insertOne(c, "INSERT INTO dataset (study_id, status_id, name, description, sql_statement, "
                    + "num_runs, date_created, owner_id) VALUES (" + study + ", 1, 'casc-it-dataset', '', '', 0, "
                    + "now(), 1) RETURNING dataset_id");
            datasetOfSite = insertDataset(c, siteLive, "casc-it-site-dataset", 1);
            datasetRemoved = insertDataset(c, study, "casc-it-gone-dataset", 5);
        }
    }

    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void previewNamesWhatARemovalWouldTake() throws Exception {
        mockMvc().perform(get("/api/v1/studies/" + OID + "/removal-preview").session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oid").value(OID))
                .andExpect(jsonPath("$.siteNames.length()").value(1))
                .andExpect(jsonPath("$.siteNames[0]").value("Cascade IT site A"))
                .andExpect(jsonPath("$.roleBindings").value(3))
                .andExpect(jsonPath("$.subjects").value(2))
                .andExpect(jsonPath("$.groupClasses").value(2))
                .andExpect(jsonPath("$.eventDefinitions").value(1))
                .andExpect(jsonPath("$.events").value(2))
                .andExpect(jsonPath("$.eventCrfs").value(2))
                .andExpect(jsonPath("$.itemData").value(2))
                .andExpect(jsonPath("$.datasets").value(2));
        // A preview changes nothing.
        assertEquals(1, statusOf("study", "study_id", study));
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
    }

    @Test
    @Order(2)
    void previewIsForSystemAdministratorsOnly() throws Exception {
        mockMvc().perform(get("/api/v1/studies/" + OID + "/removal-preview").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void removalWithoutAReasonIsRefusedAndChangesNothing() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + OID + "/disable")
                        .contentType("application/json").content("{\"reason\":\"  \"}")
                        .session(sysadminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mockMvc().perform(post("/api/v1/studies/" + OID + "/disable").session(sysadminSession()))
                .andExpect(status().isBadRequest());
        assertEquals(1, statusOf("study", "study_id", study));
    }

    @Test
    @Order(4)
    void aSiteCannotBeRemovedThroughTheStudyEndpoint() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + OID + "_A/disable")
                        .contentType("application/json").content("{\"reason\":\"wrong door\"}")
                        .session(sysadminSession()))
                .andExpect(status().isConflict());
        assertEquals(1, statusOf("study", "study_id", siteLive));
    }

    @Test
    @Order(5)
    void aRemovalThatFailsPartWayChangesNothing() throws Exception {
        // The datasets are the cascade's last step.
        try (AutoCloseable _ = failUpdatesOf("dataset", "dataset_id", dataset)) {
            mockMvc().perform(post("/api/v1/studies/" + OID + "/disable")
                            .contentType("application/json").content("{\"reason\":\"doomed\"}")
                            .session(sysadminSession()))
                    .andExpect(status().isInternalServerError());
        }
        assertEquals(1, statusOf("study", "study_id", study));
        assertEquals(1, statusOf("study", "study_id", siteLive));
        assertEquals(1, roleStatus("casc_multi", study, "Investigator"));
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(1, statusOf("study_group_class", "study_group_class_id", groupClass));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapLive));
        assertEquals(1, statusOf("study_event_definition", "study_event_definition_id", defLive));
        assertEquals(1, statusOf("event_definition_crf", "event_definition_crf_id", edc));
        assertEquals(1, statusOf("study_event", "study_event_id", evLive));
        assertEquals(1, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(1, statusOf("item_data", "item_data_id", idLive));
        assertEquals(1, statusOf("dataset", "dataset_id", dataset));
        assertEquals(0, studyAuditCount(AuditTypeIds.STUDY_LIFECYCLE_CHANGED, study));
        assertEquals(1, studyAuditCount(61, study), "the failure is audited as OPERATION_FAILED");
    }

    @Test
    @Order(6)
    void removalCascadesLikeRemoveStudyServlet() throws Exception {
        MockHttpSession session = sysadminSession();
        StudyBean sessionStudy = studyBean(study, 0, Status.AVAILABLE);
        session.setAttribute("study", sessionStudy);
        mockMvc().perform(post("/api/v1/studies/" + OID + "/disable")
                        .contentType("application/json").content("{\"reason\":\"Study closed early\"}")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));
        // As RemoveStudyServlet does to the current study.
        assertEquals(Status.DELETED, sessionStudy.getStatus());

        // The study is removed and remembers what it was; the live site is auto-removed.
        assertEquals(5, statusOf("study", "study_id", study));
        assertEquals(1, oldStatusOf("study", "study_id", study));
        assertEquals(7, statusOf("study", "study_id", siteLive));
        assertEquals(1, oldStatusOf("study", "study_id", siteLive));
        assertEquals(5, statusOf("study", "study_id", siteRemoved));

        // Role bindings: auto-removed row by row, role names untouched.
        assertEquals(7, roleStatus("casc_multi", study, "Investigator"));
        assertEquals(7, roleStatus("casc_multi", study, "monitor"));
        assertEquals(5, roleStatus("casc_multi", study, "director"));
        assertEquals(7, roleStatus("casc_site", siteLive, "Investigator"));
        assertEquals(3, roleRowCount("casc_multi", study));

        assertEquals(7, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjSigned));
        assertEquals(5, statusOf("study_subject", "study_subject_id", subjRemoved));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOnRemovedSite));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOfRemovedPerson));

        assertEquals(7, statusOf("study_group_class", "study_group_class_id", groupClass));
        assertEquals(7, statusOf("study_group_class", "study_group_class_id", groupClassOfSite));
        assertEquals(5, statusOf("study_group_class", "study_group_class_id", groupClassRemoved));
        assertEquals(7, statusOf("subject_group_map", "subject_group_map_id", mapLive));
        assertEquals(7, statusOf("subject_group_map", "subject_group_map_id", mapOfSiteClass));
        // A map goes with its subject: these subjects were not removed here.
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedSubject));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfSubjectOnRemovedSite));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedPerson));

        assertEquals(7, statusOf("study_event_definition", "study_event_definition_id", defLive));
        assertEquals(5, statusOf("study_event_definition", "study_event_definition_id", defRemoved));
        assertEquals(7, statusOf("event_definition_crf", "event_definition_crf_id", edc));
        assertEquals(5, statusOf("event_definition_crf", "event_definition_crf_id", edcRemoved));
        assertEquals(7, statusOf("event_definition_crf", "event_definition_crf_id", edcOfRemovedCrf));
        // Parent-level rows only, as the servlet's findAllByDefinition.
        assertEquals(1, statusOf("event_definition_crf", "event_definition_crf_id", edcOfSite));

        assertEquals(7, statusOf("study_event", "study_event_id", evLive));
        assertEquals(7, statusOf("study_event", "study_event_id", evSigned));

        assertEquals(7, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(1, oldStatusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(5, statusOf("event_crf", "event_crf_id", ecRemoved));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecOfSignedSubject));

        assertEquals(7, statusOf("item_data", "item_data_id", idLive));
        assertEquals(1, oldStatusOf("item_data", "item_data_id", idLive));
        assertEquals(5, statusOf("item_data", "item_data_id", idRemoved));
        assertEquals(7, statusOf("item_data", "item_data_id", idOfSignedSubject));
        assertEquals(7, statusOf("item_data", "item_data_id", idHiddenUnderLive));
        assertEquals(7, oldStatusOf("item_data", "item_data_id", idHiddenUnderLive));
        // Hiding a value must not strip the record of what produced it.
        assertEquals("modality_baseline", sourceKindOf(idLive));
        assertEquals(PROVENANCE_IDS, sourceIdsOf(idLive));

        assertEquals(7, statusOf("dataset", "dataset_id", dataset));
        assertEquals(7, statusOf("dataset", "dataset_id", datasetOfSite));
        assertEquals(5, statusOf("dataset", "dataset_id", datasetRemoved));

        // One lifecycle audit row, carrying the operator's reason.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT reason_for_change, old_value, new_value FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? AND audit_table = 'study' AND entity_id = ? "
                             + "ORDER BY audit_id")) {
            ps.setInt(1, AuditTypeIds.STUDY_LIFECYCLE_CHANGED);
            ps.setInt(2, study);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a study lifecycle audit row");
                assertEquals("Study closed early", rs.getString(1));
                assertEquals("available", rs.getString(2));
                assertEquals("removed", rs.getString(3));
            }
        }
    }

    @Test
    @Order(7)
    void restoreBringsBackWhatTheRemovalTookAndNothingElse() throws Exception {
        MockHttpSession session = sysadminSession();
        StudyBean sessionSite = studyBean(siteLive, study, Status.AUTO_DELETED);
        session.setAttribute("study", sessionSite);
        mockMvc().perform(post("/api/v1/studies/" + OID + "/restore")
                        .contentType("application/json").content("{\"reason\":\"Removed by mistake\"}")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("available"));
        // As RestoreStudyServlet does when the current study is one of the sites.
        assertEquals(Status.AVAILABLE, sessionSite.getStatus());

        assertEquals(1, statusOf("study", "study_id", study));
        assertEquals(1, statusOf("study", "study_id", siteLive));
        assertEquals(5, statusOf("study", "study_id", siteRemoved));

        assertEquals(1, roleStatus("casc_multi", study, "Investigator"));
        assertEquals(1, roleStatus("casc_multi", study, "monitor"));
        assertEquals(5, roleStatus("casc_multi", study, "director"));
        assertEquals(1, roleStatus("casc_site", siteLive, "Investigator"));
        assertEquals(3, roleRowCount("casc_multi", study));
        // The removed account's role stays with the account.
        assertEquals(7, roleStatus("casc_gone", study, "Investigator"));

        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
        // study_subject keeps no prior status; the servlet restores to available too.
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjSigned));
        assertEquals(5, statusOf("study_subject", "study_subject_id", subjRemoved));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOnRemovedSite));
        // A removed person's study subject stays with the person.
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOfRemovedPerson));

        assertEquals(1, statusOf("study_group_class", "study_group_class_id", groupClass));
        assertEquals(1, statusOf("study_group_class", "study_group_class_id", groupClassOfSite));
        assertEquals(5, statusOf("study_group_class", "study_group_class_id", groupClassRemoved));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapLive));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfSiteClass));
        // Still live, so the subject has its group and arm when it comes back.
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedSubject));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfSubjectOnRemovedSite));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedPerson));

        assertEquals(1, statusOf("study_event_definition", "study_event_definition_id", defLive));
        assertEquals(5, statusOf("study_event_definition", "study_event_definition_id", defRemoved));
        assertEquals(1, statusOf("event_definition_crf", "event_definition_crf_id", edc));
        assertEquals(5, statusOf("event_definition_crf", "event_definition_crf_id", edcRemoved));
        // A CRF removed from the library keeps its event-definition CRFs removed.
        assertEquals(7, statusOf("event_definition_crf", "event_definition_crf_id", edcOfRemovedCrf));
        assertEquals(1, statusOf("event_definition_crf", "event_definition_crf_id", edcOfSite));

        assertEquals(1, statusOf("study_event", "study_event_id", evLive));
        assertEquals(1, statusOf("study_event", "study_event_id", evSigned));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedSubject));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedDef));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedPerson));

        assertEquals(1, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(5, statusOf("event_crf", "event_crf_id", ecRemoved));
        assertEquals(1, statusOf("event_crf", "event_crf_id", ecOfSignedSubject));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecOfRemovedSubject));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecOfRemovedPerson));

        assertEquals(1, statusOf("item_data", "item_data_id", idLive));
        assertEquals(5, statusOf("item_data", "item_data_id", idRemoved));
        assertEquals(1, statusOf("item_data", "item_data_id", idOfSignedSubject));
        assertEquals(7, statusOf("item_data", "item_data_id", idOfRemovedSubject));
        assertEquals(7, statusOf("item_data", "item_data_id", idOfRemovedPerson));
        assertEquals(7, statusOf("item_data", "item_data_id", idHiddenUnderLive));
        assertEquals("modality_baseline", sourceKindOf(idLive));
        assertEquals(PROVENANCE_IDS, sourceIdsOf(idLive));

        assertEquals(1, statusOf("dataset", "dataset_id", dataset));
        assertEquals(1, statusOf("dataset", "dataset_id", datasetOfSite));
        assertEquals(5, statusOf("dataset", "dataset_id", datasetRemoved));
    }

    @Test
    @Order(8)
    void restoreReturnsALockedStudyToLocked() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE study SET status_id = 6 WHERE study_id = " + study);
        }
        mockMvc().perform(post("/api/v1/studies/" + OID + "/disable")
                        .contentType("application/json").content("{\"reason\":\"archive\"}")
                        .session(sysadminSession()))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/studies/" + OID + "/restore")
                        .contentType("application/json").content("{\"reason\":\"archive undone\"}")
                        .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("locked"));
        assertEquals(6, statusOf("study", "study_id", study));
    }

    /* ------------------------------------------------------------------ */

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new StudiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean s = new StudyBean();
        s.setId(1);
        s.setOid("S_DEFAULTS1");
        session.setAttribute("study", s);
        return session;
    }

    private static StudyBean studyBean(int id, int parentId, Status status) {
        StudyBean s = new StudyBean();
        s.setId(id);
        s.setParentStudyId(parentId);
        s.setStatus(status);
        return s;
    }

    private static MockHttpSession userSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(4);
        ub.setName("datamanager");
        session.setAttribute("userBean", ub);
        return session;
    }
}
