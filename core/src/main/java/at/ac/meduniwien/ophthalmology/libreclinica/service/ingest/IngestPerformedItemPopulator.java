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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.SourcedItemDataWriter;

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
 * {@code ingest_item_id}, so the SPA can explain the value and a later unbind
 * can reverse exactly what this caused.
 */
public class IngestPerformedItemPopulator {

    private static final Logger LOG = LoggerFactory.getLogger(IngestPerformedItemPopulator.class);

    /** Tag written into {@code item_data.source_kind}. */
    public static final String SOURCE_KIND = SourcedItemDataWriter.Source.INGEST.kind();

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
        /**
         * Another automatic source owns this item's value and was left alone.
         *
         * <p>P3.0 — before the shared writer this case overwrote. No pair of
         * sources currently targets the same item (checklist boxes and fluid
         * metrics are different items), so nothing observable changes; one
         * machine silently overruling another is simply not a behaviour worth
         * preserving until it does happen.
         */
        OTHER_SOURCE_KEPT,
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
     * @param sourceKind    {@code ingest_item.source_kind}
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

            // P3.0 — the upsert and the "never overwrite a person" rule are
            // shared with the retinal populator; only the audit rule below is
            // ours.
            SourcedItemDataWriter.Result result = SourcedItemDataWriter.upsert(
                    c, eventCrfId, itemId, map.performedValue,
                    new SourcedItemDataWriter.Ref(SourcedItemDataWriter.Source.INGEST, imageIngestId),
                    actorUserId);

            switch (result.outcome()) {
                case OPERATOR_VALUE_KEPT -> {
                    LOG.info("performed-tick skipped for image {}: event_crf {} item {} carries an operator value",
                            imageIngestId, eventCrfId, map.itemOid);
                    return Outcome.OPERATOR_VALUE_KEPT;
                }
                case OTHER_SOURCE_KEPT -> {
                    LOG.info("performed-tick skipped for image {}: event_crf {} item {} was written by "
                            + "another source", imageIngestId, eventCrfId, map.itemOid);
                    return Outcome.OTHER_SOURCE_KEPT;
                }
                case UNCHANGED -> {
                    return Outcome.ALREADY_SET;
                }
                case WRITTEN -> { /* fall through to the audit row */ }
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

    /** What undoing a tick did. */
    public enum ClearOutcome {
        /** The value was removed; the box reads as unanswered again. */
        CLEARED,
        /**
         * Another bound file from the same device still evidences the visit, so
         * the tick stands and now points at that one instead.
         */
        REPOINTED,
        /** This file never ticked anything. */
        NOTHING_TO_CLEAR,
        /** The undo failed; the unbind itself is unaffected. */
        FAILED
    }

    /**
     * P3.2 — undo the tick a bind caused, when that bind is undone.
     *
     * <p>An unbind that left the checklist box ticked would be worse than no
     * unbind at all: the form would keep asserting a modality was performed on
     * a visit that no longer has any evidence of it, and the person who
     * corrected the mis-bind would have no reason to suspect it.
     *
     * <p>Two cases, and the difference matters clinically. If another file from
     * the same device is still bound to that visit, the box is still true — the
     * modality was performed, this was simply not the image proving it — so the
     * value stays and its provenance moves to the remaining file. Only when
     * nothing is left is the value removed.
     *
     * <p>Removal means deleting the row this populator created, not blanking
     * it. A blanked row carries no {@code source_kind}, which reads as "a person
     * typed this", and a later re-bind would then refuse to touch it — the box
     * would be permanently stuck empty. Deleting restores exactly the state
     * before the bind.
     *
     * <p>Only rows this populator wrote are touched: a value a person typed is
     * never removed, whatever it says.
     */
    public ClearOutcome clearPerformed(long ingestItemId, int actorUserId) {
        try (Connection c = dataSource.getConnection()) {
            List<Ticked> ticked = findTicked(c, ingestItemId);
            if (ticked.isEmpty()) return ClearOutcome.NOTHING_TO_CLEAR;

            boolean repointedAny = false;
            for (Ticked t : ticked) {
                Long replacement = anotherBoundFileFor(c, ingestItemId, t.eventCrfId());
                if (replacement != null) {
                    repoint(c, t.itemDataId(), replacement);
                    writeUntickAudit(c, t, actorUserId, "repointed to file " + replacement);
                    repointedAny = true;
                } else {
                    delete(c, t.itemDataId());
                    writeUntickAudit(c, t, actorUserId, null);
                }
            }
            LOG.info("performed-untick: file {} released {} CRF value(s)", ingestItemId, ticked.size());
            return repointedAny ? ClearOutcome.REPOINTED : ClearOutcome.CLEARED;
        } catch (SQLException e) {
            // Never propagate: the unbind is the clinically meaningful outcome
            // and a stale tick is visible on the form.
            LOG.error("performed-untick failed for file {}: {}", ingestItemId, e.getMessage());
            return ClearOutcome.FAILED;
        }
    }

    /** A CRF value this file is responsible for. */
    private record Ticked(int itemDataId, int eventCrfId, int itemId, String itemOid, String value) {}

    private static List<Ticked> findTicked(Connection c, long ingestItemId) throws SQLException {
        List<Ticked> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id.item_data_id, id.event_crf_id, id.item_id, i.oc_oid, id.value "
                        + "  FROM item_data id "
                        + "  JOIN item i ON i.item_id = id.item_id "
                        + " WHERE id.source_image_ingest_id = ? "
                        + "   AND lower(id.source_kind) = ?")) {
            ps.setLong(1, ingestItemId);
            ps.setString(2, SOURCE_KIND);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Ticked(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        }
        return out;
    }

    /**
     * Another file still bound to this visit that would tick the same box —
     * same ingress and same device, since that pair is what the map is keyed
     * on.
     */
    private static Long anotherBoundFileFor(Connection c, long ingestItemId, int eventCrfId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT other.ingest_item_id "
                        + "  FROM ingest_item other "
                        + "  JOIN ingest_item self ON self.ingest_item_id = ? "
                        + " WHERE other.ingest_item_id <> self.ingest_item_id "
                        + "   AND other.status = 'BOUND' "
                        + "   AND other.bound_event_crf_id = ? "
                        + "   AND lower(other.source_kind) = lower(self.source_kind) "
                        + "   AND lower(COALESCE(other.device, other.source_ae_title, '')) "
                        + "     = lower(COALESCE(self.device, self.source_ae_title, '')) "
                        + " ORDER BY other.ingest_item_id LIMIT 1")) {
            ps.setLong(1, ingestItemId);
            ps.setInt(2, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Long.valueOf(rs.getLong(1)) : null;
            }
        }
    }

    private static void repoint(Connection c, int itemDataId, long toIngestItemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE item_data SET source_image_ingest_id = ?, date_updated = NOW() "
                        + " WHERE item_data_id = ?")) {
            ps.setLong(1, toIngestItemId);
            ps.setInt(2, itemDataId);
            ps.executeUpdate();
        }
    }

    private static void delete(Connection c, int itemDataId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM item_data WHERE item_data_id = ?")) {
            ps.setInt(1, itemDataId);
            ps.executeUpdate();
        }
    }

    /** Audit type 130 — seeded by lc-muw-2026-10-05-audit-types-ingest.xml. */
    private static final int AUDIT_TYPE_INGEST_UNBIND = 130;

    private static void writeUntickAudit(Connection c, Ticked t, int actorUserId, String note)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                        + "audit_table, entity_id, entity_name, old_value, new_value, event_crf_id) "
                        + "VALUES (?, now(), ?, 'item_data', ?, ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_INGEST_UNBIND);
            ps.setInt(2, actorUserId);
            ps.setInt(3, t.itemDataId());
            ps.setString(4, t.itemOid());
            ps.setString(5, t.value());
            ps.setString(6, note);   // null = the value is gone
            ps.setInt(7, t.eventCrfId());
            ps.executeUpdate();
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
