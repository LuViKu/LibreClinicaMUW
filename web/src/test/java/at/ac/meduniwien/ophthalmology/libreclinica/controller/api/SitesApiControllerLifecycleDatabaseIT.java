/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Removing and restoring a site through
 * {@code POST /studies/{parent}/sites/{site}/disable} and {@code /restore},
 * checked row by row against what {@code RemoveSiteServlet} /
 * {@code RestoreSiteServlet} change.
 *
 * <p>The fixture is one site with a row of every kind the servlets cascade
 * to, rows of its parent study that the site's removal must not touch, and
 * rows that were removed on their own before: a revoked role, the role of
 * a removed account, a removed subject, the study subject of a removed
 * person, an event of a removed definition, a hidden event CRF, an item
 * hidden under a live event CRF, a removed item and a removed dataset. The
 * subjects removed on their own keep a live subject map, as every subject
 * removal leaves it.
 *
 * <p>The tests run in order: the refusals, a removal that fails part-way,
 * remove, restore, locked, a concurrent change, and the parent study's
 * status transitions around a removed site.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SitesApiControllerLifecycleDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String PARENT = "S_SITE_IT";
    private static final String SITE = "S_SITE_IT_A";

    private static int parent;
    private static int site;
    private static int siteOfRemovedParent;
    private static int siteRemovedB;
    private static int subjOfRemovedB;
    private static int siteOfOddParent;
    private static int mapOfSiteClass;
    private static int mapOfRemovedSubject;
    private static int mapOfRemovedPerson;
    private static int mapOfParentClass;
    private static int subjLive;
    private static int subjRemoved;
    private static int subjOfRemovedPerson;
    private static int subjOfParent;
    private static int defLive;
    private static int evLive;
    private static int evOfRemovedDef;
    private static int evOfRemovedSubject;
    private static int evOfRemovedPerson;
    private static int evOfParent;
    private static int ecLive;
    private static int ecHidden;
    private static int ecOfRemovedPerson;
    private static int ecOfParent;
    private static int idLive;
    private static int idRemoved;
    private static int idOfHidden;
    private static int idHiddenUnderLive;
    private static int idOfParent;
    private static int datasetLive;
    private static int datasetRemoved;
    /** {@code source_retinal_job_id|source_ingest_item_id} of {@code idLive}. */
    private static String provenanceIds;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            parent = insertStudy(c, null, "site-it", "Site IT", PARENT, 1);
            site = insertStudy(c, parent, "site-it-a", "Site IT A", SITE, 1);
            int removedParent = insertStudy(c, null, "site-it-gone", "Site IT gone", "S_SITE_IT_GONE", 5);
            siteOfRemovedParent = insertStudy(c, removedParent, "site-it-gone-a", "Site IT gone A",
                    "S_SITE_IT_GONE_A", 7);
            // It was locked when its study was removed.
            setOldStatus(c, "study", "study_id", siteOfRemovedParent, 6);
            // A second site of the parent, removed on its own before.
            siteRemovedB = insertStudy(c, parent, "site-it-b", "Site IT B", "S_SITE_IT_B", 5);
            subjOfRemovedB = insertStudySubject(c, "SITE-B1", siteRemovedB, 7);
            // A site left auto-removed (recorded locked) under a live study.
            int oddParent = insertStudy(c, null, "site-it-odd", "Site IT odd", "S_SITE_IT_ODD", 1);
            siteOfOddParent = insertStudy(c, oddParent, "site-it-odd-a", "Site IT odd A", "S_SITE_IT_ODD_A", 7);
            setOldStatus(c, "study", "study_id", siteOfOddParent, 6);

            insertUser(c, "site_live", 1);
            insertUser(c, "site_gone", 5);
            insertUser(c, "site_parent", 1);
            // Two live roles on the same (site, user) pair, and a revoked one.
            insertRole(c, "site_live", site, "Investigator", 1);
            insertRole(c, "site_live", site, "monitor", 1);
            insertRole(c, "site_live", site, "coordinator", 5);
            insertRole(c, "site_gone", site, "Investigator", 1);
            insertRole(c, "site_parent", parent, "director", 1);

            subjLive = insertStudySubject(c, "SITE-1", site, 1);
            subjRemoved = insertStudySubject(c, "SITE-2", site, 5);
            subjOfRemovedPerson = insertStudySubject(c, "SITE-3", site, 7);
            removePerson(c, subjOfRemovedPerson);
            subjOfParent = insertStudySubject(c, "SITE-4", parent, 1);

            int siteClass = insertGroupClass(c, site, "Site arm");
            int parentClass = insertGroupClass(c, parent, "Parent arm");
            int siteGroup = insertGroup(c, siteClass, "S");
            mapOfSiteClass = insertMap(c, siteClass, subjLive, siteGroup, 1);
            // No subject removal touches the subject's maps: they stay live.
            mapOfRemovedSubject = insertMap(c, siteClass, subjRemoved, siteGroup, 1);
            mapOfRemovedPerson = insertMap(c, siteClass, subjOfRemovedPerson, siteGroup, 1);
            mapOfParentClass = insertMap(c, parentClass, subjLive, insertGroup(c, parentClass, "P"), 1);

            defLive = insertDefinition(c, parent, "SE_SITE_LIVE", 1);
            int defRemoved = insertDefinition(c, parent, "SE_SITE_GONE", 5);
            evLive = insertEvent(c, defLive, subjLive, 1);
            evOfRemovedDef = insertEvent(c, defRemoved, subjLive, 7);
            evOfRemovedSubject = insertEvent(c, defLive, subjRemoved, 7);
            evOfRemovedPerson = insertEvent(c, defLive, subjOfRemovedPerson, 7);
            evOfParent = insertEvent(c, defLive, subjOfParent, 1);

            // One event CRF per (event, version, subject): the hidden one on evLive needs its own version.
            int secondVersion = insertOne(c, "INSERT INTO crf_version (crf_id, name, description, status_id, "
                    + "date_created, owner_id, oc_oid) VALUES (1, 'v-site', '', 1, now(), 1, 'F_DEMOGRAPHICS_VSITE') "
                    + "RETURNING crf_version_id");
            ecLive = insertEventCrf(c, evLive, subjLive, 1, 1);
            // Hidden before the site, recorded as available: the site's removal must not bring it back.
            ecHidden = insertEventCrf(c, evLive, subjLive, secondVersion, 7);
            setOldStatus(c, "event_crf", "event_crf_id", ecHidden, 1);
            ecOfRemovedPerson = insertEventCrf(c, evOfRemovedPerson, subjOfRemovedPerson, 1, 7);
            setOldStatus(c, "event_crf", "event_crf_id", ecOfRemovedPerson, 1);
            ecOfParent = insertEventCrf(c, evOfParent, subjOfParent, 1, 1);

            idLive = insertItemData(c, ecLive, 1, 1, "retinal_inference");
            idRemoved = insertItemData(c, ecLive, 2, 5, null);
            idOfHidden = insertItemData(c, ecHidden, 1, 7, null);
            setOldStatus(c, "item_data", "item_data_id", idOfHidden, 1);
            idOfParent = insertItemData(c, ecOfParent, 1, 1, null);
            // Hidden before the site, recorded as available, under a live event CRF.
            idHiddenUnderLive = insertItemData(c, ecLive, 3, 7, null);
            setOldStatus(c, "item_data", "item_data_id", idHiddenUnderLive, 1);
            setProvenanceIds(c, idLive, ecLive, subjLive, 990201L);
            provenanceIds = sourceIdsOf(idLive);

            datasetLive = insertDataset(c, site, "site-it-ds", 1);
            datasetRemoved = insertDataset(c, site, "site-it-ds-gone", 5);
        }
    }

    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void aSiteCannotComeBackUnderARemovedStudy() throws Exception {
        mockMvc().perform(post("/api/v1/studies/S_SITE_IT_GONE/sites/S_SITE_IT_GONE_A/restore")
                        .session(sysadminSession()))
                .andExpect(status().isConflict());
        assertEquals(7, statusOf("study", "study_id", siteOfRemovedParent));

        // Nor be removed on its own while its study is removed: that would
        // overwrite the status it recorded (locked) with auto-removed.
        mockMvc().perform(post("/api/v1/studies/S_SITE_IT_GONE/sites/S_SITE_IT_GONE_A/disable")
                        .session(sysadminSession()))
                .andExpect(status().isConflict());
        assertEquals(7, statusOf("study", "study_id", siteOfRemovedParent));
        assertEquals(6, oldStatusOf("study", "study_id", siteOfRemovedParent));
    }

    @Test
    @Order(2)
    void aLockedStudyRefusesSiteRemovalAndRestore() throws Exception {
        setStudyStatus(parent, 6);
        try {
            mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/disable")
                            .session(sysadminSession()))
                    .andExpect(status().isConflict());
            mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/S_SITE_IT_B/restore")
                            .session(sysadminSession()))
                    .andExpect(status().isConflict());
        } finally {
            setStudyStatus(parent, 1);
        }
        assertEquals(1, statusOf("study", "study_id", site));
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(1, statusOf("item_data", "item_data_id", idLive));
        assertEquals(5, statusOf("study", "study_id", siteRemovedB));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOfRemovedB));
    }

    @Test
    @Order(3)
    void aRemovalThatFailsPartWayChangesNothing() throws Exception {
        // The datasets are the cascade's last step.
        try (AutoCloseable _ = failUpdatesOf("dataset", "dataset_id", datasetLive)) {
            mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/disable")
                            .session(sysadminSession()))
                    .andExpect(status().isInternalServerError());
        }
        assertEquals(1, statusOf("study", "study_id", site));
        assertEquals(1, roleStatus("site_live", site, "Investigator"));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfSiteClass));
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(1, statusOf("study_event", "study_event_id", evLive));
        assertEquals(1, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(1, statusOf("item_data", "item_data_id", idLive));
        assertEquals(1, statusOf("dataset", "dataset_id", datasetLive));
        assertEquals(List.of(), lifecycleAudit());
        assertEquals(1, studyAuditCount(61, site), "the failure is audited as OPERATION_FAILED");
    }

    @Test
    @Order(4)
    void removalCascadesLikeRemoveSiteServlet() throws Exception {
        MockHttpSession session = sysadminSession();
        StudyBean sessionSite = new StudyBean();
        sessionSite.setId(site);
        sessionSite.setParentStudyId(parent);
        sessionSite.setStatus(Status.AVAILABLE);
        session.setAttribute("study", sessionSite);
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/disable")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));
        // As RemoveSiteServlet does to the current study.
        assertEquals(Status.DELETED, sessionSite.getStatus());

        assertEquals(5, statusOf("study", "study_id", site));
        assertEquals(1, oldStatusOf("study", "study_id", site));
        assertEquals(1, statusOf("study", "study_id", parent));

        // Role bindings: auto-removed row by row, role names untouched; the parent's stay.
        assertEquals(7, roleStatus("site_live", site, "Investigator"));
        assertEquals(7, roleStatus("site_live", site, "monitor"));
        assertEquals(5, roleStatus("site_live", site, "coordinator"));
        assertEquals(3, roleRowCount("site_live", site));
        assertEquals(7, roleStatus("site_gone", site, "Investigator"));
        assertEquals(1, roleStatus("site_parent", parent, "director"));

        // The site's own subject groups go with it; the parent's do not.
        assertEquals(7, statusOf("subject_group_map", "subject_group_map_id", mapOfSiteClass));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfParentClass));
        // A map goes with its subject: these subjects were not removed here.
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedSubject));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedPerson));

        assertEquals(7, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(5, statusOf("study_subject", "study_subject_id", subjRemoved));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOfRemovedPerson));
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjOfParent));

        // Definitions belong to the parent study.
        assertEquals(1, statusOf("study_event_definition", "study_event_definition_id", defLive));

        assertEquals(7, statusOf("study_event", "study_event_id", evLive));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedDef));
        assertEquals(1, statusOf("study_event", "study_event_id", evOfParent));

        assertEquals(7, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(1, oldStatusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecHidden));
        assertEquals(7, oldStatusOf("event_crf", "event_crf_id", ecHidden));
        assertEquals(1, statusOf("event_crf", "event_crf_id", ecOfParent));

        assertEquals(7, statusOf("item_data", "item_data_id", idLive));
        assertEquals(1, oldStatusOf("item_data", "item_data_id", idLive));
        assertEquals(5, statusOf("item_data", "item_data_id", idRemoved));
        assertEquals(7, statusOf("item_data", "item_data_id", idOfHidden));
        assertEquals(7, statusOf("item_data", "item_data_id", idHiddenUnderLive));
        assertEquals(7, oldStatusOf("item_data", "item_data_id", idHiddenUnderLive));
        assertEquals(1, statusOf("item_data", "item_data_id", idOfParent));
        // Hiding a value must not strip the record of what produced it.
        assertEquals("retinal_inference", sourceKindOf(idLive));
        assertEquals(provenanceIds, sourceIdsOf(idLive));

        assertEquals(7, statusOf("dataset", "dataset_id", datasetLive));
        assertEquals(5, statusOf("dataset", "dataset_id", datasetRemoved));

        assertEquals(List.of("available>removed"), lifecycleAudit());
    }

    @Test
    @Order(5)
    void restoreBringsBackWhatTheRemovalTookAndNothingElse() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/restore")
                        .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("available"));

        assertEquals(1, statusOf("study", "study_id", site));

        assertEquals(1, roleStatus("site_live", site, "Investigator"));
        assertEquals(1, roleStatus("site_live", site, "monitor"));
        assertEquals(5, roleStatus("site_live", site, "coordinator"));
        // The removed account's role stays with the account.
        assertEquals(7, roleStatus("site_gone", site, "Investigator"));

        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfSiteClass));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfParentClass));
        // Still live, so the subject has its group and arm when it comes back.
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedSubject));
        assertEquals(1, statusOf("subject_group_map", "subject_group_map_id", mapOfRemovedPerson));

        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(5, statusOf("study_subject", "study_subject_id", subjRemoved));
        // A removed person's study subject stays with the person.
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjOfRemovedPerson));

        assertEquals(1, statusOf("study_event", "study_event_id", evLive));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedDef));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedSubject));
        assertEquals(7, statusOf("study_event", "study_event_id", evOfRemovedPerson));

        assertEquals(1, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecHidden));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecOfRemovedPerson));

        assertEquals(1, statusOf("item_data", "item_data_id", idLive));
        assertEquals(5, statusOf("item_data", "item_data_id", idRemoved));
        assertEquals(7, statusOf("item_data", "item_data_id", idOfHidden));
        assertEquals(7, statusOf("item_data", "item_data_id", idHiddenUnderLive));
        assertEquals("retinal_inference", sourceKindOf(idLive));
        assertEquals(provenanceIds, sourceIdsOf(idLive));

        assertEquals(1, statusOf("dataset", "dataset_id", datasetLive));
        // RestoreSiteServlet would make this one available too.
        assertEquals(5, statusOf("dataset", "dataset_id", datasetRemoved));

        assertEquals(List.of("available>removed", "removed>available"), lifecycleAudit());
    }

    @Test
    @Order(6)
    void restoreReturnsALockedSiteToLocked() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE study SET status_id = 6 WHERE study_id = " + site);
        }
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/disable")
                        .session(sysadminSession()))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/restore")
                        .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("locked"));
        assertEquals(6, statusOf("study", "study_id", site));
    }

    @Test
    @Order(7)
    void aSiteThatIsNoLongerLiveIsNotRemovedAgain() throws Exception {
        // The removal matches only a live site, so the status it recorded stays.
        mockMvc().perform(post("/api/v1/studies/S_SITE_IT_ODD/sites/S_SITE_IT_ODD_A/disable")
                        .session(sysadminSession()))
                .andExpect(status().isConflict());
        assertEquals(7, statusOf("study", "study_id", siteOfOddParent));
        assertEquals(6, oldStatusOf("study", "study_id", siteOfOddParent));
    }

    @Test
    @Order(8)
    void theStudyStatusLeavesARemovedSiteRemoved() throws Exception {
        setStudyStatus(site, 1);
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/disable")
                        .session(sysadminSession()))
                .andExpect(status().isOk());

        MockMvc studies = ProductionMvc.standalone(new StudiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        studies.perform(post("/api/v1/studies/" + PARENT + "/status").contentType("application/json")
                        .content("{\"targetStatus\":\"LOCKED\",\"reason\":\"database lock\"}")
                        .session(sysadminSession()))
                .andExpect(status().isOk());
        assertEquals(5, statusOf("study", "study_id", site));
        assertEquals(1, oldStatusOf("study", "study_id", site));
        assertEquals(5, statusOf("study", "study_id", siteRemovedB));
        studies.perform(post("/api/v1/studies/" + PARENT + "/status").contentType("application/json")
                        .content("{\"targetStatus\":\"AVAILABLE\"}")
                        .session(sysadminSession()))
                .andExpect(status().isOk());
        assertEquals(5, statusOf("study", "study_id", site));
        assertEquals(1, oldStatusOf("study", "study_id", site));

        // A study removal and restore leaves the removed site and its data removed.
        studies.perform(post("/api/v1/studies/" + PARENT + "/disable").contentType("application/json")
                        .content("{\"reason\":\"r\"}").session(sysadminSession()))
                .andExpect(status().isOk());
        studies.perform(post("/api/v1/studies/" + PARENT + "/restore").contentType("application/json")
                        .content("{\"reason\":\"r\"}").session(sysadminSession()))
                .andExpect(status().isOk());
        assertEquals(5, statusOf("study", "study_id", site));
        assertEquals(7, roleStatus("site_live", site, "Investigator"));
        assertEquals(7, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(7, statusOf("event_crf", "event_crf_id", ecLive));

        // ... and the site's own restore still brings it all back.
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites/" + SITE + "/restore")
                        .session(sysadminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("available"));
        assertEquals(1, roleStatus("site_live", site, "Investigator"));
        assertEquals(1, statusOf("study_subject", "study_subject_id", subjLive));
        assertEquals(1, statusOf("event_crf", "event_crf_id", ecLive));
        assertEquals(1, statusOf("item_data", "item_data_id", idLive));
    }

    /* ------------------------------------------------------------------ */

    private static void setStudyStatus(int studyId, int statusId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE study SET status_id = " + statusId + " WHERE study_id = " + studyId);
        }
    }

    /** The site's lifecycle audit rows, as {@code old>new}, oldest first. */
    private static List<String> lifecycleAudit() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT old_value, new_value FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = 'study' AND entity_id = ? ORDER BY audit_id")) {
            ps.setInt(1, AuditTypeIds.SITE_LIFECYCLE_CHANGED);
            ps.setInt(2, site);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1) + ">" + rs.getString(2));
            }
        }
        return out;
    }

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new SitesApiController(DATA_SOURCE))
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
        s.setId(parent);
        s.setOid(PARENT);
        session.setAttribute("study", s);
        return session;
    }
}
