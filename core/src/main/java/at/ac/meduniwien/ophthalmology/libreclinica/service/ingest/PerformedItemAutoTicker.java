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

import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfEnsurer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.SourcedItemDataWriter;

/**
 * DR-025 P1-5 / P3.4 — ticks a visit's "modality performed" box when a file
 * from that modality is filed against the visit.
 *
 * <p>The HealthAEye visit plan is a per-modality checklist. A file that
 * arrived from a device and was filed against a visit is the evidence that the
 * device was used on that visit, so asking the operator to also tick a box is
 * duplicate data entry — and the first time someone forgets, the checklist and
 * the files disagree about what happened to a patient.
 *
 * <p><strong>P3.4 — what to tick is configuration now.</strong> This used to
 * read {@code ingest_performed_item_map}, which held two hard-coded rows: a
 * Remidio ticks one item, an Optomed ticks another. A third study, or the four
 * Spectralis modalities the visit plan already lists, needed a code change. It
 * now reads the per-study {@code imaging_modality} catalogue and its role-based
 * bindings, which an administrator maintains.
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
public class PerformedItemAutoTicker {

    private static final Logger LOG = LoggerFactory.getLogger(PerformedItemAutoTicker.class);

    /** Tag written into {@code item_data.source_kind}. */
    public static final String SOURCE_KIND = SourcedItemDataWriter.Source.INGEST.kind();

    private final DataSource dataSource;

    public PerformedItemAutoTicker(DataSource dataSource) {
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
        /** No binding for this device, or its item is on no CRF of this visit. */
        NOT_APPLICABLE,
        /** The write failed; the bind itself is unaffected. */
        FAILED
    }

    /**
     * @param ingestItemId  the file that was filed
     * @param eventCrfId    the CRF instance on the visit, when one is already
     *                      started; null is fine — see below
     * @param studyEventId  the visit, needed to start the item's own CRF when
     *                      that is where the box lives
     * @param sourceKind    {@code ingest_item.source_kind}
     * @param deviceKey     the camera's AE title, or the portal's device field
     * @param laterality    the eye, so a per-eye binding can win over the
     *                      both-eyes one; null means both
     * @param studyId       the subject's study, for its catalogue
     * @param actorUserId   who to record as the author — the filing user, or
     *                      the {@code system} account for a machine bind
     */
    public Outcome markPerformed(long ingestItemId, Integer eventCrfId, Integer studyEventId,
                                 String sourceKind, String deviceKey, String laterality,
                                 int studyId, int actorUserId) {
        if (sourceKind == null || sourceKind.isBlank()) return Outcome.NOT_APPLICABLE;

        try (Connection c = dataSource.getConnection()) {
            Long modalityId = modalityOf(c, ingestItemId);
            if (modalityId == null && (deviceKey == null || deviceKey.isBlank())) {
                return Outcome.NOT_APPLICABLE;
            }
            Binding binding = findBinding(c, modalityId, studyId, deviceKey, laterality);
            if (binding == null) {
                LOG.debug("performed-tick skipped for file {}: no performed binding for {}/{}",
                        ingestItemId, sourceKind, deviceKey);
                return Outcome.NOT_APPLICABLE;
            }

            // P3.4 — the box may live on a different form from the one the file
            // was filed against. The visit plan's checklist is one CRF; a study
            // may file scans against another. Resolve inside the visit rather
            // than assuming the filing CRF, and start the owning form when it
            // has not been started — an operator should not have to open a CRF
            // by hand before the platform can record what it already knows.
            Target target = resolveTarget(c, eventCrfId, studyEventId, binding.itemOid(), actorUserId);
            if (target == null) {
                // Named deliberately: an operator seeing an un-ticked box needs
                // to know the platform tried. The OID is configuration, not
                // patient data.
                LOG.warn("performed-tick skipped for file {}: item {} is on no CRF of this visit",
                        ingestItemId, binding.itemOid());
                return Outcome.NOT_APPLICABLE;
            }

            // P3.0 — the upsert and the "never overwrite a person" rule are
            // shared with the retinal populator; only the audit rule below is
            // ours.
            SourcedItemDataWriter.Result result = SourcedItemDataWriter.upsert(
                    c, target.eventCrfId(), target.itemId(), binding.performedValue(),
                    new SourcedItemDataWriter.Ref(SourcedItemDataWriter.Source.INGEST, ingestItemId),
                    actorUserId);

            switch (result.outcome()) {
                case OPERATOR_VALUE_KEPT -> {
                    LOG.info("performed-tick skipped for file {}: event_crf {} item {} carries an operator value",
                            ingestItemId, target.eventCrfId(), binding.itemOid());
                    return Outcome.OPERATOR_VALUE_KEPT;
                }
                case OTHER_SOURCE_KEPT -> {
                    LOG.info("performed-tick skipped for file {}: event_crf {} item {} was written by "
                            + "another source", ingestItemId, target.eventCrfId(), binding.itemOid());
                    return Outcome.OTHER_SOURCE_KEPT;
                }
                case UNCHANGED -> {
                    return Outcome.ALREADY_SET;
                }
                case WRITTEN -> { /* fall through to the audit row */ }
            }
            writeAudit(c, target.eventCrfId(), ingestItemId, binding.itemOid(),
                    binding.performedValue(), actorUserId);
            LOG.info("performed-tick: file {} marked {} ({}) performed on event_crf {}",
                    ingestItemId, binding.itemOid(), binding.modalityCode(), target.eventCrfId());
            return Outcome.WRITTEN;
        } catch (SQLException e) {
            // Never propagate: the image is bound, which is the clinically
            // meaningful outcome. A missing tick is visible and fixable.
            LOG.error("performed-tick failed for image {} on event_crf {}: {}",
                    ingestItemId, eventCrfId, e.getMessage());
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
                        + " WHERE id.source_ingest_item_id = ? "
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
                "UPDATE item_data SET source_ingest_item_id = ?, date_updated = NOW() "
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

    /** What a modality's binding says to write. */
    private record Binding(String itemOid, String performedValue, String modalityCode) {}

    /**
     * The performed binding for this file's modality and eye.
     *
     * <p>Resolution order, and why: the file's own {@code imaging_modality_id}
     * wins when something already classified it — a DICOM whose calling AE
     * title matched, or an operator who chose. Otherwise the device is matched
     * against the study's catalogue, which is how an upload from a named
     * device finds its modality without anybody saying so.
     *
     * <p>Laterality: a binding for the file's eye wins over the both-eyes one,
     * so a CRF that grew per-eye boxes starts using them without the
     * both-eyes row having to be removed first.
     *
     * <p>The study walks up to its parent: a site inherits its study's
     * catalogue rather than duplicating it.
     */
    private static Binding findBinding(Connection c, Long modalityId, int studyId,
                                       String deviceKey, String laterality) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT b.item_oid, b.performed_value, im.code "
                        + "  FROM imaging_modality_item_binding b "
                        + "  JOIN imaging_modality im ON im.imaging_modality_id = b.imaging_modality_id "
                        + " WHERE b.role = 'performed' "
                        + "   AND im.status_id = 1 "
                        + "   AND b.laterality IN ('OU', COALESCE(?, 'OU')) ");
        if (modalityId != null) {
            sql.append("   AND im.imaging_modality_id = ? ");
        } else {
            sql.append("   AND im.study_id IN (?, COALESCE((SELECT parent_study_id FROM study "
                    + "                                      WHERE study_id = ?), -1)) ")
               .append("   AND lower(COALESCE(im.device, '')) = lower(?) ");
        }
        // An eye-specific binding beats the both-eyes one.
        sql.append(" ORDER BY CASE WHEN b.laterality = 'OU' THEN 1 ELSE 0 END, "
                + "          b.imaging_modality_item_binding_id LIMIT 1");

        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, laterality);
            if (modalityId != null) {
                ps.setLong(i, modalityId);
            } else {
                ps.setInt(i++, studyId);
                ps.setInt(i++, studyId);
                ps.setString(i, deviceKey == null ? "" : deviceKey);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String value = rs.getString(2);
                return new Binding(rs.getString(1),
                        value == null || value.isBlank() ? "1" : value,
                        rs.getString(3));
            }
        }
    }

    /** What the file itself says it is, when anything has classified it. */
    private static Long modalityOf(Connection c, long ingestItemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT imaging_modality_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    /** Where the box actually is: the form instance, and the item on it. */
    private record Target(int eventCrfId, int itemId) {}

    /**
     * Find the item on the visit, starting its form when necessary.
     *
     * <p>Three steps, in order of how little they assume:
     *
     * <ol>
     *   <li>The CRF the file was filed against, when one was named. The common
     *       case, and the cheapest.</li>
     *   <li>Any other CRF already started on this visit that carries the item.
     *       A study whose checklist is a separate form from the one scans are
     *       filed against lands here.</li>
     *   <li>A form on this visit's definition that carries the item but has
     *       not been started. Started via {@link EventCrfEnsurer} — refusing to
     *       record something the platform already knows, because nobody has
     *       opened a form yet, would mean the checklist silently disagrees with
     *       the files until an operator notices.</li>
     * </ol>
     *
     * @return null when no form of this visit carries the item at all
     */
    private static Target resolveTarget(Connection c, Integer eventCrfId, Integer studyEventId,
                                        String itemOid, int actorUserId) throws SQLException {
        if (eventCrfId != null && eventCrfId > 0) {
            Integer itemId = resolveItemInCrfVersion(c, eventCrfId, itemOid);
            if (itemId != null) return new Target(eventCrfId, itemId);
        }
        if (studyEventId == null || studyEventId <= 0) return null;

        // 2. Another started form on the same visit.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ec.event_crf_id, i.item_id "
                        + "  FROM event_crf ec "
                        + "  JOIN item_form_metadata ifm ON ifm.crf_version_id = ec.crf_version_id "
                        + "  JOIN item i ON i.item_id = ifm.item_id "
                        + " WHERE ec.study_event_id = ? AND i.oc_oid = ? "
                        + "   AND COALESCE(ec.status_id, 0) NOT IN (5, 7) "
                        + " ORDER BY ec.event_crf_id LIMIT 1")) {
            ps.setInt(1, studyEventId);
            ps.setString(2, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Target(rs.getInt(1), rs.getInt(2));
            }
        }

        // 3. A form on this visit's definition that nobody has started yet.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT cv.crf_version_id, i.item_id "
                        + "  FROM study_event se "
                        + "  JOIN event_definition_crf edc "
                        + "    ON edc.study_event_definition_id = se.study_event_definition_id "
                        + "   AND COALESCE(edc.status_id, 0) NOT IN (5, 7) "
                        + "  JOIN crf_version cv ON cv.crf_id = edc.crf_id "
                        + "  JOIN item_form_metadata ifm ON ifm.crf_version_id = cv.crf_version_id "
                        + "  JOIN item i ON i.item_id = ifm.item_id "
                        + " WHERE se.study_event_id = ? AND i.oc_oid = ? "
                        + " ORDER BY (cv.crf_version_id = edc.default_version_id) DESC, "
                        + "          cv.crf_version_id DESC LIMIT 1")) {
            ps.setInt(1, studyEventId);
            ps.setString(2, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int crfVersionId = rs.getInt(1);
                int itemId = rs.getInt(2);
                EventCrfEnsurer.Instance instance =
                        EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, actorUserId);
                if (instance.removed()) {
                    // Somebody deleted this form. Reviving it to record a tick
                    // would undo their decision — see EventCrfEnsurer.
                    LOG.info("performed-tick skipped: the form carrying {} on visit {} was removed",
                            itemOid, studyEventId);
                    return null;
                }
                return new Target(instance.eventCrfId(), itemId);
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

    private static void writeAudit(Connection c, int eventCrfId, long ingestItemId,
                                   String itemOid, String value, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                        + "audit_table, entity_id, entity_name, old_value, new_value, event_crf_id) "
                        + "VALUES (?, now(), ?, 'item_data', ?, ?, NULL, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_IMAGE_PERFORMED_AUTOTICK);
            ps.setInt(2, actorUserId);
            ps.setInt(3, (int) ingestItemId);
            ps.setString(4, itemOid);
            ps.setString(5, value + " (from file " + ingestItemId + ")");
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
