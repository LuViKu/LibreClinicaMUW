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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import tools.jackson.databind.JsonNode;

/**
 * DR-040 phase 4: where a study or site is. The location options a form may
 * offer ({@code GET /locations}), the facility address a site takes from its
 * location, moving a site or a study, and the rules on whose locations those
 * may be.
 */
class SiteLocationDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String PARENT = "S_P4LOC";
    private static final String DIRECTOR = "p4_director";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static int parent;
    private static int directorId;
    private static int defaultId;
    private static int locB;
    private static int locC;
    private static int locGone;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            defaultId = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
            locB = LifecycleFixtures.insertOne(c, "INSERT INTO location (code, name, status_id, street, city, zip, "
                    + "country, date_created) VALUES ('P4-B', 'Bravo Klinik', 1, 'Gasse 1', 'Graz', '8010', 'AT', now()) "
                    + "RETURNING location_id");
            locC = LifecycleFixtures.insertOne(c, "INSERT INTO location (code, name, status_id, city, date_created) "
                    + "VALUES ('P4-C', 'Charlie Klinik', 1, 'Linz', now()) RETURNING location_id");
            locGone = LifecycleFixtures.insertOne(c, "INSERT INTO location (code, name, status_id, date_created) "
                    + "VALUES ('P4-GONE', 'Alte Klinik', 5, now()) RETURNING location_id");
            parent = LifecycleFixtures.insertStudy(c, null, "p4loc", "P4 location study", PARENT, 1);
            exec(c, "UPDATE study SET location_id = " + defaultId + " WHERE study_id = " + parent);
            LifecycleFixtures.insertUser(c, DIRECTOR, 1);
            directorId = LifecycleFixtures.intQuery("SELECT user_id FROM user_account WHERE user_name = '" + DIRECTOR + "'");
            LifecycleFixtures.insertRole(c, DIRECTOR, parent, "coordinator", 1);
            // The director's scope: the parent study's location, and B by assignment. Not C.
            exec(c, "INSERT INTO user_location (user_account_id, location_id, can_manage_sources) VALUES ("
                    + directorId + ", " + locB + ", false)");
        }
    }

    @BeforeEach
    void multiMode() {
        LocationMode.set(LocationMode.MULTI);
    }

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "DELETE FROM audit_log_event WHERE audit_table = 'study' AND entity_id IN "
                    + "(SELECT study_id FROM study WHERE parent_study_id = " + parent + " OR study_id = " + parent + ")");
            exec(c, "DELETE FROM study WHERE parent_study_id = " + parent);
            exec(c, "UPDATE study SET location_id = " + defaultId + " WHERE study_id = " + parent);
        }
    }

    @AfterAll
    static void singleMode() {
        LocationMode.set(LocationMode.SINGLE);
    }

    /* ---------------------------------------------------------------- */
    /* GET /locations                                                    */
    /* ---------------------------------------------------------------- */

    @Test
    void aSystemAdministratorIsOfferedEveryAvailableLocationByName() throws Exception {
        JsonNode list = call(get("/api/v1/locations"), sysadmin(), 200);
        List<String> codes = codes(list);
        assertTrue(codes.containsAll(List.of("P4-B", "P4-C", "MUW-AUGEN")), codes.toString());
        assertFalse(codes.contains("P4-GONE"), "a disabled location is not offered");
        JsonNode b = byCode(list, "P4-B");
        assertEquals("Bravo Klinik", b.get("name").asText());
        assertEquals("Graz", b.get("city").asText());
        assertEquals("8010", b.get("zip").asText());
        List<String> names = new ArrayList<>();
        list.forEach(n -> names.add(n.get("name").asText()));
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals(sorted, names, "ordered by name");
    }

    @Test
    void anyoneElseIsOfferedTheLocationsInTheirOwnScopeOnly() throws Exception {
        List<String> codes = codes(call(get("/api/v1/locations"), director(), 200));
        assertEquals(List.of("P4-B", "MUW-AUGEN").stream().sorted().toList(), codes.stream().sorted().toList(),
                "the parent study's location and the assigned one; never C");
    }

    @Test
    void anAnonymousCallerIsNotOfferedAnything() throws Exception {
        assertEquals(401, mvc().perform(get("/api/v1/locations").session(new MockHttpSession()))
                .andReturn().getResponse().getStatus());
    }

    /* ---------------------------------------------------------------- */
    /* creating a site                                                   */
    /* ---------------------------------------------------------------- */

    @Test
    void aNewSiteTakesItsFacilityAddressFromItsLocation() throws Exception {
        String uid = "p4-site-" + SEQ.incrementAndGet();
        JsonNode site = call(json(post("/api/v1/studies/" + PARENT + "/sites"), "{\"name\":\"P4 site " + uid
                + "\",\"uniqueProtocolId\":\"" + uid + "\",\"principalInvestigator\":\"Dr. P\","
                + "\"facilityName\":\"typed name\",\"facilityCity\":\"typed city\",\"facilityContactEmail\":\"a@b.c\","
                + "\"locationId\":" + locB + "}"), sysadmin(), 201);
        assertEquals(locB, site.get("locationId").asInt());
        assertEquals("P4-B", site.get("locationCode").asText());
        assertEquals("Bravo Klinik", site.get("locationName").asText());
        Row row = row(uid);
        assertEquals(locB, row.locationId);
        assertEquals("Bravo Klinik", row.facilityName, "the location's name, not the typed one");
        assertEquals("Graz", row.facilityCity);
        assertEquals("8010", row.facilityZip);
        assertEquals("AT", row.facilityCountry);
        assertEquals("a@b.c", row.contactEmail, "the contact stays what was sent");
    }

    @Test
    void inMultiModeASiteWithoutALocationIsRefusedOnTheField() throws Exception {
        String uid = "p4-site-" + SEQ.incrementAndGet();
        JsonNode body = call(json(post("/api/v1/studies/" + PARENT + "/sites"), "{\"name\":\"P4 site " + uid
                + "\",\"uniqueProtocolId\":\"" + uid + "\",\"principalInvestigator\":\"Dr. P\"}"), sysadmin(), 400);
        assertTrue(body.toString().contains("locationId"), body.toString());
        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM study WHERE unique_identifier = '" + uid + "'"));
    }

    @Test
    void theSitesListSaysWhereEachSiteIs() throws Exception {
        String uid = createSite(defaultId);
        JsonNode list = call(get("/api/v1/studies/" + PARENT + "/sites"), sysadmin(), 200);
        JsonNode mine = null;
        for (JsonNode n : list) if (("S_" + uid.toUpperCase()).equals(n.get("oid").asText())) mine = n;
        assertEquals("MUW-AUGEN", mine.get("locationCode").asText(), list.toString());
    }

    /* ---------------------------------------------------------------- */
    /* moving a site                                                     */
    /* ---------------------------------------------------------------- */

    @Test
    void aSiteMovesToALocationInTheCallersScopeAndTakesItsAddress() throws Exception {
        String uid = createSite(defaultId);
        JsonNode moved = call(json(put("/api/v1/studies/" + PARENT + "/sites/S_" + uid.toUpperCase()),
                "{\"locationId\":" + locB + "}"), director(), 200);
        assertEquals("P4-B", moved.get("locationCode").asText());
        Row row = row(uid);
        assertEquals(locB, row.locationId);
        assertEquals("Bravo Klinik", row.facilityName);
        assertEquals("Graz", row.facilityCity);
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM audit_log_event WHERE audit_table = 'study' "
                + "AND entity_id = " + row.id + " AND audit_log_event_type_id = " + AuditTypeIds.SITE_FIELD_UPDATED
                + " AND entity_name = 'location' AND old_value = 'MUW-AUGEN' AND new_value = 'P4-B' "
                + "AND user_id = " + directorId), "the move is audited, by whom and from where to where");
    }

    @Test
    void aSiteCannotBeMovedOutOfTheCallersScopeNorToADisabledLocation() throws Exception {
        String uid = createSite(defaultId);
        String path = "/api/v1/studies/" + PARENT + "/sites/S_" + uid.toUpperCase();
        JsonNode outOfScope = call(json(put(path), "{\"locationId\":" + locC + "}"), director(), 400);
        assertTrue(outOfScope.toString().contains("locationId"), outOfScope.toString());
        JsonNode disabled = call(json(put(path), "{\"locationId\":" + locGone + "}"), sysadmin(), 400);
        assertTrue(disabled.toString().contains("locationId"), disabled.toString());
        assertEquals(defaultId, row(uid).locationId, "nothing moved");
        assertEquals(0, LifecycleFixtures.intQuery("SELECT count(*) FROM audit_log_event WHERE audit_table = 'study' "
                + "AND entity_id = " + row(uid).id), "and nothing was audited");
    }

    @Test
    void anUpdateWithoutALocationLeavesItWhereItIs() throws Exception {
        String uid = createSite(locB);
        call(json(put("/api/v1/studies/" + PARENT + "/sites/S_" + uid.toUpperCase()),
                "{\"name\":\"P4 renamed " + uid + "\"}"), sysadmin(), 200);
        assertEquals(locB, row(uid).locationId);
    }

    /* ---------------------------------------------------------------- */
    /* a study's location                                                */
    /* ---------------------------------------------------------------- */

    @Test
    void aStudyMovesThroughItsOwnEditButASiteDoesNot() throws Exception {
        JsonNode moved = call(json(put("/api/v1/studies/" + PARENT), "{\"locationId\":" + locB + "}"), sysadmin(), 200);
        assertEquals("P4-B", moved.get("locationCode").asText());
        assertEquals(locB, LifecycleFixtures.intQuery("SELECT location_id FROM study WHERE study_id = " + parent));
        assertEquals(1, LifecycleFixtures.intQuery("SELECT count(*) FROM audit_log_event WHERE audit_table = 'study' "
                + "AND entity_id = " + parent + " AND old_value = 'MUW-AUGEN' AND new_value = 'P4-B'"));

        String uid = createSite(defaultId);
        JsonNode refused = call(json(put("/api/v1/studies/S_" + uid.toUpperCase()), "{\"locationId\":" + locB + "}"),
                sysadmin(), 400);
        assertTrue(refused.toString().contains("locationId"), refused.toString());
        assertEquals(defaultId, row(uid).locationId);
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                           */
    /* ---------------------------------------------------------------- */

    private record Row(int id, int locationId, String facilityName, String facilityCity, String facilityZip,
                       String facilityCountry, String contactEmail) {}

    private static Row row(String uid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT study_id, location_id, facility_name, facility_city, "
                     + "facility_zip, facility_country, facility_contact_email FROM study WHERE unique_identifier = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no study " + uid);
                return new Row(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7));
            }
        }
    }

    /** A site at the location, made by a system administrator; returns its unique id. */
    private String createSite(int locationId) throws Exception {
        String uid = "p4-site-" + SEQ.incrementAndGet();
        call(json(post("/api/v1/studies/" + PARENT + "/sites"), "{\"name\":\"P4 site " + uid
                + "\",\"uniqueProtocolId\":\"" + uid + "\",\"principalInvestigator\":\"Dr. P\",\"locationId\":"
                + locationId + "}"), sysadmin(), 201);
        return uid;
    }

    private static List<String> codes(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("code").asText()));
        return out;
    }

    private static JsonNode byCode(JsonNode list, String code) {
        for (JsonNode n : list) if (code.equals(n.get("code").asText())) return n;
        throw new AssertionError("no " + code + " in " + list);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode call(MockHttpServletRequestBuilder req, MockHttpSession session, int expected) throws Exception {
        MockHttpServletResponse resp = mvc().perform(req.session(session)).andReturn().getResponse();
        String body = resp.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expected, resp.getStatus(), body);
        return body.isEmpty() ? null : Json.mapper().readTree(body);
    }

    private MockMvc mvc() {
        return ProductionMvc.standalone(new LocationsApiController(DATA_SOURCE), new SitesApiController(DATA_SOURCE),
                        new StudiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        return session(ub);
    }

    private static MockHttpSession director() {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(directorId);
        ub.setName(DIRECTOR);
        ub.addUserType(UserType.USER);
        return session(ub);
    }

    private static MockHttpSession session(UserAccountBean ub) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", ub);
        StudyBean s = new StudyBean();
        s.setId(parent);
        s.setOid(PARENT);
        session.setAttribute("study", s);
        return session;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (java.sql.Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
