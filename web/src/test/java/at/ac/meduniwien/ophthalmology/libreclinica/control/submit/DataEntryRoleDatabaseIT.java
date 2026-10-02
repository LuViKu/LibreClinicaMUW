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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.DynamicsMetadataService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.InstantOnChangeService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.SimpleConditionalDisplayService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;

/**
 * Who may save a CRF section through the legacy initial and double data-entry
 * servlets, against the real schema. Both apply the role rule of administrative
 * editing and the table of contents ({@link SubmitDataServlet#maySubmitData}):
 * the coordinator, director, investigator and the two research-assistant roles
 * enter data, while a Monitor's request is refused before the event CRF is
 * loaded or created and leaves every row as it was. Double data entry also
 * refuses an event CRF outside the session's study tree.
 * <p>
 * Each test adds the event CRF it works on and deletes it again: one at
 * M-007's third visit (event 21, not scheduled, no CRF yet) in study 1, or one
 * at a new visit of subject EIAMD139 in study 102.
 */
class DataEntryRoleDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** M-007's "V3 Day 90": not scheduled, no event CRF. */
    private static final int SUBJECT = 7;
    private static final int EVENT = 21;
    private static final int DEFINITION_CRF = 3;
    /** Demographics v1.0 and its "Vitals" section: height (item 3), weight (4) and systolic BP (5). */
    private static final int VERSION = 1;
    private static final int VITALS = 2;
    /** EIAMD139 and its imaging-visit definition, in study 102. */
    private static final int OTHER_STUDY_SUBJECT = 102;
    private static final int OTHER_STUDY_DEFINITION = 10;

    private static final int AVAILABLE = 1;
    /** Initial data entry complete: the stage double data entry works on. */
    private static final int PENDING = 4;
    private static final int NOT_SCHEDULED = 2;
    /** An event CRF whose entry is complete: the stage administrative editing works on. */
    private static final int UNAVAILABLE = 2;
    private static final int DATA_ENTRY_STARTED = 3;

    private static final String REFUSAL = "/WEB-INF/jsp/menu.jsp";
    private static final String NO_DATA_ENTRY =
            "You cannot perform data entry on a CRF in this Study. Change your current Study or contact the Study Director.";

    private LegacyServletHarness harness;
    private ApplicationContext savedListenerContext;

    /**
     * A CRF uploaded through the legacy pages keeps each item outside a
     * repeating group in a group named "Ungrouped", and the data-entry servlets
     * read a section's items through it. The demo Demographics CRF was seeded
     * without one; add it.
     */
    @BeforeAll
    static void ungroupedItems() throws SQLException {
        if (queryInt("SELECT COUNT(*) FROM item_group_metadata WHERE crf_version_id = " + VERSION) > 0) {
            return;
        }
        int group = queryInt("INSERT INTO item_group (name, crf_id, status_id, date_created, owner_id, oc_oid)"
                + " SELECT 'Ungrouped', crf_id, 1, now(), 1, 'IG_DEMOGRAPHICS_UNGROUPED' FROM crf_version"
                + " WHERE crf_version_id = " + VERSION + " RETURNING item_group_id");
        update("INSERT INTO item_group_metadata (item_group_id, header, subheader, layout, repeat_number, repeat_max,"
                + " repeat_array, row_start_number, crf_version_id, item_id, ordinal, borders, show_group, repeating_group)"
                + " SELECT " + group + ", '', '', '', 1, 1, '', 0, crf_version_id, item_id,"
                + " ROW_NUMBER() OVER (ORDER BY section_id, ordinal), 0, true, false"
                + " FROM item_form_metadata WHERE crf_version_id = " + VERSION);
    }

    @BeforeEach
    void setUp() throws Exception {
        // Seed rows inserted by SQL lack what the legacy pages always set: an
        // event without a start date cannot be updated by StudyEventDAO.
        update("UPDATE study_event SET date_start = date_created WHERE study_event_id = " + EVENT + " AND date_start IS NULL");
        // StudyEventDAO.update notifies a rule listener that reads its rule sets
        // from the Spring context; give it one with none.
        GenericApplicationContext rules = new GenericApplicationContext();
        rules.registerBean("ruleSetDao", RuleSetDao.class, () -> mock(RuleSetDao.class));
        rules.refresh();
        savedListenerContext = listenerContext();
        new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE)).setApplicationContext(rules);
        // The section has no rules, conditional display or dynamics, so the
        // services behind those have nothing to do.
        harness = new LegacyServletHarness(DATA_SOURCE)
                .bean("securityManager", mock(SecurityManager.class))
                .bean("mailSender", mock(JavaMailSenderImpl.class))
                .bean("ruleSetService", mock(RuleSetServiceInterface.class))
                .bean("dynamicsMetadataService", mock(DynamicsMetadataService.class))
                .bean("simpleConditionalDisplayService", mock(SimpleConditionalDisplayService.class))
                .bean("instantOnChangeService", mock(InstantOnChangeService.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        field.set(null, savedListenerContext);
    }

    private static ApplicationContext listenerContext() throws ReflectiveOperationException {
        Field field = StudyEventBeanListener.class.getDeclaredField("cntxt");
        field.setAccessible(true);
        return (ApplicationContext) field.get(null);
    }

    // ---- a Monitor ------------------------------------------------------------------------

    @Test
    void aMonitorCannotSaveASectionInInitialDataEntry() throws Exception {
        int ec = addEventCrf(AVAILABLE);
        try {
            Outcome out = run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_monitor"),
                    saveVitals(ec));

            assertEquals(0, itemDataRows(ec), "nothing was saved");
            assertNull(updater(ec), "the event CRF is as it was");
            assertEquals(REFUSAL, out.forwardedUrl());
            assertTrue(out.pageMessages().contains(NO_DATA_ENTRY), String.valueOf(out.pageMessages()));
        } finally {
            removeEventCrf(ec);
        }
    }

    @Test
    void aMonitorCannotSaveASectionInDoubleDataEntry() throws Exception {
        int ec = addEventCrf(PENDING);
        try {
            Outcome out = run(new DoubleDataEntryServlet(), "POST", "/DoubleDataEntry", user("manual_monitor"),
                    saveVitals(ec));

            assertEquals(0, itemDataRows(ec), "nothing was saved");
            assertNull(updater(ec), "the event CRF is as it was");
            assertEquals(0, validator(ec), "no second entry was recorded");
            assertEquals(REFUSAL, out.forwardedUrl());
            assertTrue(out.pageMessages().contains(NO_DATA_ENTRY), String.valueOf(out.pageMessages()));
        } finally {
            removeEventCrf(ec);
        }
    }

    @Test
    void aMonitorCannotStartACrf() throws Exception {
        // The subject matrix starts a CRF with a link naming the event and the
        // CRF version; the servlet creates the event CRF while loading it.
        try {
            Outcome out = run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_monitor"),
                    startDemographics());

            assertEquals(0, eventCrfsOfEvent(), "no event CRF was created");
            assertEquals(NOT_SCHEDULED, eventStatus(), "the event is as it was");
            assertEquals(REFUSAL, out.forwardedUrl());
        } finally {
            removeEventCrfsOfEvent();
        }
    }

    // ---- the data-entry roles -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "coordinator", "director", "Investigator", "ra", "ra2" })
    void theDataEntryRolesSaveASectionInInitialDataEntry(String role) throws Exception {
        int ec = addEventCrf(AVAILABLE);
        update("UPDATE study_user_role SET role_name = '" + role + "' WHERE user_name = 'manual_crc' AND study_id = 1");
        try {
            Outcome out = run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_crc"),
                    saveVitals(ec));

            assertEquals("71.5", itemValue(ec, 4), "the weight was saved");
            assertEquals("125", itemValue(ec, 5), "the blood pressure was saved");
            assertNotEquals(REFUSAL, out.forwardedUrl());
        } finally {
            update("UPDATE study_user_role SET role_name = 'coordinator' WHERE user_name = 'manual_crc' AND study_id = 1");
            removeEventCrf(ec);
        }
    }

    @Test
    void aDataEntryRoleSavesASectionInDoubleDataEntry() throws Exception {
        int ec = addEventCrf(PENDING);
        try {
            UserAccountBean crc = user("manual_crc");
            Outcome out = run(new DoubleDataEntryServlet(), "POST", "/DoubleDataEntry", crc, saveVitals(ec));

            assertEquals("71.5", itemValue(ec, 4), "the weight was saved");
            assertEquals(crc.getId(), validator(ec), "the second entry is recorded");
            assertNotEquals(REFUSAL, out.forwardedUrl());
        } finally {
            removeEventCrf(ec);
        }
    }

    @Test
    void aDataEntryRoleStartsACrf() throws Exception {
        try {
            run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_crc"), startDemographics());

            assertEquals(1, eventCrfsOfEvent(), "the event CRF was created");
            assertEquals(DATA_ENTRY_STARTED, eventStatus());
        } finally {
            removeEventCrfsOfEvent();
        }
    }

    // ---- the study tree -------------------------------------------------------------------

    @Test
    void doubleDataEntryRefusesAnEventCrfOutsideTheCurrentStudy() throws Exception {
        // manual_crc enters data in study 1; this event CRF is study 102's.
        int event = queryInt("INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal,"
                + " date_start, owner_id, status_id, date_created, subject_event_status_id, start_time_flag, end_time_flag)"
                + " VALUES (" + OTHER_STUDY_DEFINITION + ", " + OTHER_STUDY_SUBJECT + ", 99, now(), 1, 1, now(), "
                + DATA_ENTRY_STARTED + ", false, false) RETURNING study_event_id");
        int ec = queryInt("INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, status_id, owner_id,"
                + " date_created, study_subject_id, electronic_signature_status, sdv_status)"
                + " VALUES (" + event + ", " + VERSION + ", 1, " + PENDING + ", 1, now(), " + OTHER_STUDY_SUBJECT
                + ", false, false) RETURNING event_crf_id");
        try {
            Outcome out = run(new DoubleDataEntryServlet(), "POST", "/DoubleDataEntry", user("manual_crc"),
                    saveVitals(ec));

            assertEquals(0, itemDataRows(ec), "nothing was saved");
            assertEquals(0, validator(ec), "no second entry was recorded");
            assertEquals("/MainMenu", out.forwardedUrl());
            assertTrue(out.pageMessages().contains("Required Event CRF does not belong to your Studies."),
                    String.valueOf(out.pageMessages()));
        } finally {
            update("DELETE FROM item_data WHERE event_crf_id = " + ec);
            update("DELETE FROM event_crf WHERE event_crf_id = " + ec);
            update("DELETE FROM study_event WHERE study_event_id = " + event);
        }
    }

    @Test
    void initialDataEntryRefusesAnEventCrfOfAnotherStudyTheUserIsMonitorIn() throws Exception {
        // manual_crc is coordinator in study 1, the session's study, and only
        // monitor in study 102, whose event CRF this is.
        int[] other = otherStudyEventCrf(AVAILABLE);
        update("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name)"
                + " VALUES ('monitor', 102, 1, 1, now(), 'manual_crc')");
        try {
            Outcome out = run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_crc"),
                    saveVitals(other[1]));

            assertEquals(0, itemDataRows(other[1]), "nothing was saved");
            assertEquals("/MainMenu", out.forwardedUrl());
            assertTrue(out.pageMessages().contains("Required Event CRF does not belong to your Studies."),
                    String.valueOf(out.pageMessages()));
        } finally {
            update("DELETE FROM study_user_role WHERE user_name = 'manual_crc' AND study_id = 102");
            removeOtherStudyEventCrf(other);
        }
    }

    @Test
    void initialDataEntryChecksTheEventCrfItSavesInto() throws Exception {
        // "ecId" names an event CRF of the user's; "eventCRFId", the one the
        // servlet loads and saves into, is study 102's.
        int own = addEventCrf(AVAILABLE);
        int[] other = otherStudyEventCrf(AVAILABLE);
        try {
            String[] params = saveVitals(other[1]);
            String[] withOwn = java.util.Arrays.copyOf(params, params.length + 2);
            withOwn[params.length] = "ecId";
            withOwn[params.length + 1] = String.valueOf(own);
            Outcome out = run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_crc"), withOwn);

            assertEquals(0, itemDataRows(other[1]), "nothing was saved");
            assertEquals(0, itemDataRows(own), "nor into the user's own");
            assertEquals("/MainMenu", out.forwardedUrl());
        } finally {
            removeOtherStudyEventCrf(other);
            removeEventCrf(own);
        }
    }

    @Test
    void administrativeEditingRefusesAnEventCrfOfAnotherStudyTheUserIsMonitorIn() throws Exception {
        int[] other = otherStudyEventCrf(UNAVAILABLE);
        update("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name)"
                + " VALUES ('monitor', 102, 1, 1, now(), 'manual_crc')");
        try {
            Outcome out = run(new AdministrativeEditingServlet(), "POST", "/AdministrativeEditing", user("manual_crc"),
                    saveVitals(other[1]));

            assertEquals(0, itemDataRows(other[1]), "nothing was saved");
            assertEquals("/MainMenu", out.forwardedUrl());
            assertTrue(out.pageMessages().contains("Required Event CRF does not belong to your Studies."),
                    String.valueOf(out.pageMessages()));
        } finally {
            update("DELETE FROM study_user_role WHERE user_name = 'manual_crc' AND study_id = 102");
            removeOtherStudyEventCrf(other);
        }
    }

    // ---- closed records -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "event CRF removed", "event CRF auto-removed", "event removed", "study subject removed",
            "study subject locked", "study subject signed", "subject removed", "study locked", "study frozen" })
    void nothingIsSavedIntoAClosedRecordInInitialDataEntry(String closed) throws Exception {
        int ec = addEventCrf(AVAILABLE);
        String reopen = close(closed, ec);
        try {
            Outcome out = run(new InitialDataEntryServlet(), "POST", "/InitialDataEntry", user("manual_crc"), saveVitals(ec));

            assertEquals(0, itemDataRows(ec), "nothing was saved");
            assertNull(updater(ec), "the event CRF is as it was");
            assertClosedRefusal(out);
        } finally {
            update(reopen);
            removeEventCrf(ec);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "event CRF removed", "event removed", "study subject locked", "study subject signed",
            "study locked" })
    void nothingIsSavedIntoAClosedRecordInDoubleDataEntry(String closed) throws Exception {
        int ec = addEventCrf(PENDING);
        String reopen = close(closed, ec);
        try {
            Outcome out = run(new DoubleDataEntryServlet(), "POST", "/DoubleDataEntry", user("manual_crc"), saveVitals(ec));

            assertEquals(0, itemDataRows(ec), "nothing was saved");
            assertEquals(0, validator(ec), "no second entry was recorded");
            assertClosedRefusal(out);
        } finally {
            update(reopen);
            removeEventCrf(ec);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "event removed", "study subject locked", "study subject signed", "study frozen" })
    // (A removed event CRF has no stage to edit in: administrative editing already stops it before the save.)
    void nothingIsSavedIntoAClosedRecordInAdministrativeEditing(String closed) throws Exception {
        int ec = addEventCrf(UNAVAILABLE);
        String reopen = close(closed, ec);
        try {
            Outcome out = run(new AdministrativeEditingServlet(), "POST", "/AdministrativeEditing", user("manual_crc"),
                    saveVitals(ec));

            assertEquals(0, itemDataRows(ec), "nothing was saved");
            assertNull(updater(ec), "the event CRF is as it was");
            assertClosedRefusal(out);
        } finally {
            update(reopen);
            removeEventCrf(ec);
        }
    }

    @Test
    void administrativeEditingStillSavesIntoAnOpenRecord() throws Exception {
        int ec = addEventCrf(UNAVAILABLE);
        try {
            Outcome out = run(new AdministrativeEditingServlet(), "POST", "/AdministrativeEditing", user("manual_crc"),
                    saveVitals(ec));

            assertEquals("71.5", itemValue(ec, 4), "the weight was saved");
            assertNotEquals("/ViewStudySubject", out.forwardedUrl());
        } finally {
            removeEventCrf(ec);
        }
    }

    private static void assertClosedRefusal(Outcome out) {
        assertEquals("/ViewStudySubject", out.forwardedUrl());
        assertTrue(out.pageMessages().stream().anyMatch(m -> m.contains("nothing can be saved")),
                String.valueOf(out.pageMessages()));
    }

    /** Closes the record the event CRF {@code ec} sits in; returns the SQL that opens it again. */
    private static String close(String closed, int ec) throws SQLException {
        String table;
        String key;
        int status;
        switch (closed) {
            case "event CRF removed" -> { table = "event_crf"; key = "event_crf_id = " + ec; status = 5; }
            case "event CRF auto-removed" -> { table = "event_crf"; key = "event_crf_id = " + ec; status = 7; }
            case "event removed" -> { table = "study_event"; key = "study_event_id = " + EVENT; status = 5; }
            case "study subject removed" -> { table = "study_subject"; key = "study_subject_id = " + SUBJECT; status = 5; }
            case "study subject locked" -> { table = "study_subject"; key = "study_subject_id = " + SUBJECT; status = 6; }
            case "study subject signed" -> { table = "study_subject"; key = "study_subject_id = " + SUBJECT; status = 8; }
            case "subject removed" -> {
                table = "subject";
                key = "subject_id = (SELECT subject_id FROM study_subject WHERE study_subject_id = " + SUBJECT + ")";
                status = 5;
            }
            case "study locked" -> { table = "study"; key = "study_id = 1"; status = 6; }
            case "study frozen" -> { table = "study"; key = "study_id = 1"; status = 9; }
            default -> throw new IllegalArgumentException(closed);
        }
        int before = queryInt("SELECT status_id FROM " + table + " WHERE " + key);
        update("UPDATE " + table + " SET status_id = " + status + " WHERE " + key);
        return "UPDATE " + table + " SET status_id = " + before + " WHERE " + key;
    }

    /** Guards the fixture: the rows the tests rely on exist and are as described. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(0, eventCrfsOfEvent(), "event 21 has no CRF yet");
        assertEquals(NOT_SCHEDULED, eventStatus());
        assertEquals(SUBJECT, queryInt("SELECT study_subject_id FROM study_event WHERE study_event_id = " + EVENT));
        assertEquals(3, queryInt("SELECT COUNT(*) FROM item_form_metadata WHERE section_id = " + VITALS));
        assertEquals(5, queryInt("SELECT COUNT(*) FROM item_group_metadata WHERE crf_version_id = " + VERSION
                + " AND repeating_group = false"));
        assertEquals(102, queryInt("SELECT study_id FROM study_subject WHERE study_subject_id = " + OTHER_STUDY_SUBJECT));
        assertEquals(102, queryInt("SELECT study_id FROM study_event_definition"
                + " WHERE study_event_definition_id = " + OTHER_STUDY_DEFINITION));
        assertEquals("monitor", queryString("SELECT role_name FROM study_user_role WHERE user_name = 'manual_monitor' AND study_id = 1"));
        assertEquals("coordinator", queryString("SELECT role_name FROM study_user_role WHERE user_name = 'manual_crc' AND study_id = 1"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_crc' AND study_id = 102"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Where a request was forwarded, and the page messages it set. */
    private record Outcome(String forwardedUrl, List<String> pageMessages) {
    }

    /** A save of the "Vitals" section: height 170, weight 71.5 and systolic BP 125. */
    private static String[] saveVitals(int eventCrfId) {
        return new String[] { "eventCRFId", String.valueOf(eventCrfId), "sectionId", String.valueOf(VITALS),
                "submitted", "1", "input3", "170", "input4", "71.5", "input5", "125" };
    }

    /** The subject matrix's request that starts the Demographics CRF at M-007's third visit. */
    private static String[] startDemographics() throws SQLException {
        int subjectId = queryInt("SELECT subject_id FROM study_subject WHERE study_subject_id = " + SUBJECT);
        return new String[] { "crfVersionId", String.valueOf(VERSION), "studyEventId", String.valueOf(EVENT),
                "eventDefinitionCRFId", String.valueOf(DEFINITION_CRF), "subjectId", String.valueOf(subjectId) };
    }

    /** A user with the roles login would load. */
    private static UserAccountBean user(String name) {
        UserAccountDAO dao = new UserAccountDAO(DATA_SOURCE);
        UserAccountBean ub = dao.findByUserName(name);
        for (StudyUserRoleBean role : dao.findAllRolesByUserName(name)) {
            ub.addRole(role);
        }
        ub.setPasswdTimestamp(new Date());
        return ub;
    }

    @SuppressWarnings("unchecked")
    private Outcome run(HttpServlet servlet, String method, String path, UserAccountBean user, String... params)
            throws Exception {
        MockHttpServletRequest req = harness.request(method, path, user);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        if (servlet instanceof AdministrativeEditingServlet) {
            // Without a forced reason for change, an edit saves with the form alone.
            ((StudyBean) Objects.requireNonNull(req.getSession()).getAttribute("study")).getStudyParameterConfig().setAdminForcedReasonForChange("false");
        }
        MockHttpServletResponse resp = harness.run(servlet, req);
        Object messages = req.getAttribute("pageMessages");
        return new Outcome(resp.getForwardedUrl(), messages == null ? List.of() : (List<String>) messages);
    }

    private static int addEventCrf(int status) throws SQLException {
        return queryInt("INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, status_id, owner_id,"
                + " date_created, study_subject_id, electronic_signature_status, sdv_status)"
                + " VALUES (" + EVENT + ", " + VERSION + ", 1, " + status + ", 1, now(), " + SUBJECT + ", false, false)"
                + " RETURNING event_crf_id");
    }

    /** @return {study event, event CRF}: a new visit of EIAMD139, in study 102, with a Demographics CRF */
    private static int[] otherStudyEventCrf(int status) throws SQLException {
        int event = queryInt("INSERT INTO study_event (study_event_definition_id, study_subject_id, sample_ordinal,"
                + " date_start, owner_id, status_id, date_created, subject_event_status_id, start_time_flag, end_time_flag)"
                + " VALUES (" + OTHER_STUDY_DEFINITION + ", " + OTHER_STUDY_SUBJECT + ", 99, now(), 1, 1, now(), "
                + DATA_ENTRY_STARTED + ", false, false) RETURNING study_event_id");
        int ec = queryInt("INSERT INTO event_crf (study_event_id, crf_version_id, completion_status_id, status_id, owner_id,"
                + " date_created, study_subject_id, electronic_signature_status, sdv_status)"
                + " VALUES (" + event + ", " + VERSION + ", 1, " + status + ", 1, now(), " + OTHER_STUDY_SUBJECT
                + ", false, false) RETURNING event_crf_id");
        return new int[] { event, ec };
    }

    private static void removeOtherStudyEventCrf(int[] other) throws SQLException {
        update("DELETE FROM item_data WHERE event_crf_id = " + other[1]);
        update("DELETE FROM event_crf WHERE event_crf_id = " + other[1]);
        update("DELETE FROM study_event WHERE study_event_id = " + other[0]);
    }

    private static void removeEventCrf(int eventCrfId) throws SQLException {
        update("DELETE FROM item_data WHERE event_crf_id = " + eventCrfId);
        update("DELETE FROM event_crf WHERE event_crf_id = " + eventCrfId);
        update("UPDATE study_event SET subject_event_status_id = " + NOT_SCHEDULED + " WHERE study_event_id = " + EVENT);
    }

    private static void removeEventCrfsOfEvent() throws SQLException {
        update("DELETE FROM item_data WHERE event_crf_id IN (SELECT event_crf_id FROM event_crf WHERE study_event_id = "
                + EVENT + ")");
        update("DELETE FROM event_crf WHERE study_event_id = " + EVENT);
        update("UPDATE study_event SET subject_event_status_id = " + NOT_SCHEDULED + " WHERE study_event_id = " + EVENT);
    }

    private static int eventCrfsOfEvent() throws SQLException {
        return queryInt("SELECT COUNT(*) FROM event_crf WHERE study_event_id = " + EVENT);
    }

    private static int eventStatus() throws SQLException {
        return queryInt("SELECT subject_event_status_id FROM study_event WHERE study_event_id = " + EVENT);
    }

    private static int itemDataRows(int eventCrfId) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM item_data WHERE event_crf_id = " + eventCrfId);
    }

    private static String itemValue(int eventCrfId, int itemId) throws SQLException {
        return queryString("SELECT value FROM item_data WHERE event_crf_id = " + eventCrfId + " AND item_id = " + itemId);
    }

    private static Integer updater(int eventCrfId) throws SQLException {
        String value = queryString("SELECT update_id FROM event_crf WHERE event_crf_id = " + eventCrfId);
        return value == null ? null : Integer.valueOf(value);
    }

    private static int validator(int eventCrfId) throws SQLException {
        return queryInt("SELECT COALESCE(validator_id, 0) FROM event_crf WHERE event_crf_id = " + eventCrfId);
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "no row for " + sql);
            return rs.getInt(1);
        }
    }

    /** The first column of the first row; null when there is no row or the value is null. */
    private static String queryString(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void update(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
