/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * DR-040 — the inbox is scoped by location.
 *
 * <p>An unfiled (unbound or dismissed) file that no signed-in user uploaded
 * has no study, so a study cannot say who may see it. Its place can: a user
 * sees the device files of the locations they work at and never those of
 * another. The world is the cross-site one (site A at location LA, site B at
 * LB, Default Study at LA), so two sites of one study sit at two places, and
 * the rule is checked end to end: the list, the counts, a single read, the
 * preview and every state change, including the retinal parked-job endpoints
 * that share the rule.
 *
 * <p>The rule being pinned, for an unfiled file:
 * <ul>
 *   <li>uploaded by signed-in staff ({@code origin_study_id}): visible to those
 *       who can see that study, as before, whatever its location;</li>
 *   <li>from a device or an anonymous page ({@code origin_location_id}):
 *       visible to those who work at that location;</li>
 *   <li>of neither: visible to system administrators only;</li>
 * </ul>
 * and a filed file is decided by its subject's study, as before.
 */
@SuppressWarnings("null")
class LocationVisibilityDatabaseIT extends CrossSiteIsolationSupport {

    private static final String INGEST = "/api/v1/ingest/";
    private static final AtomicInteger N = new AtomicInteger(5000);
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /* ====================================================================== */
    /* Who may see what                                                        */
    /* ====================================================================== */

    @Test
    void aSiteUserSeesTheDeviceFilesOfItsLocationAndNeverThoseOfAnother() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        MockHttpSession b = loginAs(Who.CRC, B);
        int unboundA = deviceItem("UNBOUND", LA);
        int unboundB = deviceItem("UNBOUND", LB);
        int dismissedA = deviceItem("DISMISSED", LA);
        int dismissedB = deviceItem("DISMISSED", LB);

        Set<Integer> seenByA = listed(a, "UNBOUND");
        Set<Integer> seenByB = listed(b, "UNBOUND");
        assertTrue(seenByA.contains(unboundA) && !seenByA.contains(unboundB), "site A lists LA's file, not LB's: " + seenByA);
        assertTrue(seenByB.contains(unboundB) && !seenByB.contains(unboundA), "site B lists LB's file, not LA's: " + seenByB);
        Set<Integer> dismissedByA = listed(a, "DISMISSED");
        Set<Integer> dismissedByB = listed(b, "DISMISSED");
        assertTrue(dismissedByA.contains(dismissedA) && !dismissedByA.contains(dismissedB), "dismissed, site A: " + dismissedByA);
        assertTrue(dismissedByB.contains(dismissedB) && !dismissedByB.contains(dismissedA), "dismissed, site B: " + dismissedByB);

        assertEquals(200, call(get(INGEST + unboundA), a).status);
        assertEquals(404, call(get(INGEST + unboundB), a).status, "a file of LB is not found, not forbidden");
        assertEquals(200, call(get(INGEST + dismissedA), a).status);
        assertEquals(404, call(get(INGEST + dismissedB), a).status);
        assertEquals(200, call(get(INGEST + unboundB), b).status);
        assertEquals(404, call(get(INGEST + unboundA), b).status);
    }

    @Test
    void thePreviewIsStreamedToTheLocationOnly() throws Exception {
        int onA = deviceItem("UNBOUND", LA);
        int onB = deviceItem("UNBOUND", LB);
        MockHttpSession a = loginAs(Who.INV, A);
        Resp own = call(get(INGEST + onA + "/preview"), a);
        assertEquals(200, own.status, own.snippet());
        assertEquals(404, call(get(INGEST + onB + "/preview"), a).status);
    }

    @Test
    void theCountsFollowTheRule() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        int before = unboundCount(a);
        deviceItem("UNBOUND", LB);
        assertEquals(before, unboundCount(a), "a file of LB does not count for site A");
        deviceItem("UNBOUND", LA);
        assertEquals(before + 1, unboundCount(a), "a file of LA does");
    }

    @Test
    void everyStateChangeFollowsTheRule() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        Fx own = newSet(A);
        String target = "\"studySubjectId\":" + own.ss + ",\"studyEventId\":" + own.event
                + ",\"eventCrfId\":" + own.eventCrf + ",\"laterality\":\"OD\"";

        // bind
        int foreign = deviceItem("UNBOUND", LB);
        Map<String, String> before = snapshot();
        assertEquals(404, call(json(post(INGEST + foreign + "/bind"), "{" + target + "}"), a).status);
        assertEquals(before, snapshot(), "the refused bind changed tables " + diff(before, snapshot()));
        int mine = deviceItem("UNBOUND", LA);
        Resp bound = call(json(post(INGEST + mine + "/bind"), "{" + target + "}"), a);
        assertEquals(200, bound.status, bound.snippet());

        // bulk bind: the file of LB is skipped as not found, the other is filed
        int foreign2 = deviceItem("UNBOUND", LB);
        int mine2 = deviceItem("UNBOUND", LA);
        Resp bulk = call(json(post(INGEST + "bulk-bind"), "{\"ids\":[" + foreign2 + "," + mine2
                + "],\"studySubjectId\":" + own.ss + ",\"studyEventId\":" + own.event + ",\"eventCrfId\":"
                + own.eventCrf + "}"), a);
        assertEquals(200, bulk.status, bulk.snippet());
        assertTrue(bulk.body.contains("\"bound\":[" + mine2 + "]"), bulk.snippet());
        boolean skippedAsNotFound = false;
        for (tools.jackson.databind.JsonNode skipped : tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(bulk.body).get("skipped")) {
            skippedAsNotFound |= skipped.get("id").asInt() == foreign2 && "NOT_FOUND".equals(skipped.get("reason").asText());
        }
        assertTrue(skippedAsNotFound, "the file of LB is skipped as not found: " + bulk.snippet());
        assertEquals("UNBOUND", statusOf(foreign2));

        // dismiss and bulk dismiss
        int foreign3 = deviceItem("UNBOUND", LB);
        before = snapshot();
        assertEquals(404, call(json(post(INGEST + foreign3 + "/dismiss"), "{\"reason\":\"x\"}"), a).status);
        assertEquals(before, snapshot());
        int mine3 = deviceItem("UNBOUND", LA);
        assertEquals(200, call(json(post(INGEST + mine3 + "/dismiss"), "{\"reason\":\"x\"}"), a).status);
        int foreign4 = deviceItem("UNBOUND", LB);
        int mine4 = deviceItem("UNBOUND", LA);
        Resp bulkDismiss = call(json(post(INGEST + "bulk-dismiss"),
                "{\"ids\":[" + foreign4 + "," + mine4 + "],\"reason\":\"x\"}"), a);
        assertTrue(bulkDismiss.body.contains("\"dismissed\":[" + mine4 + "]"), bulkDismiss.snippet());
        assertEquals("UNBOUND", statusOf(foreign4));

        // restore
        int dismissedB = deviceItem("DISMISSED", LB);
        before = snapshot();
        assertEquals(404, call(post(INGEST + dismissedB + "/restore"), a).status);
        assertEquals(before, snapshot());
        int dismissedA = deviceItem("DISMISSED", LA);
        assertEquals(200, call(post(INGEST + dismissedA + "/restore"), a).status);

        // undo of an upload (the file may not be undoable, but the caller may not learn it exists)
        int undoB = deviceItem("UNBOUND", LB);
        assertEquals(404, call(delete(INGEST + "upload/items/" + undoB), a).status);
        int undoA = deviceItem("UNBOUND", LA);
        assertNotEquals(404, call(delete(INGEST + "upload/items/" + undoA), a).status,
                "the caller's own location's file is found");
        assertEquals("UNBOUND", statusOf(undoB));
    }

    @Test
    void theRetinalParkedJobEndpointsFollowTheRule() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        Fx own = newSet(A);
        long onB = parkedJob(LB);
        long onA = parkedJob(LA);
        String bind = "{\"eventCrfId\":" + own.eventCrf + "}";

        Map<String, String> before = snapshot();
        assertEquals(404, call(json(patch("/api/v1/retinal-jobs/" + onB + "/bind"), bind), a).status);
        assertEquals(404, call(delete(INGEST + "upload/jobs/" + onB), a).status);
        Resp bulk = call(json(post("/api/v1/retinal-jobs/bulk-bind"),
                "{\"jobIds\":[" + onB + "],\"eventCrfId\":" + own.eventCrf + "}"), a);
        assertTrue(bulk.body.contains("\"notFound\":1"), bulk.snippet());
        assertEquals(before, snapshot(), "refused calls changed tables " + diff(before, snapshot()));
        assertEquals("parked", jobStatus(onB));

        Resp bound = call(json(patch("/api/v1/retinal-jobs/" + onA + "/bind"), bind), a);
        assertEquals(200, bound.status, bound.snippet());
    }

    /* ---------------------------------------------------------------------- */
    /* The other kinds of origin                                               */
    /* ---------------------------------------------------------------------- */

    @Test
    void aStaffUploadKeepsTheStudyRuleWhateverItsLocation() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        // Uploaded in site A, stamped with LB: the study decides.
        int fromA = staffItem(A, LB);
        // Uploaded in site B, stamped with LA: the study decides.
        int fromB = staffItem(B, LA);
        assertTrue(listed(a, "UNBOUND").contains(fromA), "site A sees what site A uploaded");
        assertFalse(listed(a, "UNBOUND").contains(fromB), "LA is site A's place, but the upload was site B's");
        assertEquals(200, call(get(INGEST + fromA), a).status);
        assertEquals(404, call(get(INGEST + fromB), a).status);
        MockHttpSession b = loginAs(Who.CRC, B);
        assertTrue(listed(b, "UNBOUND").contains(fromB));
        assertFalse(listed(b, "UNBOUND").contains(fromA));
    }

    @Test
    void aFileOfUnknownOriginIsVisibleToSystemAdministratorsOnly() throws Exception {
        int nowhere = deviceItem("UNBOUND", null);
        int dismissed = deviceItem("DISMISSED", null);
        for (Site site : new Site[] {A, B}) {
            MockHttpSession s = loginAs(Who.CRC, site);
            assertFalse(listed(s, "UNBOUND").contains(nowhere), site.tag + " lists it");
            assertEquals(404, call(get(INGEST + nowhere), s).status);
            assertEquals(404, call(json(post(INGEST + nowhere + "/dismiss"), "{\"reason\":\"x\"}"), s).status);
            assertEquals(404, call(post(INGEST + dismissed + "/restore"), s).status);
        }
        MockHttpSession parentUser = parentUser();
        assertFalse(listed(parentUser, "UNBOUND").contains(nowhere), "not even a role on the parent study");
        assertEquals("UNBOUND", statusOf(nowhere));

        MockHttpSession admin = sysadmin();
        assertTrue(listed(admin, "UNBOUND").contains(nowhere), "a system administrator sees it");
        assertEquals(200, call(get(INGEST + nowhere), admin).status);
        assertEquals(200, call(post(INGEST + dismissed + "/restore"), admin).status);
        assertEquals(200, call(json(post(INGEST + nowhere + "/dismiss"), "{\"reason\":\"x\"}"), admin).status);
    }

    @Test
    void aParentStudyRoleReachesEverySitesLocation() throws Exception {
        int onA = deviceItem("UNBOUND", LA);
        int onB = deviceItem("UNBOUND", LB);
        MockHttpSession parentUser = parentUser();
        Set<Integer> seen = listed(parentUser, "UNBOUND");
        assertTrue(seen.contains(onA) && seen.contains(onB), "the parent study's coordinator works at both places");
        assertEquals(200, call(get(INGEST + onB), parentUser).status);
    }

    @Test
    void anExplicitAssignmentGrantsThatLocation() throws Exception {
        MockHttpSession a = loginAs(Who.DIR, A);
        int onB = deviceItem("UNBOUND", LB);
        assertFalse(listed(a, "UNBOUND").contains(onB));
        int userId = userId(user(Who.DIR, A));
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "INSERT INTO user_location (user_account_id, location_id) VALUES (" + userId + ", " + LB + ")");
        }
        try {
            assertTrue(listed(a, "UNBOUND").contains(onB), "the assignment adds LB");
            assertEquals(200, call(get(INGEST + onB), a).status);
            int onA = deviceItem("UNBOUND", LA);
            assertTrue(listed(a, "UNBOUND").contains(onA), "and keeps LA");
        } finally {
            try (Connection c = DATA_SOURCE.getConnection()) {
                exec(c, "DELETE FROM user_location WHERE user_account_id = " + userId);
            }
        }
        assertFalse(listed(a, "UNBOUND").contains(onB), "removing it takes LB away again");
    }

    @Test
    void aUserWithNoRoleAnywhereSeesNoDeviceFile() throws Exception {
        int onA = deviceItem("UNBOUND", LA);
        int onB = deviceItem("UNBOUND", LB);
        // Signed in, an active study and a role bound to the session (as a stale or forged session could),
        // but no live role in the database: the location scope reads the roles, not the session.
        try (Connection c = DATA_SOURCE.getConnection()) {
            if (count("SELECT count(*) FROM user_account WHERE user_name = 'iso_norole'") == 0) {
                LifecycleFixtures.insertUser(c, "iso_norole", 1);
            }
        }
        UserAccountBean ub = new UserAccountDAO(DATA_SOURCE).findByUserName("iso_norole");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyDAO(DATA_SOURCE).findByPK(A.id);
        s.setAttribute("study", study);
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.COORDINATOR);
        s.setAttribute("userRole", role);
        Set<Integer> seen = listed(s, "UNBOUND");
        assertFalse(seen.contains(onA) || seen.contains(onB), "no location, no device file: " + seen);
        assertEquals(404, call(get(INGEST + onA), s).status);
    }

    /* ---------------------------------------------------------------------- */
    /* Filed files, and a file that comes back                                 */
    /* ---------------------------------------------------------------------- */

    @Test
    void aFiledFileIsDecidedByItsSubjectsStudyAndComesBackToItsOwnLocation() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        Fx own = newSet(A);
        // A file of LB, filed against a subject of site A (a system administrator did it, say).
        int fromB = deviceItem("UNBOUND", LB);
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE ingest_item SET status = 'BOUND', bound_study_subject_id = " + own.ss
                    + ", bound_study_event_id = " + own.event + ", match_policy = 'manual' WHERE ingest_item_id = " + fromB);
        }
        assertEquals(200, call(get(INGEST + fromB), a).status, "site A sees it by the subject's study");
        assertTrue(listed(a, "BOUND").contains(fromB));

        Resp unbind = call(json(post(INGEST + fromB + "/unbind"), "{\"dismiss\":false}"), a);
        assertEquals(200, unbind.status, unbind.snippet());
        assertEquals("UNBOUND", statusOf(fromB));
        assertEquals(404, call(get(INGEST + fromB), a).status, "unfiled again, it is back in LB's pool");
        assertTrue(listed(loginAs(Who.CRC, B), "UNBOUND").contains(fromB));
    }

    @Test
    void aTwinInAnotherLocationIsNamedByItsNumberOnly() throws Exception {
        MockHttpSession a = loginAs(Who.CRC, A);
        int n = N.incrementAndGet();
        String pixels = String.format("e%063d", n);
        int mine = deviceItem("UNBOUND", LA);
        int theirs = deviceItem("UNBOUND", LB);
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE ingest_item SET pixel_sha256 = '" + pixels + "' WHERE ingest_item_id IN ("
                    + mine + ", " + theirs + ")");
        }
        Resp row = call(get(INGEST + mine), a);
        assertEquals(200, row.status, row.snippet());
        assertTrue(row.body.contains("\"twin\":{\"ingestItemId\":" + theirs), "the twin is named: " + row.snippet());
        assertFalse(row.body.contains("LOCVIS-LB"), "but nothing of LB's file is told: " + row.snippet());
    }

    /* ====================================================================== */
    /* The scope itself                                                        */
    /* ====================================================================== */

    @Test
    void theScopeOfEachKindOfUser() throws Exception {
        LocationScope scope = new LocationScope(DATA_SOURCE);
        assertEquals(Set.of(LA), scope.visibleLocations(account(user(Who.CRC, A))).ids(), "a site role");
        assertEquals(Set.of(LB), scope.visibleLocations(account(user(Who.INV, B))).ids());
        assertEquals(Set.of(LA, LB), scope.visibleLocations(account(PARENT_USER)).ids(),
                "a role on the parent study: its own location and every site's");
        assertEquals(Set.of(LA), scope.visibleLocations(account("iso_multi")).ids(), "a role on a site and on study X");
        assertTrue(scope.visibleLocations(account("root")).isAll(), "a system administrator has every location");
        assertTrue(scope.visibleLocations(null).ids().isEmpty());

        // A role that was revoked, or a site that was removed, grants nothing.
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE study_user_role SET status_id = 5 WHERE user_name = '" + user(Who.CRC, A) + "'");
        }
        try {
            assertTrue(scope.visibleLocations(account(user(Who.CRC, A))).ids().isEmpty(), "a revoked role");
        } finally {
            try (Connection c = DATA_SOURCE.getConnection()) {
                exec(c, "UPDATE study_user_role SET status_id = 1 WHERE user_name = '" + user(Who.CRC, A) + "'");
            }
        }
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE study SET status_id = 5 WHERE study_id = " + B.id);
        }
        try {
            assertTrue(scope.visibleLocations(account(user(Who.INV, B))).ids().isEmpty(), "a removed site");
            assertEquals(Set.of(LA), scope.visibleLocations(account(PARENT_USER)).ids(),
                    "and a removed site no longer adds its place to the parent's coordinator");
        } finally {
            try (Connection c = DATA_SOURCE.getConnection()) {
                exec(c, "UPDATE study SET status_id = 1 WHERE study_id = " + B.id);
            }
        }
    }

    @Test
    void aRemovedLocationGrantsNothingAndTheDefaultIsTheOnlyActiveOne() throws Exception {
        LocationScope scope = new LocationScope(DATA_SOURCE);
        assertNull(scope.defaultLocationId(), "two active locations: there is no default");
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE location SET status_id = 5 WHERE location_id = " + LB);
        }
        try {
            assertEquals(LA, scope.defaultLocationId(), "one active location is the default");
            assertTrue(scope.visibleLocations(account(user(Who.INV, B))).ids().isEmpty(), "a removed place grants nothing");
            assertEquals(Set.of(LA), scope.visibleLocations(account(PARENT_USER)).ids());
        } finally {
            try (Connection c = DATA_SOURCE.getConnection()) {
                exec(c, "UPDATE location SET status_id = 1 WHERE location_id = " + LB);
            }
        }
        assertNull(scope.defaultLocationId());
        assertEquals(Integer.valueOf(LB), scope.locationOfStudy(B.id));
        assertEquals(Integer.valueOf(LB), scope.originForStaffUpload(B.id), "a staff upload takes its study's place");
        assertNull(scope.originForStaffUpload(null), "and with no study and two places, none");
    }

    /* ====================================================================== */
    /* Fixtures                                                                */
    /* ====================================================================== */

    private static final String PARENT_USER = "iso_parent";

    /** A user with a role on Default Study itself (the parent of both sites), made on first use. */
    private static synchronized MockHttpSession parentUser() throws Exception {
        ensureParentUser();
        return login(PARENT_USER);
    }

    private static void ensureParentUser() throws Exception {
        if (count("SELECT count(*) FROM user_account WHERE user_name = '" + PARENT_USER + "'") > 0) return;
        try (Connection c = DATA_SOURCE.getConnection()) {
            LifecycleFixtures.insertUser(c, PARENT_USER, 1);
            LifecycleFixtures.insertRole(c, PARENT_USER, 1, "director", 1);
            exec(c, "UPDATE user_account SET active_study = 1 WHERE user_name = '" + PARENT_USER + "'");
        }
    }

    private static UserAccountBean account(String userName) throws Exception {
        if (PARENT_USER.equals(userName)) ensureParentUser();
        return new UserAccountDAO(DATA_SOURCE).findByUserName(userName);
    }

    private static int userId(String userName) {
        return new UserAccountDAO(DATA_SOURCE).findByUserName(userName).getId();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userBean", new UserAccountDAO(DATA_SOURCE).findByUserName("root"));
        s.setAttribute("study", new StudyDAO(DATA_SOURCE).findByPK(A.id));
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.ADMIN);
        s.setAttribute("userRole", role);
        return s;
    }

    /** An unfiled file from a device at {@code location} (null: of unknown origin), with a preview that can be streamed. */
    private static int deviceItem(String status, Integer location) throws Exception {
        int n = N.incrementAndGet();
        String place = location == null ? "NONE" : location == LA ? "LA" : "LB";
        Path dir = Files.createDirectories(fileRoot.resolve("ingest-store").resolve("location-visibility"));
        Path png = dir.resolve("locvis-" + place.toLowerCase() + "-" + n + ".png");
        Files.write(png, PNG);
        try (Connection c = DATA_SOURCE.getConnection()) {
            return LifecycleFixtures.insertOne(c, "INSERT INTO ingest_item (kind, source_kind, device, stored_path, "
                    + "preview_png_path, original_filename, laterality, received_at, status, patient_id, sha256, "
                    + "byte_size, content_type, origin_location_id) VALUES ('image', 'upload', 'locvis-cam', '"
                    + png.toString().replace('\\', '/') + "', '" + png.toString().replace('\\', '/') + "', 'locvis-"
                    + place.toLowerCase() + "-" + n + ".png', 'OD', now(), '" + status + "', 'LOCVIS-" + place + "-"
                    + n + "', '" + String.format("d%063d", n) + "', 20, 'image/png', "
                    + (location == null ? "NULL" : location) + ") RETURNING ingest_item_id");
        }
    }

    /** An unfiled file a signed-in user of {@code site} uploaded, stamped with {@code location}. */
    private static int staffItem(Site site, int location) throws Exception {
        int id = deviceItem("UNBOUND", location);
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE ingest_item SET origin_study_id = " + site.id + " WHERE ingest_item_id = " + id);
        }
        return id;
    }

    /** A parked retinal job whose scan is an unfiled file of {@code location}. */
    private static long parkedJob(int location) throws Exception {
        int item = deviceItem("UNBOUND", location);
        long job = 930_000L + N.incrementAndGet();
        try (Connection c = DATA_SOURCE.getConnection()) {
            exec(c, "UPDATE ingest_item SET kind = 'e2e' WHERE ingest_item_id = " + item);
            exec(c, "INSERT INTO retinal_inference_job (job_id, task, e2e_path, eye_laterality, status, enqueued_at, "
                    + "model_version, ingest_item_id) VALUES (" + job + ", 'fluid', '/nowhere/locvis-" + job
                    + ".e2e', 'OD', 'parked', now(), 'v1', " + item + ")");
        }
        return job;
    }

    /* ---------------------------------------------------------------------- */

    private static Set<Integer> listed(MockHttpSession session, String status) throws Exception {
        Resp r = call(get(INGEST + "inbox?status=" + status + "&q=LOCVIS&limit=500"), session);
        assertEquals(200, r.status, r.snippet());
        return idsOf(r.body);
    }

    private static int unboundCount(MockHttpSession session) throws Exception {
        Resp r = call(get(INGEST + "inbox/counts"), session);
        assertEquals(200, r.status, r.snippet());
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.body).get("unbound").asInt();
    }

    private static Set<Integer> idsOf(String body) throws Exception {
        Set<Integer> out = new LinkedHashSet<>();
        for (tools.jackson.databind.JsonNode item : tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("items")) {
            out.add(item.get("id").asInt());
        }
        return out;
    }

    private static String statusOf(long ingestItemId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String jobStatus(long jobId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM retinal_inference_job WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int count(String sql) throws Exception {
        return LifecycleFixtures.intQuery(sql);
    }
}
