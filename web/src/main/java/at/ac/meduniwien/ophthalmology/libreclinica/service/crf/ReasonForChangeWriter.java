/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crf;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 admin-rfc — Reason-For-Change writer.
 *
 * <p>Writes one {@code discrepancy_note} row of {@code type_id = 4}
 * ({@code REASON_FOR_CHANGE}) per RFC capture, populates the
 * {@code dn_item_data_map} mapping, and threads follow-up RFCs as
 * children of the most-recent prior RFC parent on the same
 * {@code item_data} row.
 *
 * <p><strong>Threading model:</strong>
 * <ul>
 *   <li>If no RFC parent exists for the {@code item_data} (lookup via
 *       {@link DiscrepancyNoteDAO#findLatestRfcParentForItemData})
 *       then the new row is itself a parent
 *       ({@code parent_dn_id IS NULL}).</li>
 *   <li>Otherwise the new row hangs off that prior parent via
 *       {@code parent_dn_id} so the discrepancy view shows one tree
 *       per item per saved value.</li>
 * </ul>
 *
 * <p><strong>Failure semantics:</strong> any exception from the DAO
 * surface is caught + logged at WARN. The CRF save MUST NOT roll back
 * just because an RFC write hiccupped — losing the RFC row is annoying
 * but losing the item_data update is worse. Mirrors the same trade-off
 * the audit-event writer makes on lines 622-653 of
 * {@code EventCrfsApiController}.
 *
 * <p>The writer is stateless; instantiate per-request inside the
 * controller (cheap, mirrors the DAO-per-request pattern the existing
 * controllers already use).
 */
public class ReasonForChangeWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ReasonForChangeWriter.class);

    /** Column name written to {@code dn_item_data_map.column_name}. */
    public static final String DEFAULT_COLUMN = "value";

    private final DiscrepancyNoteDAO dao;

    public ReasonForChangeWriter(DiscrepancyNoteDAO dao) {
        this.dao = dao;
    }

    /**
     * Persist one RFC note + mapping for the given item_data save.
     *
     * @param itemDataId target {@code item_data.id} (must be > 0; the
     *                   {@code dn_item_data_map} insert requires the
     *                   item_data row to already exist).
     * @param study      active study (for {@code discrepancy_note.study_id}).
     * @param actor      authenticated user (for {@code owner_id}).
     * @param reasonText reason-for-change text supplied by the operator
     *                   (mapped to {@code description}; trimmed).
     * @return the created {@link DiscrepancyNoteBean} or {@code null} on
     *         failure (caller logs + continues; the CRF save persists
     *         regardless per the failure-semantics contract).
     */
    public DiscrepancyNoteBean writeRfc(int itemDataId, StudyBean study,
                                        UserAccountBean actor, String reasonText) {
        if (itemDataId <= 0) {
            LOG.warn("ReasonForChangeWriter: itemDataId={} is non-positive; skipping RFC write", itemDataId);
            return null;
        }
        if (study == null || study.getId() == 0) {
            LOG.warn("ReasonForChangeWriter: no active study bound; skipping RFC write for item_data {}", itemDataId);
            return null;
        }
        if (actor == null || actor.getId() == 0) {
            LOG.warn("ReasonForChangeWriter: no actor; skipping RFC write for item_data {}", itemDataId);
            return null;
        }
        String description = reasonText == null ? "" : reasonText.trim();
        if (description.isEmpty()) {
            LOG.warn("ReasonForChangeWriter: empty reason text for item_data {}; skipping RFC write", itemDataId);
            return null;
        }

        try {
            int parentId = dao.findLatestRfcParentForItemData(itemDataId);

            DiscrepancyNoteBean dn = new DiscrepancyNoteBean();
            // discrepancy_note_type_id = 4 (REASON_FOR_CHANGE).
            dn.setDiscrepancyNoteTypeId(DiscrepancyNoteType.REASON_FOR_CHANGE.getId());
            dn.setDisType(DiscrepancyNoteType.REASON_FOR_CHANGE);
            // OC convention: a parent RFC has resolution_status NOT_APPLICABLE;
            // child threads inherit the parent's status. ResolutionStatus.NOT_APPLICABLE = 5.
            dn.setResolutionStatusId(ResolutionStatus.NOT_APPLICABLE.getId());
            dn.setResStatus(ResolutionStatus.NOT_APPLICABLE);
            dn.setDescription(truncate(description, 255));
            dn.setDetailedNotes(description);
            dn.setOwner(actor);
            dn.setOwnerId(actor.getId());
            dn.setStudyId(study.getId());
            // Mapping target — DnItemDataMapDAO.createMapping reads these.
            dn.setEntityType("itemData");
            dn.setEntityId(itemDataId);
            dn.setColumn(DEFAULT_COLUMN);
            dn.setActivated(true);
            // Threading: existing parent becomes our parent_dn_id; otherwise
            // 0 (DAO inserts NULL via its nullVars path).
            dn.setParentDnId(parentId);

            DiscrepancyNoteBean created = dao.create(dn);
            if (created != null && created.getId() > 0) {
                // The mapping row must be written separately — DAO.create
                // only touches discrepancy_note.
                dao.createMapping(created);
                LOG.debug("RFC DN id={} written for item_data {} (parent {}); actor {} study {}",
                        created.getId(), itemDataId, parentId,
                        actor.getName(), study.getOid());
                return created;
            }
            LOG.warn("RFC DN create returned no id for item_data {} (actor {}, study {})",
                    itemDataId, actor.getName(), study.getOid());
            return null;
        } catch (Exception e) {
            LOG.warn("RFC DN write failed for item_data {} (continuing without RFC row): {}",
                    itemDataId, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
