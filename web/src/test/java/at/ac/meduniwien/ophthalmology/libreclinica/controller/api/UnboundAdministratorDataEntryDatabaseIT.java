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

import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.insertStudySubject;
import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.insertUser;
import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.intQuery;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A system administrator who opens a study they hold no role in (the
 * study picker allows it, for the administration screens) gets the
 * legacy "invalid" session role, and the clinical data writes refuse it,
 * as {@code SubmitDataServlet.maySubmitData} does in the servlets:
 * enrolling a subject, scheduling an event, starting a CRF, saving its
 * items and marking it complete.
 */
class UnboundAdministratorDataEntryDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String ADMIN = "entry_unbound_admin";
    /** M-004's V1 CRF in the demo seed: started, not complete. */
    private static final int OPEN_EVENT_CRF = 9;

    private static int subjectWithoutEvents;

    private MockHttpSession session;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            insertUser(c, ADMIN, 1);
            subjectWithoutEvents = insertStudySubject(c, "UNBOUND-S", 1, 1);
            // user_type_id 1: a system administrator, with no study binding.
            s.executeUpdate("UPDATE user_account SET user_type_id = 1 WHERE user_name = '" + ADMIN + "'");
        }
    }

    /** The session as the study picker leaves it for this administrator on the Default Study. */
    @BeforeEach
    void openTheDefaultStudy() throws Exception {
        UserAccountBean ub = new UserAccountDAO(DATA_SOURCE).findByUserName(ADMIN);
        session = new MockHttpSession();
        session.setAttribute("userBean", ub);
        ProductionMvc.standalone(new MeApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(post("/api/v1/me/activeStudy").contentType("application/json")
                        .content("{\"oid\":\"S_DEFAULTS1\"}").session(session))
                .andExpect(status().isOk());
        assertEquals(Role.INVALID, ((StudyUserRoleBean) Objects.requireNonNull(session.getAttribute("userRole"))).getRole());
    }

    @Test
    void enrollingASubjectIsRefused() throws Exception {
        refused(subjects().perform(post("/api/v1/subjects").contentType("application/json")
                .content("{\"id\":\"UNBOUND-1\",\"gender\":\"f\",\"yearOfBirth\":1970,"
                        + "\"enrolledOn\":\"2026-01-01\"}")
                .session(session)));
        assertEquals(0, intQuery("SELECT count(*) FROM study_subject WHERE label = 'UNBOUND-1'"));
    }

    @Test
    void schedulingAnEventIsRefused() throws Exception {
        refused(events().perform(post("/api/v1/events").contentType("application/json")
                .content("{\"subjectId\":\"UNBOUND-S\",\"eventDefinitionOid\":\"SE_V1_INCLUSION\","
                        + "\"dateStarted\":\"2026-01-01\"}")
                .session(session)));
        assertEquals(0, intQuery("SELECT count(*) FROM study_event WHERE study_subject_id = "
                + subjectWithoutEvents));
    }

    @Test
    void startingACrfIsRefused() throws Exception {
        // Event 11 has no event CRF on its definition's CRF yet (EventCrfStartApiControllerDatabaseIT).
        refused(events().perform(post("/api/v1/events/11/crfs/2:start").contentType("application/json")
                .content("{}").session(session)));
        assertEquals(0, intQuery("SELECT count(*) FROM event_crf WHERE study_event_id = 11"));
    }

    @Test
    void savingItemsIsRefused() throws Exception {
        refused(eventCrfs().perform(post("/api/v1/eventCrfs/" + OPEN_EVENT_CRF + "/items")
                .contentType("application/json").content("{\"values\":{\"I_HEIGHT_CM\":\"170\"}}")
                .session(session)));
        assertEquals(0, intQuery("SELECT count(*) FROM item_data WHERE event_crf_id = " + OPEN_EVENT_CRF
                + " AND value = '170'"));
    }

    @Test
    void markingACrfCompleteIsRefused() throws Exception {
        refused(eventCrfs().perform(post("/api/v1/eventCrfs/" + OPEN_EVENT_CRF + "/markComplete")
                .session(session)));
        assertEquals(1, intQuery("SELECT count(*) FROM event_crf WHERE event_crf_id = " + OPEN_EVENT_CRF
                + " AND date_completed IS NULL"));
    }

    /* ------------------------------------------------------------------ */

    private static void refused(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("no role in this study")));
    }

    private static MockMvc subjects() {
        return ProductionMvc.standalone(new SubjectsApiController(DATA_SOURCE,
                        Mockito.mock(SecurityManager.class), new SiteVisibilityFilter(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MockMvc events() {
        return ProductionMvc.standalone(new EventsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE), new VisitIntervalCalculator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MockMvc eventCrfs() {
        return ProductionMvc.standalone(new EventCrfsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE),
                        Mockito.mock(CrfFileStorageService.class),
                        new EventCrfPresenceRegistry(),
                        new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
}
