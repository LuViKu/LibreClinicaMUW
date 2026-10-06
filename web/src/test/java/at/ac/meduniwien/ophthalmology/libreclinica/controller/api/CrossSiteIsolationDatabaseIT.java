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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockHttpSession;

/**
 * Cross-site isolation of the SPA REST surface: a user whose every role is on
 * ONE site of a multicenter study reads or changes nothing of another site.
 * See {@link CrossSiteIsolationSupport} for the world, the session and the
 * oracles, and {@link CrossSiteIsolationMatrix} for the endpoints.
 *
 * <p>Every authenticated case is asserted here. A refusal found missing is
 * parked in {@link CrossSiteIsolationMatrix#KNOWN_LEAKS} (empty today) and
 * runs in {@link CrossSiteIsolationLeaksIT} until the guard exists; that class
 * otherwise holds only the anonymous Public* portal cases, which are by design
 * on the internal deployment and closed on the internet-facing one.
 *
 * <p>Run it (Linux, Docker socket for Testcontainers; see CLAUDE.md for the container recipe):
 * <pre>
 *   mvn -B -ntp -DskipSpa=true -P integration-tests -pl web  *       -Dtest='CrossSiteIsolationDatabaseIT' test           # the gate
 *   mvn ... -Dtest='CrossSiteIsolationLeaksIT' test           # the findings (expected to fail)
 *   ISO_ONLY='EventCrfsApiController#saveItems' mvn ...        # only the matrix names matching a regex
 * </pre>
 * A failing name reads {@code Controller#handler[|variant]|ROLE|HOME-&gt;OTHER}: a {@code ROLE} of site
 * {@code HOME} called it with the identifiers of site {@code OTHER}. Each case also creates a fresh set of
 * records for writes (reads share one set per site), so a write that is not refused cannot spoil the next case.
 */
class CrossSiteIsolationDatabaseIT extends CrossSiteIsolationSupport {

    /* ====================================================================== */
    /* Inventory                                                               */
    /* ====================================================================== */

    /**
     * Every request-mapped handler of {@code controller/api}, found by
     * reflection, is in the matrix or on an out-of-scope list with a reason: a
     * new endpoint cannot skip the isolation check silently.
     */
    @Test
    void everyEndpointIsInTheMatrixOrExplicitlyOutOfScope() throws Exception {
        List<ApiEndpointInventory.Endpoint> all = ApiEndpointInventory.all();
        Set<String> inMatrix = new TreeSet<>(CrossSiteIsolationMatrix.allKeys());
        Set<String> uncovered = new TreeSet<>();
        Set<String> staleMatrix = new TreeSet<>(inMatrix);
        int idTaking = 0;
        int matrix = 0;
        int controllerOut = 0;
        int handlerOut = 0;
        for (ApiEndpointInventory.Endpoint e : all) {
            staleMatrix.remove(e.key);
            boolean ids = e.inputs.stream().anyMatch(ApiEndpointInventory::idLike);
            if (ids) idTaking++;
            String ctrl = e.controller.getSimpleName();
            if (inMatrix.contains(e.key)) {
                matrix++;
            } else if (CrossSiteIsolationMatrix.OUT_OF_SCOPE_HANDLERS.containsKey(e.key)) {
                handlerOut++;
            } else if (CrossSiteIsolationMatrix.OUT_OF_SCOPE_CONTROLLERS.containsKey(ctrl)) {
                controllerOut++;
            } else if (CrossSiteIsolationMatrix.STUDY_DESIGN_CONTROLLERS.contains(ctrl)) {
                controllerOut++;
            } else {
                uncovered.add(e.toString());
            }
        }
        System.out.println("[isolation] controller/api handlers: " + all.size() + ", identifier-taking: " + idTaking
                + ", in the matrix: " + matrix + ", out of scope by controller: " + controllerOut
                + ", out of scope by handler: " + handlerOut);
        assertTrue(uncovered.isEmpty(),
                "endpoints neither in the isolation matrix nor out of scope: " + String.join("\n  ", uncovered));
        assertTrue(staleMatrix.isEmpty(), "matrix keys without a handler: " + staleMatrix);
        for (Map.Entry<String, String> e : CrossSiteIsolationMatrix.OUT_OF_SCOPE_HANDLERS.entrySet()) {
            assertTrue(all.stream().anyMatch(x -> x.key.equals(e.getKey())), "stale out-of-scope handler " + e.getKey());
        }
    }

    /** The parked-job pool handlers exist, and the matrix (not a blanket exemption) covers each. */
    @Test
    void theParkedJobHandlersAreInventoriedAndInTheMatrix() throws Exception {
        Set<String> inventory = ApiEndpointInventory.keys(ApiEndpointInventory.all());
        Set<String> matrix = new TreeSet<>(CrossSiteIsolationMatrix.allKeys());
        for (String key : new String[] {
                "RetinalResultsApiController#listParkedJobs", "RetinalResultsApiController#bindParkedJob",
                "RetinalResultsApiController#bulkBindParkedJobs", "RetinalResultsApiController#getJob",
                "RetinalJobArtifactsApiController#streamArtifact", "RetinalJobStatusSseController#stream",
                "IngestUploadApiController#undoStaffUploadJob"}) {
            assertTrue(inventory.contains(key), "no such handler any more: " + key);
            assertTrue(matrix.contains(key), "not in the isolation matrix: " + key);
        }
        assertEquals(403, call(get("/api/v1/retinal-jobs?status=parked"), loginAs(Who.DIR, A)).status,
                "the cross-study parked list is sysadmin-only");
    }

    /* ====================================================================== */
    /* The matrix                                                              */
    /* ====================================================================== */

    @TestFactory
    Stream<DynamicNode> siteAUserCannotReachSiteB() {
        return CrossSiteIsolationMatrix.run(name -> !CrossSiteIsolationMatrix.isKnownLeak(name));
    }

    /* ====================================================================== */
    /* Session tampering                                                       */
    /* ====================================================================== */

    /** The way to a wider view is the study switch; a site user cannot take it. */
    @TestFactory
    Stream<DynamicNode> aSiteUserCannotSwitchTheActiveStudyToTheParentOrAnotherSite() {
        List<DynamicNode> nodes = new ArrayList<>();
        for (Who w : Who.values()) {
            nodes.add(org.junit.jupiter.api.DynamicTest.dynamicTest("switch refused for " + w, () -> {
                MockHttpSession s = freshLogin(user(w, A));
                for (String oid : new String[] {"S_DEFAULTS1", B.oid, studyXOid}) {
                    Resp r = call(json(post("/api/v1/me/activeStudy"), "{\"oid\":\"" + oid + "\"}"), s);
                    assertEquals(403, r.status, w + " switching to " + oid + " got " + r.snippet());
                }
                StudyBeanProbe.assertBoundTo(s, A.id);
                Fx b = newSet(B);
                Resp listing = call(get("/api/v1/subjects/"), s);
                assertFalse(B.markedIn(listing.body), "the subject list shows site B after refused switches");
                int st = call(get("/api/v1/subjects/" + b.oid), s).status;
                assertTrue(st == 403 || st == 404, "site B's subject after refused switches: " + st);
            }));
        }
        nodes.add(org.junit.jupiter.api.DynamicTest.dynamicTest("switch to the own site works (control)", () -> {
            MockHttpSession s = freshLogin(user(Who.INV, A));
            Resp r = call(json(post("/api/v1/me/activeStudy"), "{\"oid\":\"" + A.oid + "\"}"), s);
            assertEquals(200, r.status, r.snippet());
        }));
        return nodes.stream();
    }

    /**
     * A user with roles on site A and on another study, bound to either, can
     * deep-link to a job or visit of a study she holds a role on, and to
     * nothing of site B.
     */
    @TestFactory
    Stream<DynamicNode> aMultiStudyUserCannotDeepLinkIntoASiteSheHasNoRoleOn() {
        List<DynamicNode> nodes = new ArrayList<>();
        Set<String> deepLinkControllers = Set.of("NamdClinicalApiController", "RetinalJobArtifactsApiController",
                "RetinalResultsApiController", "RetinalJobStatusSseController");
        CrossSiteIsolationMatrix.define();
        for (String boundTo : new String[] {"A", "X"}) {
            for (Case k : CASES) {
                String ctrl = k.key.substring(0, k.key.indexOf('#'));
                if (!deepLinkControllers.contains(ctrl) || k.kind == Kind.LIST) continue;
                if (CrossSiteIsolationMatrix.KNOWN_LEAKS.contains(k.key)) continue;
                nodes.add(org.junit.jupiter.api.DynamicTest.dynamicTest(k.key + " bound to " + boundTo, () -> {
                    MockHttpSession s = freshLogin("iso_multi");
                    if (boundTo.equals("X")) {
                        Resp sw = call(json(post("/api/v1/me/activeStudy"), "{\"oid\":\"" + studyXOid + "\"}"), s);
                        assertEquals(200, sw.status, "iso_multi may work in study X: " + sw.snippet());
                    }
                    Fx foreign = newSet(B);
                    var req = k.req.apply(foreign);
                    Map<String, String> before = snapshot();
                    Resp r = call(req, s);
                    Map<String, String> after = snapshot();
                    assertTrue(refused(k, r, B), "DEEP-LINK LEAK " + k.key + " (session bound to " + boundTo
                            + ") reached site B: " + r.snippet());
                    assertEquals(before, after, "deep link changed " + diff(before, after));
                }));
            }
        }
        // Control: the deep link is a relaxation for a study she HAS a role on.
        nodes.add(org.junit.jupiter.api.DynamicTest.dynamicTest("deep link into a site she holds a role on works (control)", () -> {
            MockHttpSession s = freshLogin("iso_multi");
            call(json(post("/api/v1/me/activeStudy"), "{\"oid\":\"" + studyXOid + "\"}"), s);
            Fx own = newSet(A);
            Resp r = call(get("/api/v1/retinal-jobs/" + own.job), s);
            assertEquals(200, r.status, r.snippet());
        }));
        return nodes.stream();
    }

    /* ====================================================================== */
    /* Ingest origin                                                           */
    /* ====================================================================== */

    private static Integer originOf(long ingestItemId) throws Exception {
        try (java.sql.Connection c = DATA_SOURCE.getConnection();
             java.sql.Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                     "SELECT origin_study_id FROM ingest_item WHERE ingest_item_id = " + ingestItemId)) {
            if (!rs.next()) return null;
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }
    }

    /** A staff upload records the uploader's session study, for a parked and for a visit-picked file. */
    @Test
    void aStaffUploadRecordsTheUploadersStudyAsItsOrigin() throws Exception {
        Fx own = newSet(A);
        MockHttpSession s = loginAs(Who.INV, A);
        // Parked: no visit named, so the file lands UNBOUND.
        Resp parked = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/ingest/upload/commit")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "origin-a.jpg", "image/jpeg",
                        originJpeg(1)))
                .param("scanDate", java.time.LocalDate.now().toString()), s);
        assertEquals(201, parked.status, parked.snippet());
        long parkedId = idOf(parked.body);
        assertEquals(Integer.valueOf(A.id), originOf(parkedId), "the unbound upload carries the uploader's study");

        // Filed against a visit at upload.
        Resp filed = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/ingest/upload/commit")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "origin-b.jpg", "image/jpeg",
                        originJpeg(2)))
                .param("patientId", own.pid).param("studyEventId", String.valueOf(own.event))
                .param("scanDate", java.time.LocalDate.now().toString()), s);
        assertEquals(201, filed.status, filed.snippet());
        assertEquals(Integer.valueOf(A.id), originOf(idOf(filed.body)), "the bound upload carries the uploader's study");

        // Site B's coordinator sees neither file in the unbound pool.
        Resp bView = call(get("/api/v1/ingest/inbox"), loginAs(Who.CRC, B));
        assertEquals(200, bView.status, bView.snippet());
        assertFalse(bView.body.contains("\"id\":" + parkedId + ","), "site B must not list site A's parked upload");
        assertEquals(404, call(get("/api/v1/ingest/" + parkedId), loginAs(Who.CRC, B)).status);
        // Site A's coordinator does.
        Resp aView = call(get("/api/v1/ingest/inbox"), loginAs(Who.CRC, A));
        assertTrue(aView.body.contains("\"id\":" + parkedId + ","), "site A lists its own parked upload: " + aView.snippet());
    }

    /**
     * Items from anonymous / device ingress carry no origin and stay visible and
     * workable across studies: the internal deployment's behaviour is unchanged.
     */
    @Test
    void anItemWithNoOriginStaysVisibleAcrossStudies() throws Exception {
        int neutral = neutralUnboundItem();
        assertEquals(null, originOf(neutral));
        for (Site site : new Site[] {A, B}) {
            MockHttpSession s = loginAs(Who.CRC, site);
            Resp list = call(get("/api/v1/ingest/inbox"), s);
            assertEquals(200, list.status, list.snippet());
            assertTrue(list.body.contains("\"id\":" + neutral + ","), site.tag + " lists the origin-less item: " + list.snippet());
            assertEquals(200, call(get("/api/v1/ingest/" + neutral), s).status, site.tag + " opens it");
        }
        // And either site may dismiss it (current internal behaviour).
        Resp dismiss = call(json(post("/api/v1/ingest/" + neutral + "/dismiss"), "{\"reason\":\"x\"}"),
                loginAs(Who.CRC, B));
        assertEquals(200, dismiss.status, dismiss.snippet());
    }

    /** Binding needs a target subject the caller can see, even for an item the caller can see. */
    @Test
    void bindingAnItemIntoAnInvisibleSubjectIsRefused() throws Exception {
        Fx b = newSet(B);
        int neutral = neutralUnboundItem();
        Map<String, String> before = snapshot();
        Resp r = call(json(post("/api/v1/ingest/" + neutral + "/bind"), "{\"studySubjectId\":" + b.ss
                + ",\"studyEventId\":" + b.event + ",\"eventCrfId\":" + b.eventCrf + ",\"laterality\":\"OD\"}"),
                loginAs(Who.CRC, A));
        assertTrue(r.status == 403 || r.status == 404, "bind into site B's subject: " + r.snippet());
        assertEquals(before, snapshot(), "the refused bind changed tables");
    }

    private static long idOf(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"ingestItemId\"\\s*:\\s*(\\d+)").matcher(body);
        assertTrue(m.find(), "no ingestItemId in " + body);
        return Long.parseLong(m.group(1));
    }

    /** A tiny JPEG that differs per {@code salt} (the upload refuses a repeat of the same bytes). */
    private static byte[] originJpeg(int salt) {
        byte[] j = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0,
                0, 1, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xD9};
        j[19] = (byte) salt;
        j[20] = (byte) (System.nanoTime() & 0x7F);
        return j;
    }

    /** Small helper so the tamper test can read what the real study switch left in the session. */
    private static final class StudyBeanProbe {
        static void assertBoundTo(MockHttpSession s, int studyId) {
            at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean study =
                    (at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean) s.getAttribute("study");
            assertEquals(studyId, study.getId(), "the session's active study");
        }
    }
}
