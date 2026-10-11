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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.UploaderTokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * DR-040 phase 2 — uploaders, tokens, ingest sources and the Standort-Admin
 * right, through the HTTP API against a real PostgreSQL with the production
 * changelog applied (contract: loc2-contract.md).
 *
 * <p>Pinned:
 * <ul>
 *   <li><b>Who:</b> anonymous 401; a user who manages nothing 403 (an
 *       explicit assignment without the right included); a Standort-Admin
 *       addressing another location's uploader or location 404, never 403, so
 *       the answer is no oracle; the ingest-source administration is for
 *       system administrators only.</li>
 *   <li><b>Issue:</b> 201 with the token, shown in that answer and nowhere
 *       else (not by a later read, not in the database, not in an audit row);
 *       the database holds its SHA-256 and the eight-character hint; names are
 *       unique per location without regard to case; a removed location is 409.</li>
 *   <li><b>Replace and revoke:</b> the old token is refused at once; revoke is
 *       idempotent; replace brings a revoked uploader back.</li>
 *   <li><b>Assign:</b> a source's location places its waiting files (unbound
 *       or dismissed, no origin yet) in the same transaction, and nothing else.</li>
 *   <li><b>The right:</b> granted and revoked with the user assignment, kept
 *       by an absent body, and it is what {@code /me} lists.</li>
 *   <li><b>Audit:</b> one row of the right type per state change, no token in
 *       it, and the change and its row are one transaction.</li>
 * </ul>
 */
@SuppressWarnings("null")
class IngestSourceApiDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API = "/api/v1";
    private static final int ROOT = 1;
    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    private static int defaultId;
    private static int locA;
    private static int locB;
    private static final Map<String, Integer> UID = new HashMap<>();

    private static MockMvc mvc;
    private static IngestSourceService sources;

    @BeforeAll
    static void seed() throws Exception {
        defaultId = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        try (Connection c = DATA_SOURCE.getConnection()) {
            locA = LifecycleFixtures.insertOne(c,
                    "INSERT INTO location (code, name) VALUES ('ISA-A', 'ISA Alpha') RETURNING location_id");
            locB = LifecycleFixtures.insertOne(c,
                    "INSERT INTO location (code, name) VALUES ('ISA-B', 'ISA Beta') RETURNING location_id");
            for (String user : new String[] {"isa_admin_a", "isa_admin_b", "isa_admin_ab", "isa_member_a",
                    "isa_nobody", "isa_target"}) {
                LifecycleFixtures.insertUser(c, user, 1);
                UID.put(user, LifecycleFixtures.insertOne(c,
                        "SELECT user_id FROM user_account WHERE user_name = '" + user + "'"));
            }
        }
        mvc = mvcOn(new UploadersApiController(DATA_SOURCE), new AdminIngestSourcesApiController(DATA_SOURCE),
                new AdminLocationsApiController(DATA_SOURCE), new MeApiController(DATA_SOURCE),
                new AuditApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)));
        sources = new IngestSourceService(DATA_SOURCE);
    }

    /** No sources, no files of the tests, the three locations available, and the known set of rights. */
    @BeforeEach
    void reset() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table IN ('ingest_source', 'location')");
            exec(c, "DELETE FROM ingest_item WHERE device = 'isa-cam'");
            exec(c, "DELETE FROM ingest_source");
            exec(c, "DELETE FROM uploader_instance WHERE instance_uid LIKE 'isa-%'");
            exec(c, "DELETE FROM user_location");
            exec(c, "UPDATE location SET status_id = 1 WHERE location_id IN (" + defaultId + ", " + locA + ", " + locB + ")");
            grant(c, "isa_admin_a", locA, true);
            grant(c, "isa_admin_b", locB, true);
            grant(c, "isa_admin_ab", locA, true);
            grant(c, "isa_admin_ab", locB, true);
            grant(c, "isa_member_a", locA, false);
        }
    }

    private static void grant(Connection c, String user, int location, boolean manage) throws SQLException {
        exec(c, "INSERT INTO user_location (user_account_id, location_id, can_manage_sources) VALUES ("
                + UID.get(user) + ", " + location + ", " + manage + ")");
    }

    /* ====================================================================== */
    /* Who may call what                                                       */
    /* ====================================================================== */

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("GET", API + "/uploaders", null, false),
                Arguments.of("POST", API + "/locations/1/uploaders", "{\"displayName\":\"Guarded\"}", false),
                Arguments.of("PUT", API + "/uploaders/1", "{\"displayName\":\"Guarded\"}", false),
                Arguments.of("POST", API + "/uploaders/1/replace", null, false),
                Arguments.of("POST", API + "/uploaders/1/revoke", null, false),
                Arguments.of("GET", API + "/admin/ingest-sources", null, true),
                Arguments.of("PUT", API + "/admin/ingest-sources/1/location", "{\"locationId\":1}", true));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void anonymousIs401AndAUserWhoManagesNothingIs403AndNothingChanges(String verb, String path, String body,
                                                                       boolean sysadminOnly) throws Exception {
        String before = snapshot();
        assertEquals(401, status(verb, path, body, new MockHttpSession()), "anonymous");
        assertEquals(403, status(verb, path, body, as("isa_nobody")), "a user with no assignment");
        assertEquals(403, status(verb, path, body, as("isa_member_a")),
                "an assigned user without the right is not a Standort-Admin");
        if (sysadminOnly) {
            assertEquals(403, status(verb, path, body, as("isa_admin_a")),
                    "the ingest-source administration is for system administrators only");
        }
        assertEquals(before, snapshot(), "a refused call wrote something");
    }

    /* ====================================================================== */
    /* Issue                                                                   */
    /* ====================================================================== */

    @Test
    void issuingAnUploaderAnswers201WithTheTokenAndStoresOnlyItsHash() throws Exception {
        Resp r = send(json(post(API + "/locations/" + locA + "/uploaders"), "{\"displayName\":\"  Clarus 1 \"}"), admin());

        assertEquals(201, r.status, r.body);
        assertTrue(r.header("Cache-Control").contains("no-store"), "a token must not be cached: " + r.header("Cache-Control"));
        JsonNode body = r.json();
        String token = body.get("token").asText();
        assertTrue(token.matches("mwu_[A-Za-z0-9_-]{43}"), token);
        JsonNode u = body.get("uploader");
        long id = u.get("id").asLong();
        assertEquals("Clarus 1", u.get("displayName").asText(), "trimmed");
        assertEquals(locA, u.get("locationId").asInt());
        assertEquals("ISA-A", u.get("locationCode").asText());
        assertEquals("ISA Alpha", u.get("locationName").asText());
        assertEquals(token.substring(4, 12), u.get("tokenHint").asText());
        assertEquals("active", u.get("status").asText());
        assertEquals("root", u.get("createdBy").asText());
        assertNotNull(Instant.parse(u.get("createdAt").asText()), "an ISO-8601 instant");
        for (String field : new String[] {"lastUsedAt", "revokedAt", "instanceUid", "lastHeartbeatAt"}) {
            assertTrue(u.has(field) && u.get(field).isNull(), field + " is null: " + u);
        }
        assertFalse(u.has("token") || u.has("tokenHash"), "the uploader carries no secret: " + u);

        Map<String, Object> row = one("SELECT * FROM ingest_source WHERE ingest_source_id = " + id);
        assertEquals("uploader", row.get("kind"));
        assertNull(row.get("source_key"));
        assertEquals(locA, row.get("location_id"));
        assertEquals(UploaderTokens.hash(token), row.get("token_hash"));
        assertEquals(token.substring(4, 12), row.get("token_hint"));
        assertEquals(ROOT, row.get("created_by"));
        assertNotNull(row.get("created_at"));
        assertNull(row.get("last_used_at"));
        assertNull(row.get("revoked_at"));
        assertEquals(0, appearances(token), "the token is stored nowhere, in no table or audit row");
    }

    @Test
    void theTokenIsNeverShownAgain() throws Exception {
        String token = issue(locA, "Shown once", admin()).get("token").asText();

        String list = send(get(API + "/uploaders"), admin()).body;
        String sources_ = send(get(API + "/admin/ingest-sources"), admin()).body;

        for (String text : new String[] {list, sources_}) {
            assertFalse(text.contains(token), "the token is in a later answer");
            assertFalse(text.contains(UploaderTokens.hash(token)), "the hash is in a later answer");
        }
        assertTrue(list.contains(token.substring(4, 12)), "the hint is shown");
    }

    @Test
    void aNameIsUniquePerLocationWithoutRegardToCaseAndFreeAtAnotherLocation() throws Exception {
        issue(locA, "Clarus 1", admin());
        String before = snapshot();

        JsonNode dup = send(json(post(API + "/locations/" + locA + "/uploaders"), "{\"displayName\":\"CLARUS 1\"}"),
                admin()).expect(400);

        assertEquals("Validation failed", dup.get("message").asText());
        assertEquals(Set.of("displayName"), fields(dup));
        assertEquals(before, snapshot(), "the refused issue wrote something");
        issue(locB, "Clarus 1", admin()); // the same name at another place is fine
        issue(locA, "Clarus 2", admin());
    }

    @Test
    void aBadOrMissingNameIsRefusedWithAFieldError() throws Exception {
        String before = snapshot();
        for (String body : new String[] {"{}", "{\"displayName\":null}", "{\"displayName\":\"   \"}",
                "{\"displayName\":\"" + "n".repeat(65) + "\"}", "{\"displayName\":\"two\\nlines\"}",
                "{\"displayName\":\"zero\\u200bwidth\"}"}) {
            JsonNode r = send(json(post(API + "/locations/" + locA + "/uploaders"), body), admin()).expect(400);
            assertEquals(Set.of("displayName"), fields(r), body);
        }
        issue(locA, "n".repeat(64), admin()); // the limit itself is fine
        JsonNode missing = send(post(API + "/locations/" + locA + "/uploaders").contentType(MediaType.APPLICATION_JSON),
                admin()).expect(400);
        assertEquals("Request body is required", missing.get("message").asText());
        assertEquals(1, count("SELECT count(*) FROM ingest_source"), "only the one valid issue created a row");
        assertNotEquals(before, snapshot());
    }

    @Test
    void aLocationThatIsRemovedIs409AndAnUnknownOneIs404() throws Exception {
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locB);
        String before = snapshot();

        JsonNode removed = send(json(post(API + "/locations/" + locB + "/uploaders"), "{\"displayName\":\"Late\"}"),
                admin()).expect(409);
        assertTrue(removed.get("message").asText().contains("ISA-B"), removed.toString());
        send(json(post(API + "/locations/99999/uploaders"), "{\"displayName\":\"Nowhere\"}"), admin()).expect(404);

        assertEquals(before, snapshot());
    }

    @Test
    void aStandortAdminIssuesAtTheirOwnLocationOnlyAndTheOthersAre404() throws Exception {
        issue(locA, "By the A admin", as("isa_admin_a"));
        String before = snapshot();

        send(json(post(API + "/locations/" + locB + "/uploaders"), "{\"displayName\":\"Not mine\"}"),
                as("isa_admin_a")).expect(404);
        send(json(post(API + "/locations/99999/uploaders"), "{\"displayName\":\"Nowhere\"}"),
                as("isa_admin_a")).expect(404);
        // The right counts at an available location only: with A disabled, the A admin manages nothing
        // (403) and the A-and-B admin manages B alone, so A is no location of theirs (404).
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locA);
        send(json(post(API + "/locations/" + locA + "/uploaders"), "{\"displayName\":\"Gone\"}"),
                as("isa_admin_a")).expect(403);
        send(json(post(API + "/locations/" + locA + "/uploaders"), "{\"displayName\":\"Gone\"}"),
                as("isa_admin_ab")).expect(404);
        exec("UPDATE location SET status_id = 1 WHERE location_id = " + locA);

        assertEquals(before, snapshot(), "a refused call wrote something");
        issue(locB, "By the AB admin at B", as("isa_admin_ab"));
    }

    /* ====================================================================== */
    /* List                                                                    */
    /* ====================================================================== */

    @Test
    void theListIsScopedToTheLocationsTheCallerManagesAndOrderedByLocationThenName() throws Exception {
        issue(locB, "Zeta", admin());
        issue(locA, "beta", admin());
        issue(locA, "Alpha", admin());
        issue(defaultId, "Default one", admin());

        assertEquals(List.of("ISA Alpha/Alpha", "ISA Alpha/beta", "ISA Beta/Zeta", "Medizinische Universität Wien – Augenklinik/Default one"),
                labels(send(get(API + "/uploaders"), admin()).json()), "a system administrator sees every location");
        assertEquals(List.of("ISA Alpha/Alpha", "ISA Alpha/beta"),
                labels(send(get(API + "/uploaders"), as("isa_admin_a")).json()), "a Standort-Admin sees their own");
        assertEquals(List.of("ISA Beta/Zeta"), labels(send(get(API + "/uploaders"), as("isa_admin_b")).json()));
        assertEquals(List.of("ISA Alpha/Alpha", "ISA Alpha/beta", "ISA Beta/Zeta"),
                labels(send(get(API + "/uploaders"), as("isa_admin_ab")).json()));
    }

    @Test
    void theListOnlyHoldsUploadersAndShowsRevokedOnesAndTheHeartbeatOfTheirInstance() throws Exception {
        JsonNode one = issue(locA, "Watcher", admin()).get("uploader");
        long second = issue(locA, "Retired", admin()).get("uploader").get("id").asLong();
        sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        send(post(API + "/uploaders/" + second + "/revoke"), admin()).expect(200);
        exec("INSERT INTO uploader_instance (instance_uid, kind, display_name, last_seen_at) "
                + "VALUES ('isa-inst-1', 'export-watcher', 'PC 1', '2026-10-11 10:00:00')");
        sources.noteInstance(one.get("id").asLong(), "isa-inst-1");

        JsonNode list = send(get(API + "/uploaders"), admin()).json();

        assertEquals(2, list.size(), "the AE title is not an uploader: " + list);
        JsonNode watcher = byField(list, "displayName", "Watcher");
        assertEquals("isa-inst-1", watcher.get("instanceUid").asText());
        assertEquals(Timestamp.valueOf("2026-10-11 10:00:00").toInstant().toString(),
                watcher.get("lastHeartbeatAt").asText(), "last_seen_at of the instance");
        JsonNode retired = byField(list, "displayName", "Retired");
        assertEquals("revoked", retired.get("status").asText());
        assertNotNull(Instant.parse(retired.get("revokedAt").asText()));
        assertTrue(retired.get("lastHeartbeatAt").isNull(), "no instance, no heartbeat");

        exec("UPDATE ingest_source SET instance_uid = 'isa-unknown' WHERE ingest_source_id = " + one.get("id").asLong());
        assertTrue(byField(send(get(API + "/uploaders"), admin()).json(), "displayName", "Watcher")
                .get("lastHeartbeatAt").isNull(), "an instance that never reported has no heartbeat");
    }

    /* ====================================================================== */
    /* Rename                                                                  */
    /* ====================================================================== */

    @Test
    void renameChangesTheNameAuditsOnceAndTheSameNameChangesNothing() throws Exception {
        long id = issue(locA, "Old name", admin()).get("uploader").get("id").asLong();
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        JsonNode renamed = send(json(put(API + "/uploaders/" + id), "{\"displayName\":\" New name \"}"), admin()).expect(200);

        assertEquals("New name", renamed.get("displayName").asText());
        assertEquals(id, renamed.get("id").asLong());
        assertEquals("New name", one("SELECT display_name FROM ingest_source WHERE ingest_source_id = " + id).get("display_name"));
        List<Audit> rows = audits("ingest_source");
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(AuditTypeIds.UPLOADER_RENAMED, rows.get(0).type());
        assertEquals((int) id, rows.get(0).entityId());
        assertEquals("New name", rows.get(0).entityName());
        assertEquals("name=Old name", rows.get(0).oldValue());
        assertEquals("name=New name; location=ISA-A", rows.get(0).newValue());
        assertEquals(ROOT, rows.get(0).userId());

        send(json(put(API + "/uploaders/" + id), "{\"displayName\":\"New name\"}"), admin()).expect(200);
        assertEquals(1, audits("ingest_source").size(), "the same name is no change");
        send(json(put(API + "/uploaders/" + id), "{\"displayName\":\"NEW NAME\"}"), admin()).expect(200);
        assertEquals(2, audits("ingest_source").size(), "a change of case is a rename");
    }

    @Test
    void aRenameKeepsTheNameUniqueAndAnInvalidNameIsRefused() throws Exception {
        long a = issue(locA, "First", admin()).get("uploader").get("id").asLong();
        issue(locA, "Second", admin());
        issue(locB, "Third", admin());
        String before = snapshot();

        assertEquals(Set.of("displayName"), fields(send(json(put(API + "/uploaders/" + a), "{\"displayName\":\"second\"}"), admin()).expect(400)));
        assertEquals(Set.of("displayName"), fields(send(json(put(API + "/uploaders/" + a), "{\"displayName\":\"\"}"), admin()).expect(400)));
        assertEquals(Set.of("displayName"), fields(send(json(put(API + "/uploaders/" + a), "{\"displayName\":\"" + "x".repeat(65) + "\"}"), admin()).expect(400)));
        send(put(API + "/uploaders/" + a).contentType(MediaType.APPLICATION_JSON), admin()).expect(400);
        assertEquals(before, snapshot());
        send(json(put(API + "/uploaders/" + a), "{\"displayName\":\"Third\"}"), admin()).expect(200); // taken at B only
    }

    @Test
    void aStandortAdminRenamesTheirOwnUploadersOnlyAndOnlyUploadersCanBeRenamed() throws Exception {
        long mine = issue(locA, "Mine", admin()).get("uploader").get("id").asLong();
        long theirs = issue(locB, "Theirs", admin()).get("uploader").get("id").asLong();
        long ae = sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        String before = snapshot();

        send(json(put(API + "/uploaders/" + mine), "{\"displayName\":\"Mine, renamed\"}"), as("isa_admin_a")).expect(200);
        send(json(put(API + "/uploaders/" + theirs), "{\"displayName\":\"Stolen\"}"), as("isa_admin_a")).expect(404);
        send(json(put(API + "/uploaders/99999"), "{\"displayName\":\"Nobody\"}"), as("isa_admin_a")).expect(404);
        send(json(put(API + "/uploaders/99999"), "{\"displayName\":\"Nobody\"}"), admin()).expect(404);
        send(json(put(API + "/uploaders/" + ae), "{\"displayName\":\"Not an uploader\"}"), admin()).expect(404);

        assertEquals("Theirs", one("SELECT display_name FROM ingest_source WHERE ingest_source_id = " + theirs).get("display_name"));
        assertNotEquals(before, snapshot());
        assertEquals(1, audits("ingest_source").stream().filter(a -> a.type() == AuditTypeIds.UPLOADER_RENAMED).count());
    }

    /* ====================================================================== */
    /* Replace and revoke                                                      */
    /* ====================================================================== */

    @Test
    void replacingTheTokenStopsTheOldOneAtOnceAndTheNewOneWorks() throws Exception {
        JsonNode issued = issue(locA, "Clarus 1", admin());
        String oldToken = issued.get("token").asText();
        long id = issued.get("uploader").get("id").asLong();
        assertTrue(sources.findActiveByToken(oldToken).isPresent());
        // It has been used: that history starts over with the new token.
        assertNotNull(one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id).get("last_used_at"));
        sources.noteInstance(id, "isa-inst-2");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        JsonNode replaced = send(post(API + "/uploaders/" + id + "/replace"), admin()).expect(200);

        String newToken = replaced.get("token").asText();
        assertTrue(newToken.matches("mwu_[A-Za-z0-9_-]{43}"));
        assertNotEquals(oldToken, newToken);
        assertEquals(id, replaced.get("uploader").get("id").asLong(), "the same uploader");
        assertEquals(newToken.substring(4, 12), replaced.get("uploader").get("tokenHint").asText());
        assertTrue(replaced.get("uploader").get("lastUsedAt").isNull(), "the new token has not been used yet");
        assertTrue(replaced.get("uploader").get("instanceUid").isNull());
        assertTrue(sources.findActiveByToken(oldToken).isEmpty(), "the old token is refused at once");
        assertEquals(id, sources.findActiveByToken(newToken).orElseThrow().id());
        assertEquals(UploaderTokens.hash(newToken), one("SELECT token_hash FROM ingest_source WHERE ingest_source_id = " + id).get("token_hash"));
        assertEquals(0, appearances(newToken) + appearances(oldToken), "no token is stored anywhere");

        List<Audit> rows = audits("ingest_source");
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(AuditTypeIds.UPLOADER_TOKEN_REPLACED, rows.get(0).type());
        assertEquals("Clarus 1", rows.get(0).entityName());
        assertEquals("hint=" + oldToken.substring(4, 12), rows.get(0).oldValue());
        assertEquals("hint=" + newToken.substring(4, 12) + "; location=ISA-A", rows.get(0).newValue());
    }

    @Test
    void replacingBringsARevokedUploaderBackAndIsRefusedAtADisabledLocation() throws Exception {
        JsonNode issued = issue(locA, "Clarus 1", admin());
        String oldToken = issued.get("token").asText();
        long id = issued.get("uploader").get("id").asLong();
        send(post(API + "/uploaders/" + id + "/revoke"), admin()).expect(200);
        assertTrue(sources.findActiveByToken(oldToken).isEmpty());
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        JsonNode back = send(post(API + "/uploaders/" + id + "/replace"), admin()).expect(200);

        assertEquals("active", back.get("uploader").get("status").asText());
        assertTrue(back.get("uploader").get("revokedAt").isNull());
        assertTrue(sources.findActiveByToken(back.get("token").asText()).isPresent());
        assertTrue(sources.findActiveByToken(oldToken).isEmpty(), "the revoked token stays dead");
        Audit a = audits("ingest_source").get(0);
        assertEquals("hint=" + oldToken.substring(4, 12) + "; status=revoked", a.oldValue());
        assertTrue(a.newValue().endsWith("; status=active"), a.newValue());

        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locA);
        String before = snapshot();
        JsonNode refused = send(post(API + "/uploaders/" + id + "/replace"), admin()).expect(409);
        assertTrue(refused.get("message").asText().contains("ISA-A"), refused.toString());
        assertEquals(before, snapshot(), "a refused replace changed the token");
        assertTrue(sources.findActiveByToken(back.get("token").asText()).isEmpty(), "and a disabled location refuses its tokens anyway");
    }

    @Test
    void revokingRefusesTheTokenAtOnceAuditsOnceAndIsIdempotent() throws Exception {
        JsonNode issued = issue(locA, "Clarus 1", admin());
        String token = issued.get("token").asText();
        long id = issued.get("uploader").get("id").asLong();
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        JsonNode revoked = send(post(API + "/uploaders/" + id + "/revoke"), admin()).expect(200);

        assertEquals("revoked", revoked.get("status").asText());
        assertNotNull(Instant.parse(revoked.get("revokedAt").asText()));
        assertEquals(token.substring(4, 12), revoked.get("tokenHint").asText(), "the hint stays");
        assertTrue(sources.findActiveByToken(token).isEmpty());
        assertEquals(ROOT, one("SELECT revoked_by FROM ingest_source WHERE ingest_source_id = " + id).get("revoked_by"));
        Object stamp = one("SELECT revoked_at FROM ingest_source WHERE ingest_source_id = " + id).get("revoked_at");
        assertEquals(1, audits("ingest_source").size());
        Audit a = audits("ingest_source").get(0);
        assertEquals(AuditTypeIds.UPLOADER_TOKEN_REVOKED, a.type());
        assertEquals("Clarus 1", a.entityName());
        assertEquals("status=active; hint=" + token.substring(4, 12), a.oldValue());
        assertEquals("status=revoked; location=ISA-A", a.newValue());

        JsonNode again = send(post(API + "/uploaders/" + id + "/revoke"), admin()).expect(200);
        assertEquals("revoked", again.get("status").asText());
        assertEquals(stamp, one("SELECT revoked_at FROM ingest_source WHERE ingest_source_id = " + id).get("revoked_at"));
        assertEquals(1, audits("ingest_source").size(), "revoking a revoked uploader is no change");
    }

    @Test
    void aStandortAdminReplacesAndRevokesTheirOwnUploadersOnly() throws Exception {
        JsonNode mine = issue(locA, "Mine", admin());
        JsonNode theirs = issue(locB, "Theirs", admin());
        long theirId = theirs.get("uploader").get("id").asLong();
        String theirToken = theirs.get("token").asText();

        send(post(API + "/uploaders/" + mine.get("uploader").get("id").asLong() + "/replace"), as("isa_admin_a")).expect(200);
        send(post(API + "/uploaders/" + mine.get("uploader").get("id").asLong() + "/revoke"), as("isa_admin_a")).expect(200);
        send(post(API + "/uploaders/" + theirId + "/replace"), as("isa_admin_a")).expect(404);
        send(post(API + "/uploaders/" + theirId + "/revoke"), as("isa_admin_a")).expect(404);
        send(post(API + "/uploaders/99999/revoke"), as("isa_admin_a")).expect(404);
        send(post(API + "/uploaders/99999/replace"), admin()).expect(404);

        assertTrue(sources.findActiveByToken(theirToken).isPresent(), "the other location's token is untouched");
    }

    /* ====================================================================== */
    /* The lookup the device endpoints will make                               */
    /* ====================================================================== */

    @Test
    void aTokenIsAcceptedUntilItsUploaderIsRevokedOrItsLocationDisabled() throws Exception {
        JsonNode issued = issue(locA, "Clarus 1", admin());
        String token = issued.get("token").asText();
        long id = issued.get("uploader").get("id").asLong();

        IngestSourceService.Source s = sources.findActiveByToken(token).orElseThrow();
        assertEquals(id, s.id());
        assertEquals(locA, s.locationId());
        assertEquals("uploader", s.kind());

        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locA);
        assertTrue(sources.findActiveByToken(token).isEmpty(), "a disabled location refuses its tokens");
        exec("UPDATE location SET status_id = 1 WHERE location_id = " + locA);
        assertTrue(sources.findActiveByToken(token).isPresent(), "and takes them back when it is restored");
        send(post(API + "/uploaders/" + id + "/revoke"), admin()).expect(200);
        assertTrue(sources.findActiveByToken(token).isEmpty());
        assertTrue(sources.findActiveByToken("mwu_" + "z".repeat(43)).isEmpty(), "an unknown token");
    }

    @Test
    void lastUsedIsWrittenOnTheFirstUseAndAtMostOnceAMinute() throws Exception {
        JsonNode issued = issue(locA, "Clarus 1", admin());
        String token = issued.get("token").asText();
        long id = issued.get("uploader").get("id").asLong();
        assertTrue(one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id).get("last_used_at") == null);

        sources.findActiveByToken(token);
        Object first = one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id).get("last_used_at");
        assertNotNull(first, "the first use is written");
        sources.findActiveByToken(token);
        sources.findActiveByToken(token);
        assertEquals(first, one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id).get("last_used_at"),
                "used again within a minute: not written again");

        exec("UPDATE ingest_source SET last_used_at = now() - interval '2 minutes' WHERE ingest_source_id = " + id);
        Object stale = one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id).get("last_used_at");
        sources.findActiveByToken(token);
        assertNotEquals(stale, one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id).get("last_used_at"),
                "older than a minute: renewed");

        JsonNode list = send(get(API + "/uploaders"), admin()).json();
        assertNotNull(Instant.parse(byField(list, "displayName", "Clarus 1").get("lastUsedAt").asText()));
    }

    /* ====================================================================== */
    /* The ingest sources, for system administrators                           */
    /* ====================================================================== */

    @Test
    void theSourceListHoldsEveryKindWithItsCountsAndLocation() throws Exception {
        long uploader = issue(locA, "Clarus 1", admin()).get("uploader").get("id").asLong();
        long ae = sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        long remidio = sources.upsertAutoSource("remidio", "remidio-main", null);
        long placed = sources.upsertAutoSource("dicom_ae", "SPECTRALIS", "Spectralis");
        sources.assignLocation(placed, locB, ROOT);
        // The unassigned AE title has two unbound files, a dismissed one and a filed one: only the unbound ones are pending.
        item(ae, "UNBOUND", null, null);
        item(ae, "UNBOUND", null, null);
        item(ae, "DISMISSED", null, null);
        item(ae, "BOUND", null, null);
        item(uploader, "UNBOUND", locA, null);
        item(null, "UNBOUND", null, null);

        JsonNode list = send(get(API + "/admin/ingest-sources"), admin()).expect(200);

        assertEquals(4, list.size(), list.toString());
        assertEquals(List.of(ae, remidio, placed, uploader),
                texts(list, "id").stream().map(Long::valueOf).toList(),
                "unassigned first (location_id IS NULL), then by kind, then by name without regard to case: "
                        + "dicom_ae 'Clarus camera', remidio, then the assigned dicom_ae 'Spectralis', uploader 'Clarus 1'");
        JsonNode clarus = byField(list, "sourceKey", "CLARUS");
        assertEquals("dicom_ae", clarus.get("kind").asText());
        assertEquals("Clarus camera", clarus.get("displayName").asText());
        assertTrue(clarus.get("locationId").isNull() && clarus.get("locationName").isNull());
        assertEquals(2, clarus.get("pendingItemCount").asInt(), "the UNBOUND files of this source");
        assertEquals("active", clarus.get("status").asText());
        assertNotNull(Instant.parse(clarus.get("lastSeenAt").asText()), "seen when it was first sighted");
        JsonNode account = byField(list, "kind", "remidio");
        assertEquals("remidio-main", account.get("displayName").asText(), "a blank name defaults to the key");
        assertEquals(0, account.get("pendingItemCount").asInt());
        JsonNode up = byField(list, "kind", "uploader");
        assertTrue(up.get("sourceKey").isNull());
        assertEquals(locA, up.get("locationId").asInt());
        assertEquals("ISA Alpha", up.get("locationName").asText());
        assertEquals(1, up.get("pendingItemCount").asInt());
        assertTrue(up.get("lastSeenAt").isNull(), "an uploader that was never used");
        send(post(API + "/uploaders/" + uploader + "/revoke"), admin()).expect(200);
        assertEquals("revoked", byField(send(get(API + "/admin/ingest-sources"), admin()).json(), "kind", "uploader")
                .get("status").asText());
    }

    @Test
    void assigningALocationPlacesTheWaitingFilesAndNothingElseInOneTransaction() throws Exception {
        long ae = sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        long other = sources.upsertAutoSource("dicom_ae", "OTHER", "Other camera");
        long waitingUnbound = item(ae, "UNBOUND", null, null);
        long waitingDismissed = item(ae, "DISMISSED", null, null);
        long alreadyPlaced = item(ae, "UNBOUND", locB, null);
        long ofAStudy = item(ae, "UNBOUND", null, 1);
        long filed = item(ae, "BOUND", null, null);
        long ofAnotherSource = item(other, "UNBOUND", null, null);
        long ofNoSource = item(null, "UNBOUND", null, null);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        JsonNode dto = send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locA + "}"),
                admin()).expect(200);

        assertEquals(locA, dto.get("locationId").asInt());
        assertEquals("ISA Alpha", dto.get("locationName").asText());
        assertEquals(3, dto.get("pendingItemCount").asInt(),
                "the three unbound files of the source (placed now, or placed before, or a user's upload) still wait to be filed");
        assertEquals(locA, originOf(waitingUnbound));
        assertEquals(locA, originOf(waitingDismissed));
        assertEquals(locB, originOf(alreadyPlaced), "a file that already has an origin keeps it");
        assertNull(originOf(ofAStudy), "a file a user uploaded in a study is decided by that study");
        assertNull(originOf(filed), "a filed file is decided by its subject's study");
        assertNull(originOf(ofAnotherSource));
        assertNull(originOf(ofNoSource));
        assertEquals(locA, one("SELECT location_id FROM ingest_source WHERE ingest_source_id = " + ae).get("location_id"));

        List<Audit> rows = audits("ingest_source");
        assertEquals(1, rows.size(), rows.toString());
        Audit a = rows.get(0);
        assertEquals(AuditTypeIds.INGEST_SOURCE_LOCATION_ASSIGNED, a.type());
        assertEquals((int) ae, a.entityId());
        assertEquals("Clarus camera", a.entityName());
        assertEquals("location=unassigned", a.oldValue());
        assertEquals("kind=dicom_ae; location=ISA-A; items=2", a.newValue());
        assertEquals(ROOT, a.userId());
    }

    @Test
    void assigningTheSameLocationAgainPlacesStragglersAndAuditsOnlyIfSomethingChanged() throws Exception {
        long ae = sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locA + "}"), admin()).expect(200);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locA + "}"), admin()).expect(200);
        assertEquals(0, audits("ingest_source").size(), "nothing changed, nothing audited");

        long straggler = item(ae, "UNBOUND", null, null);
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locA + "}"), admin()).expect(200);
        assertEquals(locA, originOf(straggler));
        assertEquals(1, audits("ingest_source").size());
        assertEquals("kind=dicom_ae; location=ISA-A; items=1", audits("ingest_source").get(0).newValue());
    }

    @Test
    void movingASourceDoesNotMoveItsFilesAndNullTakesItBackToUnassigned() throws Exception {
        long ae = sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locA + "}"), admin()).expect(200);
        long placed = item(ae, "UNBOUND", locA, null);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");

        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locB + "}"), admin()).expect(200);
        assertEquals(locA, originOf(placed), "files already placed stay where they are");
        JsonNode back = send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":null}"), admin()).expect(200);

        assertTrue(back.get("locationId").isNull());
        assertTrue(one("SELECT location_id FROM ingest_source WHERE ingest_source_id = " + ae).get("location_id") == null);
        List<Audit> rows = audits("ingest_source");
        assertEquals(2, rows.size());
        assertEquals("location=ISA-A", rows.get(0).oldValue());
        assertEquals("kind=dicom_ae; location=ISA-B; items=0", rows.get(0).newValue());
        assertEquals("location=ISA-B", rows.get(1).oldValue());
        assertEquals("kind=dicom_ae; location=unassigned; items=0", rows.get(1).newValue());
    }

    @Test
    void anUnavailableUnknownOrMissingLocationAndABadBodyChangeNothing() throws Exception {
        long ae = sources.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        long waiting = item(ae, "UNBOUND", null, null);
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locB);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");
        String before = snapshot();

        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locB + "}"), admin()).expect(409);
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":99999}"), admin()).expect(409);
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{}"), admin()).expect(400);
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":\"A\"}"), admin()).expect(400);
        send(put(API + "/admin/ingest-sources/" + ae + "/location").contentType(MediaType.APPLICATION_JSON), admin()).expect(400);
        send(json(put(API + "/admin/ingest-sources/99999/location"), "{\"locationId\":" + locA + "}"), admin()).expect(404);

        assertEquals(before, snapshot());
        assertNull(originOf(waiting));
    }

    @Test
    void anUploaderCanBeMovedButNotTakenToNoLocationAndNeedsAFreeNameThere() throws Exception {
        long up = issue(locA, "Clarus 1", admin()).get("uploader").get("id").asLong();
        issue(locB, "clarus 1", admin());
        String before = snapshot();

        JsonNode none = send(json(put(API + "/admin/ingest-sources/" + up + "/location"), "{\"locationId\":null}"), admin()).expect(400);
        assertEquals(Set.of("locationId"), fields(none));
        JsonNode taken = send(json(put(API + "/admin/ingest-sources/" + up + "/location"), "{\"locationId\":" + locB + "}"), admin()).expect(400);
        assertEquals(Set.of("locationId"), fields(taken));
        assertEquals(before, snapshot());

        long free = issue(defaultId, "Free name", admin()).get("uploader").get("id").asLong();
        JsonNode moved = send(json(put(API + "/admin/ingest-sources/" + free + "/location"), "{\"locationId\":" + locA + "}"), admin()).expect(200);
        assertEquals(locA, moved.get("locationId").asInt());
    }

    /* ====================================================================== */
    /* The Standort-Admin right                                                */
    /* ====================================================================== */

    @Test
    void grantingTheRightWhileAssigningWritesItAndAuditsBothChanges() throws Exception {
        String user = "isa_target";
        send(get(API + "/uploaders"), as(user)).expect(403);
        String before = snapshot();

        JsonNode detail = send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{\"canManageSources\":true}"),
                admin()).expect(200);

        assertTrue(byField(detail.get("users"), "username", user).get("canManageSources").asBoolean());
        assertEquals(true, one("SELECT can_manage_sources FROM user_location WHERE user_account_id = " + UID.get(user)
                + " AND location_id = " + locA).get("can_manage_sources"));
        List<Audit> rows = audits("location");
        assertEquals(List.of(AuditTypeIds.LOCATION_USER_ASSIGNED, AuditTypeIds.LOCATION_ADMIN_GRANTED),
                rows.stream().map(Audit::type).toList());
        for (Audit a : rows) {
            assertEquals(locA, a.entityId());
            assertEquals("ISA-A", a.entityName());
            assertEquals("user=" + user, a.newValue());
            assertEquals(ROOT, a.userId());
        }
        assertNotEquals(before, snapshot());
        // The right is what opens the uploaders of that location, and no other.
        issue(locA, "By the new admin", as(user));
        send(json(post(API + "/locations/" + locB + "/uploaders"), "{\"displayName\":\"Elsewhere\"}"), as(user)).expect(404);
    }

    @Test
    void anAbsentOrEmptyBodyKeepsTheRightAndFalseTakesItAway() throws Exception {
        String user = "isa_admin_a"; // holds the right at A
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        send(put(API + "/admin/locations/" + locA + "/users/" + user), admin()).expect(200);
        send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{}"), admin()).expect(200);
        send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{\"canManageSources\":null}"), admin()).expect(200);
        send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{\"canManageSources\":true}"), admin()).expect(200);
        assertTrue(holds(user, locA), "the right is kept");
        assertEquals(0, audits("location").size(), "nothing changed, nothing audited");

        JsonNode off = send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{\"canManageSources\":false}"), admin()).expect(200);
        assertFalse(byField(off.get("users"), "username", user).get("canManageSources").asBoolean());
        assertFalse(holds(user, locA));
        assertEquals(List.of(AuditTypeIds.LOCATION_ADMIN_REVOKED), audits("location").stream().map(Audit::type).toList());
        assertEquals("user=" + user, audits("location").get(0).oldValue());
        assertNull(audits("location").get(0).newValue());
        send(get(API + "/uploaders"), as(user)).expect(403);

        send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{\"canManageSources\":false}"), admin()).expect(200);
        send(put(API + "/admin/locations/" + locA + "/users/" + user), admin()).expect(200);
        assertEquals(1, audits("location").size(), "false again, or no body: no change");
        send(json(put(API + "/admin/locations/" + locA + "/users/" + user), "{\"canManageSources\":true}"), admin()).expect(200);
        assertEquals(List.of(AuditTypeIds.LOCATION_ADMIN_REVOKED, AuditTypeIds.LOCATION_ADMIN_GRANTED),
                audits("location").stream().map(Audit::type).toList());
    }

    @Test
    void aNewAssignmentWithoutABodyStartsWithoutTheRightAndUnassigningARightHolderRevokesIt() throws Exception {
        send(put(API + "/admin/locations/" + locB + "/users/isa_target"), admin()).expect(200);
        assertFalse(holds("isa_target", locB), "a new assignment starts without the right");
        assertEquals(List.of(AuditTypeIds.LOCATION_USER_ASSIGNED), audits("location").stream().map(Audit::type).toList());

        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");
        send(delete(API + "/admin/locations/" + locA + "/users/isa_admin_a"), admin()).expect(200);
        assertEquals(List.of(AuditTypeIds.LOCATION_USER_UNASSIGNED, AuditTypeIds.LOCATION_ADMIN_REVOKED),
                audits("location").stream().map(Audit::type).toList(), "the right goes with the assignment, and is audited");
        send(get(API + "/uploaders"), as("isa_admin_a")).expect(403);

        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");
        send(delete(API + "/admin/locations/" + locA + "/users/isa_member_a"), admin()).expect(200);
        assertEquals(List.of(AuditTypeIds.LOCATION_USER_UNASSIGNED), audits("location").stream().map(Audit::type).toList(),
                "no right held, none revoked");
    }

    /* ====================================================================== */
    /* /me                                                                     */
    /* ====================================================================== */

    @Test
    void meListsTheAvailableLocationsTheCallerMayManage() throws Exception {
        assertEquals(List.of("ISA-A", "ISA-B", "MUW-AUGEN"), codes(me(admin())),
                "a system administrator may manage every available location, ordered by name");
        JsonNode first = me(admin()).get(0);
        assertEquals(locA, first.get("id").asInt());
        assertEquals("ISA Alpha", first.get("name").asText());
        assertEquals(Set.of("id", "code", "name"), fieldNames(first), "nothing but id, code and name");

        assertEquals(List.of("ISA-A"), codes(me(as("isa_admin_a"))));
        assertEquals(List.of("ISA-B"), codes(me(as("isa_admin_b"))));
        assertEquals(List.of("ISA-A", "ISA-B"), codes(me(as("isa_admin_ab"))));
        assertEquals(List.of(), codes(me(as("isa_member_a"))), "assigned, but without the right");
        assertEquals(List.of(), codes(me(as("isa_nobody"))));

        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locB);
        assertEquals(List.of("ISA-A", "MUW-AUGEN"), codes(me(admin())), "a removed location is not listed");
        assertEquals(List.of("ISA-A"), codes(me(as("isa_admin_ab"))));
        assertEquals(List.of(), codes(me(as("isa_admin_b"))));
    }

    /* ====================================================================== */
    /* The audit trail                                                         */
    /* ====================================================================== */

    @Test
    void everyStateChangeWritesExactlyOneRowOfItsTypeAndNoTokenIsInAnyOfThem() throws Exception {
        JsonNode issued = issue(locA, "Trail", admin());
        long id = issued.get("uploader").get("id").asLong();
        String t1 = issued.get("token").asText();
        send(json(put(API + "/uploaders/" + id), "{\"displayName\":\"Trail II\"}"), admin()).expect(200);
        String t2 = send(post(API + "/uploaders/" + id + "/replace"), admin()).expect(200).get("token").asText();
        send(post(API + "/uploaders/" + id + "/revoke"), admin()).expect(200);
        long ae = sources.upsertAutoSource("dicom_ae", "TRAIL", "Trail camera");
        send(json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locB + "}"), admin()).expect(200);
        send(json(put(API + "/admin/locations/" + locA + "/users/isa_target"), "{\"canManageSources\":true}"), admin()).expect(200);
        send(json(put(API + "/admin/locations/" + locA + "/users/isa_target"), "{\"canManageSources\":false}"), admin()).expect(200);

        List<Integer> types = new ArrayList<>();
        for (Audit a : audits("ingest_source", "location")) {
            types.add(a.type());
            String text = a.entityName() + "|" + a.oldValue() + "|" + a.newValue();
            for (String secret : new String[] {t1, t2, UploaderTokens.hash(t1), UploaderTokens.hash(t2)}) {
                assertFalse(text.contains(secret), "a token or hash is in an audit row: " + text);
            }
            assertFalse(text.contains("F L") || text.contains("example.invalid"), "a person's name or e-mail: " + text);
        }
        assertEquals(List.of(AuditTypeIds.INGEST_SOURCE_CREATED, AuditTypeIds.UPLOADER_RENAMED,
                AuditTypeIds.UPLOADER_TOKEN_REPLACED, AuditTypeIds.UPLOADER_TOKEN_REVOKED,
                AuditTypeIds.INGEST_SOURCE_CREATED, AuditTypeIds.INGEST_SOURCE_LOCATION_ASSIGNED,
                AuditTypeIds.LOCATION_USER_ASSIGNED, AuditTypeIds.LOCATION_ADMIN_GRANTED,
                AuditTypeIds.LOCATION_ADMIN_REVOKED),
                types, "one row per state change, in the order they happened");
        Audit created = audits("ingest_source").get(0);
        assertEquals("kind=uploader; location=ISA-A; hint=" + t1.substring(4, 12), created.newValue());
        Audit auto = audits("ingest_source").stream().filter(a -> "Trail camera".equals(a.entityName())).findFirst().orElseThrow();
        assertEquals(AuditTypeIds.INGEST_SOURCE_CREATED, auto.type());
        assertNull(auto.userId(), "a first sighting is the system's doing");
        assertEquals("kind=dicom_ae; key=TRAIL; location=unassigned", auto.newValue());
    }

    @Test
    void theTypeIdsAreTheOnesTheChangelogSeedsAndTheyShowInTheSystemAuditLog() throws Exception {
        Map<Integer, String> seeded = new LinkedHashMap<>();
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT audit_log_event_type_id, name FROM audit_log_event_type "
                     + "WHERE audit_log_event_type_id BETWEEN 201 AND 207 ORDER BY 1")) {
            while (rs.next()) seeded.put(rs.getInt(1), rs.getString(2));
        }
        assertEquals(Map.of(201, "ingest_source_created", 202, "uploader_token_replaced", 203, "uploader_token_revoked",
                204, "uploader_renamed", 205, "ingest_source_location_assigned", 206, "location_admin_granted",
                207, "location_admin_revoked"), seeded);

        issue(locA, "Logged", admin());
        JsonNode page = send(get(API + "/audit/system").param("variant", "admin").param("pageSize", "500"), admin()).expect(200);
        JsonNode row = null;
        for (JsonNode e : page.get("events")) {
            if ("Logged".equals(e.path("details").asText()) && "Ingest source created".equals(e.get("title").asText())) row = e;
        }
        assertNotNull(row, "the issue is in the admin log: " + page);
        assertEquals("admin", row.get("variant").asText());
        assertEquals("root", row.get("actor").asText());
        assertTrue(row.get("after").asText().startsWith("kind=uploader; location=ISA-A; hint="), row.toString());
    }

    /* ====================================================================== */
    /* One transaction                                                         */
    /* ====================================================================== */

    @Test
    void ifTheAuditRowCannotBeWrittenNothingIsChanged() throws Exception {
        JsonNode issued = issue(locA, "Atomic", admin());
        long id = issued.get("uploader").get("id").asLong();
        String token = issued.get("token").asText();
        long ae = sources.upsertAutoSource("dicom_ae", "ATOMIC", "Atomic camera");
        long waiting = item(ae, "UNBOUND", null, null);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");
        DataSource failing = failingOn("INSERT INTO audit_log_event", "XX000");
        MockMvc broken = mvcOn(new UploadersApiController(failing), new AdminIngestSourcesApiController(failing),
                new AdminLocationsApiController(failing));

        send(broken, json(post(API + "/locations/" + locA + "/uploaders"), "{\"displayName\":\"Never\"}"), admin()).expect(500);
        send(broken, json(put(API + "/uploaders/" + id), "{\"displayName\":\"Never renamed\"}"), admin()).expect(500);
        send(broken, post(API + "/uploaders/" + id + "/replace"), admin()).expect(500);
        send(broken, post(API + "/uploaders/" + id + "/revoke"), admin()).expect(500);
        send(broken, json(put(API + "/admin/ingest-sources/" + ae + "/location"), "{\"locationId\":" + locA + "}"), admin()).expect(500);
        send(broken, json(put(API + "/admin/locations/" + locA + "/users/isa_target"), "{\"canManageSources\":true}"), admin()).expect(500);

        assertEquals(0, count("SELECT count(*) FROM ingest_source WHERE display_name = 'Never'"), "no uploader was issued");
        assertEquals("Atomic", one("SELECT display_name FROM ingest_source WHERE ingest_source_id = " + id).get("display_name"));
        assertTrue(sources.findActiveByToken(token).isPresent(), "the token was not replaced or revoked");
        assertNull(originOf(waiting), "the waiting file was not placed");
        assertTrue(one("SELECT location_id FROM ingest_source WHERE ingest_source_id = " + ae).get("location_id") == null);
        assertEquals(0, count("SELECT count(*) FROM user_location WHERE user_account_id = " + UID.get("isa_target")));
        assertEquals(0, audits("ingest_source", "location").size());
    }

    @Test
    void severalRequestsForOneNameMakeOneUploaderAndTheRestAreFieldErrorsNotServerErrors() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(json(post(API + "/locations/" + locA + "/uploaders"), "{\"displayName\":\"Race\"}")
                        .session(admin())).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();

        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(n - 1, statuses.stream().filter(s -> s == 400).count(), statuses.toString());
        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE display_name = 'Race'"));
    }

    /* ====================================================================== */
    /* Wiring                                                                  */
    /* ====================================================================== */

    private static MockMvc mvcOn(Object... controllers) {
        return ProductionMvc.standalone(controllers).setControllerAdvice(new ApiExceptionHandler()).build();
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

    /** A signed-in user who is not an administrator; the rights are whatever the database says. */
    private static MockHttpSession as(String username) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(UID.get(username));
        ub.setName(username);
        s.setAttribute("userBean", ub);
        return s;
    }

    private static AbstractMockHttpServletRequestBuilder<?> json(AbstractMockHttpServletRequestBuilder<?> b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final class Resp {
        final int status;
        final String body;
        private final MockHttpServletResponse raw;

        Resp(MockHttpServletResponse raw) throws java.io.UnsupportedEncodingException {
            this.raw = raw;
            this.status = raw.getStatus();
            this.body = raw.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        }

        JsonNode json() {
            return body.isBlank() ? null : JSON.readTree(body);
        }

        /** Asserts the status and returns the JSON body. */
        JsonNode expect(int expected) {
            assertEquals(expected, status, "status; the body was: " + body);
            return json();
        }

        String header(String name) {
            String v = raw.getHeader(name);
            return v == null ? "" : v;
        }
    }

    private static Resp send(AbstractMockHttpServletRequestBuilder<?> req, MockHttpSession session) throws Exception {
        return send(mvc, req, session);
    }

    private static Resp send(MockMvc on, AbstractMockHttpServletRequestBuilder<?> req, MockHttpSession session)
            throws Exception {
        return new Resp(on.perform(req.session(session)).andReturn().getResponse());
    }

    private static int status(String verb, String path, String body, MockHttpSession session) throws Exception {
        AbstractMockHttpServletRequestBuilder<?> req = request(HttpMethod.valueOf(verb), path);
        if (body != null) req = json(req, body);
        return send(req, session).status;
    }

    /** Issues an uploader as {@code who}, asserts the 201 and returns the {@code IssuedUploaderDto}. */
    private static JsonNode issue(int location, String name, MockHttpSession who) throws Exception {
        return send(json(post(API + "/locations/" + location + "/uploaders"),
                JSON.writeValueAsString(Map.of("displayName", name))), who).expect(201);
    }

    private static JsonNode me(MockHttpSession who) throws Exception {
        return send(get(API + "/me"), who).expect(200).get("manageableLocations");
    }

    /* ---------------- reading answers ---------------- */

    private static Set<String> fields(JsonNode body) {
        Set<String> out = new TreeSet<>();
        for (JsonNode e : body.get("errors")) out.add(e.get("field").asText());
        return out;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static JsonNode byField(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asText())) return n;
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    private static List<String> codes(JsonNode array) {
        return texts(array, "code");
    }

    /** {@code location name/uploader name}, in the order the list gave them. */
    private static List<String> labels(JsonNode uploaders) {
        List<String> out = new ArrayList<>();
        uploaders.forEach(n -> out.add(n.get("locationName").asText() + "/" + n.get("displayName").asText()));
        return out;
    }

    /* ====================================================================== */
    /* Fixtures and database                                                   */
    /* ====================================================================== */

    private static Integer boundSubject;

    /** An ingested file of the source (or of none) with the status and origins given. */
    private static long item(Long sourceId, String status, Integer originLocation, Integer originStudy) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            if (boundSubject == null) boundSubject = LifecycleFixtures.insertStudySubject(c, "ISA-S1", 1, 1);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ingest_item (kind, source_kind, stored_path, status, device, received_at, "
                            + "origin_location_id, origin_study_id, ingest_source_id, bound_study_subject_id) "
                            + "VALUES ('image', 'upload', ?, ?, 'isa-cam', now(), ?, ?, ?, ?) RETURNING ingest_item_id")) {
                ps.setString(1, "/x/isa-" + SEQ.incrementAndGet());
                ps.setString(2, status);
                ps.setObject(3, originLocation);
                ps.setObject(4, originStudy);
                ps.setObject(5, sourceId);
                ps.setObject(6, "BOUND".equals(status) ? boundSubject : null);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private static Integer originOf(long itemId) throws SQLException {
        return (Integer) one("SELECT origin_location_id FROM ingest_item WHERE ingest_item_id = " + itemId)
                .get("origin_location_id");
    }

    private static boolean holds(String user, int location) throws SQLException {
        return (Boolean) one("SELECT can_manage_sources FROM user_location WHERE user_account_id = " + UID.get(user)
                + " AND location_id = " + location).get("can_manage_sources");
    }

    /** One md5 over everything a call to these endpoints can change; equal snapshots mean nothing was written. */
    private static String snapshot() throws SQLException {
        String sql = "SELECT "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM ingest_source x) || '/' || "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM user_location x) || '/' || "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM location x) || '/' || "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM ingest_item x "
                + " WHERE x.device = 'isa-cam') || '/' || "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM audit_log_event x "
                + " WHERE x.audit_table IN ('ingest_source', 'location'))";
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** How many rows of {@code ingest_source} and {@code audit_log_event} contain the text anywhere. */
    private static int appearances(String needle) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT (SELECT count(*) FROM ingest_source s WHERE strpos(s::text, ?) > 0) "
                        + "+ (SELECT count(*) FROM audit_log_event a WHERE strpos(a::text, ?) > 0)")) {
            ps.setString(1, needle);
            ps.setString(2, needle);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private record Audit(int type, int entityId, String entityName, String oldValue, String newValue, Integer userId) {}

    /** The audit rows against the given tables, oldest first. */
    private static List<Audit> audits(String... tables) throws SQLException {
        String in = String.join(", ", java.util.Arrays.stream(tables).map(t -> "'" + t + "'").toList());
        List<Audit> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT audit_log_event_type_id, entity_id, entity_name, old_value, new_value, "
                     + "user_id FROM audit_log_event WHERE audit_table IN (" + in + ") ORDER BY audit_id")) {
            while (rs.next()) {
                int user = rs.getInt(6);
                boolean noUser = rs.wasNull();
                out.add(new Audit(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        noUser ? null : user));
            }
        }
        return out;
    }

    /** The one row of the query as column name to value. */
    private static Map<String, Object> one(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                out.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
            }
            return out;
        }
    }

    private static int count(String sql) throws SQLException {
        return LifecycleFixtures.intQuery(sql);
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, sql);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    /**
     * The database, except that preparing a statement that starts with
     * {@code sqlPrefix} fails with {@code sqlState}: a write that cannot be made.
     */
    private static DataSource failingOn(String sqlPrefix, String sqlState) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, DATA_SOURCE, args);
                    if (!"getConnection".equals(method.getName())) return result;
                    Connection real = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (connProxy, connMethod, connArgs) -> {
                                if ("prepareStatement".equals(connMethod.getName()) && connArgs != null
                                        && connArgs[0] instanceof String sql && sql.startsWith(sqlPrefix)) {
                                    throw new SQLException("injected failure", sqlState);
                                }
                                return invoke(connMethod, real, connArgs);
                            });
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
