/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockHttpSession;

/**
 * The cross-site refusals that are missing. Every test here FAILS until the
 * guard it names exists; none was weakened to pass. As of the 2026-10 isolation
 * fixes the authenticated cases are all in {@link CrossSiteIsolationDatabaseIT};
 * what remains here are the anonymous Public* portal and device endpoints. Those
 * serve the internal clinic by design (devices and portals upload without a
 * session and see every study's patients) and are closed on the internet-facing
 * multicenter deployment, so these tests are expected to fail on the internal
 * configuration and document exactly what the closure removes. They live apart from
 * {@link CrossSiteIsolationDatabaseIT} so that class stays a regression gate
 * for what holds, and the class name has no {@code Database} in it, so the
 * default {@code mvn test} (which selects {@code *DatabaseIT}) does not run it;
 * {@code -P integration-tests} or {@code -Dtest=CrossSiteIsolationLeaksIT} does.
 *
 * <p>Two groups:
 * <ul>
 *   <li>{@link #knownLeaks}: the matrix cases listed in
 *       {@link CrossSiteIsolationMatrix#KNOWN_LEAKS} (an authenticated site-A
 *       user, site-B identifiers).</li>
 *   <li>The unauthenticated portal and device endpoints: no session, so no
 *       site; the questions are whether they hand out another site's patients
 *       and whether they change another site's records.</li>
 * </ul>
 */
@Tag("isolation-leak")
class CrossSiteIsolationLeaksIT extends CrossSiteIsolationSupport {

    /**
     * Authenticated cases listed in {@link CrossSiteIsolationMatrix#KNOWN_LEAKS} (empty since the
     * 2026-10 fixes; a new finding is parked there until its guard exists).
     */
    @TestFactory
    Stream<DynamicNode> knownLeaks() {
        return CrossSiteIsolationMatrix.run(CrossSiteIsolationMatrix::isKnownLeak);
    }

    /* ====================================================================== */
    /* Unauthenticated endpoints                                               */
    /* ====================================================================== */

    private static Resp anonymously(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> req)
            throws Exception {
        return call(req, new MockHttpSession());
    }

    private void assertShowsNothingOfSiteB(String what, Resp r) {
        System.out.println("[isolation] anonymous " + what + " -> " + r.snippet());
        assertFalse(B.markedIn(r.body), "UNAUTHENTICATED LEAK " + what + ": an anonymous caller got site-B data ["
                + r.snippet() + "] ...." + B.context(r.body));
    }

    /** A visit list names no label, so the marker is the visit's id. */
    private void assertListsNoVisitOfSiteB(String what, Fx b, Resp r) {
        System.out.println("[isolation] anonymous " + what + " -> " + r.snippet());
        assertFalse(r.body.contains("\"id\":\"" + b.event + "\""), "UNAUTHENTICATED LEAK " + what
                + ": an anonymous caller got the visits of a site-B patient [" + r.snippet() + "]");
    }

    @Test
    void anonymousPatientSearchOfTheUploadPortalShowsSiteBPatients() throws Exception {
        Fx b = newSet(B);
        assertShowsNothingOfSiteB("GET /public/upload/patients/search",
                anonymously(get("/api/v1/public/upload/patients/search?q=" + b.label)));
    }

    @Test
    void anonymousPatientSearchOfTheOctPortalShowsSiteBPatients() throws Exception {
        Fx b = newSet(B);
        assertShowsNothingOfSiteB("GET /public/oct-upload/patients/search",
                anonymously(get("/api/v1/public/oct-upload/patients/search?q=" + b.label)));
    }

    @Test
    void anonymousPatientSearchOfTheImagePortalShowsSiteBPatients() throws Exception {
        Fx b = newSet(B);
        assertShowsNothingOfSiteB("GET /public/image-upload/patients/search",
                anonymously(get("/api/v1/public/image-upload/patients/search?q=" + b.label)));
    }

    @Test
    void anonymousVisitListOfAPatientShowsSiteBVisits() throws Exception {
        Fx b = newSet(B);
        assertListsNoVisitOfSiteB("GET /public/upload/patients/{ss}/events", b,
                anonymously(get("/api/v1/public/upload/patients/" + b.ss + "/events")));
    }

    @Test
    void anonymousOctPortalVisitListOfAPatientShowsSiteBVisits() throws Exception {
        Fx b = newSet(B);
        assertListsNoVisitOfSiteB("GET /public/oct-upload/patients/{ss}/events", b,
                anonymously(get("/api/v1/public/oct-upload/patients/" + b.ss + "/events")));
    }

    @Test
    void anonymousDicomWorklistShowsSiteBPatients() throws Exception {
        Fx b = newSet(B);
        String today = java.time.LocalDate.now().toString();
        assertShowsNothingOfSiteB("GET /internal/dicom-worklist",
                anonymously(get("/api/v1/internal/dicom-worklist?from=" + today + "&to=" + today)));
    }

    @Test
    void anonymousOptomedWorklistShowsSiteBPatients() throws Exception {
        Fx b = newSet(B);
        assertShowsNothingOfSiteB("GET /device/optomed/worklist.txt",
                anonymously(get("/api/v1/device/optomed/worklist.txt?date=" + java.time.LocalDate.now())));
    }

    @Test
    void anonymousUndoOfAFreshUploadDeletesSiteBsJob() throws Exception {
        Fx b = newSet(B);
        Resp r = anonymously(delete("/api/v1/public/upload/jobs/" + b.jobFresh));
        assertTrue(count("SELECT count(*) FROM retinal_inference_job WHERE job_id = " + b.jobFresh) > 0,
                "UNAUTHENTICATED WRITE LEAK DELETE /public/upload/jobs/{id}: an anonymous caller deleted site B's job ["
                        + r.snippet() + "]");
    }

    @Test
    void anonymousUndoOfAFreshUploadDeletesSiteBsIngestItem() throws Exception {
        Fx b = newSet(B);
        Resp r = anonymously(delete("/api/v1/public/upload/items/" + b.ingestFresh));
        assertTrue(count("SELECT count(*) FROM ingest_item WHERE ingest_item_id = " + b.ingestFresh) > 0,
                "UNAUTHENTICATED WRITE LEAK DELETE /public/upload/items/{id}: an anonymous caller deleted site B's file ["
                        + r.snippet() + "]");
    }

    private static int count(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
