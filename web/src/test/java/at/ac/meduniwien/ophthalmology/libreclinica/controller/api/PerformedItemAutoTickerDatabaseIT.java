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

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.PerformedItemAutoTicker;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.PerformedItemAutoTicker.Outcome;

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
class PerformedItemAutoTickerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Seeded: study_event 3 of study 1, first live event_crf, crf_version 1. */
    private static final int EVENT_CRF_ID = 3;
    private static final int STUDY_ID = 1;
    /** The visit event_crf 3 belongs to, so the ticker can start a form. */
    private static final int STUDY_EVENT_ID = 3;
    private static final int ITEM_ID = 5;
    private static final String ITEM_OID = "I_BLOOD_PRESSURE_SYS";
    /** A second empty item, for the study-override case. */
    private static final int OVERRIDE_ITEM_ID = 2;
    private static final String OVERRIDE_ITEM_OID = "I_CONSENT_SIGNED";
    private static final String DEVICE = "it-camera";
    private static final int ACTOR = 1;

    private long imageId;

    private int modalityId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // P3.4 — configuration, not a hard-coded map row: a study's
            // catalogue says which acquisition a device performs and which box
            // on the form that ticks.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, "
                            + "kinds_accepted, laterality_required, ordinal, status_id, created_by_user_id) "
                            + "VALUES (?, 'TICK_IT', 'Testgerät', 'Test device', ?, 'dicom', false, 1, 1, 1) "
                            + "RETURNING imaging_modality_id")) {
                ps.setInt(1, STUDY_ID);
                ps.setString(2, DEVICE);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    modalityId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality_item_binding "
                            + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                            + "VALUES (?, 'performed', 'OU', ?, '1', 1)")) {
                ps.setInt(1, modalityId);
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
        exec("DELETE FROM imaging_modality WHERE study_id = " + STUDY_ID
                + " AND code IN ('TICK_IT', 'TICK_IT_OTHER')");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private PerformedItemAutoTicker ticker() {
        return new PerformedItemAutoTicker(DATA_SOURCE);
    }

    /** Fire the tick the way a bind does. */
    private PerformedItemAutoTicker.Outcome tick(Integer eventCrfId, String sourceKind,
                                                 String device) {
        return ticker().markPerformed(imageId, eventCrfId, STUDY_EVENT_ID,
                sourceKind, device, null, STUDY_ID, ACTOR);
    }

    private record Row(String value, String sourceKind, Long sourceImageId, int count) {}

    private Row readItemData() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value, source_kind, source_ingest_item_id FROM item_data "
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
                tick(EVENT_CRF_ID, "dicom", DEVICE));

        Row row = readItemData();
        assertEquals(1, row.count());
        assertEquals("1", row.value());
        assertEquals("ingest", row.sourceKind(), "the value must be traceable to the ingest that caused it");
        assertEquals(Long.valueOf(imageId), row.sourceImageId());
        assertEquals(1, auditRows(), "an automatic CRF write must leave an audit row");
    }

    @Test
    void aSecondBindOfTheSameDevice_leavesOneRowAndNoSecondAudit() throws Exception {
        tick(EVENT_CRF_ID, "dicom", DEVICE);
        assertEquals(Outcome.ALREADY_SET,
                tick(EVENT_CRF_ID, "dicom", DEVICE));

        assertEquals(1, readItemData().count());
        assertEquals(1, auditRows());
    }

    /**
     * P3.4 — a per-eye binding beats the both-eyes one.
     *
     * <p>A CRF that grows a box per eye should start using it without the
     * OU row having to be removed first, or a study mid-revision would have
     * a window where nothing ticks at all.
     */
    @Test
    void aPerEyeBinding_winsOverTheBothEyesOne() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO imaging_modality_item_binding "
                             + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                             + "VALUES (?, 'performed', 'OD', ?, 'Y', 1)")) {
            ps.setInt(1, modalityId);
            ps.setString(2, OVERRIDE_ITEM_OID);
            ps.executeUpdate();
        }
        try {
            assertEquals(Outcome.WRITTEN, ticker().markPerformed(
                    imageId, EVENT_CRF_ID, STUDY_EVENT_ID, "dicom", DEVICE, "OD", STUDY_ID, ACTOR));

            assertEquals(0, readItemData().count(), "the both-eyes item must not be written");
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT value FROM item_data WHERE event_crf_id = ? AND item_id = ?")) {
                ps.setInt(1, EVENT_CRF_ID);
                ps.setInt(2, OVERRIDE_ITEM_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "the eye's own item should carry the value");
                    assertEquals("Y", rs.getString(1), "with that binding's coded yes value");
                }
            }
        } finally {
            exec("DELETE FROM item_data WHERE event_crf_id = " + EVENT_CRF_ID
                    + " AND item_id = " + OVERRIDE_ITEM_ID);
        }
    }

    /** A file whose eye has no binding still ticks the both-eyes box. */
    @Test
    void anEyeWithNoBindingOfItsOwn_fallsBackToBothEyes() throws Exception {
        assertEquals(Outcome.WRITTEN, ticker().markPerformed(
                imageId, EVENT_CRF_ID, STUDY_EVENT_ID, "dicom", DEVICE, "OS", STUDY_ID, ACTOR));
        assertEquals(1, readItemData().count());
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
                tick(EVENT_CRF_ID, "dicom", DEVICE));

        Row row = readItemData();
        assertEquals("0", row.value(), "the operator's value must stand");
        assertNull(row.sourceKind(), "the row must still read as operator-entered");
        assertEquals(0, auditRows(), "nothing was written, so nothing is audited");
    }

    @Test
    void anUnknownDevice_writesNothing() throws Exception {
        assertEquals(Outcome.NOT_APPLICABLE,
                tick(EVENT_CRF_ID, "dicom", "no-such-camera"));
        assertEquals(0, readItemData().count());
    }

    @Test
    void anItemMissingFromTheVisitsCrfVersion_isSkippedRatherThanGuessed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int other;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, "
                            + "kinds_accepted, laterality_required, ordinal, status_id, created_by_user_id) "
                            + "VALUES (?, 'TICK_IT_OTHER', 'Anderes', 'Other', 'other-camera', "
                            + "'dicom', false, 2, 1, 1) RETURNING imaging_modality_id")) {
                ps.setInt(1, STUDY_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    other = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality_item_binding "
                            + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                            + "VALUES (?, 'performed', 'OU', 'I_NOT_IN_ANY_CRF_ANYWHERE', '1', 1)")) {
                ps.setInt(1, other);
                ps.executeUpdate();
            }
        }
        assertEquals(Outcome.NOT_APPLICABLE,
                tick(EVENT_CRF_ID, "dicom", "other-camera"));
        assertEquals(0, readItemData().count());
    }

    /**
     * P3.4 — a visit whose form nobody has opened still gets its box ticked.
     *
     * <p>This previously wrote nothing, which meant the checklist silently
     * disagreed with the files until an operator happened to open the CRF.
     * The ticker starts the form that carries the box.
     */
    @Test
    void aVisitWhoseFormIsNotStarted_getsItStarted() throws Exception {
        assertEquals(Outcome.WRITTEN, tick(null, "dicom", DEVICE));
        assertEquals(1, readItemData().count(),
                "the platform already knew this; not recording it helps nobody");
    }

    @Test
    void theDeviceKeyMatchesRegardlessOfCase() throws Exception {
        assertEquals(Outcome.WRITTEN,
                tick(EVENT_CRF_ID, "DICOM", DEVICE.toUpperCase(java.util.Locale.ROOT)));
        assertEquals(1, readItemData().count());
    }

    /* ---------------- the system actor ---------------- */

    @Test
    void theSystemAccountExistsAndCannotLogIn() throws Exception {
        Integer id = PerformedItemAutoTicker.systemUserId(DATA_SOURCE);
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
