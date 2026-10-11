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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DR-040 phase 2, slice D2 — {@code GET /api/v1/admin/ingest-sources/anonymous-uploaders}:
 * the uploader programs that have been seen lately and have never reported
 * with a token. It is what System &gt; Standorte tells an administrator before
 * a second location is created ("N uploaders have no token yet"), because that
 * is the moment the anonymous front door closes.
 *
 * <p>Pinned: system administrators only (anonymous 401, anybody else 403, a
 * Standort-Admin included); an instance counts when its last heartbeat is
 * within seven days and no ingest source names it (a revoked uploader still
 * does: it has reported with a token); newest first; at most 50 listed while
 * {@code count} is the whole number; and nothing but the name, the kind and
 * the time is shown.
 */
@SuppressWarnings("null")
class AnonymousUploadersApiDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "/api/v1/admin/ingest-sources/anonymous-uploaders";
    private static final int ROOT = 1;

    private static int standortAdminId;
    private static int plainUserId;
    private static MockMvc mvc;

    @BeforeAll
    static void seed() throws Exception {
        int location;
        try (Connection c = DATA_SOURCE.getConnection()) {
            location = LifecycleFixtures.insertOne(c,
                    "INSERT INTO location (code, name) VALUES ('AUP-A', 'Anonymous uploaders A') RETURNING location_id");
            LifecycleFixtures.insertUser(c, "aup_standort_admin", 1);
            LifecycleFixtures.insertUser(c, "aup_plain", 1);
            standortAdminId = LifecycleFixtures.intQuery("SELECT user_id FROM user_account WHERE user_name = 'aup_standort_admin'");
            plainUserId = LifecycleFixtures.intQuery("SELECT user_id FROM user_account WHERE user_name = 'aup_plain'");
            exec(c, "INSERT INTO user_location (user_account_id, location_id, can_manage_sources) VALUES ("
                    + standortAdminId + ", " + location + ", true)");
        }
        mvc = ProductionMvc.standalone(new AdminIngestSourcesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM ingest_source");
            exec(c, "DELETE FROM uploader_instance");
        }
    }

    /* ====================================================================== */
    /* Who may ask                                                             */
    /* ====================================================================== */

    @Test
    void onlyASystemAdministratorMayAsk() throws Exception {
        instance("aup-00000001", "Clarus PC", "export-watcher", "1 hour");

        assertEquals(401, call(new MockHttpSession()).getResponse().getStatus(), "anonymous");
        assertEquals(403, call(as(plainUserId, "aup_plain")).getResponse().getStatus(), "an ordinary user");
        assertEquals(403, call(as(standortAdminId, "aup_standort_admin")).getResponse().getStatus(),
                "a Standort-Admin is not a system administrator: the question is about the whole installation");
        assertEquals(200, call(admin()).getResponse().getStatus());
    }

    /* ====================================================================== */
    /* What counts                                                             */
    /* ====================================================================== */

    @Test
    void noInstancesIsACountOfZeroAndAnEmptyList() throws Exception {
        JsonNode body = answer();
        assertEquals(0, body.get("count").asInt());
        assertTrue(body.get("instances").isArray());
        assertEquals(0, body.get("instances").size());
    }

    @Test
    void anInstanceSeenLatelyWithNoTokenCountsAndOneThatReportedWithATokenDoesNot() throws Exception {
        instance("aup-00000001", "Clarus PC", "export-watcher", "1 hour");
        instance("aup-00000002", "Spectralis PC", "export-watcher", "2 hours");
        instance("aup-00000003", "Optomed PC", "optomed-bridge", "3 hours");
        source("aup-00000003", false);

        JsonNode body = answer();
        assertEquals(2, body.get("count").asInt());
        assertEquals(List.of("Clarus PC", "Spectralis PC"), texts(body.get("instances"), "displayName"));
    }

    @Test
    void anInstanceWhoseTokenWasRevokedHasStillReportedWithAToken() throws Exception {
        instance("aup-00000001", "Clarus PC", "export-watcher", "1 hour");
        source("aup-00000001", true);
        assertEquals(0, answer().get("count").asInt());
    }

    @Test
    void anInstanceNotSeenForMoreThanSevenDaysDoesNotCount() throws Exception {
        instance("aup-00000001", "Seen yesterday", "export-watcher", "1 day");
        instance("aup-00000002", "Seen six days ago", "export-watcher", "6 days 23 hours");
        instance("aup-00000003", "Seen eight days ago", "export-watcher", "8 days");
        instance("aup-00000004", "Seen a year ago", "optomed-bridge", "365 days");

        JsonNode body = answer();
        assertEquals(2, body.get("count").asInt());
        assertEquals(List.of("Seen yesterday", "Seen six days ago"), texts(body.get("instances"), "displayName"));
    }

    @Test
    void theListIsNewestFirst() throws Exception {
        instance("aup-00000001", "Oldest", "export-watcher", "5 days");
        instance("aup-00000002", "Newest", "optomed-bridge", "5 minutes");
        instance("aup-00000003", "Middle", "export-watcher", "2 days");

        assertEquals(List.of("Newest", "Middle", "Oldest"), texts(answer().get("instances"), "displayName"));
    }

    @Test
    void atMostFiftyAreListedWhileTheCountIsTheWholeNumber() throws Exception {
        for (int i = 1; i <= 55; i++) {
            // i minutes ago: instance 1 is the newest.
            instance(String.format("aup-%08d", i), "PC " + i, "export-watcher", i + " minutes");
        }
        JsonNode body = answer();
        assertEquals(55, body.get("count").asInt());
        JsonNode listed = body.get("instances");
        assertEquals(50, listed.size());
        assertEquals("PC 1", listed.get(0).get("displayName").asText(), "newest first");
        assertEquals("PC 50", listed.get(49).get("displayName").asText(), "the five oldest are left out");
    }

    /* ====================================================================== */
    /* What is shown                                                           */
    /* ====================================================================== */

    @Test
    void anInstanceIsShownByNameKindAndTimeOnly() throws Exception {
        instance("aup-00000001", "Clarus PC", "export-watcher", "90 minutes");

        JsonNode body = answer();
        assertEquals(Set.of("count", "instances"), fields(body));
        JsonNode one = body.get("instances").get(0);
        assertEquals(Set.of("displayName", "kind", "lastSeenAt"), fields(one),
                "no instance id, no version, no file counts: the question is who, not how they are");
        assertEquals("Clarus PC", one.get("displayName").asText());
        assertEquals("export-watcher", one.get("kind").asText());
        Instant seen = Instant.parse(one.get("lastSeenAt").asText());
        long minutesAgo = java.time.Duration.between(seen, Instant.now()).toMinutes();
        assertTrue(minutesAgo >= 89 && minutesAgo <= 92, "last seen about 90 minutes ago, not " + minutesAgo + " (" + seen + ")");
    }

    /* ====================================================================== */
    /* Helpers                                                                 */
    /* ====================================================================== */

    private static MvcResult call(MockHttpSession session) throws Exception {
        return mvc.perform(get(URL).session(session)).andReturn();
    }

    private static JsonNode answer() throws Exception {
        MvcResult r = call(admin());
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return JSON.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(ROOT);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        return s;
    }

    private static MockHttpSession as(int userId, String username) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId);
        ub.setName(username);
        s.setAttribute("userBean", ub);
        return s;
    }

    /** A heartbeat row last seen the given interval ago (a SQL interval, e.g. "90 minutes"). */
    private static void instance(String uid, String name, String kind, String ago) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "INSERT INTO uploader_instance (instance_uid, kind, display_name, last_seen_at) VALUES ('"
                    + uid + "', '" + kind + "', '" + name + "', now() - interval '" + ago + "')");
        }
    }

    /** An uploader source that last reported as the instance. */
    private static void source(String instanceUid, boolean revoked) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int location = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE code = 'AUP-A'");
            exec(c, "INSERT INTO ingest_source (kind, display_name, location_id, token_hash, token_hint, instance_uid, "
                    + "revoked_at) VALUES ('uploader', 'Uploader of " + instanceUid + "', " + location + ", md5('"
                    + instanceUid + "') || md5('x" + instanceUid + "'), 'abcd1234', '" + instanceUid + "', "
                    + (revoked ? "now()" : "NULL") + ")");
        }
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
