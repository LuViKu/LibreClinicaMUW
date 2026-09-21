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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

/**
 * P2-4 — a finished inference really lands in the CRF.
 *
 * <p>The unit tests beside this pin the wiring: that completion calls the
 * populator, that a parked scan is skipped, that a failure does not undo a
 * finished job. This one pins the outcome that matters, against a real
 * database: the fluid volumes a clinician sees in the viewer end up as CRF
 * values, which is the only way they reach a dataset export.
 *
 * <p>Fixture: a seeded event_crf plus a synthetic completed job and result.
 * The populator resolves its target items by OID globally rather than within
 * the visit's CRF version, so any live event_crf exercises the same path; the
 * nAMD items themselves come from a production seed migration, and the fixture
 * says so rather than failing obscurely if that seed is ever dropped.
 */
class RetinalAutoPopulateOnDoneDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int ACTOR = 1;

    /** Resolved per test: the first live event_crf in the seeded database. */
    private int eventCrfId;
    private long jobId;

    @BeforeEach
    void resolveFixture() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_crf_id FROM event_crf WHERE status_id NOT IN (5, 7) "
                             + "ORDER BY event_crf_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the seed should provide at least one live event_crf");
            eventCrfId = rs.getInt(1);
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM item WHERE oc_oid = 'I_NAMD_OD_IRF_MM3'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getInt(1),
                    "the nAMD AI items are missing — has the production seed migration changed?");
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE FROM item_data WHERE event_crf_id = " + eventCrfId
                + " AND item_id IN (SELECT item_id FROM item WHERE oc_oid LIKE 'I_NAMD_O%_MM3')");
        if (jobId > 0) {
            exec("DELETE FROM retinal_inference_result WHERE job_id = " + jobId);
            exec("DELETE FROM retinal_inference_job WHERE job_id = " + jobId);
            jobId = 0;
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** A completed fluid job on the fixture visit, with the given metrics. */
    private long seedDoneJob(String laterality, String payloadJson) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_job "
                            + "(event_crf_id, task, e2e_path, eye_laterality, status, enqueued_at, completed_at) "
                            + "VALUES (?, 'fluid', '/tmp/it.e2e', ?, 'done', NOW(), NOW()) "
                            + "RETURNING job_id")) {
                ps.setInt(1, eventCrfId);
                ps.setString(2, laterality);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_result (job_id, task, output_payload, created_at) "
                            + "VALUES (?, 'fluid', ?::jsonb, NOW())")) {
                ps.setLong(1, id);
                ps.setString(2, payloadJson);
                ps.executeUpdate();
            }
            return id;
        }
    }

    private record Row(String value, String sourceKind, Long sourceJobId) {}

    private Row readItem(String itemOid) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT d.value, d.source_kind, d.source_retinal_job_id "
                             + "  FROM item_data d JOIN item i ON i.item_id = d.item_id "
                             + " WHERE d.event_crf_id = ? AND i.oc_oid = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long j = rs.getLong(3);
                return new Row(rs.getString(1), rs.getString(2), rs.wasNull() ? null : j);
            }
        }
    }

    private int auditCount() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 120 "
                             + "AND event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private RetinalResultItemDataPopulator populator() {
        return new RetinalResultItemDataPopulator(DATA_SOURCE);
    }

    /* ---------------- the write ---------------- */

    @Test
    void aFinishedJobsMetricsBecomeCrfValues() throws Exception {
        jobId = seedDoneJob("OD", "{\"irf_mm3\": 0.05, \"srf_mm3\": 0.02}");

        var result = populator().populateForEventCrf(eventCrfId, ACTOR);
        assertNotNull(result);

        Row irf = readItem("I_NAMD_OD_IRF_MM3");
        assertNotNull(irf, "the measured IRF volume should be in the CRF");
        assertTrue(irf.value().startsWith("0.05"), "unexpected value: " + irf.value());
        assertEquals("retinal_inference", irf.sourceKind(),
                "the value must be traceable to the job that produced it");
        assertEquals(Long.valueOf(jobId), irf.sourceJobId());

        assertNotNull(readItem("I_NAMD_OD_SRF_MM3"), "the SRF volume should be there too");
    }

    /** The laterality decides which eye's items are written; the other eye stays empty. */
    @Test
    void theOtherEyesItemsAreNotTouched() throws Exception {
        jobId = seedDoneJob("OS", "{\"irf_mm3\": 0.07}");
        populator().populateForEventCrf(eventCrfId, ACTOR);

        assertNotNull(readItem("I_NAMD_OS_IRF_MM3"), "the scanned eye should be populated");
        assertNull(readItem("I_NAMD_OD_IRF_MM3"), "the other eye must stay empty");
    }

    /** Running twice must not produce a second row: the job completes, and may be re-dispatched. */
    @Test
    void repeatingThePopulateIsIdempotent() throws Exception {
        jobId = seedDoneJob("OD", "{\"irf_mm3\": 0.05}");
        populator().populateForEventCrf(eventCrfId, ACTOR);
        populator().populateForEventCrf(eventCrfId, ACTOR);

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM item_data d JOIN item i ON i.item_id = d.item_id "
                             + " WHERE d.event_crf_id = ? AND i.oc_oid = 'I_NAMD_OD_IRF_MM3'")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    /* ---------------- the refusals ---------------- */

    /**
     * A value a clinician typed is never overwritten by the machine, even when
     * the machine measured something different. Disagreement is a finding for a
     * person to resolve, not something to silently overwrite.
     */
    @Test
    void anOperatorsValueSurvives() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                             + "owner_id, ordinal, deleted) "
                             + "SELECT item_id, ?, 1, '9.99', NOW(), 1, 1, false "
                             + "  FROM item WHERE oc_oid = 'I_NAMD_OD_IRF_MM3'")) {
            ps.setInt(1, eventCrfId);
            ps.executeUpdate();
        }
        jobId = seedDoneJob("OD", "{\"irf_mm3\": 0.05}");

        populator().populateForEventCrf(eventCrfId, ACTOR);

        Row irf = readItem("I_NAMD_OD_IRF_MM3");
        assertEquals("9.99", irf.value(), "the operator's value must stand");
        assertNull(irf.sourceKind(), "and it must still read as operator-entered");
    }

    /** A job that has not finished has no result to copy. */
    @Test
    void anUnfinishedJobWritesNothing() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO retinal_inference_job "
                             + "(event_crf_id, task, e2e_path, eye_laterality, status, enqueued_at) "
                             + "VALUES (?, 'fluid', '/tmp/it.e2e', 'OD', 'segmenting', NOW()) "
                             + "RETURNING job_id")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                jobId = rs.getLong(1);
            }
        }
        populator().populateForEventCrf(eventCrfId, ACTOR);
        assertNull(readItem("I_NAMD_OD_IRF_MM3"));
    }

    /** An automatic CRF write has to be explainable from the audit trail alone. */
    @Test
    void everyAutomaticWriteIsAudited() throws Exception {
        int before = auditCount();
        jobId = seedDoneJob("OD", "{\"irf_mm3\": 0.05, \"srf_mm3\": 0.02}");
        populator().populateForEventCrf(eventCrfId, ACTOR);
        assertTrue(auditCount() > before, "the automatic writes left no audit trail");
    }
}
