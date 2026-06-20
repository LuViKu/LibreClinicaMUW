/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * nAMD treat-and-extend (2026-06-20) — testcontainers IT for the
 * {@link RetinalResultItemDataPopulator} service.
 *
 * <p>Pinned cases:
 *
 * <ul>
 *   <li>Happy path: one completed OD-laterality fluid job with
 *       irf_mm3 / srf_mm3 / ped_mm3 / total_fluid_volume_mm3 →
 *       writes 4 item_data rows on the NAMD_OD_* items, each marked
 *       with source_kind="retinal_inference" + source_retinal_job_id
 *       pointing at the job.</li>
 *   <li>Re-run is idempotent: second invocation against the same
 *       job updates the existing rows, doesn't duplicate.</li>
 *   <li>Operator override is preserved: a row with source_kind=NULL
 *       (operator-entered) is left untouched.</li>
 *   <li>OS laterality writes to OS items.</li>
 *   <li>Unknown laterality yields a warning + no writes.</li>
 * </ul>
 */
class RetinalResultItemDataPopulatorIT extends AbstractApiControllerDatabaseIT {

    private static final int SEED_DEF_ID = 1;       // demo event definition
    private static final int SEED_SUBJECT_ID = 1;   // demo study_subject
    private static final int SEED_CRF_VERSION = 21; // NAMD_VISIT v1.0

    @AfterEach
    void cleanupSeededRows() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Clean in FK-safe order.
            executeUpdate(c, "DELETE FROM item_data WHERE event_crf_id IN "
                    + "(SELECT event_crf_id FROM event_crf WHERE study_event_id IN "
                    + " (SELECT study_event_id FROM study_event WHERE sample_ordinal >= 9000))");
            executeUpdate(c, "DELETE FROM retinal_inference_result WHERE job_id IN "
                    + "(SELECT job_id FROM retinal_inference_job WHERE event_crf_id IN "
                    + " (SELECT event_crf_id FROM event_crf WHERE study_event_id IN "
                    + "  (SELECT study_event_id FROM study_event WHERE sample_ordinal >= 9000)))");
            executeUpdate(c, "DELETE FROM retinal_inference_job WHERE event_crf_id IN "
                    + "(SELECT event_crf_id FROM event_crf WHERE study_event_id IN "
                    + " (SELECT study_event_id FROM study_event WHERE sample_ordinal >= 9000))");
            executeUpdate(c, "DELETE FROM event_crf WHERE study_event_id IN "
                    + "(SELECT study_event_id FROM study_event WHERE sample_ordinal >= 9000)");
            executeUpdate(c, "DELETE FROM study_event WHERE sample_ordinal >= 9000");
        }
    }

    @Test
    void writesPerEyeItemsFromCompletedOdJob() throws SQLException {
        int eventCrfId = seedEventCrf(9001);
        long jobId = seedRetinalJob(eventCrfId, "OD",
                "{\"irf_mm3\":0.42,\"srf_mm3\":0.18,\"ped_mm3\":1.23,\"total_fluid_volume_mm3\":1.83}");

        RetinalResultItemDataPopulator svc = new RetinalResultItemDataPopulator(DATA_SOURCE);
        RetinalResultItemDataPopulator.PopulateResult result =
                svc.populateForEventCrf(eventCrfId, /* operator */ 1);

        assertEquals(1, result.jobsProcessed());
        assertEquals(4, result.rowsWritten());
        assertTrue(result.warnings().isEmpty());

        assertEquals(0.42, readItemDataValue(eventCrfId, "I_NAMD_OD_IRF_MM3"), 1e-6);
        assertEquals(0.18, readItemDataValue(eventCrfId, "I_NAMD_OD_SRF_MM3"), 1e-6);
        assertEquals(1.23, readItemDataValue(eventCrfId, "I_NAMD_OD_PED_MM3"), 1e-6);
        assertEquals(1.83, readItemDataValue(eventCrfId, "I_NAMD_OD_TOTAL_FLUID_MM3"), 1e-6);

        // OS items stayed empty.
        assertNull(readItemDataValueOrNull(eventCrfId, "I_NAMD_OS_IRF_MM3"));

        // Source columns populated on each row.
        Long sourceJob = readSourceJobId(eventCrfId, "I_NAMD_OD_IRF_MM3");
        assertNotNull(sourceJob);
        assertEquals(jobId, sourceJob.longValue());
    }

    @Test
    void rerunIsIdempotent() throws SQLException {
        int eventCrfId = seedEventCrf(9002);
        seedRetinalJob(eventCrfId, "OD",
                "{\"irf_mm3\":0.10,\"srf_mm3\":0.20,\"ped_mm3\":0.30,\"total_fluid_volume_mm3\":0.60}");

        RetinalResultItemDataPopulator svc = new RetinalResultItemDataPopulator(DATA_SOURCE);
        svc.populateForEventCrf(eventCrfId, 1);

        int countAfterFirst = countItemDataRows(eventCrfId);

        // Re-run — should not duplicate rows.
        svc.populateForEventCrf(eventCrfId, 1);
        int countAfterSecond = countItemDataRows(eventCrfId);

        assertEquals(countAfterFirst, countAfterSecond);
    }

    @Test
    void operatorEntryIsNotOverwritten() throws SQLException {
        int eventCrfId = seedEventCrf(9003);
        // Operator pre-entered a value (source_kind NULL).
        seedOperatorItemData(eventCrfId, "I_NAMD_OD_IRF_MM3", "9.99");
        seedRetinalJob(eventCrfId, "OD",
                "{\"irf_mm3\":0.42,\"srf_mm3\":0.18}");

        RetinalResultItemDataPopulator svc = new RetinalResultItemDataPopulator(DATA_SOURCE);
        svc.populateForEventCrf(eventCrfId, 1);

        // Operator entry stays at 9.99; SRF picks up the auto value.
        assertEquals(9.99, readItemDataValue(eventCrfId, "I_NAMD_OD_IRF_MM3"), 1e-6);
        assertEquals(0.18, readItemDataValue(eventCrfId, "I_NAMD_OD_SRF_MM3"), 1e-6);
    }

    @Test
    void osLateralityWritesToOsItems() throws SQLException {
        int eventCrfId = seedEventCrf(9004);
        seedRetinalJob(eventCrfId, "OS",
                "{\"irf_mm3\":0.05,\"total_fluid_volume_mm3\":0.05}");

        RetinalResultItemDataPopulator svc = new RetinalResultItemDataPopulator(DATA_SOURCE);
        svc.populateForEventCrf(eventCrfId, 1);

        assertEquals(0.05, readItemDataValue(eventCrfId, "I_NAMD_OS_IRF_MM3"), 1e-6);
        assertEquals(0.05, readItemDataValue(eventCrfId, "I_NAMD_OS_TOTAL_FLUID_MM3"), 1e-6);
        assertNull(readItemDataValueOrNull(eventCrfId, "I_NAMD_OD_IRF_MM3"));
    }

    @Test
    void unknownLateralityYieldsWarning() throws SQLException {
        int eventCrfId = seedEventCrf(9005);
        seedRetinalJob(eventCrfId, "XX",
                "{\"irf_mm3\":0.42}");

        RetinalResultItemDataPopulator svc = new RetinalResultItemDataPopulator(DATA_SOURCE);
        RetinalResultItemDataPopulator.PopulateResult result =
                svc.populateForEventCrf(eventCrfId, 1);

        assertEquals(1, result.jobsProcessed());
        assertEquals(0, result.rowsWritten());
        assertEquals(1, result.warnings().size());
    }

    /* ---------- helpers ---------- */

    private int seedEventCrf(int sampleOrdinal) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int studyEventId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_event "
                            + "  (study_event_definition_id, study_subject_id, location, sample_ordinal, "
                            + "   date_start, owner_id, status_id, date_created, subject_event_status_id, "
                            + "   start_time_flag, end_time_flag) "
                            + "VALUES (?, ?, '', ?, NOW(), 1, 1, NOW(), 1, false, false) "
                            + "RETURNING study_event_id")) {
                ps.setInt(1, SEED_DEF_ID);
                ps.setInt(2, SEED_SUBJECT_ID);
                ps.setInt(3, sampleOrdinal);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    studyEventId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO event_crf "
                            + "  (study_event_id, crf_version_id, owner_id, study_subject_id, "
                            + "   date_created, status_id, completion_status_id, validate_string, "
                            + "   annotations, interviewer_name, date_interviewed, sdv_status, "
                            + "   electronic_signature_status) "
                            + "VALUES (?, ?, 1, ?, NOW(), 1, 1, '', '', '', NULL, false, false) "
                            + "RETURNING event_crf_id")) {
                ps.setInt(1, studyEventId);
                ps.setInt(2, SEED_CRF_VERSION);
                ps.setInt(3, SEED_SUBJECT_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    private long seedRetinalJob(int eventCrfId, String laterality, String payloadJson) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            long jobId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_job "
                            + "  (event_crf_id, task, e2e_path, eye_laterality, status, enqueued_at, scan_index) "
                            + "VALUES (?, 'fluid', '/tmp/test.e2e', ?, 'done', NOW(), 0) "
                            + "RETURNING job_id")) {
                ps.setInt(1, eventCrfId);
                ps.setString(2, laterality);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    jobId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO retinal_inference_result "
                            + "  (job_id, task, output_payload, created_at) "
                            + "VALUES (?, 'fluid', ?::jsonb, NOW())")) {
                ps.setLong(1, jobId);
                ps.setString(2, payloadJson);
                ps.executeUpdate();
            }
            return jobId;
        }
    }

    private void seedOperatorItemData(int eventCrfId, String itemOid, String value) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO item_data "
                             + "  (item_id, event_crf_id, status_id, value, "
                             + "   date_created, owner_id, ordinal, deleted) "
                             + "SELECT item_id, ?, 1, ?, NOW(), 1, 1, false "
                             + "  FROM item WHERE oc_oid = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, value);
            ps.setString(3, itemOid);
            ps.executeUpdate();
        }
    }

    private double readItemDataValue(int eventCrfId, String itemOid) throws SQLException {
        Double v = readItemDataValueOrNull(eventCrfId, itemOid);
        assertNotNull(v, "Expected item_data for " + itemOid);
        return v;
    }

    private Double readItemDataValueOrNull(int eventCrfId, String itemOid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id.value FROM item_data id "
                             + "  JOIN item i ON i.item_id = id.item_id "
                             + " WHERE id.event_crf_id = ? AND i.oc_oid = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String s = rs.getString(1);
                if (s == null || s.isBlank()) return null;
                return Double.parseDouble(s);
            }
        }
    }

    private Long readSourceJobId(int eventCrfId, String itemOid) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id.source_retinal_job_id FROM item_data id "
                             + "  JOIN item i ON i.item_id = id.item_id "
                             + " WHERE id.event_crf_id = ? AND i.oc_oid = ?")) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private int countItemDataRows(int eventCrfId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM item_data WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void executeUpdate(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** Silence unused-import warning. */
    @SuppressWarnings("unused")
    private static final Class<Timestamp> UNUSED = Timestamp.class;
}
