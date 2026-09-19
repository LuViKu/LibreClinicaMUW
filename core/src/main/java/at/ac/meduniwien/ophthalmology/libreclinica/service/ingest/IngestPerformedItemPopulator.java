/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DR-025 P1-5 — ticks a visit's "modality performed" box when an image from
 * that modality is bound to the visit.
 *
 * <p>The HealthAEye visit plan is a per-modality checklist. An image that
 * arrived from a device and was bound to a visit is the evidence that the
 * device was used on that visit, so asking the operator to also tick a box is
 * duplicate data entry — and the first time someone forgets, the checklist and
 * the images disagree about what happened to a patient.
 *
 * <p>Rules this keeps to, because a CRF value nobody typed has to be
 * defensible:
 *
 * <ul>
 *   <li><strong>An operator's value is never overwritten.</strong> A row with
 *       no {@code source_kind} was typed by a person and wins, including when
 *       the person recorded "not performed".</li>
 *   <li><strong>The item is resolved inside the visit's own CRF version.</strong>
 *       Two studies may both have an item called
 *       {@code I_HEALT_OPTOMED_PERFORMED}; writing into the wrong one would put
 *       data on the wrong form.</li>
 *   <li><strong>A missing item is a warning, not a failure.</strong> The bind
 *       already succeeded and the image is on the right visit — refusing that
 *       because a checklist box is absent from the CRF would be the worse
 *       outcome. The box can still be ticked by hand.</li>
 *   <li><strong>Idempotent.</strong> Re-binding the same image, or binding a
 *       second image from the same device, leaves one row.</li>
 * </ul>
 *
 * <p>Writes carry {@code source_kind='ingest'} and the originating
 * {@code image_ingest_id}, so the SPA can explain the value and a later unbind
 * can reverse exactly what this caused.
 */
public class IngestPerformedItemPopulator {

    private static final Logger LOG = LoggerFactory.getLogger(IngestPerformedItemPopulator.class);

    /** Tag written into {@code item_data.source_kind}. */
    public static final String SOURCE_KIND = "ingest";

    private final DataSource dataSource;

    public IngestPerformedItemPopulator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** What a call did, so callers can log or audit without re-querying. */
    public enum Outcome {
        /** A value was written or updated. */
        WRITTEN,
        /** Already ticked from an ingest source — nothing to do. */
        ALREADY_SET,
        /** An operator's own value is present and was left alone. */
        OPERATOR_VALUE_KEPT,
        /** No map row for this device, or the item is not in this CRF version. */
        NOT_APPLICABLE,
        /** The write failed; the bind itself is unaffected. */
        FAILED
    }

    /**
     * @param imageIngestId the image that was bound
     * @param eventCrfId    the CRF instance on the visit; {@code null} when the
     *                      CRF has not been started, in which case nothing is
     *                      written
     * @param sourceKind    {@code image_ingest.source_kind}
     * @param deviceKey     the camera's AE title, or the portal's device field
     * @param studyId       the bound subject's study, for a study-specific map row
     * @param actorUserId   who to record as the author — the binding user, or
     *                      the {@code system} account for a machine bind
     */
    public Outcome markPerformed(long imageIngestId, Integer eventCrfId, String sourceKind,
                                 String deviceKey, int studyId, int actorUserId) {
        if (eventCrfId == null || eventCrfId <= 0) {
            LOG.debug("performed-tick skipped for image {}: the visit has no started CRF", imageIngestId);
            return Outcome.NOT_APPLICABLE;
        }
        if (sourceKind == null || sourceKind.isBlank() || deviceKey == null || deviceKey.isBlank()) {
            return Outcome.NOT_APPLICABLE;
        }

        try (Connection c = dataSource.getConnection()) {
            MapRow map = findMap(c, studyId, sourceKind, deviceKey);
            if (map == null) {
                LOG.debug("performed-tick skipped for image {}: no map row for {}/{}",
                        imageIngestId, sourceKind, deviceKey);
                return Outcome.NOT_APPLICABLE;
            }

            Integer itemId = resolveItemInCrfVersion(c, eventCrfId, map.itemOid);
            if (itemId == null) {
                // Named deliberately: an operator seeing an un-ticked box needs
                // to know the platform tried. The OID is configuration, not
                // patient data.
                LOG.warn("performed-tick skipped for image {}: item {} is not in the CRF version on event_crf {}",
                        imageIngestId, map.itemOid, eventCrfId);
                return Outcome.NOT_APPLICABLE;
            }

            Existing existing = findExisting(c, eventCrfId, itemId);
            if (existing != null && existing.sourceKind == null) {
                LOG.info("performed-tick skipped for image {}: event_crf {} item {} carries an operator value",
                        imageIngestId, eventCrfId, map.itemOid);
                return Outcome.OPERATOR_VALUE_KEPT;
            }
            if (existing != null && map.performedValue.equals(existing.value)) {
                return Outcome.ALREADY_SET;
            }

            if (existing == null) {
                insert(c, eventCrfId, itemId, map.performedValue, imageIngestId, actorUserId);
            } else {
                update(c, existing.itemDataId, map.performedValue, imageIngestId, actorUserId);
            }
            writeAudit(c, eventCrfId, imageIngestId, map.itemOid, map.performedValue, actorUserId);
            LOG.info("performed-tick: image {} marked {} performed on event_crf {}",
                    imageIngestId, map.itemOid, eventCrfId);
            return Outcome.WRITTEN;
        } catch (SQLException e) {
            // Never propagate: the image is bound, which is the clinically
            // meaningful outcome. A missing tick is visible and fixable.
            LOG.error("performed-tick failed for image {} on event_crf {}: {}",
                    imageIngestId, eventCrfId, e.getMessage());
            return Outcome.FAILED;
        }
    }

    /* ------------------------------------------------------------------ */

    private record MapRow(String itemOid, String performedValue) {}

    /** A study row wins over a global one. Device keys compare case-insensitively. */
    private static MapRow findMap(Connection c, int studyId, String sourceKind, String deviceKey)
            throws SQLException {
        String sql = "SELECT item_oid, performed_value "
                + "  FROM ingest_performed_item_map "
                + " WHERE lower(source_kind) = lower(?) "
                + "   AND lower(device_key) = lower(?) "
                + "   AND (study_id IS NULL OR study_id = ?) "
                + " ORDER BY (study_id IS NULL) "   // false (study row) sorts first
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sourceKind);
            ps.setString(2, deviceKey);
            ps.setInt(3, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String value = rs.getString(2);
                return new MapRow(rs.getString(1), value == null || value.isBlank() ? "1" : value);
            }
        }
    }

    /**
     * Resolves the item within the CRF version this event_crf is on, so a
     * same-named item in another study's CRF cannot be hit.
     */
    private static Integer resolveItemInCrfVersion(Connection c, int eventCrfId, String itemOid)
            throws SQLException {
        String sql = "SELECT i.item_id "
                + "  FROM event_crf ec "
                + "  JOIN item_form_metadata ifm ON ifm.crf_version_id = ec.crf_version_id "
                + "  JOIN item i ON i.item_id = ifm.item_id "
                + " WHERE ec.event_crf_id = ? AND i.oc_oid = ? "
                + " LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            ps.setString(2, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private record Existing(int itemDataId, String value, String sourceKind) {}

    private static Existing findExisting(Connection c, int eventCrfId, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_data_id, value, source_kind FROM item_data "
                        + " WHERE event_crf_id = ? AND item_id = ? "
                        + "   AND (deleted IS NULL OR deleted = false) "
                        + " ORDER BY item_data_id LIMIT 1")) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Existing(rs.getInt(1), rs.getString(2), rs.getString(3));
            }
        }
    }

    private static void insert(Connection c, int eventCrfId, int itemId, String value,
                               long imageIngestId, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO item_data (item_id, event_crf_id, status_id, value, date_created, "
                        + "owner_id, ordinal, deleted, source_kind, source_image_ingest_id) "
                        + "VALUES (?, ?, 1, ?, NOW(), ?, 1, false, ?, ?)")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, value);
            ps.setInt(4, actorUserId);
            ps.setString(5, SOURCE_KIND);
            ps.setLong(6, imageIngestId);
            ps.executeUpdate();
        }
    }

    private static void update(Connection c, int itemDataId, String value,
                               long imageIngestId, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE item_data SET value = ?, date_updated = NOW(), update_id = ?, "
                        + "source_kind = ?, source_image_ingest_id = ? WHERE item_data_id = ?")) {
            ps.setString(1, value);
            ps.setInt(2, actorUserId);
            ps.setString(3, SOURCE_KIND);
            ps.setLong(4, imageIngestId);
            ps.setInt(5, itemDataId);
            ps.executeUpdate();
        }
    }

    /** Audit type 129 — seeded by lc-muw-2026-09-18-ingest-performed-item-map.xml. */
    private static final int AUDIT_TYPE_IMAGE_PERFORMED_AUTOTICK = 129;

    private static void writeAudit(Connection c, int eventCrfId, long imageIngestId,
                                   String itemOid, String value, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                        + "audit_table, entity_id, entity_name, old_value, new_value, event_crf_id) "
                        + "VALUES (?, now(), ?, 'item_data', ?, ?, NULL, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_IMAGE_PERFORMED_AUTOTICK);
            ps.setInt(2, actorUserId);
            ps.setInt(3, (int) imageIngestId);
            ps.setString(4, itemOid);
            ps.setString(5, value + " (from image " + imageIngestId + ")");
            ps.setInt(6, eventCrfId);
            ps.executeUpdate();
        }
    }

    /** Resolves the locked {@code system} account for writes nobody performed. */
    public static Integer systemUserId(DataSource dataSource) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM user_account WHERE user_name = ?")) {
            ps.setString(1, "system");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            LOG.warn("could not resolve the system service account: {}", e.getMessage());
            return null;
        }
    }

    /** Normalised device key, so 'optomedlumo' and 'OPTOMEDLUMO' are one device. */
    public static String normaliseDeviceKey(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
