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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import at.ac.meduniwien.ophthalmology.libreclinica.service.EventCrfVersionMigrationService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Moving existing event CRFs to another CRF version
 * ({@code /api/v1/crfs/{crfOid}/event-crf-migration}) against a real schema:
 * who may, what is selected and skipped, what the move clears, and that a
 * run writes all of it or nothing. See {@link EventCrfVersionMigrationService}.
 */
class EventCrfMigrationApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static CrfLibraryFixtures fx;
    private static int dmId;
    private static int crcId;
    private static int invId;
    private static int siteDmId;
    private static int adminId;

    @BeforeAll
    static void users() throws Exception {
        fx = new CrfLibraryFixtures(DATA_SOURCE);
        dmId = fx.user("migit-dm", false);
        fx.role("migit-dm", CrfLibraryFixtures.STUDY_ID, "director");
        crcId = fx.user("migit-crc", false);
        fx.role("migit-crc", CrfLibraryFixtures.STUDY_ID, "coordinator");
        invId = fx.user("migit-inv", false);
        fx.role("migit-inv", CrfLibraryFixtures.STUDY_ID, "Investigator");
        siteDmId = fx.user("migit-sitedm", false);
        fx.role("migit-sitedm", fx.site("S_MIGIT_SITEDM", "Site of the site-level director"), "director");
        adminId = fx.user("migit-admin", true);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new EventCrfMigrationApiController(
                        DATA_SOURCE, new EventCrfVersionMigrationService(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession dm() {
        return CrfLibraryFixtures.session(dmId, "migit-dm", false);
    }

    /** The CRF of one test and the rows around it. */
    private record World(String t, int crf, int v1, int v2, int itemA, int itemB, int site,
                         int s1, int ev1, int ec1, int s2, int ev2, int ec2,
                         int ec3, int ev4, int ec4, int ec5) {

        String url() {
            return "/api/v1/crfs/F_" + t + "/event-crf-migration";
        }

        String body(String extra, Integer expected) {
            return "{\"studyOid\":\"" + CrfLibraryFixtures.STUDY_OID + "\","
                    + "\"sourceVersionOid\":\"F_" + t + "_V1\",\"targetVersionOid\":\"F_" + t + "_V2\""
                    + (extra == null ? "" : "," + extra)
                    + (expected == null ? "" : ",\"expectedEventCrfCount\":" + expected) + "}";
        }
    }

    /**
     * A CRF with versions v1 (items A and B) and v2 (item A only), assigned to
     * event definition 1 of study 1, and a site that offers only v1. On v1:
     * <ul>
     *   <li>ec1: subject S1, completed event, SDV verified, values for A and B;</li>
     *   <li>ec2: subject S2 signed and its event signed (audit trail: before
     *       signing 1 and 4), the CRF signed, SDV verified, a value for B;</li>
     *   <li>ec3: of a locked subject; ec4: in a locked event;</li>
     *   <li>ec5: of a subject at the site that offers only v1.</li>
     * </ul>
     */
    private static World world() throws Exception {
        String t = "MG" + SEQ.incrementAndGet();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        int v2 = fx.version(crf, "v2", "F_" + t + "_V2", 1);
        int sec1 = fx.section(v1, "S1", 1);
        int sec2 = fx.section(v2, "S2", 1);
        int a = fx.item(t + "_A", 5);
        int b = fx.item(t + "_B", 5);
        fx.place(a, v1, sec1, 1);
        fx.place(b, v1, sec1, 2);
        fx.place(a, v2, sec2, 1);
        fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, CrfLibraryFixtures.STUDY_ID, crf, v1, null);
        int site = fx.site("S_" + t, "Site " + t);
        fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, site, crf, v1, String.valueOf(v1));

        int s1 = fx.subject(t + "-1", CrfLibraryFixtures.STUDY_ID, 1);
        int ev1 = fx.event(s1, CrfLibraryFixtures.SED_ID, 4, 1);
        int ec1 = fx.eventCrf(ev1, s1, v1, 1, true, true, false);
        fx.itemData(ec1, a, "x", 1);
        fx.itemData(ec1, b, "y", 1);

        int s2 = fx.subject(t + "-2", CrfLibraryFixtures.STUDY_ID, 8);
        fx.signedAudit("study_subject", s2, "1");
        int ev2 = fx.event(s2, CrfLibraryFixtures.SED_ID, 8, 1);
        fx.signedAudit("study_event", ev2, "4");
        int ec2 = fx.eventCrf(ev2, s2, v1, 8, true, true, true);
        fx.itemData(ec2, b, "z", 1);

        int s3 = fx.subject(t + "-3", CrfLibraryFixtures.STUDY_ID, 6);
        int ec3 = fx.eventCrf(fx.event(s3, CrfLibraryFixtures.SED_ID, 4, 1), s3, v1, 1, false, true, false);

        int s4 = fx.subject(t + "-4", CrfLibraryFixtures.STUDY_ID, 1);
        int ev4 = fx.event(s4, CrfLibraryFixtures.SED_ID, 7, 1);
        int ec4 = fx.eventCrf(ev4, s4, v1, 1, false, true, false);

        int s5 = fx.subject(t + "-5", site, 1);
        int ec5 = fx.eventCrf(fx.event(s5, CrfLibraryFixtures.SED_ID, 4, 1), s5, v1, 1, false, true, false);
        return new World(t, crf, v1, v2, a, b, site, s1, ev1, ec1, s2, ev2, ec2, ec3, ev4, ec4, ec5);
    }

    private int versionOf(int eventCrfId) throws Exception {
        return fx.intValue("SELECT crf_version_id FROM event_crf WHERE event_crf_id = ?", eventCrfId);
    }

    /* ------------------------------------------------------------------ */
    /* Who may                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void onlyADataManagerOrCrcOfTheStudyMayMoveEventCrfs() throws Exception {
        World w = world();
        MockHttpSession[] refused = {
                CrfLibraryFixtures.session(invId, "migit-inv", false),
                CrfLibraryFixtures.session(siteDmId, "migit-sitedm", false),
                CrfLibraryFixtures.session(adminId, "migit-admin", true),
        };
        for (MockHttpSession s : refused) {
            mvc().perform(get(w.url() + "/options").param("studyOid", CrfLibraryFixtures.STUDY_OID).session(s))
                    .andExpect(status().isForbidden());
            mvc().perform(post(w.url() + "/preview").session(s).contentType("application/json")
                            .content(w.body(null, null)))
                    .andExpect(status().isForbidden());
            mvc().perform(post(w.url()).session(s).contentType("application/json").content(w.body(null, 2)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString("Data Manager or CRC")));
        }
        mvc().perform(post(w.url()).session(new MockHttpSession()).contentType("application/json")
                        .content(w.body(null, 2)))
                .andExpect(status().isUnauthorized());
        assertThat(versionOf(w.ec1())).isEqualTo(w.v1());

        mvc().perform(post(w.url() + "/preview").session(CrfLibraryFixtures.session(crcId, "migit-crc", false))
                        .contentType("application/json").content(w.body(null, null)))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */
    /* Options and preview                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void optionsOfferTheVersionsWithTheirCountsAndTheStudysSitesAndEvents() throws Exception {
        World w = world();
        mvc().perform(get(w.url() + "/options").param("studyOid", "S_" + w.t()).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.study.oid").value(CrfLibraryFixtures.STUDY_OID))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].oid").value("F_" + w.t() + "_V1"))
                .andExpect(jsonPath("$.versions[0].eventCrfCount").value(5))
                .andExpect(jsonPath("$.versions[1].eventCrfCount").value(0))
                .andExpect(jsonPath("$.sites[0].oid").value(CrfLibraryFixtures.STUDY_OID))
                .andExpect(jsonPath("$.sites[?(@.oid == 'S_" + w.t() + "')]").exists())
                .andExpect(jsonPath("$.eventDefinitions.length()").value(1))
                .andExpect(jsonPath("$.eventDefinitions[0].oid").value(CrfLibraryFixtures.SED_OID));
    }

    @Test
    void thePreviewCountsWhatMovesListsWhatDoesNotAndWritesNothing() throws Exception {
        World w = world();
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCrfCount").value(2))
                .andExpect(jsonPath("$.subjectCount").value(2))
                .andExpect(jsonPath("$.sdvVerifiedCount").value(2))
                .andExpect(jsonPath("$.signedSubjectCount").value(1))
                .andExpect(jsonPath("$.signedEventCount").value(1))
                .andExpect(jsonPath("$.signedEventCrfCount").value(1))
                .andExpect(jsonPath("$.eventCrfs.length()").value(2))
                .andExpect(jsonPath("$.eventCrfs[0].eventCrfId").value(w.ec1()))
                .andExpect(jsonPath("$.eventCrfs[1].subjectSigned").value(true))
                .andExpect(jsonPath("$.locked.length()").value(2))
                .andExpect(jsonPath("$.locked[0].row.eventCrfId").value(w.ec3()))
                .andExpect(jsonPath("$.locked[0].reason").value("subject-locked"))
                .andExpect(jsonPath("$.locked[1].reason").value("event-locked"))
                .andExpect(jsonPath("$.notOffered.length()").value(1))
                .andExpect(jsonPath("$.notOffered[0].siteOid").value("S_" + w.t()))
                .andExpect(jsonPath("$.notOffered[0].eventCrfCount").value(1))
                .andExpect(jsonPath("$.hiddenValueCount").value(2))
                .andExpect(jsonPath("$.hiddenItems[0].oid").value("I_" + w.t() + "_B"))
                .andExpect(jsonPath("$.hiddenItems[0].valueCount").value(2));

        assertThat(versionOf(w.ec1())).isEqualTo(w.v1());
        assertThat(fx.boolValue("SELECT sdv_status FROM event_crf WHERE event_crf_id = ?", w.ec1())).isTrue();
    }

    /* ------------------------------------------------------------------ */
    /* Run                                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void aRunMovesTheEventCrfsClearsSdvAndRemovesTheSignaturesOverThem() throws Exception {
        World w = world();
        mvc().perform(post(w.url()).session(dm()).contentType("application/json").content(w.body(null, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migratedEventCrfCount").value(2))
                .andExpect(jsonPath("$.subjectCount").value(2))
                .andExpect(jsonPath("$.sdvClearedCount").value(2))
                .andExpect(jsonPath("$.unsignedSubjectCount").value(1))
                .andExpect(jsonPath("$.unsignedEventCount").value(1))
                .andExpect(jsonPath("$.unsignedEventCrfCount").value(1))
                .andExpect(jsonPath("$.log.length()").value(2))
                .andExpect(jsonPath("$.log[0].studySubjectLabel").value(w.t() + "-1"));

        assertThat(versionOf(w.ec1())).isEqualTo(w.v2());
        assertThat(versionOf(w.ec2())).isEqualTo(w.v2());
        assertThat(fx.boolValue("SELECT sdv_status FROM event_crf WHERE event_crf_id = ?", w.ec1())).isFalse();
        assertThat(fx.boolValue("SELECT sdv_status FROM event_crf WHERE event_crf_id = ?", w.ec2())).isFalse();
        assertThat(fx.status("study_subject", w.s2())).as("back to its status before signing").isEqualTo(1);
        assertThat(fx.intValue("SELECT subject_event_status_id FROM study_event WHERE study_event_id = ?", w.ev2()))
                .isEqualTo(4);
        assertThat(fx.status("event_crf", w.ec2())).isEqualTo(1);
        assertThat(fx.boolValue("SELECT electronic_signature_status FROM event_crf WHERE event_crf_id = ?", w.ec2()))
                .isFalse();
        assertThat(fx.intValue("SELECT subject_event_status_id FROM study_event WHERE study_event_id = ?", w.ev1()))
                .as("not signed: untouched").isEqualTo(4);
        assertThat(versionOf(w.ec3())).as("locked subject").isEqualTo(w.v1());
        assertThat(versionOf(w.ec4())).as("locked event").isEqualTo(w.v1());
        assertThat(versionOf(w.ec5())).as("site offers only v1").isEqualTo(w.v1());

        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 33"
                + " AND event_crf_id = ? AND new_value = 'v2'", w.ec1())).isEqualTo(1);
        assertThat(fx.intValue("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 144"
                + " AND entity_id = ? AND user_id = ?", w.ec2(), dmId)).isEqualTo(1);
        assertThat(fx.stringValue("SELECT new_value FROM audit_log_event WHERE audit_log_event_type_id = 143"
                + " AND entity_id = ?", w.crf())).contains("eventCrfs=2").contains("to=F_" + w.t() + "_V2");
    }

    @Test
    void aRunWhoseSelectionNoLongerMatchesItsPreviewChangesNothing() throws Exception {
        World w = world();
        mvc().perform(post(w.url()).session(dm()).contentType("application/json").content(w.body(null, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("changed since the preview")));

        assertThat(versionOf(w.ec1())).isEqualTo(w.v1());
        assertThat(versionOf(w.ec2())).isEqualTo(w.v1());
        assertThat(fx.status("study_subject", w.s2())).isEqualTo(8);

        mvc().perform(post(w.url()).session(dm()).contentType("application/json").content(w.body(null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expectedEventCrfCount"));
    }

    @Test
    void aSubjectLabelOrEventCrfIdsNarrowTheMoveToOneSubjectsEventCrf() throws Exception {
        World w = world();
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body("\"studySubjectLabel\":\" " + w.t() + "-1 \"", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studySubjectLabel").value(w.t() + "-1"))
                .andExpect(jsonPath("$.eventCrfCount").value(1));
        mvc().perform(post(w.url()).session(dm()).contentType("application/json")
                        .content(w.body("\"studySubjectLabel\":\"" + w.t() + "-1\"", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migratedEventCrfCount").value(1));
        assertThat(versionOf(w.ec1())).isEqualTo(w.v2());
        assertThat(versionOf(w.ec2())).isEqualTo(w.v1());

        mvc().perform(post(w.url()).session(dm()).contentType("application/json")
                        .content(w.body("\"eventCrfIds\":[" + w.ec2() + "]", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.log[0].eventCrfId").value(w.ec2()));
        assertThat(versionOf(w.ec2())).isEqualTo(w.v2());
    }

    @Test
    void anEventThatIsNotSignedKeepsItsStatusWhateverItsSigningHistory() throws Exception {
        World w = world();
        // ev1 was signed once and has since moved on; the trail says it was 3 before signing.
        fx.signedAudit("study_event", w.ev1(), "3");

        mvc().perform(post(w.url()).session(dm()).contentType("application/json")
                        .content(w.body("\"eventCrfIds\":[" + w.ec1() + "]", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unsignedEventCount").value(0));
        assertThat(fx.intValue("SELECT subject_event_status_id FROM study_event WHERE study_event_id = ?", w.ev1()))
                .isEqualTo(4);
    }

    @Test
    void withoutASigningInTheAuditTrailTheSignaturesStillGo() throws Exception {
        String t = "MG" + SEQ.incrementAndGet();
        int crf = fx.crf("CRF " + t, "F_" + t, dmId);
        int v1 = fx.version(crf, "v1", "F_" + t + "_V1", 1);
        fx.version(crf, "v2", "F_" + t + "_V2", 1);
        fx.eventDefinitionCrf(CrfLibraryFixtures.SED_ID, CrfLibraryFixtures.STUDY_ID, crf, v1, null);
        int subj = fx.subject(t + "-1", CrfLibraryFixtures.STUDY_ID, 8);
        int complete = fx.event(subj, CrfLibraryFixtures.SED_ID, 8, 1);
        fx.eventCrf(complete, subj, v1, 8, false, true, true);
        int subj2 = fx.subject(t + "-2", CrfLibraryFixtures.STUDY_ID, 1);
        int started = fx.event(subj2, CrfLibraryFixtures.SED_ID, 8, 1);
        fx.eventCrf(started, subj2, v1, 8, false, false, true);

        mvc().perform(post("/api/v1/crfs/F_" + t + "/event-crf-migration").session(dm())
                        .contentType("application/json")
                        .content("{\"studyOid\":\"" + CrfLibraryFixtures.STUDY_OID + "\",\"sourceVersionOid\":\"F_"
                                + t + "_V1\",\"targetVersionOid\":\"F_" + t + "_V2\",\"expectedEventCrfCount\":2}"))
                .andExpect(status().isOk());

        assertThat(fx.status("study_subject", subj)).isEqualTo(1);
        assertThat(fx.intValue("SELECT subject_event_status_id FROM study_event WHERE study_event_id = ?", complete))
                .as("all its CRFs complete").isEqualTo(4);
        assertThat(fx.intValue("SELECT subject_event_status_id FROM study_event WHERE study_event_id = ?", started))
                .as("a CRF not complete").isEqualTo(3);
    }

    /* ------------------------------------------------------------------ */
    /* Validation                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    void anInvalidSelectionIsRefusedFieldByField() throws Exception {
        World w = world();
        String same = "{\"studyOid\":\"" + CrfLibraryFixtures.STUDY_OID + "\",\"sourceVersionOid\":\"F_" + w.t()
                + "_V1\",\"targetVersionOid\":\"F_" + w.t() + "_V1\"}";
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json").content(same))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetVersionOid"));
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body("\"siteOids\":[\"S_NO_SUCH_SITE\"]", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("siteOids"));
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body("\"eventDefinitionOids\":[\"SE_NO_SUCH\"]", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("eventDefinitionOids"));
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body("\"studySubjectLabel\":\"nobody\"", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("studySubjectLabel"));
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content("{\"sourceVersionOid\":\"F_" + w.t() + "_V1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("studyOid"));

        fx.execute("UPDATE crf_version SET status_id = 6 WHERE crf_version_id = ?", w.v2());
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body(null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("not available")));

        mvc().perform(post("/api/v1/crfs/F_NO_SUCH_CRF/event-crf-migration/preview").session(dm())
                        .contentType("application/json").content(w.body(null, null)))
                .andExpect(status().isNotFound());
        fx.execute("UPDATE crf SET status_id = 5 WHERE crf_id = ?", w.crf());
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body(null, null)))
                .andExpect(status().isConflict());
    }

    @Test
    void aLockedSourceVersionMayBeMovedOffOf() throws Exception {
        World w = world();
        fx.execute("UPDATE crf_version SET status_id = 6 WHERE crf_version_id = ?", w.v1());
        mvc().perform(post(w.url() + "/preview").session(dm()).contentType("application/json")
                        .content(w.body(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCrfCount").value(2));
    }
}
