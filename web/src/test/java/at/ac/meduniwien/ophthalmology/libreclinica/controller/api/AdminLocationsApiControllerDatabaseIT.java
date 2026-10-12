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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * DR-040 phase 1 — System &gt; Standorte: the administration of global
 * locations, against a real PostgreSQL with the production changelog applied
 * ({@code /api/v1/admin/locations}).
 *
 * <p>Pinned:
 * <ul>
 *   <li><b>Who:</b> anonymous 401 and anyone but a system administrator 403,
 *       on every endpoint, and a refused call changes nothing.</li>
 *   <li><b>Create:</b> 201 and the stored row, strings trimmed and a blank
 *       address field stored as none; a code or name already used (without
 *       regard to case, a disabled location's too) and every bad shape answer
 *       400 with a field error and create nothing.</li>
 *   <li><b>Read:</b> the list is ordered by name, removed locations included,
 *       with the four counts (removed studies do not count); the detail lists
 *       the studies and sites at the location and the users assigned to it.</li>
 *   <li><b>Edit:</b> the code never changes; an absent address field stays
 *       and an empty one clears; the same values change and audit nothing.</li>
 *   <li><b>Disable and restore:</b> 409 while a study or site that is not
 *       removed is at the location and for the only available location; both
 *       calls are idempotent; the status ids are the ones {@code LocationScope}
 *       reads.</li>
 *   <li><b>Assign and unassign:</b> idempotent; 404 for an unknown or removed
 *       user, 409 for a removed location; what is written is what
 *       {@code LocationScope} reads.</li>
 *   <li><b>Audit:</b> every state change writes exactly one row of its type,
 *       naming the location code (and the user name of an assignment, nothing
 *       else personal); a refused call or a call that changes nothing writes
 *       none; the change and its row are one transaction; the system audit
 *       log shows them under the admin filter.</li>
 * </ul>
 */
@SuppressWarnings("null")
class AdminLocationsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/admin/locations";
    private static final int ROOT = 1;
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    /** The location the changelog seeds. */
    private static int defaultId;
    private static String defaultName;

    private static MockMvc mvc;

    @BeforeAll
    static void seed() throws Exception {
        defaultId = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        defaultName = stringQuery("SELECT name FROM location WHERE location_id = " + defaultId);
        try (Connection c = DATA_SOURCE.getConnection()) {
            LifecycleFixtures.insertUser(c, "locadm_user", 1);
            LifecycleFixtures.insertUser(c, "locadm_user2", 1);
            LifecycleFixtures.insertUser(c, "locadm_removed", 5);
            LifecycleFixtures.insertUser(c, "locadm_auto", 7);
            LifecycleFixtures.insertUser(c, "locadm_nameless", 1);
            exec(c, "UPDATE user_account SET first_name = '', last_name = '' WHERE user_name = 'locadm_nameless'");
        }
        mvc = mvcOn(new AdminLocationsApiController(DATA_SOURCE));
    }

    /**
     * Back to the changelog's one location, no assignments, no audit rows of
     * locations. Multi-location mode: most tests here make further locations;
     * the single-mode rules have tests of their own.
     */
    @BeforeEach
    void reset() throws Exception {
        LocationMode.set(LocationMode.MULTI);
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table = 'location'");
            exec(c, "DELETE FROM ingest_item WHERE device = 'locadm-cam'");
            exec(c, "DELETE FROM study WHERE unique_identifier LIKE 'locadm-%' AND parent_study_id IS NOT NULL");
            exec(c, "DELETE FROM study WHERE unique_identifier LIKE 'locadm-%'");
            exec(c, "DELETE FROM user_location");
            exec(c, "DELETE FROM location WHERE location_id <> " + defaultId);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE location SET name = ?, street = NULL, city = NULL, zip = NULL, country = NULL, "
                            + "status_id = 1, date_updated = NULL, update_id = NULL WHERE location_id = ?")) {
                ps.setString(1, defaultName);
                ps.setInt(2, defaultId);
                ps.executeUpdate();
            }
        }
    }

    /* ====================================================================== */
    /* Who may call it                                                         */
    /* ====================================================================== */

    static Stream<Arguments> endpoints() {
        String user = BASE + "/1/users/locadm_user";
        return Stream.of(
                Arguments.of("GET", BASE, null),
                Arguments.of("POST", BASE, "{\"code\":\"IT-GUARD\",\"name\":\"Guarded\"}"),
                Arguments.of("GET", BASE + "/1", null),
                Arguments.of("PUT", BASE + "/1", "{\"name\":\"Renamed\"}"),
                Arguments.of("POST", BASE + "/1/disable", null),
                Arguments.of("POST", BASE + "/1/restore", null),
                Arguments.of("PUT", user, null),
                Arguments.of("DELETE", user, null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void anonymousIs401AndAnythingButASystemAdministratorIs403AndNothingChanges(String verb, String path, String body)
            throws Exception {
        String before = snapshot();
        assertEquals(401, status(verb, path, body, new MockHttpSession()), "anonymous");
        assertEquals(403, status(verb, path, body, physician()), "a user who is not a system administrator");
        assertEquals(before, snapshot(), "a refused call wrote something");
    }

    /* ====================================================================== */
    /* Create                                                                  */
    /* ====================================================================== */

    @Test
    void createStoresTheLocationTrimmedAndAnswers201() throws Exception {
        JsonNode body = call(json(post(BASE), "{\"code\":\"  IT-A \",\"name\":\"  Alpha Clinic \","
                + "\"street\":\" W\\u00e4hringer G\\u00fcrtel 18 \",\"city\":\"Wien\",\"zip\":\"   \",\"country\":\"AT\"}"),
                admin(), 201);

        int id = body.get("id").asInt();
        assertTrue(id > 0);
        assertEquals("IT-A", body.get("code").asText());
        assertEquals("Alpha Clinic", body.get("name").asText());
        assertEquals("available", body.get("status").asText());
        assertEquals("Währinger Gürtel 18", body.get("street").asText());
        assertEquals("Wien", body.get("city").asText());
        assertTrue(body.get("zip").isNull(), "a blank field is none: " + body);
        assertEquals("AT", body.get("country").asText());
        for (String count : new String[] {"siteCount", "studyCount", "ingestItemCount", "userCount"}) {
            assertEquals(0, body.get(count).asInt(), count);
        }

        Map<String, Object> row = row(id);
        assertEquals("IT-A", row.get("code"));
        assertEquals("Alpha Clinic", row.get("name"));
        assertEquals(1, row.get("status_id"));
        assertEquals("Währinger Gürtel 18", row.get("street"));
        assertNull(row.get("zip"), "stored as NULL");
        assertEquals(ROOT, row.get("owner_id"));
        assertNotNull(row.get("date_created"));
        assertNull(row.get("date_updated"));
    }

    @Test
    void aCodeAlreadyUsedIsRefusedWithoutRegardToCase() throws Exception {
        create("IT-DUP", "First");
        String before = snapshot();

        JsonNode body = call(json(post(BASE), "{\"code\":\"it-dup\",\"name\":\"Second\"}"), admin(), 400);

        assertEquals("Validation failed", body.get("message").asText());
        assertEquals(java.util.Set.of("code"), fields(body));
        assertEquals(before, snapshot(), "the refused create wrote something");
    }

    @Test
    void aNameAlreadyUsedIsRefusedWithoutRegardToCaseAndADisabledLocationsNameToo() throws Exception {
        create("IT-N1", "Alpha Clinic");
        int disabled = create("IT-N2", "Gamma Clinic");
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + disabled);
        String before = snapshot();

        JsonNode live = call(json(post(BASE), "{\"code\":\"IT-N3\",\"name\":\"ALPHA CLINIC\"}"), admin(), 400);
        assertEquals(java.util.Set.of("name"), fields(live));
        JsonNode removed = call(json(post(BASE), "{\"code\":\"IT-N4\",\"name\":\"gamma clinic\"}"), admin(), 400);
        assertEquals(java.util.Set.of("name"), fields(removed));
        assertTrue(removed.get("errors").get(0).get("message").asText().contains("restore"),
                "the message says to restore the disabled one: " + removed);
        assertEquals(before, snapshot());
    }

    @Test
    void aBadCodeIsRefused() throws Exception {
        String before = snapshot();
        for (String code : new String[] {"A", "a b", "x".repeat(31), "code!", "k\\u00fchn", "slash/slash"}) {
            JsonNode body = call(json(post(BASE), "{\"code\":\"" + code + "\",\"name\":\"Bad code\"}"), admin(), 400);
            assertEquals(java.util.Set.of("code"), fields(body), code);
        }
        JsonNode missing = call(json(post(BASE), "{\"name\":\"No code\"}"), admin(), 400);
        assertEquals(java.util.Set.of("code"), fields(missing));
        assertEquals(before, snapshot(), "a refused create wrote something");
    }

    @Test
    void aCodeOfTheLimitsIsAccepted() throws Exception {
        create("ab", "Shortest code");
        create("a".repeat(30), "Longest code");
        create("Under_Score-9", "Mixed code");
        assertEquals(4, LifecycleFixtures.intQuery("SELECT count(*) FROM location"));
    }

    @Test
    void everyBadFieldIsReportedAtOnce() throws Exception {
        JsonNode body = call(json(post(BASE), "{\"code\":\"IT-LONG\",\"name\":\"" + "n".repeat(101) + "\","
                + "\"street\":\"" + "s".repeat(256) + "\",\"city\":\"" + "c".repeat(256) + "\","
                + "\"zip\":\"" + "z".repeat(65) + "\",\"country\":\"" + "k".repeat(65) + "\"}"), admin(), 400);
        assertEquals(java.util.Set.of("name", "street", "city", "zip", "country"), fields(body));

        JsonNode noName = call(json(post(BASE), "{\"code\":\"IT-NONAME\",\"name\":\"   \"}"), admin(), 400);
        assertEquals(java.util.Set.of("name"), fields(noName));
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM location"), "nothing was created");

        create("IT-EDGE", "n".repeat(100));
    }

    @Test
    void aMissingBodyIsRefused() throws Exception {
        JsonNode body = call(post(BASE).contentType(MediaType.APPLICATION_JSON), admin(), 400);
        assertEquals("Request body is required", body.get("message").asText());
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM location"));
    }

    @Test
    void anIdThatIsNotANumberIs400() throws Exception {
        call(get(BASE + "/abc"), admin(), 400);
        call(post(BASE + "/abc/disable"), admin(), 400);
    }

    /* ====================================================================== */
    /* List and detail                                                         */
    /* ====================================================================== */

    @Test
    void theListIsOrderedByNameWithRemovedOnesIncludedAndTheCountsAreRight() throws Exception {
        int beta = create("IT-BETA", "it beta");
        int alpha = create("IT-ALPHA", "IT Alpha");

        // At alpha: a parent study and a site count; a removed site, an auto-removed study do not.
        int parent = studyAt(alpha, null, "locadm-p1", 1);
        studyAt(alpha, parent, "locadm-s1", 4);
        studyAt(alpha, parent, "locadm-s2", 5);
        studyAt(alpha, null, "locadm-p2", 7);
        ingestAt(alpha);
        ingestAt(alpha);
        ingestAt(beta);
        exec("INSERT INTO user_location (user_account_id, location_id) "
                + "SELECT user_id, " + alpha + " FROM user_account WHERE user_name IN ('locadm_user', 'locadm_user2')");
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + beta);

        JsonNode list = call(get(BASE), admin(), 200);

        assertTrue(list.isArray());
        List<String> codes = new ArrayList<>();
        list.forEach(n -> codes.add(n.get("code").asText()));
        assertEquals(List.of("IT-ALPHA", "IT-BETA", "MUW-AUGEN"), codes, "ordered by name without regard to case");

        JsonNode a = list.get(0);
        assertEquals(alpha, a.get("id").asInt());
        assertEquals("available", a.get("status").asText());
        assertEquals(1, a.get("siteCount").asInt(), "the pending site; a removed one does not count");
        assertEquals(1, a.get("studyCount").asInt(), "the study; an auto-removed one does not count");
        assertEquals(2, a.get("ingestItemCount").asInt());
        assertEquals(2, a.get("userCount").asInt());
        assertTrue(a.get("street").isNull(), "an address never given is null");

        JsonNode b = list.get(1);
        assertEquals("removed", b.get("status").asText(), "removed locations are listed");
        assertEquals(1, b.get("ingestItemCount").asInt());
        assertEquals(0, b.get("siteCount").asInt() + b.get("studyCount").asInt() + b.get("userCount").asInt());

        JsonNode seeded = list.get(2);
        assertTrue(seeded.get("studyCount").asInt() >= 1, "the changelog points the demo studies at the default location");
    }

    @Test
    void theDetailListsTheStudiesAndSitesAtTheLocationAndItsAssignedUsers() throws Exception {
        int where = create("IT-DETAIL", "Detail Clinic");
        int parent = studyAt(where, null, "locadm-p1", 1);
        studyAt(where, parent, "locadm-s1", 4);
        studyAt(where, parent, "locadm-gone", 5);
        int elsewhere = create("IT-ELSE", "Elsewhere");
        studyAt(elsewhere, null, "locadm-p9", 1);
        exec("INSERT INTO user_location (user_account_id, location_id) SELECT user_id, " + where
                + " FROM user_account WHERE user_name IN ('locadm_user2', 'locadm_user', 'locadm_nameless')");

        JsonNode d = call(get(BASE + "/" + where), admin(), 200);

        assertEquals("IT-DETAIL", d.get("code").asText());
        assertEquals("Detail Clinic", d.get("name").asText());
        assertEquals(1, d.get("studyCount").asInt());
        assertEquals(1, d.get("siteCount").asInt());
        assertEquals(3, d.get("userCount").asInt());

        JsonNode studies = d.get("studies");
        assertEquals(2, studies.size(), "removed studies are not listed: " + studies);
        JsonNode p = byField(studies, "oid", "S_LOCADM_P1");
        assertEquals("locadm-p1 name", p.get("name").asText());
        assertFalse(p.get("isSite").asBoolean());
        assertTrue(p.get("parentOid").isNull());
        assertEquals("available", p.get("status").asText());
        JsonNode s = byField(studies, "oid", "S_LOCADM_S1");
        assertTrue(s.get("isSite").asBoolean());
        assertEquals("S_LOCADM_P1", s.get("parentOid").asText());
        assertEquals("pending", s.get("status").asText());

        JsonNode users = d.get("users");
        assertEquals(List.of("locadm_nameless", "locadm_user", "locadm_user2"), texts(users, "username"),
                "ordered by user name");
        assertEquals("F L", byField(users, "username", "locadm_user").get("displayName").asText());
        assertEquals("locadm_nameless", byField(users, "username", "locadm_nameless").get("displayName").asText(),
                "an account with no name shows its user name");
        for (JsonNode u : users) assertFalse(u.get("canManageSources").asBoolean());
    }

    @Test
    void anUnknownLocationIs404OnEveryEndpoint() throws Exception {
        call(get(BASE + "/99999"), admin(), 404);
        call(json(put(BASE + "/99999"), "{\"name\":\"Nobody\"}"), admin(), 404);
        call(post(BASE + "/99999/disable"), admin(), 404);
        call(post(BASE + "/99999/restore"), admin(), 404);
        call(put(BASE + "/99999/users/locadm_user"), admin(), 404);
        call(delete(BASE + "/99999/users/locadm_user"), admin(), 404);
        assertEquals(0, audits().size());
    }

    /* ====================================================================== */
    /* Edit                                                                    */
    /* ====================================================================== */

    @Test
    void editChangesTheNameAndAddressIgnoresTheCodeAndAuditsOnce() throws Exception {
        int id = createFull("IT-EDIT", "Old Name", "Old Street 1", "Wien", "1090", "AT");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        JsonNode d = call(json(put(BASE + "/" + id), "{\"code\":\"HACKED\",\"name\":\" New Name \","
                + "\"street\":\"New Street 2\",\"city\":\"Wien\"}"), admin(), 200);

        assertEquals("IT-EDIT", d.get("code").asText(), "the code is immutable");
        assertEquals("New Name", d.get("name").asText());
        assertEquals("New Street 2", d.get("street").asText());
        assertEquals("1090", d.get("zip").asText(), "an absent field stays");
        assertEquals("AT", d.get("country").asText());
        assertTrue(d.has("studies") && d.has("users"), "the answer is the detail: " + d);

        Map<String, Object> row = row(id);
        assertEquals("IT-EDIT", row.get("code"));
        assertEquals("New Name", row.get("name"));
        assertNotNull(row.get("date_updated"));
        assertEquals(ROOT, row.get("update_id"));

        List<Audit> rows = audits();
        assertEquals(1, rows.size(), "exactly one audit row: " + rows);
        Audit a = rows.get(0);
        assertEquals(AuditTypeIds.LOCATION_UPDATED, a.type());
        assertEquals(id, a.entityId());
        assertEquals("IT-EDIT", a.entityName());
        assertEquals("name=Old Name; street=Old Street 1", a.oldValue(), "only what changed");
        assertEquals("name=New Name; street=New Street 2", a.newValue());
        assertEquals(ROOT, a.userId());
    }

    @Test
    void anEmptyAddressFieldClearsItAndAnAbsentOneStays() throws Exception {
        int id = createFull("IT-CLEAR", "Clear Me", "Street 1", "Wien", "1090", "AT");

        JsonNode d = call(json(put(BASE + "/" + id), "{\"name\":\"Clear Me\",\"city\":\"\",\"zip\":null,\"country\":\"  \"}"),
                admin(), 200);

        assertTrue(d.get("city").isNull(), "an empty string clears");
        assertTrue(d.get("country").isNull(), "a blank string clears");
        assertEquals("1090", d.get("zip").asText(), "null leaves the field as it is");
        assertEquals("Street 1", d.get("street").asText(), "absent leaves the field as it is");
        Audit a = audits().get(audits().size() - 1);
        assertEquals("city=Wien; country=AT", a.oldValue());
        assertEquals("city=; country=", a.newValue(), "a cleared field reads field=");
    }

    @Test
    void anEditToTheSameValuesChangesAndAuditsNothing() throws Exception {
        int id = createFull("IT-SAME", "Same Name", "Street 1", "Wien", "1090", "AT");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        JsonNode d = call(json(put(BASE + "/" + id), "{\"code\":\"IGNORED\",\"name\":\"Same Name\",\"street\":\"Street 1\","
                + "\"city\":\"Wien\",\"zip\":\"1090\",\"country\":\"AT\"}"), admin(), 200);

        assertEquals("Same Name", d.get("name").asText());
        assertNull(row(id).get("date_updated"), "nothing was written");
        assertEquals(0, audits().size());
    }

    @Test
    void aNewNameMustStayUniqueButTheOwnNameMayChangeCase() throws Exception {
        int a = create("IT-U1", "Alpha Clinic");
        create("IT-U2", "Beta Clinic");

        JsonNode taken = call(json(put(BASE + "/" + a), "{\"name\":\"beta CLINIC\"}"), admin(), 400);
        assertEquals(java.util.Set.of("name"), fields(taken));
        assertEquals("Alpha Clinic", row(a).get("name"));
        assertEquals(0, audits().stream().filter(x -> x.type() == AuditTypeIds.LOCATION_UPDATED).count());

        JsonNode recased = call(json(put(BASE + "/" + a), "{\"name\":\"ALPHA CLINIC\"}"), admin(), 200);
        assertEquals("ALPHA CLINIC", recased.get("name").asText());
        assertEquals(1, audits().stream().filter(x -> x.type() == AuditTypeIds.LOCATION_UPDATED).count());
    }

    @Test
    void editValidationNamesTheFieldsAndWritesNothing() throws Exception {
        int id = create("IT-VAL", "Valid");
        String before = snapshot();

        assertEquals(java.util.Set.of("name"), fields(call(json(put(BASE + "/" + id), "{\"street\":\"No name\"}"), admin(), 400)));
        assertEquals(java.util.Set.of("name"), fields(call(json(put(BASE + "/" + id), "{\"name\":\"  \"}"), admin(), 400)));
        assertEquals(java.util.Set.of("name", "street", "city", "zip", "country"), fields(call(json(put(BASE + "/" + id),
                "{\"name\":\"" + "n".repeat(101) + "\",\"street\":\"" + "s".repeat(256) + "\",\"city\":\"" + "c".repeat(256)
                        + "\",\"zip\":\"" + "z".repeat(65) + "\",\"country\":\"" + "k".repeat(65) + "\"}"), admin(), 400)));
        assertEquals("Request body is required",
                call(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON), admin(), 400).get("message").asText());
        assertEquals(before, snapshot());
    }

    @Test
    void aDisabledLocationCanStillBeEdited() throws Exception {
        int id = create("IT-OLD", "Old Place");
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + id);
        JsonNode d = call(json(put(BASE + "/" + id), "{\"name\":\"Old Place, closed\"}"), admin(), 200);
        assertEquals("removed", d.get("status").asText(), "an edit does not restore");
    }

    /**
     * The SPA's form always sends every address field, a blank one as an empty
     * string: that clears the field and is no validation error.
     */
    @Test
    void theFormSendsEveryAddressFieldAndABlankOneClearsIt() throws Exception {
        int id = createFull("IT-FORM", "Form Clinic", "Street 1", "Wien", "1090", "AT");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        JsonNode d = call(json(put(BASE + "/" + id),
                "{\"name\":\"Form Clinic\",\"street\":\"\",\"city\":\"\",\"zip\":\"\",\"country\":\"\"}"), admin(), 200);

        for (String field : new String[] {"street", "city", "zip", "country"}) {
            assertTrue(d.get(field).isNull(), field + " is cleared: " + d);
            assertNull(row(id).get(field), field + " is stored as NULL");
        }
        assertEquals(1, audits().size());
        assertEquals("street=Street 1; city=Wien; zip=1090; country=AT", audits().get(0).oldValue());

        // The same form saved again changes nothing.
        call(json(put(BASE + "/" + id),
                "{\"name\":\"Form Clinic\",\"street\":\"\",\"city\":\"\",\"zip\":\"\",\"country\":\"\"}"), admin(), 200);
        assertEquals(1, audits().size(), "saving the unchanged form is not audited");
    }

    /** The SPA sends {@code {}} with the calls that need no body, and nothing with the unassign. */
    @Test
    void anEmptyJsonBodyIsIgnoredWhereTheSpaSendsOne() throws Exception {
        int id = create("IT-EMPTY", "Empty Body Clinic");

        call(json(put(BASE + "/" + id + "/users/locadm_user"), "{}"), admin(), 200);
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM user_location WHERE location_id = " + id));
        call(delete(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM user_location WHERE location_id = " + id));

        assertEquals("removed", call(json(post(BASE + "/" + id + "/disable"), "{}"), admin(), 200).get("status").asText());
        assertEquals("available", call(json(post(BASE + "/" + id + "/restore"), "{}"), admin(), 200).get("status").asText());
    }

    /**
     * Every mutation answers with the numbers and lists as they are after it:
     * the page replaces its row with the answer and does not read again.
     */
    @Test
    void mutationResponsesCarryFreshCountsAndLists() throws Exception {
        int id = create("IT-FRESH", "Fresh Clinic");
        ingestAt(id);
        ingestAt(id);

        JsonNode assigned = call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        assertCounts(assigned, 0, 0, 2, 1);
        assertEquals(1, assigned.get("users").size());
        assertEquals(0, assigned.get("studies").size());

        int parent = studyAt(id, null, "locadm-p1", 1);
        studyAt(id, parent, "locadm-s1", 1);
        JsonNode second = call(put(BASE + "/" + id + "/users/locadm_user2"), admin(), 200);
        assertCounts(second, 1, 1, 2, 2);
        assertEquals(2, second.get("users").size());
        assertEquals(2, second.get("studies").size());

        JsonNode edited = call(json(put(BASE + "/" + id), "{\"name\":\"Fresh Clinic II\"}"), admin(), 200);
        assertCounts(edited, 1, 1, 2, 2);
        assertEquals(2, edited.get("studies").size());

        JsonNode unassigned = call(delete(BASE + "/" + id + "/users/locadm_user2"), admin(), 200);
        assertCounts(unassigned, 1, 1, 2, 1);
        assertEquals(1, unassigned.get("users").size());

        exec("UPDATE study SET location_id = " + defaultId + " WHERE unique_identifier IN ('locadm-p1', 'locadm-s1')");
        JsonNode disabled = call(post(BASE + "/" + id + "/disable"), admin(), 200);
        assertCounts(disabled, 0, 0, 2, 1);
        JsonNode restored = call(post(BASE + "/" + id + "/restore"), admin(), 200);
        assertCounts(restored, 0, 0, 2, 1);
    }

    private static void assertCounts(JsonNode dto, int sites, int studies, int ingestItems, int users) {
        assertEquals(sites, dto.get("siteCount").asInt(), "siteCount in " + dto);
        assertEquals(studies, dto.get("studyCount").asInt(), "studyCount in " + dto);
        assertEquals(ingestItems, dto.get("ingestItemCount").asInt(), "ingestItemCount in " + dto);
        assertEquals(users, dto.get("userCount").asInt(), "userCount in " + dto);
    }

    @Test
    void theStudiesOfALocationNameTheirStatusAsTheStudyApiDoes() throws Exception {
        int id = create("IT-STATUS", "Status Clinic");
        studyAt(id, null, "locadm-a", 1);
        studyAt(id, null, "locadm-b", 4);
        studyAt(id, null, "locadm-c", 6);
        studyAt(id, null, "locadm-d", 9);

        JsonNode studies = call(get(BASE + "/" + id), admin(), 200).get("studies");

        assertEquals("available", byField(studies, "oid", "S_LOCADM_A").get("status").asText());
        assertEquals("pending", byField(studies, "oid", "S_LOCADM_B").get("status").asText());
        assertEquals("locked", byField(studies, "oid", "S_LOCADM_C").get("status").asText());
        assertEquals("frozen", byField(studies, "oid", "S_LOCADM_D").get("status").asText());
    }

    /* ====================================================================== */
    /* Disable and restore                                                     */
    /* ====================================================================== */

    @Test
    void disableRemovesTheLocationAndRestoreBringsItBackEachAuditedOnce() throws Exception {
        int id = create("IT-DIS", "Closing Clinic");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        JsonNode off = call(post(BASE + "/" + id + "/disable"), admin(), 200);
        assertEquals("removed", off.get("status").asText());
        assertEquals("IT-DIS", off.get("code").asText());
        assertEquals(5, row(id).get("status_id"));
        assertEquals(ROOT, row(id).get("update_id"));
        assertNotNull(row(id).get("date_updated"));
        assertFalse(new LocationScope(DATA_SOURCE).isAvailable(id), "LocationScope reads the same status");
        assertEquals(1, audits().size());
        Audit disabled = audits().get(0);
        assertEquals(AuditTypeIds.LOCATION_DISABLED, disabled.type());
        assertEquals(id, disabled.entityId());
        assertEquals("IT-DIS", disabled.entityName());
        assertEquals("available", disabled.oldValue());
        assertEquals("removed", disabled.newValue());
        assertEquals(ROOT, disabled.userId());

        JsonNode on = call(post(BASE + "/" + id + "/restore"), admin(), 200);
        assertEquals("available", on.get("status").asText());
        assertEquals(1, row(id).get("status_id"));
        assertTrue(new LocationScope(DATA_SOURCE).isAvailable(id));
        assertEquals(2, audits().size());
        Audit restored = audits().get(1);
        assertEquals(AuditTypeIds.LOCATION_RESTORED, restored.type());
        assertEquals("removed", restored.oldValue());
        assertEquals("available", restored.newValue());
    }

    @Test
    void disablingAndRestoringTwiceChangesAndAuditsNothingTheSecondTime() throws Exception {
        int id = create("IT-IDEM", "Idempotent Clinic");
        call(post(BASE + "/" + id + "/restore"), admin(), 200);
        assertEquals(1, audits().size(), "restoring an available location is no change: only the create is audited");

        call(post(BASE + "/" + id + "/disable"), admin(), 200);
        Object stamp = row(id).get("date_updated");
        JsonNode again = call(post(BASE + "/" + id + "/disable"), admin(), 200);
        assertEquals("removed", again.get("status").asText());
        assertEquals(stamp, row(id).get("date_updated"));
        assertEquals(2, audits().size(), "create and the one disable");
    }

    @Test
    void aLocationWithAStudyOrSiteIsNotDisabled() throws Exception {
        int id = create("IT-BUSY", "Busy Clinic");
        int parent = studyAt(id, null, "locadm-p1", 1);
        // A removed and an auto-removed study at the location do not hold it.
        studyAt(id, null, "locadm-gone", 5);
        studyAt(id, null, "locadm-auto", 7);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        JsonNode one = call(post(BASE + "/" + id + "/disable"), admin(), 409);
        assertTrue(one.get("message").asText().contains("1 study or site"), "names the count: " + one);
        assertEquals(1, row(id).get("status_id"), "still available");

        studyAt(id, parent, "locadm-s1", 4);
        JsonNode two = call(post(BASE + "/" + id + "/disable"), admin(), 409);
        assertTrue(two.get("message").asText().contains("2 studies or sites"), "a pending site holds it too: " + two);
        assertTrue(two.get("message").asText().contains("IT-BUSY"));
        assertEquals(0, audits().size(), "a refused disable is not audited");

        // Moving the two away frees it.
        exec("UPDATE study SET location_id = " + defaultId + " WHERE unique_identifier IN ('locadm-p1', 'locadm-s1')");
        call(post(BASE + "/" + id + "/disable"), admin(), 200);
        assertEquals(5, row(id).get("status_id"));
    }

    @AfterAll
    static void backToSingleMode() {
        LocationMode.set(LocationMode.SINGLE);
    }

    /* ====================================================================== */
    /* Single-location mode                                                    */
    /* ====================================================================== */

    @Test
    void inSingleModeASecondLocationIsRefused() throws Exception {
        LocationMode.set(LocationMode.SINGLE);
        JsonNode body = call(json(post(BASE), "{\"code\":\"IT-SECOND\",\"name\":\"Second Clinic\"}"), admin(), 409);
        assertEquals("single-location deployment", body.get("message").asText());
        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM location WHERE code = 'IT-SECOND'"));
        assertEquals(0, audits().size(), "a refused create is not audited");
    }

    @Test
    void inSingleModeTheOneLocationCanStillBeEditedAndRemadeWhenNoneIsActive() throws Exception {
        LocationMode.set(LocationMode.SINGLE);
        call(json(put(BASE + "/" + defaultId), "{\"name\":\"Renamed Clinic\"}"), admin(), 200);
        // No location active at all (disabled behind the API's back): one may be created.
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + defaultId);
        create("IT-REPLACEMENT", "Replacement Clinic");
    }

    @Test
    void inSingleModeRestoringASecondLocationIsRefusedButTheOnlyOneMayComeBack() throws Exception {
        int other = create("IT-OTHER", "Other Clinic");          // multi mode
        call(post(BASE + "/" + other + "/disable"), admin(), 200);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        LocationMode.set(LocationMode.SINGLE);
        JsonNode body = call(post(BASE + "/" + other + "/restore"), admin(), 409);
        assertEquals("single-location deployment", body.get("message").asText());
        assertEquals(5, row(other).get("status_id"), "still disabled");
        assertEquals(0, audits().size());

        // With the default gone it is the only one, and may come back.
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + defaultId);
        call(post(BASE + "/" + other + "/restore"), admin(), 200);
        assertEquals(1, row(other).get("status_id"));
    }

    @Test
    void inSingleModeTheOnlyLocationIsNotDisabledAndTheMessageSaysWhy() throws Exception {
        int id = create("IT-ONLY", "Only Clinic");               // multi mode
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + defaultId);
        LocationMode.set(LocationMode.SINGLE);
        JsonNode body = call(post(BASE + "/" + id + "/disable"), admin(), 409);
        assertTrue(body.get("message").asText().contains("serves exactly one"), body.toString());
        assertEquals(1, row(id).get("status_id"));
    }

    @Test
    void theOnlyAvailableLocationIsNotDisabled() throws Exception {
        int id = create("IT-LAST", "Last Clinic");
        // The default location is disabled behind the API's back: IT-LAST is the only one left.
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + defaultId);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        JsonNode body = call(post(BASE + "/" + id + "/disable"), admin(), 409);
        assertTrue(body.get("message").asText().contains("only available location"), body.toString());
        assertEquals(1, row(id).get("status_id"));
        assertEquals(0, audits().size());

        // With the default back there is another one.
        exec("UPDATE location SET status_id = 1 WHERE location_id = " + defaultId);
        call(post(BASE + "/" + id + "/disable"), admin(), 200);
    }

    /* ====================================================================== */
    /* Assign and unassign                                                     */
    /* ====================================================================== */

    @Test
    void assigningAUserWritesTheAssignmentOnceAndAuditsTheUserName() throws Exception {
        int id = create("IT-ASSIGN", "Assign Clinic");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");
        UserAccountBean user = new UserAccountDAO(DATA_SOURCE).findByUserName("locadm_user");
        assertFalse(new LocationScope(DATA_SOURCE).visibleLocations(user).contains(id), "not before");

        JsonNode d = call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);

        assertEquals(1, d.get("userCount").asInt());
        assertEquals(List.of("locadm_user"), texts(d.get("users"), "username"));
        assertEquals("F L", d.get("users").get(0).get("displayName").asText());
        assertFalse(d.get("users").get(0).get("canManageSources").asBoolean());
        Map<String, Object> link = assignment(id, "locadm_user");
        assertEquals(false, link.get("can_manage_sources"), "the Standort-Admin right is not granted in phase 1");
        assertEquals(ROOT, link.get("granted_by"));
        assertNotNull(link.get("date_granted"));
        assertTrue(new LocationScope(DATA_SOURCE).visibleLocations(user).contains(id),
                "what the API writes is what LocationScope reads");

        assertEquals(1, audits().size());
        Audit a = audits().get(0);
        assertEquals(AuditTypeIds.LOCATION_USER_ASSIGNED, a.type());
        assertEquals(id, a.entityId());
        assertEquals("IT-ASSIGN", a.entityName());
        assertNull(a.oldValue());
        assertEquals("user=locadm_user", a.newValue(), "the user name and nothing else personal");
        assertEquals(ROOT, a.userId());
    }

    @Test
    void assigningAnAssignedUserChangesAndAuditsNothing() throws Exception {
        int id = create("IT-TWICE", "Twice Clinic");
        call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        Object granted = assignment(id, "locadm_user").get("date_granted");

        JsonNode again = call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);

        assertEquals(1, again.get("userCount").asInt());
        assertEquals(granted, assignment(id, "locadm_user").get("date_granted"), "the first grant stands");
        assertEquals(1, audits().stream().filter(x -> x.type() == AuditTypeIds.LOCATION_USER_ASSIGNED).count());
    }

    @Test
    void anUnknownOrRemovedUserCannotBeAssignedAndADisabledLocationTakesNoOne() throws Exception {
        int id = create("IT-REFUSE", "Refusing Clinic");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");
        String before = snapshot();

        call(put(BASE + "/" + id + "/users/nobody_at_all"), admin(), 404);
        call(put(BASE + "/" + id + "/users/locadm_removed"), admin(), 404);
        call(put(BASE + "/" + id + "/users/locadm_auto"), admin(), 404);
        assertEquals(before, snapshot(), "nothing was written");

        exec("UPDATE location SET status_id = 5 WHERE location_id = " + id);
        before = snapshot();
        JsonNode removed = call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 409);
        assertTrue(removed.get("message").asText().contains("IT-REFUSE"), removed.toString());
        assertEquals(before, snapshot());
        assertEquals(0, audits().size());
    }

    @Test
    void unassigningRemovesTheAssignmentAuditsItOnceAndIsIdempotent() throws Exception {
        int id = create("IT-UN", "Unassign Clinic");
        call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        call(put(BASE + "/" + id + "/users/locadm_user2"), admin(), 200);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");
        UserAccountBean user = new UserAccountDAO(DATA_SOURCE).findByUserName("locadm_user");

        JsonNode d = call(delete(BASE + "/" + id + "/users/locadm_user"), admin(), 200);

        assertEquals(List.of("locadm_user2"), texts(d.get("users"), "username"));
        assertEquals(1, d.get("userCount").asInt());
        assertFalse(new LocationScope(DATA_SOURCE).visibleLocations(user).contains(id), "and LocationScope no longer sees it");
        assertEquals(1, audits().size());
        Audit a = audits().get(0);
        assertEquals(AuditTypeIds.LOCATION_USER_UNASSIGNED, a.type());
        assertEquals("IT-UN", a.entityName());
        assertEquals("user=locadm_user", a.oldValue());
        assertNull(a.newValue());

        call(delete(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        assertEquals(1, audits().size(), "unassigning a user who is not assigned changes nothing");
        call(delete(BASE + "/" + id + "/users/nobody_at_all"), admin(), 404);
    }

    @Test
    void theAssignmentOfARemovedUserOrOfADisabledLocationCanBeCleanedUp() throws Exception {
        int id = create("IT-CLEAN", "Clean-up Clinic");
        exec("INSERT INTO user_location (user_account_id, location_id) "
                + "SELECT user_id, " + id + " FROM user_account WHERE user_name IN ('locadm_removed', 'locadm_user')");
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + id);
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        call(delete(BASE + "/" + id + "/users/locadm_removed"), admin(), 200);
        call(delete(BASE + "/" + id + "/users/locadm_user"), admin(), 200);

        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM user_location WHERE location_id = " + id));
        assertEquals(2, audits().size());
    }

    /* ====================================================================== */
    /* The audit trail                                                         */
    /* ====================================================================== */

    @Test
    void everyStateChangeWritesExactlyOneRowOfItsTypeAndNothingPersonalBesidesTheUserName() throws Exception {
        int id = createFull("IT-TRAIL", "Trail Clinic", "Trail Street 1", "Wien", "1090", "AT");
        call(json(put(BASE + "/" + id), "{\"name\":\"Trail Clinic II\"}"), admin(), 200);
        call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        call(delete(BASE + "/" + id + "/users/locadm_user"), admin(), 200);
        call(post(BASE + "/" + id + "/disable"), admin(), 200);
        call(post(BASE + "/" + id + "/restore"), admin(), 200);

        List<Integer> types = new ArrayList<>();
        for (Audit a : audits()) {
            types.add(a.type());
            assertEquals(id, a.entityId());
            assertEquals("IT-TRAIL", a.entityName());
            assertEquals(ROOT, a.userId());
            for (String text : new String[] {String.valueOf(a.oldValue()), String.valueOf(a.newValue())}) {
                assertFalse(text.contains("locadm_user@") || text.contains("F L") || text.contains("example.invalid"),
                        "the user's name or e-mail is in the audit text: " + text);
            }
        }
        assertEquals(List.of(AuditTypeIds.LOCATION_CREATED, AuditTypeIds.LOCATION_UPDATED,
                AuditTypeIds.LOCATION_USER_ASSIGNED, AuditTypeIds.LOCATION_USER_UNASSIGNED,
                AuditTypeIds.LOCATION_DISABLED, AuditTypeIds.LOCATION_RESTORED), types);
        assertEquals("name=Trail Clinic; street=Trail Street 1; city=Wien; zip=1090; country=AT",
                audits().get(0).newValue());
    }

    @Test
    void theTypeIdsAreTheOnesTheChangelogSeedsAndTheyAreAdminRows() throws Exception {
        Map<Integer, String> seeded = new LinkedHashMap<>();
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT audit_log_event_type_id, name FROM audit_log_event_type "
                     + "WHERE audit_log_event_type_id BETWEEN 195 AND 200 ORDER BY 1")) {
            while (rs.next()) seeded.put(rs.getInt(1), rs.getString(2));
        }
        assertEquals(Map.of(195, "location_created", 196, "location_updated", 197, "location_disabled",
                198, "location_restored", 199, "location_user_assigned", 200, "location_user_unassigned"), seeded);
        assertEquals(195, AuditTypeIds.LOCATION_CREATED);
        assertEquals(196, AuditTypeIds.LOCATION_UPDATED);
        assertEquals(197, AuditTypeIds.LOCATION_DISABLED);
        assertEquals(198, AuditTypeIds.LOCATION_RESTORED);
        assertEquals(199, AuditTypeIds.LOCATION_USER_ASSIGNED);
        assertEquals(200, AuditTypeIds.LOCATION_USER_UNASSIGNED);
        for (int type = 195; type <= 200; type++) {
            assertEquals("admin", AuditApiController.variantForType(type, null), "type " + type);
        }
    }

    @Test
    void theSystemAuditLogShowsTheRowsUnderTheAdminFilterAndNameTheLocation() throws Exception {
        int id = create("IT-LOG", "Logged Clinic");
        call(put(BASE + "/" + id + "/users/locadm_user"), admin(), 200);

        MockMvc audit = mvcOn(new AuditApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)));
        JsonNode page = call(audit, get("/api/v1/audit/system").param("variant", "admin").param("pageSize", "500"),
                admin(), 200);

        JsonNode created = null;
        JsonNode assigned = null;
        for (JsonNode e : page.get("events")) {
            if (!"IT-LOG".equals(e.path("details").asText())) continue;
            if ("Location created".equals(e.get("title").asText())) created = e;
            if ("User assigned to location".equals(e.get("title").asText())) assigned = e;
        }
        assertNotNull(created, "the create is in the admin log: " + page);
        assertEquals("admin", created.get("variant").asText());
        assertEquals("root", created.get("actor").asText());
        assertEquals("name=Logged Clinic", created.get("after").asText());
        assertNotNull(assigned, "and the assignment");
        assertEquals("user=locadm_user", assigned.get("after").asText());

        // Not in any study's log: the rows belong to no study.
        JsonNode study = call(audit, get("/api/v1/audit"), adminInStudy1(), 200);
        for (JsonNode e : study.get("events")) {
            assertFalse("IT-LOG".equals(e.path("details").asText()), "a location row is in a study's log: " + e);
        }
    }

    /* ====================================================================== */
    /* One transaction                                                         */
    /* ====================================================================== */

    @Test
    void ifTheAuditRowCannotBeWrittenTheChangeIsNotMade() throws Exception {
        MockMvc broken = mvcOn(new AdminLocationsApiController(failingOn("INSERT INTO audit_log_event", "XX000")));
        int existing = create("IT-ATOMIC", "Atomic Clinic");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'location'");

        call(broken, json(post(BASE), "{\"code\":\"IT-NEW\",\"name\":\"Never Created\"}"), admin(), 500);
        call(broken, json(put(BASE + "/" + existing), "{\"name\":\"Never Renamed\"}"), admin(), 500);
        call(broken, post(BASE + "/" + existing + "/disable"), admin(), 500);
        call(broken, put(BASE + "/" + existing + "/users/locadm_user"), admin(), 500);

        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM location WHERE code = 'IT-NEW'"), "no location was created");
        assertEquals("Atomic Clinic", row(existing).get("name"));
        assertEquals(1, row(existing).get("status_id"));
        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM user_location WHERE location_id = " + existing));
        assertEquals(0, audits().size());
    }

    @Test
    void aCodeThatWinsTheRaceIsAFieldErrorNotA500() throws Exception {
        MockMvc racing = mvcOn(new AdminLocationsApiController(failingOn("INSERT INTO location ", "23505")));
        JsonNode body = call(racing, json(post(BASE), "{\"code\":\"IT-RACE\",\"name\":\"Race Clinic\"}"), admin(), 400);
        assertEquals(java.util.Set.of("code"), fields(body));
    }

    /* ====================================================================== */
    /* Wiring                                                                  */
    /* ====================================================================== */

    private static MockMvc mvcOn(Object controller) {
        return ProductionMvc.standalone(controller).setControllerAdvice(new ApiExceptionHandler()).build();
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

    /** A system administrator working in the demo study, as the study-scoped audit log needs. */
    private static MockHttpSession adminInStudy1() {
        MockHttpSession s = admin();
        at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean study =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        s.setAttribute("study", study);
        at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean role =
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean();
        role.setRole(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.ADMIN);
        role.setStudyId(1);
        s.setAttribute("userRole", role);
        return s;
    }

    private static MockHttpSession physician() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(7);
        ub.setName("physician");
        s.setAttribute("userBean", ub);
        return s;
    }

    private static AbstractMockHttpServletRequestBuilder<?> json(AbstractMockHttpServletRequestBuilder<?> b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static JsonNode call(AbstractMockHttpServletRequestBuilder<?> req, MockHttpSession session, int expected)
            throws Exception {
        return call(mvc, req, session, expected);
    }

    /** Performs the request, asserts the status and returns the JSON body (null when there is none). */
    private static JsonNode call(MockMvc on, AbstractMockHttpServletRequestBuilder<?> req, MockHttpSession session,
                                 int expected) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse r = on.perform(req.session(session)).andReturn().getResponse();
        String body = r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expected, r.getStatus(), "status; the body was: " + body);
        return body.isBlank() ? null : JSON.readTree(body);
    }

    private static int status(String verb, String path, String body, MockHttpSession session) throws Exception {
        AbstractMockHttpServletRequestBuilder<?> req = request(HttpMethod.valueOf(verb), path);
        if (body != null) req = json(req, body);
        return mvc.perform(req.session(session)).andReturn().getResponse().getStatus();
    }

    private static int create(String code, String name) throws Exception {
        return call(json(post(BASE), "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"), admin(), 201)
                .get("id").asInt();
    }

    private static int createFull(String code, String name, String street, String city, String zip, String country)
            throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("street", street);
        body.put("city", city);
        body.put("zip", zip);
        body.put("country", country);
        return call(json(post(BASE), JSON.writeValueAsString(body)), admin(), 201).get("id").asInt();
    }

    /** The field names of a 400 body's errors. */
    private static java.util.Set<String> fields(JsonNode body) {
        java.util.Set<String> out = new TreeSet<>();
        for (JsonNode e : body.get("errors")) out.add(e.get("field").asText());
        return out;
    }

    private static JsonNode byField(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.get(field).asText())) return n;
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    /* ====================================================================== */
    /* Fixtures                                                                */
    /* ====================================================================== */

    /** A study (a site when {@code parent} is given) at {@code locationId} with the given status id. */
    private static int studyAt(int locationId, Integer parent, String uid, int statusId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int id = LifecycleFixtures.insertStudy(c, parent, uid, uid + " name",
                    "S_" + uid.toUpperCase(Locale.ROOT).replace('-', '_'), statusId);
            exec(c, "UPDATE study SET location_id = " + locationId + " WHERE study_id = " + id);
            return id;
        }
    }

    /** An ingested file whose origin is the location. */
    private static void ingestAt(int locationId) throws SQLException {
        exec("INSERT INTO ingest_item (kind, source_kind, stored_path, status, device, received_at, origin_location_id) "
                + "VALUES ('image', 'upload', '/x/locadm-" + SEQ.incrementAndGet() + "', 'UNBOUND', 'locadm-cam', now(), "
                + locationId + ")");
    }

    /* ====================================================================== */
    /* Database                                                                */
    /* ====================================================================== */

    /** One md5 over the tables a location write touches; equal snapshots mean nothing was written. */
    private static String snapshot() throws SQLException {
        String sql = "SELECT "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM location x) || '/' || "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM user_location x) || '/' || "
                + "(SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM audit_log_event x "
                + " WHERE x.audit_table = 'location')";
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private record Audit(int type, int entityId, String entityName, String oldValue, String newValue, Integer userId) {}

    /** The audit rows of locations, oldest first. */
    private static List<Audit> audits() throws SQLException {
        List<Audit> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT audit_log_event_type_id, entity_id, entity_name, old_value, new_value, "
                     + "user_id FROM audit_log_event WHERE audit_table = 'location' ORDER BY audit_id")) {
            while (rs.next()) {
                int user = rs.getInt(6);
                boolean noUser = rs.wasNull();
                out.add(new Audit(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        noUser ? null : user));
            }
        }
        return out;
    }

    private static Map<String, Object> row(int id) throws SQLException {
        return one("SELECT * FROM location WHERE location_id = " + id);
    }

    private static Map<String, Object> assignment(int locationId, String username) throws SQLException {
        return one("SELECT ul.* FROM user_location ul JOIN user_account ua ON ua.user_id = ul.user_account_id "
                + "WHERE ul.location_id = " + locationId + " AND ua.user_name = '" + username + "'");
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

    private static String stringQuery(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getString(1);
        }
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
