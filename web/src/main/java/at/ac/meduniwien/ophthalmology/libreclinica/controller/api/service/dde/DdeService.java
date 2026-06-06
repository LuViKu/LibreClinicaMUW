/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.CrfEntryDto;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.DdeCommitResponse;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.DdeConflictsDto;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.DdeReconcileRequest;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfsApiController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 dde — service-layer for blind double-data-entry.
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li>{@link #commitPass2} — diff pass-2 against pass-1, spawn one
 *       FAILEDVAL {@code discrepancy_note} row per mismatch, mark
 *       DDE complete if zero mismatches.</li>
 *   <li>{@link #listConflicts} — build the {@link DdeConflictsDto}
 *       for the reconcile view (side-by-side IDE vs DDE values).</li>
 *   <li>{@link #resolveConflict} — DM/Admin picks the canonical
 *       value, writes audit event, closes the FAILEDVAL note.</li>
 * </ol>
 *
 * <p>Audit emission delegates to
 * {@link EventCrfsApiController#writeAuditEvent} (promoted to
 * package-private as a precondition for this work).
 *
 * <p>The pass-1 values for the diff live in {@code item_data}; the
 * blind pass-2 values arrive in the controller and are NOT persisted
 * to {@code item_data} until reconciliation chooses {@code dde} or
 * {@code manual}. We instead persist pass-2 values into a sidecar
 * column on the FAILEDVAL note's description field (format:
 * {@code "DDE pass-2: '<value>' vs IDE: '<value>'"}) so the reconcile
 * view can reproduce the side-by-side without re-prompting the
 * pass-2 clerk.
 *
 * <p>Phase E.6 cliff: this initial cut targets the happy paths
 * (matching diffs → markCompleteDDE, mismatching diffs → spawn
 * notes). Edge cases deferred and tracked in the cluster
 * "Deferred items" log:
 * <ul>
 *   <li>Repeating ItemGroup ordinals — the diff joins by item OID
 *       only, which is wrong for repeating groups; will fail loudly
 *       (multiple item_data rows per OID) until M2 of this cluster
 *       lands the (oid, ordinal) compound key.</li>
 *   <li>Audit-event packed actionMessage parity with M10 viewer —
 *       confirmed at unit test level only; live MockMvc IT vs
 *       audit-log endpoint deferred until {@code audit-discrepancy-export}
 *       lands the export endpoint.</li>
 * </ul>
 */
public class DdeService {

    private static final Logger LOG = LoggerFactory.getLogger(DdeService.class);

    private final DataSource dataSource;

    public DdeService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Commit the blind pass-2 values. Returns a response carrying the
     * mismatch count + new EventCRF status.
     *
     * @param ecb         the event_crf being DDE-committed
     * @param ss          the parent study_subject (audit context)
     * @param study       the active study (audit context)
     * @param ddeClerk    the pass-2 clerk (the session user)
     * @param values      pass-2 values keyed by item OID
     */
    public DdeCommitResponse commitPass2(EventCRFBean ecb, StudySubjectBean ss,
                                         StudyBean study, UserAccountBean ddeClerk,
                                         Map<String, Object> values) {
        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        DiscrepancyNoteDAO dnDAO = new DiscrepancyNoteDAO(dataSource);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        int mismatch = 0;
        int matched = 0;

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String itemOid = entry.getKey();
            String pass2 = entry.getValue() == null ? "" : String.valueOf(entry.getValue());

            ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
            if (candidates == null || candidates.isEmpty()) {
                LOG.warn("DDE commit: unknown item OID '{}' on event_crf {} (skipped)",
                        itemOid, ecb.getId());
                continue;
            }
            ItemBean item = candidates.get(0);

            ItemDataBean existing = idDAO.findByItemIdAndEventCRFId(item.getId(), ecb.getId());
            String pass1 = existing != null && existing.getValue() != null
                    ? existing.getValue() : "";

            if (pass1.equals(pass2)) {
                matched++;
                continue;
            }

            mismatch++;
            // Spawn a FAILEDVAL note. Description carries both values so
            // the reconcile view + audit trail can reproduce the diff
            // without re-querying pass-2 (which lives only in this request).
            DiscrepancyNoteBean note = new DiscrepancyNoteBean();
            note.setDescription(formatDdeMismatchDescription(pass1, pass2));
            note.setDetailedNotes("DDE mismatch — IDE='" + pass1 + "' DDE='" + pass2 + "'");
            note.setDiscrepancyNoteTypeId(DiscrepancyNoteType.FAILEDVAL.getId());
            note.setResolutionStatusId(ResolutionStatus.OPEN.getId());
            note.setStudyId(study.getId());
            note.setEntityType("itemData");
            note.setEntityId(existing != null ? existing.getId() : 0);
            note.setColumn("value");
            note.setOwner(ddeClerk);

            DiscrepancyNoteBean saved = dnDAO.create(note);
            try {
                dnDAO.createMapping(saved);
            } catch (Exception mapEx) {
                LOG.warn("DDE: dn_item_data_map create failed for note {} ({})",
                        saved.getId(), mapEx.getMessage());
            }

            EventCrfsApiController.writeAuditEvent(auditDAO, ddeClerk, study, ss,
                    "dde_mismatch_spawn", "discrepancy_note", saved.getId(),
                    itemOid, pass1, pass2);
        }

        // 0 mismatches → DDE complete via markCompleteDDE on the DAO.
        if (mismatch == 0) {
            EventCRFDAO ecDAO = new EventCRFDAO(dataSource);
            ecDAO.markComplete(ecb, /* ide */ false);
            ecb.setUpdater(ddeClerk);
            ecb.setUpdatedDate(new Date());
            ecDAO.update(ecb);

            EventCrfsApiController.writeAuditEvent(auditDAO, ddeClerk, study, ss,
                    "dde_commit_complete", "event_crf", ecb.getId(),
                    "date_validate_completed", "", Instant.now().toString());

            return new DdeCommitResponse(
                    String.valueOf(ecb.getId()), 0, "dde-complete",
                    Instant.now().toString());
        }

        EventCrfsApiController.writeAuditEvent(auditDAO, ddeClerk, study, ss,
                "dde_commit_with_conflicts", "event_crf", ecb.getId(),
                "mismatch_count", "0", String.valueOf(mismatch));

        LOG.info("DDE commit event_crf {}: matched={} mismatch={} (user {})",
                ecb.getId(), matched, mismatch, ddeClerk.getName());

        return new DdeCommitResponse(
                String.valueOf(ecb.getId()), mismatch, "dde-conflicts",
                Instant.now().toString());
    }

    /**
     * List the open FAILEDVAL conflict rows for an EventCRF. Each
     * row carries the pass-1 (IDE) and pass-2 (DDE) values parsed
     * back out of the note's description (the format we wrote on
     * commit). {@code resolved=true} when the note's resolution
     * status is anything other than OPEN.
     */
    public DdeConflictsDto listConflicts(EventCRFBean ecb, StudySubjectBean ss) {
        List<DdeConflictsDto.DdeConflictItemDto> items = new ArrayList<>();
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemFormMetadataDAO ifmDAO = new ItemFormMetadataDAO(dataSource);
        DiscrepancyNoteDAO dnDAO = new DiscrepancyNoteDAO(dataSource);

        // Look up every item_data row for this EventCRF, then for each
        // ask the DN DAO for the open FAILEDVAL note (if any). This is
        // O(N items) DB calls; acceptable for the modest CRF sizes
        // (MUW CRFs average <30 items). M2 of this cluster will batch.
        @SuppressWarnings("unchecked")
        ArrayList<ItemDataBean> all = idDAO.findAllByEventCRFId(ecb.getId());
        if (all == null) return new DdeConflictsDto(
                String.valueOf(ecb.getId()), ss.getLabel(), "", List.of());

        for (ItemDataBean idb : all) {
            // Skip non-AVAILABLE rows — they are removed/locked and
            // never participate in DDE reconciliation.
            if (idb.getStatus() != null && !idb.getStatus().equals(Status.AVAILABLE)) continue;
            DiscrepancyNoteBean note = findOpenFailedValForItemData(dnDAO, idb.getId());
            if (note == null) continue;

            ItemBean item = (ItemBean) itemDAO.findByPK(idb.getItemId());
            if (item == null) continue;
            String itemOid = item.getOid();
            String label = item.getName();
            try {
                ItemFormMetadataBean ifm = ifmDAO.findByItemIdAndCRFVersionId(
                        item.getId(), ecb.getCRFVersionId());
                if (ifm != null && ifm.getLeftItemText() != null
                        && !ifm.getLeftItemText().isBlank()) {
                    label = ifm.getLeftItemText();
                }
            } catch (Exception ifmEx) {
                // Best-effort label hydration; falls back to item.name.
                LOG.debug("ifm lookup failed for item {} ({})", item.getId(), ifmEx.getMessage());
            }

            String[] parsed = parseDdeMismatchDescription(note.getDescription());
            String ideValue = parsed[0];
            String ddeValue = parsed[1];
            boolean resolved = note.getResolutionStatusId() != ResolutionStatus.OPEN.getId();
            String winner = resolved ? inferWinner(idb.getValue(), ideValue, ddeValue) : null;

            items.add(new DdeConflictsDto.DdeConflictItemDto(
                    itemOid, label, ideValue, ddeValue, resolved, winner));
        }

        return new DdeConflictsDto(
                String.valueOf(ecb.getId()), ss.getLabel(),
                "", items);
    }

    /**
     * Apply a DM/Admin's resolution to one conflict row. Returns the
     * URI of the next unresolved conflict in this EventCRF, or
     * {@code null} when all conflicts are resolved (in which case
     * the EventCRF flips to {@code dde-complete}).
     */
    public String resolveConflict(EventCRFBean ecb, StudySubjectBean ss,
                                  StudyBean study, UserAccountBean dmUser,
                                  String itemOid, DdeReconcileRequest body) {
        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        DiscrepancyNoteDAO dnDAO = new DiscrepancyNoteDAO(dataSource);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("No item with OID " + itemOid);
        }
        ItemBean item = candidates.get(0);

        ItemDataBean idb = idDAO.findByItemIdAndEventCRFId(item.getId(), ecb.getId());
        if (idb == null || idb.getId() == 0) {
            throw new IllegalArgumentException(
                    "No item_data row for item " + itemOid + " on event_crf " + ecb.getId());
        }
        DiscrepancyNoteBean note = findOpenFailedValForItemData(dnDAO, idb.getId());
        if (note == null) {
            throw new IllegalStateException(
                    "No open FAILEDVAL note for item " + itemOid + " on event_crf " + ecb.getId());
        }

        String[] parsed = parseDdeMismatchDescription(note.getDescription());
        String ideValue = parsed[0];
        String ddeValue = parsed[1];
        String oldValue = idb.getValue() == null ? "" : idb.getValue();
        String newValue = switch (body.winner()) {
            case "ide" -> ideValue;
            case "dde" -> ddeValue;
            case "manual" -> body.value() == null ? "" : body.value();
            default -> throw new IllegalArgumentException(
                    "Unknown winner: " + body.winner());
        };

        // Persist the chosen value.
        idb.setValue(newValue);
        idb.setUpdater(dmUser);
        idb.setUpdaterId(dmUser.getId());
        idb.setStatus(Status.AVAILABLE);
        idb.setOldStatus(Status.AVAILABLE);
        idDAO.update(idb);

        // Close the FAILEDVAL note.
        note.setResolutionStatusId(ResolutionStatus.CLOSED.getId());
        dnDAO.update(note);

        EventCrfsApiController.writeAuditEvent(auditDAO, dmUser, study, ss,
                "dde_resolve_" + body.winner(), "item_data", idb.getId(),
                itemOid, oldValue, newValue);
        EventCrfsApiController.writeAuditEvent(auditDAO, dmUser, study, ss,
                "dde_rfc", "item_data", idb.getId(),
                "reason_for_change", "", body.reasonForChange() == null
                        ? "" : body.reasonForChange());

        // If this was the last open FAILEDVAL note, flip EventCRF to
        // dde-complete via markCompleteDDE.
        int remaining = countOpenFailedVal(ecb.getId());
        if (remaining == 0) {
            EventCRFDAO ecDAO = new EventCRFDAO(dataSource);
            ecDAO.markComplete(ecb, /* ide */ false);
            EventCrfsApiController.writeAuditEvent(auditDAO, dmUser, study, ss,
                    "dde_reconciliation_complete", "event_crf", ecb.getId(),
                    "date_validate_completed", "", Instant.now().toString());
            return null;
        }
        // More to go — surface the next item OID as a hint URI.
        return "/event-crfs/" + ecb.getId() + "/dde-reconcile";
    }

    /**
     * Count open FAILEDVAL discrepancy_note rows linked to any
     * item_data row of the given EventCRF. Used both by the
     * {@code dde-pass} endpoint (to decide pass=reconcile vs done)
     * and by {@link #resolveConflict} (to know when reconciliation
     * is finished).
     */
    public int countOpenFailedVal(int eventCrfId) {
        String sql = "SELECT count(*) FROM discrepancy_note dn "
                + "JOIN dn_item_data_map m ON m.discrepancy_note_id = dn.discrepancy_note_id "
                + "JOIN item_data id ON id.item_data_id = m.item_data_id "
                + "WHERE id.event_crf_id = ? "
                + "  AND dn.discrepancy_note_type_id = ? "
                + "  AND dn.resolution_status_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, DiscrepancyNoteType.FAILEDVAL.getId());
            ps.setInt(3, ResolutionStatus.OPEN.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            LOG.warn("countOpenFailedVal({}): {}", eventCrfId, e.getMessage());
        }
        return 0;
    }

    /* ---------------------- internal helpers ---------------------- */

    /**
     * Locate the open FAILEDVAL note for one item_data row, if any.
     * Returns the most-recent open note when multiple exist (DDE
     * should only ever spawn one per row, but defensive).
     */
    private DiscrepancyNoteBean findOpenFailedValForItemData(DiscrepancyNoteDAO dnDAO,
                                                              int itemDataId) {
        try {
            @SuppressWarnings("unchecked")
            ArrayList<DiscrepancyNoteBean> notes =
                    dnDAO.findAllByEntityAndColumn("itemData", itemDataId, "value");
            if (notes == null) return null;
            for (DiscrepancyNoteBean n : notes) {
                if (n == null) continue;
                if (n.getDiscrepancyNoteTypeId() != DiscrepancyNoteType.FAILEDVAL.getId()) continue;
                if (n.getResolutionStatusId() != ResolutionStatus.OPEN.getId()) continue;
                return n;
            }
        } catch (Exception e) {
            LOG.debug("findOpenFailedValForItemData({}): {}", itemDataId, e.getMessage());
        }
        return null;
    }

    /**
     * Description format we use to round-trip both pass values
     * through {@code discrepancy_note.description}. {@code public}
     * so unit tests in sibling packages can pin the format without
     * reflection.
     */
    public static String formatDdeMismatchDescription(String ideValue, String ddeValue) {
        return "DDE mismatch — IDE='" + safe(ideValue) + "' DDE='" + safe(ddeValue) + "'";
    }

    /**
     * Parse the description we wrote on commit. Returns {ideValue, ddeValue}.
     * {@code public} for unit testability.
     */
    public static String[] parseDdeMismatchDescription(String description) {
        if (description == null) return new String[]{"", ""};
        int ideStart = description.indexOf("IDE='");
        int ddeStart = description.indexOf("DDE='");
        if (ideStart < 0 || ddeStart < 0) return new String[]{"", ""};
        int ideEnd = description.indexOf('\'', ideStart + 5);
        int ddeEnd = description.indexOf('\'', ddeStart + 5);
        if (ideEnd < 0 || ddeEnd < 0) return new String[]{"", ""};
        return new String[]{
                description.substring(ideStart + 5, ideEnd),
                description.substring(ddeStart + 5, ddeEnd)
        };
    }

    private static String safe(String s) {
        if (s == null) return "";
        // Strip quotes the format can't round-trip cleanly.
        return s.replace("'", "’");
    }

    /**
     * Best-effort winner inference for resolved notes.
     * Falls back to "manual" when the resolved value matches neither
     * the IDE nor the DDE value.
     */
    private static String inferWinner(String current, String ideValue, String ddeValue) {
        if (current == null) return "manual";
        if (current.equals(ideValue)) return "ide";
        if (current.equals(ddeValue)) return "dde";
        return "manual";
    }
}
