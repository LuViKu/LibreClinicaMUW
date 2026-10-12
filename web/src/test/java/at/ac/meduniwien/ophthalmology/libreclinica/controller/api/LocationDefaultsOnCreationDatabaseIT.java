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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DR-040 — a study or a site ends up with a location through every way of
 * creating one.
 *
 * <p>While one location is active it is the default, so the SPA, the sites API
 * and the legacy create servlets (which all go through
 * {@code StudyDAO.createStepOne}) put every new study and site there without
 * knowing about locations. Once there are several, the APIs, which can ask, ask:
 * {@code locationId} is required, must be an active location the caller may
 * use, and a refusal is a field error on {@code locationId} that creates
 * nothing. The legacy servlets cannot ask; their study is left unassigned for
 * an administrator, which the inbox treats as no location.
 */
@SuppressWarnings("null")
class LocationDefaultsOnCreationDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final AtomicInteger SEQ = new AtomicInteger(100);

    private static int parent;
    private static String parentOid;
    private static int onlyLocation;
    private static UserAccountBean director;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        onlyLocation = LifecycleFixtures.intQuery("SELECT location_id FROM location WHERE status_id = 1");
        try (Connection c = DATA_SOURCE.getConnection()) {
            parentOid = "S_LOCDEF_P";
            parent = LifecycleFixtures.insertStudy(c, null, "locdef-p", "Locdef parent", parentOid, 1);
            exec(c, "UPDATE study SET location_id = " + onlyLocation + " WHERE study_id = " + parent);
            LifecycleFixtures.insertUser(c, "locdef_director", 1);
            LifecycleFixtures.insertRole(c, "locdef_director", parent, "director", 1);
        }
        director = new UserAccountDAO(DATA_SOURCE).findByUserName("locdef_director");
        assertTrue(director.getId() > 0, "the director exists");
    }

    @AfterEach
    void dropTheSecondLocation() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE study SET location_id = " + onlyLocation
                    + " WHERE location_id IN (SELECT location_id FROM location WHERE code LIKE 'LOCDEF-%')");
            exec(c, "DELETE FROM location WHERE code LIKE 'LOCDEF-%'");
        }
    }

    /* ------------------------------------------------------------------ */
    /* one location                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void aStudyCreatedThroughTheApiGetsTheOnlyLocation() throws Exception {
        String uid = uid("study");
        mvc().perform(post("/api/v1/studies").contentType("application/json")
                        .content(studyJson(uid, null)).session(sysadmin()))
                .andExpect(status().isCreated());
        assertEquals(onlyLocation, locationOfStudyNamed(uid));
    }

    @Test
    void aSiteCreatedThroughTheApiGetsTheOnlyLocation() throws Exception {
        String uid = uid("site");
        mvc().perform(post("/api/v1/studies/" + parentOid + "/sites").contentType("application/json")
                        .content(siteJson(uid, null)).session(sysadmin()))
                .andExpect(status().isCreated());
        assertEquals(onlyLocation, locationOfStudyNamed(uid));
    }

    /** The legacy create servlets and the legacy MVC controller all call StudyDAO.create. */
    @Test
    void aStudyCreatedThroughTheDaoGetsTheOnlyLocation() throws Exception {
        String uid = uid("dao");
        StudyBean bean = daoStudy(uid);
        StudyBean created = new StudyDAO(DATA_SOURCE).create(bean);
        assertTrue(created.getId() > 0);
        assertEquals(onlyLocation, locationOfStudyNamed(uid));
        assertEquals(onlyLocation, new StudyDAO(DATA_SOURCE).findByPK(created.getId()).getLocationId(),
                "and the bean reads it back");
    }

    @Test
    void aDaoStudyThatNamesALocationKeepsIt() throws Exception {
        int other = secondLocation();
        String uid = uid("dao-named");
        StudyBean bean = daoStudy(uid);
        bean.setLocationId(other);
        new StudyDAO(DATA_SOURCE).create(bean);
        assertEquals(other, locationOfStudyNamed(uid));
    }

    /** An update never writes the location: the second step of a create would otherwise reset it. */
    @Test
    void updatingAStudyKeepsItsLocation() throws Exception {
        int other = secondLocation();
        String uid = uid("dao-update");
        StudyBean bean = daoStudy(uid);
        bean.setLocationId(other);
        StudyDAO dao = new StudyDAO(DATA_SOURCE);
        StudyBean created = dao.create(bean);
        StudyBean read = dao.findByPK(created.getId());
        read.setSummary("changed");
        UserAccountBean updater = new UserAccountBean();
        updater.setId(1);
        read.setUpdater(updater);
        read.setUpdatedDate(new java.util.Date());
        dao.update(read);
        assertEquals(other, locationOfStudyNamed(uid));
    }

    /* ------------------------------------------------------------------ */
    /* several locations                                                   */
    /* ------------------------------------------------------------------ */

    @Test
    void withSeveralLocationsAStudyNeedsOne() throws Exception {
        secondLocation();
        String uid = uid("study-needs");
        mvc().perform(post("/api/v1/studies").contentType("application/json")
                        .content(studyJson(uid, null)).session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'locationId')]").exists());
        assertEquals(0, count("SELECT count(*) FROM study WHERE unique_identifier = '" + uid + "'"),
                "nothing was created");
    }

    @Test
    void withSeveralLocationsASiteNeedsOne() throws Exception {
        secondLocation();
        String uid = uid("site-needs");
        mvc().perform(post("/api/v1/studies/" + parentOid + "/sites").contentType("application/json")
                        .content(siteJson(uid, null)).session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'locationId')]").exists());
        assertEquals(0, count("SELECT count(*) FROM study WHERE unique_identifier = '" + uid + "'"));
    }

    @Test
    void aNamedLocationIsUsedForAStudyAndForASite() throws Exception {
        int other = secondLocation();
        String studyUid = uid("study-named");
        mvc().perform(post("/api/v1/studies").contentType("application/json")
                        .content(studyJson(studyUid, other)).session(sysadmin()))
                .andExpect(status().isCreated());
        assertEquals(other, locationOfStudyNamed(studyUid));
        String siteUid = uid("site-named");
        mvc().perform(post("/api/v1/studies/" + parentOid + "/sites").contentType("application/json")
                        .content(siteJson(siteUid, other)).session(sysadmin()))
                .andExpect(status().isCreated());
        assertEquals(other, locationOfStudyNamed(siteUid));
    }

    @Test
    void anUnknownOrRemovedLocationIsRefusedWithAFieldError() throws Exception {
        int other = secondLocation();
        String unknown = uid("unknown");
        mvc().perform(post("/api/v1/studies").contentType("application/json")
                        .content(studyJson(unknown, 999_999)).session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'locationId')]").exists());
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE location SET status_id = 5 WHERE location_id = " + other);
        }
        String removed = uid("removed");
        mvc().perform(post("/api/v1/studies/" + parentOid + "/sites").contentType("application/json")
                        .content(siteJson(removed, other)).session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'locationId')]").exists());
        assertEquals(0, count("SELECT count(*) FROM study WHERE unique_identifier IN ('" + unknown + "', '"
                + removed + "')"));
    }

    /** A director can create sites, but only at places that are theirs. */
    @Test
    void aNonAdministratorMayOnlyUseALocationOfTheirOwn() throws Exception {
        int other = secondLocation();
        String foreign = uid("foreign");
        mvc().perform(post("/api/v1/studies/" + parentOid + "/sites").contentType("application/json")
                        .content(siteJson(foreign, other)).session(directorSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'locationId')]").exists());
        assertEquals(0, count("SELECT count(*) FROM study WHERE unique_identifier = '" + foreign + "'"));

        String own = uid("own");
        mvc().perform(post("/api/v1/studies/" + parentOid + "/sites").contentType("application/json")
                        .content(siteJson(own, onlyLocation)).session(directorSession()))
                .andExpect(status().isCreated());
        assertEquals(onlyLocation, locationOfStudyNamed(own));
    }

    /** The legacy servlets cannot ask: with several locations their study waits, unassigned. */
    @Test
    void aDaoStudyWithSeveralLocationsAndNoneNamedIsLeftUnassigned() throws Exception {
        secondLocation();
        String uid = uid("dao-unassigned");
        new StudyDAO(DATA_SOURCE).create(daoStudy(uid));
        assertNull(locationOfStudyNamed(uid));
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static String uid(String what) {
        return "locdef-" + what + "-" + SEQ.incrementAndGet();
    }

    private static String studyJson(String uid, Integer locationId) {
        return "{\"name\":\"" + uid + " name\",\"uniqueProtocolId\":\"" + uid + "\",\"briefSummary\":\"s\","
                + "\"principalInvestigator\":\"PI\",\"sponsor\":\"MUW\""
                + (locationId == null ? "" : ",\"locationId\":" + locationId) + "}";
    }

    private static String siteJson(String uid, Integer locationId) {
        return "{\"name\":\"" + uid + " name\",\"uniqueProtocolId\":\"" + uid + "\","
                + "\"principalInvestigator\":\"PI\""
                + (locationId == null ? "" : ",\"locationId\":" + locationId) + "}";
    }

    private static StudyBean daoStudy(String uid) {
        UserAccountBean owner = new UserAccountBean();
        owner.setId(1);
        owner.setName("root");
        StudyBean bean = new StudyBean();
        bean.setName(uid + " name");
        bean.setIdentifier(uid);
        bean.setSummary("s");
        bean.setPrincipalInvestigator("PI");
        bean.setSponsor("MUW");
        bean.setStatus(Status.PENDING);
        bean.setOwner(owner);
        bean.setOid("S_" + uid.toUpperCase(Locale.ROOT).replace('-', '_'));
        return bean;
    }

    private static int secondLocation() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            return LifecycleFixtures.insertOne(c, "INSERT INTO location (code, name) VALUES ('LOCDEF-B-"
                    + SEQ.incrementAndGet() + "', 'Second place') RETURNING location_id");
        }
    }

    private static Integer locationOfStudyNamed(String uid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT location_id FROM study WHERE unique_identifier = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no study " + uid);
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static int count(String sql) throws SQLException {
        return LifecycleFixtures.intQuery(sql);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static MockMvc mvc() {
        return ProductionMvc.standalone(new StudiesApiController(DATA_SOURCE), new SitesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        session.setAttribute("study", parentBean());
        return session;
    }

    private static MockHttpSession directorSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userBean", director);
        session.setAttribute("study", parentBean());
        return session;
    }

    private static StudyBean parentBean() {
        StudyBean s = new StudyBean();
        s.setId(parent);
        s.setOid(parentOid);
        return s;
    }
}
