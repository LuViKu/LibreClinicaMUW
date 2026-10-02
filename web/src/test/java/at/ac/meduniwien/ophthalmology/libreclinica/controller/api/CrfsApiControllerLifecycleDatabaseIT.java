/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.StudyEventBeanListener;
import at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling.VisitIntervalCalculator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * CRF library lifecycle against a real schema: removing a CRF or a version
 * takes the rows below it, restoring brings them back at the status they had
 * (legacy {@code RemoveCRFServlet} / {@code RestoreCRFServlet} /
 * {@code RemoveCRFVersionServlet} / {@code RestoreCRFVersionServlet}, see
 * {@link CrfLifecycleCascade}); locking or removing a version moves the
 * defaults that point at it; editing a CRF's name and description; and the
 * CRF view with its item table and the studies using it. Each test builds its
 * own CRF.
 */
class CrfsApiControllerLifecycleDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final int STUDY = CrfLibraryFixtures.STUDY_ID;
    private static final int SED = CrfLibraryFixtures.SED_ID;

    private static CrfLibraryFixtures fx;
    private static int dmId;
    private static int crcId;
    private static int invId;
    private static int otherDmId;
    private static int adminId;

    @BeforeAll
    static void users() throws Exception {
        fx = new CrfLibraryFixtures(DATA_SOURCE);
        dmId = fx.user("crfit-dm", false);
        fx.role("crfit-dm", CrfLibraryFixtures.STUDY_ID, "director");
        crcId = fx.user("crfit-crc", false);
        fx.role("crfit-crc", CrfLibraryFixtures.STUDY_ID, "coordinator");
        invId = fx.user("crfit-inv", false);
        fx.role("crfit-inv", CrfLibraryFixtures.STUDY_ID, "Investigator");
        otherDmId = fx.user("crfit-dm2", false);
        fx.role("crfit-dm2", CrfLibraryFixtures.STUDY_ID, "director");
        adminId = fx.user("crfit-admin", true);
    }

    private MockMvc mvc() {
        CrfsApiController controller = new CrfsApiController(
                DATA_SOURCE,
                Mockito.mock(CrfSpreadsheetParserService.class),
                new CrfJsonToWorkbookAdapter(),
                new CrfJsonValidator(),
                new CrfVersionMigrationService(DATA_SOURCE));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMvc of(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMvc eventCrfs() {
        return of(new EventCrfsApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE),
                Mockito.mock(CrfFileStorageService.class), new EventCrfPresenceRegistry(),
                new RetinalResultItemDataPopulator(DATA_SOURCE)));
    }

    private static MockMvc events() {
        return of(new EventsApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE),
                new VisitIntervalCalculator(DATA_SOURCE)));
    }

    private MockMvc subjects() {
        return of(buildSubjectsController());
    }

    private static MockMvc eventDefinitions() {
        return of(new EventDefinitionsApiController(DATA_SOURCE));
    }

    private static MockHttpSession dm() {
        return CrfLibraryFixtures.session(dmId, "crfit-dm", false);
    }

    /** The Data Manager with study 1 as the session's current role, as the subject and event screens read it. */
    private static MockHttpSession dmInStudy() {
        return CrfLibraryFixtures.session(dmId, "crfit-dm", false, Role.STUDYDIRECTOR);
    }

    private static MockHttpSession crc() {
        return CrfLibraryFixtures.session(crcId, "crfit-crc", false);
    }

    private static MockHttpSession investigator() {
        return CrfLibraryFixtures.session(invId, "crfit-inv", false);
    }

    private static MockHttpSession otherDm() {
        return CrfLibraryFixtures.session(otherDmId, "crfit-dm2", false);
    }

    private static MockHttpSession admin() {
        return CrfLibraryFixtures.session(adminId, "crfit-admin", true);
    }

    private static String tag() {
        return "LC" + SEQ.incrementAndGet();
    }

    /** Audit rows of {@code typeId} on event CRF {@code ec} from {@code oldValue} to {@code newValue}. */
    private static int eventCrfAudit(int typeId, int ec, String oldValue, String newValue) throws Exception {
        return fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ?"
                + " AND audit_table = 'event_crf' AND entity_id = ? AND event_crf_id = ? AND entity_name = 'Status'"
                + " AND old_value = ? AND new_value = ?", typeId, ec, ec, oldValue, newValue);
    }

    private static int oldStatus(String table, int id) throws Exception {
        return fx.intValue("SELECT COALESCE(old_status_id, -1) FROM " + table + " WHERE " + table + "_id = ?", id);
    }

    /* ------------------------------------------------------------------ */
    /* CRF disable / restore                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void disableTakesTheRowsBelowTheCrfAndRestoreBringsThemBackAsTheyWere() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 6);      // locked (archived)
        int v3 = fx.version(crf, "v3", "F_" + t + "_V3", 5);      // removed on its own
        int s1 = fx.section(v1, "S1", 1);
        int s2 = fx.section(v2, "S2", 1);
        int s3 = fx.section(v3, "S3", 1);
        int edc = fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, CrfLibraryFixtures.STUDY_ID, crf, v1, null);
        int item = fx.item(t + "_A", 5);
        int subjA = fx.subject(t + "-A", CrfLibraryFixtures.STUDY_ID, 1);
        int ecA = fx.eventCrf(fx.event(subjA, CrfLibraryFixtures.SED_ID, 4, 1), subjA, v1, 1, false, true, false);
        int idA = fx.itemData(ecA, item, "a", 1);
        int subjB = fx.subject(t + "-B", CrfLibraryFixtures.STUDY_ID, 1);
        int evB = fx.event(subjB, CrfLibraryFixtures.SED_ID, 8, 1);
        int ecB = fx.eventCrf(evB, subjB, v2, 8, true, true, true);
        int idB = fx.itemData(ecB, item, "b", 1);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));

        assertThat(fx.status("crf", crf)).isEqualTo(5);
        assertThat(fx.status("crf_version", v1)).isEqualTo(7);
        assertThat(fx.status("crf_version", v2)).isEqualTo(7);
        assertThat(fx.status("crf_version", v3)).as("removed on its own: untouched").isEqualTo(5);
        assertThat(fx.status("section", s1)).isEqualTo(7);
        assertThat(fx.status("section", s2)).isEqualTo(7);
        assertThat(fx.status("section", s3)).as("section of a removed version: untouched").isEqualTo(1);
        assertThat(fx.status("event_definition_crf", edc)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecB)).isEqualTo(7);
        assertThat(fx.intValue("SELECT old_status_id FROM event_crf WHERE event_crf_id = ?", ecB)).isEqualTo(8);
        assertThat(fx.status("item_data", idA)).isEqualTo(7);
        assertThat(fx.status("item_data", idB)).isEqualTo(7);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF, ecA, "1", "7")).isEqualTo(1);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF, ecB, "8", "7"))
                .as("each event CRF the removal took is in its study's audit log").isEqualTo(1);
        assertThat(fx.intValue("SELECT study_event_id FROM audit_log_event WHERE audit_log_event_type_id = ?"
                + " AND entity_id = ?", AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF, ecB)).isEqualTo(evB);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("available"));

        assertThat(fx.status("crf", crf)).isEqualTo(1);
        assertThat(fx.status("crf_version", v1)).isEqualTo(1);
        assertThat(fx.status("crf_version", v2)).as("comes back locked").isEqualTo(6);
        assertThat(fx.status("crf_version", v3)).isEqualTo(5);
        assertThat(fx.status("section", s1)).isEqualTo(1);
        assertThat(fx.status("section", s2)).isEqualTo(1);
        assertThat(fx.status("event_definition_crf", edc)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecB)).as("comes back signed").isEqualTo(8);
        assertThat(fx.status("item_data", idA)).isEqualTo(1);
        assertThat(fx.status("item_data", idB)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 76"
                + " AND entity_id = ? AND new_value = 'auto-removed'", v2)).isEqualTo(1);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_RESTORED, ecA, "7", "1")).isEqualTo(1);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_RESTORED, ecB, "7", "8")).isEqualTo(1);
    }

    @Test
    void restoreLeavesTheRowsAnotherRemovalTookForThatRemovalsRestore() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int subjA = fx.subject(t + "-A", CrfLibraryFixtures.STUDY_ID, 1);
        int ecA = fx.eventCrf(fx.event(subjA, CrfLibraryFixtures.SED_ID, 4, 1), subjA, v1, 1, false, true, false);
        // A removed subject: its event and event CRF were auto-removed with it.
        int subjR = fx.subject(t + "-R", CrfLibraryFixtures.STUDY_ID, 5);
        int ecR = fx.eventCrf(fx.event(subjR, CrfLibraryFixtures.SED_ID, 4, 7), subjR, v1, 7, false, true, false);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecR)).as("belongs to the subject's own restore").isEqualTo(7);
    }

    @Test
    void aSignedEventCrfOnAVersionRemovedOnItsOwnComesBackWithItsCrf() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int item = fx.item(t + "_A", 5);
        int subjA = fx.subject(t + "-A", STUDY, 1);
        int ecA = fx.eventCrf(fx.event(subjA, SED, 4, 1), subjA, v1, 1, false, true, false);
        int idA = fx.itemData(ecA, item, "a", 1);
        // Signed (SPA subject signing marks every CRF of the subject signed).
        int subjS = fx.subject(t + "-S", STUDY, 8);
        int ecS = fx.eventCrf(fx.event(subjS, SED, 8, 1), subjS, v1, 8, true, true, true);
        int idS = fx.itemData(ecS, item, "s", 1);

        mvc().perform(post(base + "/versions/F_" + t + "_V1/disable").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecS)).as("a version's removal leaves a signed event CRF").isEqualTo(8);

        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecS)).as("the CRF's removal takes it").isEqualTo(7);

        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("crf_version", v1)).as("removed on its own: stays removed").isEqualTo(5);
        assertThat(fx.status("event_crf", ecS)).as("what the CRF's removal took comes back").isEqualTo(8);
        assertThat(fx.status("item_data", idS)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecA)).as("the version's removal took it").isEqualTo(7);
        assertThat(fx.status("item_data", idA)).isEqualTo(7);

        mvc().perform(post(base + "/versions/F_" + t + "_V1/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).isEqualTo(1);
        assertThat(fx.status("item_data", idA)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecS)).isEqualTo(8);
    }

    @Test
    void rowsAnOlderScreenRemovedComeBackAvailableWhateverTheyRecorded() throws Exception {
        String t = tag();
        // As RemoveCRFServlet leaves a CRF, and every CRF removed before this
        // code: removed, its versions and event CRFs auto-removed, no audit
        // rows, and old_status_id holding whatever an earlier writer left.
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        fx.execute("UPDATE crf SET status_id = 5 WHERE crf_id = ?", crf);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 7);
        int item = fx.item(t + "_A", 5);
        int subjA = fx.subject(t + "-A", STUDY, 1);
        int ecA = fx.eventCrf(fx.event(subjA, SED, 4, 1), subjA, v1, 7, false, true, false);
        fx.execute("UPDATE event_crf SET old_status_id = 11 WHERE event_crf_id = ?", ecA);   // DeleteEventCRFServlet
        int idA = fx.itemData(ecA, item, "a", 7);
        fx.execute("UPDATE item_data SET old_status_id = 6 WHERE item_data_id = ?", idA);
        int subjB = fx.subject(t + "-B", STUDY, 1);
        int ecB = fx.eventCrf(fx.event(subjB, SED, 4, 1), subjB, v1, 7, false, true, false);
        fx.execute("UPDATE event_crf SET old_status_id = 8 WHERE event_crf_id = ?", ecB);    // a signing long undone

        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm())).andExpect(status().isOk());

        assertThat(fx.status("crf_version", v1)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecA)).as("not reset").isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 40"
                + " AND entity_id = ?", ecA)).as("no 'CRF deleted' row").isZero();
        assertThat(fx.status("item_data", idA)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecB)).as("not signed again").isEqualTo(1);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_RESTORED, ecB, "7", "1")).isEqualTo(1);
    }

    @Test
    void aVersionComesBackAtTheStatusItsLatestRemovalRecorded() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);

        mvc().perform(post(base + "/versions/F_" + t + "_V2/lock").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("crf_version", v2)).as("removed while locked").isEqualTo(6);

        mvc().perform(post(base + "/versions/F_" + t + "_V2/unlock").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("crf_version", v2)).as("unlocked before this removal").isEqualTo(1);

        // Locked, removed and restored again, then unlocked; then removed by
        // the older screen, which writes no audit row.
        mvc().perform(post(base + "/versions/F_" + t + "_V2/lock").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/versions/F_" + t + "_V2/unlock").session(dm())).andExpect(status().isOk());
        fx.execute("UPDATE crf SET status_id = 5 WHERE crf_id = ?", crf);
        fx.execute("UPDATE crf_version SET status_id = 7 WHERE crf_id = ?", crf);

        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("crf_version", v2)).as("available when the older screen removed it").isEqualTo(1);
    }

    @Test
    void anEventCrfWhoseAssignmentIsRemovedStaysRemoved() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        fx.version(crf, "v2", "F_" + t + "_V2", 1);
        // As RemoveCRFFromDefinition and UpdateEventDefinition leave it: the
        // assignment removed, its event CRFs auto-removed with their status
        // recorded, the subject and the event untouched.
        int edcGone = fx.eventDefinitionCrf(SED, STUDY, crf, v1, null);
        fx.execute("UPDATE event_definition_crf SET status_id = 5 WHERE event_definition_crf_id = ?", edcGone);
        int item = fx.item(t + "_A", 5);
        int subjA = fx.subject(t + "-A", STUDY, 1);
        int ecA = fx.eventCrf(fx.event(subjA, SED, 4, 1), subjA, v1, 7, false, true, false);
        fx.execute("UPDATE event_crf SET old_status_id = 1 WHERE event_crf_id = ?", ecA);
        int idA = fx.itemData(ecA, item, "a", 7);
        fx.execute("UPDATE item_data SET old_status_id = 1 WHERE item_data_id = ?", idA);
        fx.eventDefinitionCrf(CrfLibraryFixtures.SED2_ID, STUDY, crf, v1, null);
        int subjB = fx.subject(t + "-B", STUDY, 1);
        int ecB = fx.eventCrf(fx.event(subjB, CrfLibraryFixtures.SED2_ID, 4, 1), subjB, v1, 1, false, true, false);

        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_definition_crf", edcGone)).isEqualTo(5);
        assertThat(fx.status("event_crf", ecA)).as("its assignment is removed").isEqualTo(7);
        assertThat(fx.status("item_data", idA)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecB)).isEqualTo(1);

        mvc().perform(post(base + "/versions/F_" + t + "_V1/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/versions/F_" + t + "_V1/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).as("nor with its version").isEqualTo(7);
        assertThat(fx.status("event_crf", ecB)).isEqualTo(1);
    }

    @Test
    void rowsRemovedBeforeTheCrfKeepWhatTheyRecorded() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int itemA = fx.item(t + "_A", 5);
        int itemB = fx.item(t + "_B", 5);
        // As RemoveEventCRFServlet leaves it: removed, its values auto-removed.
        int subjD = fx.subject(t + "-D", STUDY, 1);
        int ecD = fx.eventCrf(fx.event(subjD, SED, 4, 1), subjD, v1, 5, false, true, false);
        int idD = fx.itemData(ecD, itemA, "d", 7);
        // A live event CRF with one value removed on its own (a deleted repeating row).
        int subjA = fx.subject(t + "-A", STUDY, 1);
        int ecA = fx.eventCrf(fx.event(subjA, SED, 4, 1), subjA, v1, 1, false, true, false);
        int idLive = fx.itemData(ecA, itemA, "a", 1);
        int idGone = fx.itemData(ecA, itemB, "x", 5);
        int ecDRecorded = oldStatus("event_crf", ecD);
        int idDRecorded = oldStatus("item_data", idD);
        int idGoneRecorded = oldStatus("item_data", idGone);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecD)).isEqualTo(5);
        assertThat(oldStatus("event_crf", ecD)).as("what it recorded is untouched").isEqualTo(ecDRecorded);
        assertThat(fx.status("item_data", idD)).isEqualTo(7);
        assertThat(oldStatus("item_data", idD)).isEqualTo(idDRecorded);
        assertThat(fx.status("item_data", idGone)).isEqualTo(5);
        assertThat(oldStatus("item_data", idGone)).isEqualTo(idGoneRecorded);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);
        assertThat(fx.status("item_data", idLive)).isEqualTo(7);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecD)).as("removed before the CRF: stays removed").isEqualTo(5);
        assertThat(fx.status("item_data", idD)).isEqualTo(7);
        assertThat(fx.status("item_data", idGone)).isEqualTo(5);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(1);
        assertThat(fx.status("item_data", idLive)).isEqualTo(1);
    }

    @Test
    void aRemovedEventEventDefinitionOrSiteKeepsItsRowsRemoved() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        // As RemoveStudyEventServlet leaves it: the event removed, its event
        // CRFs auto-removed, the subject available.
        int subjE = fx.subject(t + "-E", STUDY, 1);
        int ecE = fx.eventCrf(fx.event(subjE, SED, 4, 5), subjE, v1, 7, false, true, false);
        // As RemoveEventDefinitionServlet leaves it: the definition removed,
        // its assignments auto-removed.
        int sedGone = fx.eventDefinition(STUDY, "Removed " + t, "SE_R" + t, 5);
        int edcOfSed = fx.eventDefinitionCrf(sedGone, STUDY, crf, v1, null);
        fx.execute("UPDATE event_definition_crf SET status_id = 7 WHERE event_definition_crf_id = ?", edcOfSed);
        // A removed site with its own assignment, auto-removed with it.
        int site = fx.site("S_" + t, "Site " + t);
        fx.execute("UPDATE study SET status_id = 5 WHERE study_id = ?", site);
        int edcOfSite = fx.eventDefinitionCrf(SED, site, crf, v1, null);
        fx.execute("UPDATE event_definition_crf SET status_id = 7 WHERE event_definition_crf_id = ?", edcOfSite);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm())).andExpect(status().isOk());

        assertThat(fx.status("event_crf", ecE)).as("its event is removed").isEqualTo(7);
        assertThat(fx.status("event_definition_crf", edcOfSed)).as("its event definition is removed").isEqualTo(7);
        assertThat(fx.status("event_definition_crf", edcOfSite)).as("its site is removed").isEqualTo(7);
    }

    @Test
    void anAssignmentOfALockedEventDefinitionComesBackLocked() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int sedLocked = fx.eventDefinition(STUDY, "Locked " + t, "SE_L" + t, 6);
        int edcLocked = fx.eventDefinitionCrf(sedLocked, STUDY, crf, v1, null);
        fx.execute("UPDATE event_definition_crf SET status_id = 6 WHERE event_definition_crf_id = ?", edcLocked);
        int sedLater = fx.eventDefinition(STUDY, "Locked later " + t, "SE_K" + t, 1);
        int edcLater = fx.eventDefinitionCrf(sedLater, STUDY, crf, v1, null);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_definition_crf", edcLocked)).isEqualTo(7);
        assertThat(fx.status("event_definition_crf", edcLater)).isEqualTo(7);
        // Locked while the CRF was removed; the lock skips removed assignments.
        fx.execute("UPDATE study_event_definition SET status_id = 6 WHERE study_event_definition_id = ?", sedLater);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_definition_crf", edcLocked)).isEqualTo(6);
        assertThat(fx.status("event_definition_crf", edcLater)).isEqualTo(6);
    }

    @Test
    void anEventCrfItsCrfOrVersionHoldsIsNotRestoredOnItsOwn() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int subjA = fx.subject(t + "-A", STUDY, 1);
        int ecA = fx.eventCrf(fx.event(subjA, SED, 4, 1), subjA, v1, 1, false, true, false);
        int subjS = fx.subject(t + "-S", STUDY, 8);
        int ecS = fx.eventCrf(fx.event(subjS, SED, 8, 1), subjS, v1, 8, true, true, true);

        mvc().perform(post(base + "/versions/F_" + t + "_V1/disable").session(dm())).andExpect(status().isOk());
        eventCrfs().perform(post("/api/v1/eventCrfs/" + ecA + "/restore").session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("version")));
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);

        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());
        eventCrfs().perform(post("/api/v1/eventCrfs/" + ecS + "/restore").session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("CRF 'CRF " + t + "' is removed")));
        assertThat(fx.status("event_crf", ecS)).as("not made available").isEqualTo(7);

        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecS)).isEqualTo(8);
        mvc().perform(post(base + "/versions/F_" + t + "_V1/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).isEqualTo(1);
    }

    @Test
    void theRestoreOfASubjectAnEventOrAnEventDefinitionLeavesWhatTheCrfsRemovalHolds() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int subjA = fx.subject(t + "-A", STUDY, 1);
        int ecA = fx.eventCrf(fx.event(subjA, SED, 4, 1), subjA, v1, 8, false, true, true);
        int subjB = fx.subject(t + "-B", STUDY, 1);
        int evB = fx.event(subjB, SED, 4, 1);
        int ecB = fx.eventCrf(evB, subjB, v1, 8, false, true, true);
        int sed = fx.eventDefinition(STUDY, "Visit " + t, "SE_V" + t, 1);
        int edc = fx.eventDefinitionCrf(sed, STUDY, crf, v1, null);
        int subjC = fx.subject(t + "-C", STUDY, 1);
        int evC = fx.event(subjC, sed, 4, 1);
        int ecC = fx.eventCrf(evC, subjC, v1, 2, false, true, false);

        mvc().perform(post(base + "/disable").session(dm())).andExpect(status().isOk());

        // StudyEventDAO.update runs the event rules through the Spring
        // context; there are none here.
        ApplicationContext rules = Mockito.mock(ApplicationContext.class);
        Mockito.when(rules.getBean("ruleSetDao", RuleSetDao.class)).thenReturn(Mockito.mock(RuleSetDao.class));
        StudyEventBeanListener listener = new StudyEventBeanListener(new StudyEventDAO(DATA_SOURCE));
        listener.setApplicationContext(rules);
        try {
            restoresLeaveWhatTheCrfsRemovalHolds(t, base, subjA, ecA, evB, ecB, sed, edc, evC, ecC);
        } finally {
            listener.setApplicationContext(null);
        }
    }

    private void restoresLeaveWhatTheCrfsRemovalHolds(String t, String base, int subjA, int ecA, int evB, int ecB,
                                                      int sed, int edc, int evC, int ecC) throws Exception {
        subjects().perform(post("/api/v1/subjects/" + t + "-A/remove").session(dmInStudy()))
                .andExpect(status().isOk());
        subjects().perform(post("/api/v1/subjects/" + t + "-A/restore").session(dmInStudy()))
                .andExpect(status().isOk());
        assertThat(fx.status("study_subject", subjA)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecA)).as("the subject's restore leaves it to the CRF's").isEqualTo(7);

        // As the SPA's cancel leaves an event.
        fx.execute("UPDATE study_event SET status_id = 5 WHERE study_event_id = ?", evB);
        events().perform(post("/api/v1/events/" + evB + "/restore").session(dmInStudy()))
                .andExpect(status().isOk());
        assertThat(fx.status("study_event", evB)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecB)).as("the event's restore leaves it to the CRF's").isEqualTo(7);

        // As RemoveEventDefinitionServlet leaves a definition and its events.
        fx.execute("UPDATE study_event_definition SET status_id = 5 WHERE study_event_definition_id = ?", sed);
        fx.execute("UPDATE study_event SET status_id = 7 WHERE study_event_id = ?", evC);
        eventDefinitions().perform(post("/api/v1/studies/" + CrfLibraryFixtures.STUDY_OID
                        + "/event-definitions/SE_V" + t + "/restore").session(admin()))
                .andExpect(status().isOk());
        assertThat(fx.status("study_event", evC)).isEqualTo(1);
        assertThat(fx.status("event_definition_crf", edc)).as("its CRF is removed").isEqualTo(7);
        assertThat(fx.status("event_crf", ecC)).isEqualTo(7);

        mvc().perform(post(base + "/restore").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecA)).isEqualTo(8);
        assertThat(fx.status("event_crf", ecB)).isEqualTo(8);
        assertThat(fx.status("event_crf", ecC)).isEqualTo(2);
        assertThat(fx.status("event_definition_crf", edc)).isEqualTo(1);
    }

    @Test
    void aRemovalThatChangesAnotherStudysDataNeedsItsDataManagerAndAStudyThatTakesChanges() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int other = fx.topStudy("S_O" + t, "Other " + t, 1);
        int sedOther = fx.eventDefinition(other, "Visit " + t, "SE_O" + t, 1);
        int subjO = fx.subject(t + "-O", other, 1);
        int ecO = fx.eventCrf(fx.event(subjO, sedOther, 4, 1), subjO, v1, 1, false, true, false);

        mvc().perform(post(base + "/disable").session(dm()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("'Other " + t + "' (1)")));
        mvc().perform(post(base + "/versions/F_" + t + "_V1/disable").session(crc()))
                .andExpect(status().isForbidden());
        assertThat(fx.status("crf", crf)).as("nothing changed").isEqualTo(1);
        assertThat(fx.status("crf_version", v1)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecO)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ?"
                + " AND entity_id = ?", AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF, ecO)).isZero();

        mvc().perform(post(base + "/disable").session(admin())).andExpect(status().isOk());
        assertThat(fx.status("event_crf", ecO)).isEqualTo(7);

        fx.execute("UPDATE study SET status_id = 6 WHERE study_id = ?", other);    // locked since
        mvc().perform(post(base + "/restore").session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("locked")));
        assertThat(fx.status("crf", crf)).isEqualTo(5);
        assertThat(fx.status("crf_version", v1)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecO)).isEqualTo(7);
    }

    @Test
    void aFailureAfterTheCascadeChangesNothing() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int s1 = fx.section(v1, "S1", 1);
        int edc = fx.eventDefinitionCrf(SED, STUDY, crf, v1, null);
        int item = fx.item(t + "_A", 5);
        int subj = fx.subject(t + "-A", STUDY, 8);
        int ec = fx.eventCrf(fx.event(subj, SED, 8, 1), subj, v1, 8, true, true, true);
        int id = fx.itemData(ec, item, "a", 1);
        // The last write of the removal is the versions' lifecycle audit row.
        String fn = "crfit_fail_" + t.toLowerCase();
        fx.execute("CREATE FUNCTION " + fn + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " IF NEW.audit_log_event_type_id = 76 AND NEW.entity_id = " + v1
                + " THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$");
        fx.execute("CREATE TRIGGER " + fn + " BEFORE INSERT ON audit_log_event FOR EACH ROW EXECUTE PROCEDURE "
                + fn + "()");
        try {
            mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(containsString("nothing was changed")));
        } finally {
            fx.execute("DROP TRIGGER " + fn + " ON audit_log_event");
            fx.execute("DROP FUNCTION " + fn + "()");
        }

        assertThat(fx.status("crf", crf)).isEqualTo(1);
        assertThat(fx.status("crf_version", v1)).isEqualTo(1);
        assertThat(fx.status("section", s1)).isEqualTo(1);
        assertThat(fx.status("event_definition_crf", edc)).isEqualTo(1);
        assertThat(fx.status("event_crf", ec)).isEqualTo(8);
        assertThat(fx.status("item_data", id)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE (audit_log_event_type_id = ? AND entity_id = ?)"
                + " OR (audit_table = 'crf' AND entity_id = ?)", AuditTypeIds.EVENT_CRF_REMOVED_WITH_CRF, ec, crf)).isZero();
    }

    @Test
    void disableAndRestoreAreRefusedToAnInvestigatorAndRestoreOnlyTakesARemovedCrf() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        fx.version(crf, "v1", "F_" + t + "_V1", 1);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(investigator()))
                .andExpect(status().isForbidden());
        assertThat(fx.status("crf", crf)).isEqualTo(1);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(dm()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("is not removed")));

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(crc())).andExpect(status().isOk());
        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(investigator()))
                .andExpect(status().isForbidden());
        assertThat(fx.status("crf", crf)).isEqualTo(5);
        mvc().perform(post("/api/v1/crfs/F_NO_SUCH_" + t + "/restore").session(dm()))
                .andExpect(status().isNotFound());
        mvc().perform(post("/api/v1/crfs/F_" + t + "/restore").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------------------------------ */
    /* Version disable / restore / lock                                   */
    /* ------------------------------------------------------------------ */

    @Test
    void disableVersionTakesItsEventCrfsAndMovesItsDefaultsAndRestoreBringsThemBack() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int s1 = fx.section(v1, "S1", 1);
        int edcFree = fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, CrfLibraryFixtures.STUDY_ID, crf, v1, null);
        int edcOnlyV1 = fx.eventDefinitionCrf(CrfLibraryFixtures.SED2_ID, CrfLibraryFixtures.STUDY_ID, crf, v1,
                String.valueOf(v1));
        int item = fx.item(t + "_A", 5);
        int subjA = fx.subject(t + "-A", CrfLibraryFixtures.STUDY_ID, 1);
        int ecA = fx.eventCrf(fx.event(subjA, CrfLibraryFixtures.SED_ID, 4, 1), subjA, v1, 1, false, true, false);
        int idA = fx.itemData(ecA, item, "a", 1);
        int subjS = fx.subject(t + "-S", CrfLibraryFixtures.STUDY_ID, 8);
        int ecS = fx.eventCrf(fx.event(subjS, CrfLibraryFixtures.SED_ID, 8, 1), subjS, v1, 8, true, true, true);
        // Completed (MarkEventCRFComplete sets the CRF and its values unavailable) and locked.
        int subjC = fx.subject(t + "-C", CrfLibraryFixtures.STUDY_ID, 1);
        int ecC = fx.eventCrf(fx.event(subjC, CrfLibraryFixtures.SED_ID, 4, 1), subjC, v1, 2, false, true, false);
        int idC = fx.itemData(ecC, item, "c", 2);
        int subjL = fx.subject(t + "-L", CrfLibraryFixtures.STUDY_ID, 1);
        int ecL = fx.eventCrf(fx.event(subjL, CrfLibraryFixtures.SED_ID, 4, 1), subjL, v1, 6, false, true, false);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1/disable").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));

        assertThat(fx.status("crf_version", v1)).isEqualTo(5);
        assertThat(fx.status("section", s1)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);
        assertThat(fx.status("item_data", idA)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecS)).as("a signed event CRF is not removed").isEqualTo(8);
        assertThat(fx.status("event_crf", ecC)).as("completed").isEqualTo(7);
        assertThat(fx.status("item_data", idC)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecL)).as("locked").isEqualTo(7);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_REMOVED_WITH_VERSION, ecC, "2", "7")).isEqualTo(1);
        assertThat(fx.intValue("SELECT default_version_id FROM event_definition_crf WHERE event_definition_crf_id = ?",
                edcFree)).isEqualTo(v2);
        assertThat(fx.intValue("SELECT default_version_id FROM event_definition_crf WHERE event_definition_crf_id = ?",
                edcOnlyV1)).as("offers no other version: keeps its default").isEqualTo(v1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 79"
                + " AND entity_id = ? AND new_value = ?", edcFree, "F_" + t + "_V2")).isEqualTo(1);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1/restore").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("available"));

        assertThat(fx.status("crf_version", v1)).isEqualTo(1);
        assertThat(fx.status("section", s1)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(1);
        assertThat(fx.status("item_data", idA)).isEqualTo(1);
        assertThat(fx.status("event_crf", ecS)).isEqualTo(8);
        assertThat(fx.status("event_crf", ecC)).as("back completed").isEqualTo(2);
        assertThat(fx.status("item_data", idC)).isEqualTo(2);
        assertThat(fx.status("event_crf", ecL)).as("back locked").isEqualTo(6);
        assertThat(eventCrfAudit(AuditTypeIds.EVENT_CRF_RESTORED, ecL, "7", "6")).isEqualTo(1);
    }

    @Test
    void aVersionRemovedWhileLockedComesBackLocked() throws Exception {
        String t = tag();
        String base = "/api/v1/crfs/F_" + t;
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);

        mvc().perform(post(base + "/versions/F_" + t + "_V2/lock").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/versions/F_" + t + "_V2/disable").session(dm())).andExpect(status().isOk());
        mvc().perform(post(base + "/versions/F_" + t + "_V2/restore").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("locked"));

        assertThat(fx.status("crf_version", v2)).isEqualTo(6);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 76"
                + " AND entity_id = ? AND old_value = 'removed' AND new_value = 'locked'", v2)).isEqualTo(1);
    }

    @Test
    void aVersionOfARemovedCrfIsRestoredWithTheCrfNotOnItsOwn() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/disable").session(dm())).andExpect(status().isOk());
        assertThat(fx.status("crf_version", v1)).isEqualTo(7);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1/restore").session(dm()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("restore the CRF")));
        assertThat(fx.status("crf_version", v1)).isEqualTo(7);
    }

    @Test
    void lockingTheDefaultVersionMovesTheDefaultToTheNewestAvailableVersion() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int v3 = fx.version(crf, "v3", "F_" + t + "_V3", 5);
        int edc = fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, CrfLibraryFixtures.STUDY_ID, crf, v1, null);
        int edcOther = fx.eventDefinitionCrf(CrfLibraryFixtures.SED2_ID, CrfLibraryFixtures.STUDY_ID, crf, v3, null);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1/lock").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("locked"));

        assertThat(fx.intValue("SELECT default_version_id FROM event_definition_crf WHERE event_definition_crf_id = ?",
                edc)).isEqualTo(v2);
        assertThat(fx.intValue("SELECT default_version_id FROM event_definition_crf WHERE event_definition_crf_id = ?",
                edcOther)).as("defaults to another version: untouched").isEqualTo(v3);
    }

    @Test
    void aVersionIsAddressedThroughItsOwnCrf() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        fx.crf("CRF " + t + " other", "F_" + t + "_OTHER", dmId);

        mvc().perform(post("/api/v1/crfs/F_" + t + "_OTHER/versions/F_" + t + "_V1/disable").session(dm()))
                .andExpect(status().isNotFound());
        assertThat(fx.status("crf_version", v1)).isEqualTo(1);
    }

    @Test
    void theNewDefaultIsTheNewestAvailableVersionTheSelectionAllows() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int v3 = fx.version(crf, "v3", "F_" + t + "_V3", 1);
        fx.version(crf, "v4", "F_" + t + "_V4", 6);    // newest, but locked
        int edc = fx.eventDefinitionCrf(SED, STUDY, crf, v1, null);
        int edcPicky = fx.eventDefinitionCrf(CrfLibraryFixtures.SED2_ID, STUDY, crf, v1, v1 + "," + v2);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1/lock").session(dm()))
                .andExpect(status().isOk());

        assertThat(fx.intValue("SELECT default_version_id FROM event_definition_crf WHERE event_definition_crf_id = ?",
                edc)).isEqualTo(v3);
        assertThat(fx.intValue("SELECT default_version_id FROM event_definition_crf WHERE event_definition_crf_id = ?",
                edcPicky)).as("the newest its selection allows").isEqualTo(v2);
    }

    /* ------------------------------------------------------------------ */
    /* Hard remove                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void aHardRemoveDeletesOnlyTheVersionsOwnItemsAndNotWhileTheyHoldValues() throws Exception {
        String t = tag();
        String url = "/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1";
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 5);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int s1 = fx.section(v1, "S1", 1);
        int s2 = fx.section(v2, "S2", 1);
        int shared = fx.item(t + "_A", 5);
        int own = fx.item(t + "_B", 5);
        fx.map(shared, v1);
        fx.place(shared, v1, s1, 1);
        fx.map(own, v1);
        fx.place(own, v1, s1, 2);
        fx.map(shared, v2);
        fx.place(shared, v2, s2, 1);
        int subj = fx.subject(t + "-A", STUDY, 1);
        int ec = fx.eventCrf(fx.event(subj, SED, 4, 1), subj, v1, 1, false, true, false);
        int idShared = fx.itemData(ec, shared, "a", 1);
        int idOwn = fx.itemData(ec, own, "b", 1);
        // Moved to v2 as the migration moves it: the value on the item v2
        // does not have stays where it was.
        fx.execute("UPDATE event_crf SET crf_version_id = ? WHERE event_crf_id = ?", v2, ec);

        mvc().perform(delete(url).session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("1 item value(s)")));
        assertThat(fx.intValue("SELECT count(*) FROM crf_version WHERE crf_version_id = ?", v1)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM versioning_map WHERE crf_version_id = ?", v1))
                .as("nothing was changed").isEqualTo(2);
        assertThat(fx.intValue("SELECT count(*) FROM item_form_metadata WHERE crf_version_id = ?", v1)).isEqualTo(2);
        assertThat(fx.status("section", s1)).isEqualTo(1);

        fx.execute("DELETE FROM item_data WHERE item_data_id = ?", idOwn);
        mvc().perform(delete(url).session(admin())).andExpect(status().isNoContent());

        assertThat(fx.intValue("SELECT count(*) FROM crf_version WHERE crf_version_id = ?", v1)).isZero();
        assertThat(fx.intValue("SELECT count(*) FROM section WHERE section_id = ?", s1)).isZero();
        assertThat(fx.intValue("SELECT count(*) FROM item WHERE item_id = ?", own)).isZero();
        assertThat(fx.intValue("SELECT count(*) FROM item WHERE item_id = ?", shared)).as("v2 has it").isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM versioning_map WHERE crf_version_id = ?", v2)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM item_form_metadata WHERE crf_version_id = ?", v2)).isEqualTo(1);
        assertThat(fx.status("item_data", idShared)).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* Edit name and description                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void theOwnerChangesNameAndDescriptionAndEachChangeIsAudited() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);

        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm())
                        .contentType("application/json")
                        .content("{\"name\":\"  Renamed " + t + "  \",\"description\":\"New text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oid").value("F_" + t))
                .andExpect(jsonPath("$.name").value("Renamed " + t))
                .andExpect(jsonPath("$.description").value("New text"));

        assertThat(fx.stringValue("SELECT name FROM crf WHERE crf_id = ?", crf)).isEqualTo("Renamed " + t);
        assertThat(fx.stringValue("SELECT oc_oid FROM crf WHERE crf_id = ?", crf)).isEqualTo("F_" + t);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 142"
                + " AND entity_id = ? AND entity_name = 'name' AND old_value = ? AND new_value = ?",
                crf, "CRF " + t, "Renamed " + t)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 142"
                + " AND entity_id = ? AND entity_name = 'description'", crf)).isEqualTo(1);
    }

    @Test
    void editValidatesNameAndDescriptionLikeTheLegacyForm() throws Exception {
        String t = tag();
        fx.crf("CRF " + t, "F_" + t, dmId);
        fx.crf("Taken " + t, "F_" + t + "_TAKEN", dmId);

        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm()).contentType("application/json")
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm()).contentType("application/json")
                        .content("{\"name\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("255")));
        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm()).contentType("application/json")
                        .content("{\"name\":\"ok\",\"description\":\"" + "d".repeat(2049) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));
        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm()).contentType("application/json")
                        .content("{\"name\":\"Taken " + t + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("already exists")));
        assertThat(fx.stringValue("SELECT name FROM crf WHERE oc_oid = ?", "F_" + t)).isEqualTo("CRF " + t);
    }

    @Test
    void aDescriptionAloneMayChangeAndOnlyItIsAudited() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);

        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm()).contentType("application/json")
                        .content("{\"name\":\"CRF " + t + "\",\"description\":\"Only this\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Only this"));
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 142"
                + " AND entity_id = ? AND entity_name = 'description'", crf)).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 142"
                + " AND entity_id = ? AND entity_name = 'name'", crf)).as("the name did not change").isZero();
    }

    @Test
    void theLongestNameAndDescriptionTheLegacyFormTakesAreTaken() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);

        String name = (t + "n".repeat(255)).substring(0, 255);
        mvc().perform(put("/api/v1/crfs/F_" + t).session(dm()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"description\":\"" + "d".repeat(2048) + "\"}"))
                .andExpect(status().isOk());
        assertThat(fx.stringValue("SELECT name FROM crf WHERE crf_id = ?", crf)).isEqualTo(name);
        assertThat(fx.intValue("SELECT length(description) FROM crf WHERE crf_id = ?", crf)).isEqualTo(2048);
    }

    @Test
    void onlyTheOwnerAsDataManagerOrASysadminMayEdit() throws Exception {
        String t = tag();
        fx.crf("CRF " + t, "F_" + t, dmId);
        String body = "{\"name\":\"By admin " + t + "\"}";

        mvc().perform(put("/api/v1/crfs/F_" + t).session(otherDm()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc().perform(put("/api/v1/crfs/F_" + t).session(investigator()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc().perform(put("/api/v1/crfs/F_" + t).session(new MockHttpSession()).contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
        assertThat(fx.stringValue("SELECT name FROM crf WHERE oc_oid = ?", "F_" + t)).isEqualTo("CRF " + t);

        mvc().perform(put("/api/v1/crfs/F_" + t).session(admin()).contentType("application/json").content(body))
                .andExpect(status().isOk());
        assertThat(fx.stringValue("SELECT name FROM crf WHERE oc_oid = ?", "F_" + t)).isEqualTo("By admin " + t);
    }

    @Test
    void aCoordinatorOwnerMayNotEditAndARemovedCrfIsNotEdited() throws Exception {
        String t = tag();
        fx.crf("CRF " + t, "F_" + t, crcId);
        mvc().perform(put("/api/v1/crfs/F_" + t).session(crc()).contentType("application/json")
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());

        String r = tag();
        int removed = fx.crf("CRF " + r, "F_" + r, dmId);
        fx.execute("UPDATE crf SET status_id = 5 WHERE crf_id = ?", removed);
        mvc().perform(put("/api/v1/crfs/F_" + r).session(dm()).contentType("application/json")
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isConflict());
        assertThat(fx.status("crf", removed)).isEqualTo(5);
    }

    /* ------------------------------------------------------------------ */
    /* CRF view                                                           */
    /* ------------------------------------------------------------------ */

    @Test
    void theCrfViewListsItemsWithTheIntegrityCheckAndTheStudiesUsingIt() throws Exception {
        String t = tag();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int s1 = fx.section(v1, "S1", 1);
        int s2 = fx.section(v2, "S2", 1);
        int g1 = fx.group(crf, t + "_G1");
        int g2 = fx.group(crf, t + "_G2");
        int a = fx.item(t + "_A", 6);
        int b = fx.item(t + "_B", 5);
        int c = fx.item(t + "_C", 9);
        fx.place(a, v1, s1, 1);
        fx.place(a, v2, s2, 1);
        fx.place(b, v1, s1, 2);
        fx.place(b, v2, s2, 2);
        fx.place(c, v2, s2, 3);
        fx.groupItem(g1, v1, a, 1);
        fx.groupItem(g1, v2, a, 1);
        fx.groupItem(g1, v1, b, 2);
        fx.groupItem(g2, v2, b, 2);
        fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, CrfLibraryFixtures.STUDY_ID, crf, v1, null);
        int site = fx.site("S_" + t, "Site " + t);
        fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, site, crf, v1, null);

        mvc().perform(get("/api/v1/crfs/F_" + t).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oid").value("F_" + t))
                .andExpect(jsonPath("$.mayEdit").value(true))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].name").value(t + "_A"))
                .andExpect(jsonPath("$.items[0].dataType").value("INT"))
                .andExpect(jsonPath("$.items[0].versions.length()").value(2))
                .andExpect(jsonPath("$.items[0].integrity").value("ok"))
                .andExpect(jsonPath("$.items[1].name").value(t + "_B"))
                .andExpect(jsonPath("$.items[1].integrity").value("problem"))
                .andExpect(jsonPath("$.items[1].placements[0].groupLabel").value(t + "_G1"))
                .andExpect(jsonPath("$.items[1].placements[0].versionName").value("v1"))
                .andExpect(jsonPath("$.items[1].placements[1].groupLabel").value(t + "_G2"))
                .andExpect(jsonPath("$.items[1].placements[1].versionName").value("v2"))
                .andExpect(jsonPath("$.items[2].name").value(t + "_C"))
                .andExpect(jsonPath("$.items[2].dataType").value("DATE"))
                .andExpect(jsonPath("$.items[2].versions[0]").value("v2"))
                .andExpect(jsonPath("$.studies.length()").value(2))
                .andExpect(jsonPath("$.studies[0].oid").value(CrfLibraryFixtures.STUDY_OID))
                .andExpect(jsonPath("$.studies[1].oid").value("S_" + t))
                .andExpect(jsonPath("$.studies[1].parentOid").value(CrfLibraryFixtures.STUDY_OID));

        mvc().perform(get("/api/v1/crfs/F_" + t).session(otherDm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayEdit").value(false));
        mvc().perform(get("/api/v1/crfs/F_" + t).session(investigator()))
                .andExpect(status().isForbidden());
        mvc().perform(get("/api/v1/crfs/F_NO_SUCH_" + t).session(dm()))
                .andExpect(status().isNotFound());
    }
}
