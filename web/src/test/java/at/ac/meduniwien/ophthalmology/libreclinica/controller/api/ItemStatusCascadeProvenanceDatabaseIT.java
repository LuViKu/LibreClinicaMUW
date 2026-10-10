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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A status cascade hides or shows a value; it does not author it. Every
 * SPA path that moves item data between statuses (an event cancelled or
 * restored, a subject removed or restored, an event CRF restored, an
 * event definition restored, locked or unlocked) must leave the item's
 * provenance ({@code source_kind} and the source ids) as it was.
 * {@code ItemDataDAO.update} clears it, because it is the path for
 * writing a value.
 *
 * <p>Each test has its own subject, so the tests do not depend on order.
 */
@SuppressWarnings("resource") // the context is only a bean-lookup holder for the legacy DAOs and lives as long as the test JVM
class ItemStatusCascadeProvenanceDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String KIND = "retinal_inference";

    private static int eventToCancel;
    private static int itemOfCancelledEvent;
    private static int itemOfRemovedSubject;
    private static int hiddenEventCrf;
    private static int itemOfHiddenEventCrf;
    private static int itemOfRemovedDefinition;
    private static int itemOfLockedDefinition;

    @BeforeAll
    static void seed() throws SQLException {
        // StudyEventDAO.update runs the event's rules, looking up their DAO in
        // the context Spring hands StudyEventBeanListener. There are no rules
        // here; the lookup only has to succeed.
        StaticApplicationContext rules = new StaticApplicationContext();
        rules.getBeanFactory().registerSingleton("ruleSetDao", Mockito.mock(RuleSetDao.class));
        rules.refresh();
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);

        try (Connection c = DATA_SOURCE.getConnection()) {
            int def = insertDefinition(c, 1, "SE_PROV", 1);

            int ss = insertStudySubject(c, "PROV-EV", 1, 1);
            eventToCancel = insertEvent(c, def, ss, 1);
            itemOfCancelledEvent = insertItemData(c, insertEventCrf(c, eventToCancel, ss, 1, 1), 1, 1, KIND);

            ss = insertStudySubject(c, "PROV-SS", 1, 1);
            itemOfRemovedSubject = insertItemData(c, insertEventCrf(c, insertEvent(c, def, ss, 1), ss, 1, 1),
                    1, 1, KIND);

            // An event CRF removed with its items, to be restored on its own.
            ss = insertStudySubject(c, "PROV-EC", 1, 1);
            hiddenEventCrf = insertEventCrf(c, insertEvent(c, def, ss, 1), ss, 1, 7);
            itemOfHiddenEventCrf = insertItemData(c, hiddenEventCrf, 1, 7, KIND);

            // A removed definition, whose rows were auto-removed with it.
            int removedDef = insertDefinition(c, 1, "SE_PROV_GONE", 5);
            ss = insertStudySubject(c, "PROV-DEF", 1, 1);
            itemOfRemovedDefinition = insertItemData(c,
                    insertEventCrf(c, insertEvent(c, removedDef, ss, 7), ss, 1, 7), 1, 7, KIND);

            int lockDef = insertDefinition(c, 1, "SE_PROV_LOCK", 1);
            ss = insertStudySubject(c, "PROV-LOCK", 1, 1);
            itemOfLockedDefinition = insertItemData(c,
                    insertEventCrf(c, insertEvent(c, lockDef, ss, 1), ss, 1, 1), 1, 1, KIND);
        }
    }

    @AfterAll
    static void dropRuleContext() {
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(null);
    }

    @Test
    void cancellingAndRestoringAnEventKeepsTheProvenance() throws Exception {
        MockMvc events = ProductionMvc.standalone(
                        new EventsApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE), null))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        events.perform(delete("/api/v1/events/" + eventToCancel).session(session())
                        .contentType("application/json").content("{\"reasonCode\":\"PATIENT_NO_SHOW\"}"))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfCancelledEvent, 7);

        events.perform(post("/api/v1/events/" + eventToCancel + "/restore").session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfCancelledEvent, 1);
    }

    @Test
    void removingAndRestoringASubjectKeepsTheProvenance() throws Exception {
        MockMvc subjects = ProductionMvc.standalone(buildSubjectsController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        subjects.perform(post("/api/v1/subjects/PROV-SS/remove").session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfRemovedSubject, 7);

        subjects.perform(post("/api/v1/subjects/PROV-SS/restore").session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfRemovedSubject, 1);
    }

    @Test
    void restoringAnEventCrfKeepsTheProvenance() throws Exception {
        MockMvc eventCrfs = ProductionMvc.standalone(new EventCrfsApiController(
                        DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE),
                        Mockito.mock(CrfFileStorageService.class),
                        new EventCrfPresenceRegistry(),
                        new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        eventCrfs.perform(post("/api/v1/eventCrfs/" + hiddenEventCrf + "/restore").session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfHiddenEventCrf, 1);
    }

    @Test
    void restoringAnEventDefinitionKeepsTheProvenance() throws Exception {
        definitions().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_PROV_GONE/restore")
                        .session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfRemovedDefinition, 1);
    }

    @Test
    void lockingAndUnlockingAnEventDefinitionKeepsTheProvenance() throws Exception {
        definitions().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_PROV_LOCK/lock")
                        .session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfLockedDefinition, 6);

        definitions().perform(post("/api/v1/studies/S_DEFAULTS1/event-definitions/SE_PROV_LOCK/unlock")
                        .session(session()))
                .andExpect(status().is2xxSuccessful());
        assertItem(itemOfLockedDefinition, 1);
    }

    /* ------------------------------------------------------------------ */

    /** The item reached {@code status}, kept its value and provenance, and names who changed it. */
    private static void assertItem(int itemDataId, int status) throws SQLException {
        assertEquals(status, statusOf("item_data", "item_data_id", itemDataId));
        assertEquals(KIND, sourceKindOf(itemDataId));
        assertEquals(1, intQuery("SELECT count(*) FROM item_data WHERE item_data_id = " + itemDataId
                + " AND value = 'v' AND update_id = 1 AND date_updated IS NOT NULL"));
    }

    private static MockMvc definitions() {
        return ProductionMvc.standalone(new EventDefinitionsApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /** The system administrator, as a study director of the Default Study. */
    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        study.setStatus(Status.AVAILABLE);
        session.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        role.setStudyId(1);
        session.setAttribute("userRole", role);
        return session;
    }
}
