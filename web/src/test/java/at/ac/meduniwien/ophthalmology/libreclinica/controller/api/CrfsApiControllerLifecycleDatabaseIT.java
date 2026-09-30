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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import at.ac.meduniwien.ophthalmology.libreclinica.service.CrfVersionMigrationService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * CRF library lifecycle against a real schema: removing a CRF or a version
 * takes the rows below it, restoring brings them back at the status they had
 * (legacy {@code RemoveCRFServlet} / {@code RestoreCRFServlet} /
 * {@code RemoveCRFVersionServlet} / {@code RestoreCRFVersionServlet}, see
 * {@link CrfLifecycleCascade}); locking or removing a version moves the
 * defaults that point at it. Each test builds its own CRF.
 */
class CrfsApiControllerLifecycleDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static CrfLibraryFixtures fx;
    private static int dmId;
    private static int crcId;
    private static int invId;

    @BeforeAll
    static void users() throws Exception {
        fx = new CrfLibraryFixtures(DATA_SOURCE);
        dmId = fx.user("crfit-dm", false);
        fx.role("crfit-dm", CrfLibraryFixtures.STUDY_ID, "director");
        crcId = fx.user("crfit-crc", false);
        fx.role("crfit-crc", CrfLibraryFixtures.STUDY_ID, "coordinator");
        invId = fx.user("crfit-inv", false);
        fx.role("crfit-inv", CrfLibraryFixtures.STUDY_ID, "Investigator");
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

    private static MockHttpSession dm() {
        return CrfLibraryFixtures.session(dmId, "crfit-dm", false);
    }

    private static MockHttpSession crc() {
        return CrfLibraryFixtures.session(crcId, "crfit-crc", false);
    }

    private static MockHttpSession investigator() {
        return CrfLibraryFixtures.session(invId, "crfit-inv", false);
    }

    private static String tag() {
        return "LC" + SEQ.incrementAndGet();
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
        int ecB = fx.eventCrf(fx.event(subjB, CrfLibraryFixtures.SED_ID, 8, 1), subjB, v2, 8, true, true, true);
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

        mvc().perform(post("/api/v1/crfs/F_" + t + "/versions/F_" + t + "_V1/disable").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("removed"));

        assertThat(fx.status("crf_version", v1)).isEqualTo(5);
        assertThat(fx.status("section", s1)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecA)).isEqualTo(7);
        assertThat(fx.status("item_data", idA)).isEqualTo(7);
        assertThat(fx.status("event_crf", ecS)).as("a signed event CRF is not removed").isEqualTo(8);
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
}
