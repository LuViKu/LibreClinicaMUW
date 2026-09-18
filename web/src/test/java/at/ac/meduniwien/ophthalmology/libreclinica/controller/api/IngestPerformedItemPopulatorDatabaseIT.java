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

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestPerformedItemPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestPerformedItemPopulator.Outcome;

/**
 * DR-025 P1-5 — the automatic "this modality was performed" tick.
 *
 * <p>A CRF value that nobody typed has to be defensible, so these pin the
 * refusals as firmly as the writes: an operator's value is never overwritten,
 * an item that is not on the visit's own CRF version is never written into,
 * and a repeat bind does not produce a second row.
 *
 * <p>Fixture: the seeded demo study, event_crf 3 on crf_version 1. The items
 * used stand in for checklist boxes — what matters is the map/resolve/write
 * behaviour, not which item. They are deliberately items the demo seed leaves
 * empty on this event_crf, so the cleanup here never removes seeded rows that
 * sibling tests in the same container depend on.
 */
class IngestPerformedItemPopulatorDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Seeded: study_event 3 of study 1, first live event_crf, crf_version 1. */
    private static final int EVENT_CRF_ID = 3;
    private static final int STUDY_ID = 1;
    private static final int ITEM_ID = 5;
    private static final String ITEM_OID = "I_BLOOD_PRESSURE_SYS";
    /** A second empty item, for the study-override case. */
    private static final int OVERRIDE_ITEM_ID = 2;
    private static final String OVERRIDE_ITEM_OID = "I_CONSENT_SIGNED";
    private static final String DEVICE = "it-camera";
    private static final int ACTOR = 1;

    private long imageId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ingest_performed_item_map "
                            + "(study_id, source_kind, device_key, item_oid, performed_value, owner_id) "
                            + "VALUES (NULL, 'dicom', ?, ?, '1', 1)")) {
                ps.setString(1, DEVICE);
                ps.setString(2, ITEM_OID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename, "
                            + "received_at, status) VALUES ('dicom', 'dicom', ?, '/tmp/it.dcm', 'tick-it', now(), 'BOUND') "
                            + "RETURNING ingest_item_id")) {
                ps.setString(1, DEVICE);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    imageId = rs.getLong(1);
                }
            }
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_log_event_type_id = 129 AND entity_id = " + imageId);
        exec("DELETE FROM item_data WHERE event_crf_id = " + EVENT_CRF_ID + " AND item_id = " + ITEM_ID);
        exec("DELETE FROM ingest_item WHERE original_filename = 'tick-it'");
        exec("DELETE FROM ingest_performed_item_map WHERE device_key IN ('" + DEVICE + "', 'other-camera')");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private IngestPerformedItemPopulator populator() {
        return new IngestPerformedItemPopulator(DATA_SOURCE);
    }

    private record Row(String value, String sourceKind, Long sourceImageId, int count) {}

    private Row readItemData() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value, source_kind, source_image_ingest_id FROM item_data "
                             + "WHERE event_crf_id = ? AND item_id = ? ORDER BY item_data_id")) {
            ps.setInt(1, EVENT_CRF_ID);
            ps.setInt(2, ITEM_ID);
            try (ResultSet rs = ps.executeQuery()) {
                String v = null, sk = null;
                Long src = null;
                int n = 0;
                while (rs.next()) {
                    if (n == 0) {
                        v = rs.getString(1);
                        sk = rs.getString(2);
                        long s = rs.getLong(3);
                        src = rs.wasNull() ? null : s;
                    }
                    n++;
                }
                return new Row(v, sk, src, n);
            }
        }
    }

    private int auditRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 129 "
                             + "AND entity_id = ?")) {
            ps.setLong(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ---------------- the write ---------------- */

    @Test
    void bindingAnImage_ticksTheModalityPerformedBox() throws Exception {
        assertEquals(Outcome.WRITTEN,
                populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", DEVICE, STUDY_ID, ACTOR));

        Row row = readItemData();
        assertEquals(1, row.count());
        assertEquals("1", row.value());
        assertEquals("ingest", row.sourceKind(), "the value must be traceable to the ingest that caused it");
        assertEquals(Long.valueOf(imageId), row.sourceImageId());
        assertEquals(1, auditRows(), "an automatic CRF write must leave an audit row");
    }

    @Test
    void aSecondBindOfTheSameDevice_leavesOneRowAndNoSecondAudit() throws Exception {
        populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", DEVICE, STUDY_ID, ACTOR);
        assertEquals(Outcome.ALREADY_SET,
                populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", DEVICE, STUDY_ID, ACTOR));

        assertEquals(1, readItemData().count());
        assertEquals(1, auditRows());
    }

    @Test
    void aStudySpecificMapRow_winsOverTheGlobalOne() throws Exception {
        // A study whose CRF names the item differently overrides globally.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_performed_item_map "
                             + "(study_id, source_kind, device_key, item_oid, performed_value, owner_id) "
                             + "VALUES (?, 'dicom', ?, '" + OVERRIDE_ITEM_OID + "', 'Y', 1)")) {
            ps.setInt(1, STUDY_ID);
            ps.setString(2, DEVICE);
            ps.executeUpdate();
        }
        try {
            assertEquals(Outcome.WRITTEN,
                    populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", DEVICE, STUDY_ID, ACTOR));

            assertEquals(0, readItemData().count(), "the global row's item must not be written");
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT value FROM item_data WHERE event_crf_id = ? AND item_id = ?")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.setInt(2, OVERRIDE_ITEM_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "the study row's item should carry the value");
                    assertEquals("Y", rs.getString(1), "the study row's coded yes value should be used");
                }
            }
        } finally {
            exec("DELETE FROM item_data WHERE event_crf_id = " + EVENT_CRF_ID
                    + " AND item_id = " + OVERRIDE_ITEM_ID);
            exec("DELETE FROM ingest_performed_item_map WHERE study_id = " + STUDY_ID);
        }
    }

    /* ---------------- the refusals ---------------- */

    @Test
    void anOperatorsValue_isNeverOverwritten() throws Exception {
        // Including when the operator recorded that the modality was NOT done:
        // a device that later sends an image does not get to contradict them
        // silently.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                             + "owner_id, ordinal, deleted) VALUES (?, ?, 1, '0', now(), 1, 1, false)")) {
            ps.setInt(1, ITEM_ID);
            ps.setInt(2, EVENT_CRF_ID);
            ps.executeUpdate();
        }

        assertEquals(Outcome.OPERATOR_VALUE_KEPT,
                populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", DEVICE, STUDY_ID, ACTOR));

        Row row = readItemData();
        assertEquals("0", row.value(), "the operator's value must stand");
        assertNull(row.sourceKind(), "the row must still read as operator-entered");
        assertEquals(0, auditRows(), "nothing was written, so nothing is audited");
    }

    @Test
    void anUnknownDevice_writesNothing() throws Exception {
        assertEquals(Outcome.NOT_APPLICABLE,
                populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", "no-such-camera", STUDY_ID, ACTOR));
        assertEquals(0, readItemData().count());
    }

    @Test
    void anItemMissingFromTheVisitsCrfVersion_isSkippedRatherThanGuessed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_performed_item_map "
                             + "(study_id, source_kind, device_key, item_oid, performed_value, owner_id) "
                             + "VALUES (NULL, 'dicom', 'other-camera', 'I_NOT_IN_THIS_CRF', '1', 1)")) {
            ps.executeUpdate();
        }
        assertEquals(Outcome.NOT_APPLICABLE,
                populator().markPerformed(imageId, EVENT_CRF_ID, "dicom", "other-camera", STUDY_ID, ACTOR));
        assertEquals(0, readItemData().count());
    }

    @Test
    void aVisitWithoutAStartedCrf_writesNothing() throws Exception {
        assertEquals(Outcome.NOT_APPLICABLE,
                populator().markPerformed(imageId, null, "dicom", DEVICE, STUDY_ID, ACTOR));
        assertEquals(0, readItemData().count());
    }

    @Test
    void theDeviceKeyMatchesRegardlessOfCase() throws Exception {
        assertEquals(Outcome.WRITTEN,
                populator().markPerformed(imageId, EVENT_CRF_ID, "DICOM",
                        DEVICE.toUpperCase(java.util.Locale.ROOT), STUDY_ID, ACTOR));
        assertEquals(1, readItemData().count());
    }

    /* ---------------- the system actor ---------------- */

    @Test
    void theSystemAccountExistsAndCannotLogIn() throws Exception {
        Integer id = IngestPerformedItemPopulator.systemUserId(DATA_SOURCE);
        assertNotNull(id, "machine writes need an account that is not a person");

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT enabled, account_non_locked FROM user_account WHERE user_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(false, rs.getBoolean(1), "the system account must not be enabled");
                assertEquals(false, rs.getBoolean(2), "the system account must stay locked");
            }
        }
    }
}
