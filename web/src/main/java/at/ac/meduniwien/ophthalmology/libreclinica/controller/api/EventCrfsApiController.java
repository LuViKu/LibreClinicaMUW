/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseOptionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SectionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SectionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M5 — CRF Entry read adapter.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /pages/api/v1/eventCrfs/{id}} — returns the
 *       {@link CrfEntryDto} the Vue SPA's CRF Entry view renders
 *       against. Replaces the SPA's hardcoded Demographics mock
 *       in {@code stores/crfEntry.ts}.</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> chain-level
 * {@code .anyRequest().hasRole("USER")} gates this controller. The
 * endpoint also verifies that the resolved EventCRF's study_subject
 * belongs to the session-bound active study; returns 403 otherwise.
 *
 * <p><strong>Path param shape:</strong> numeric {@code event_crf_id}.
 * The SPA carries it as a string (the {@code eventCrfOid} field in
 * {@link CrfEntryDto}) since the legacy {@code event_crf} table does
 * not have an OID column. The follow-up M2 work that populates the
 * Subject Matrix row's {@code events[]} array will use the same
 * numeric-id-as-string convention; routes stay stable.
 *
 * <p><strong>Schema walk:</strong>
 * <ol>
 *   <li>{@link EventCRFDAO#findByPK} → {@link EventCRFBean}.</li>
 *   <li>{@link CRFVersionDAO#findByPK} → CRF version metadata.</li>
 *   <li>{@link SectionDAO#findAllByCRFVersionId} → ordered sections.</li>
 *   <li>{@link ItemFormMetadataDAO#findAllByCRFVersionId} → form
 *       metadata, including {@link ResponseSetBean} populated by the
 *       upstream JOIN against {@code response_set}.</li>
 *   <li>{@link ItemDAO#findAllBySectionIdOrderedByItemFormMetadataOrdinal}
 *       — items per section in the canonical display order.</li>
 *   <li>{@link ItemDataDAO#findAllByEventCRFId} → saved values
 *       keyed by item OID (zero rows for not-started CRFs).</li>
 * </ol>
 *
 * <p>Status mapping:
 * <ul>
 *   <li>{@code electronic_signature_status = true} → "locked"</li>
 *   <li>{@code date_completed != null} → "complete"</li>
 *   <li>any persisted {@link ItemDataBean} → "in-progress"</li>
 *   <li>otherwise → "not-started"</li>
 * </ul>
 *
 * <p>Repetition groups, rule-driven show/required predicates, and
 * inline discrepancy threads are out of scope for M5 (matches the
 * "Out of scope for the v0" list in {@code types/crf.ts}). M6 +
 * later milestones extend the contract.
 */
@RestController
@RequestMapping("/api/v1/eventCrfs")
@Tag(name = "Event CRFs", description = "CRF read + bulk save + markComplete.")
public class EventCrfsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventCrfsApiController.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public EventCrfsApiController(@Qualifier("dataSource") DataSource dataSource,
                                  SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping("/{id:[0-9]+}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = CrfEntryDto.class)))
    public ResponseEntity<?> getEventCrf(@PathVariable("id") int eventCrfId, HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — POST /pages/api/v1/me/activeStudy first."
            ));
        }

        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }

        // Resolve the StudySubject + verify it lives in the active study.
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "event_crf " + eventCrfId + " has no resolvable study_subject"));
        }
        StudyUserRoleBean currentRole2 = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds2 = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole2);
        if (!visibleStudyIds2.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }

        // Resolve the StudyEvent + its definition for the {eventLabel}.
        StudyEventDAO seDAO = new StudyEventDAO(dataSource);
        StudyEventBean se = (StudyEventBean) seDAO.findByPK(ecb.getStudyEventId());
        StudyEventDefinitionDAO sedDAO = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean sed = (se == null || se.getId() == 0)
                ? null
                : (StudyEventDefinitionBean) sedDAO.findByPK(se.getStudyEventDefinitionId());
        String eventLabel = formatEventLabel(sed, se);

        // Resolve the CRF version metadata + parent CRF (for the SPA's
        // {schema.name} the user sees, e.g. "Demographics").
        CRFVersionDAO crfvDAO = new CRFVersionDAO(dataSource);
        CRFVersionBean crfv = (CRFVersionBean) crfvDAO.findByPK(ecb.getCRFVersionId());
        if (crfv == null || crfv.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "event_crf " + eventCrfId + " references missing crf_version " + ecb.getCRFVersionId()));
        }
        CRFDAO crfDAO = new CRFDAO(dataSource);
        CRFBean crf = (CRFBean) crfDAO.findByPK(crfv.getCrfId());
        String crfDisplayName = (crf != null && crf.getName() != null && !crf.getName().isBlank())
                ? crf.getName()
                : firstNonBlank(crfv.getName(), "CRF");

        // Build the schema: sections in their natural order, items per
        // section in item_form_metadata.ordinal order. The DAOs do the
        // JOIN against response_set so each ItemFormMetadataBean carries
        // its populated ResponseSetBean.
        SectionDAO sectionDAO = new SectionDAO(dataSource);
        ItemFormMetadataDAO ifmDAO = new ItemFormMetadataDAO(dataSource);
        ItemDAO itemDAO = new ItemDAO(dataSource);

        List<SectionBean> sections = sectionDAO.findAllByCRFVersionId(crfv.getId());
        sections.sort(Comparator.comparingInt(SectionBean::getOrdinal));

        List<ItemFormMetadataBean> allIfms;
        try {
            allIfms = ifmDAO.findAllByCRFVersionId(crfv.getId());
        } catch (Exception e) {
            LOG.error("Failed to load item_form_metadata for crf_version {}", crfv.getId(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Schema load failed: " + e.getMessage()
            ));
        }

        // Map item_id → ItemFormMetadataBean so we can look up its
        // response-set + per-item display config when iterating items.
        Map<Integer, ItemFormMetadataBean> ifmByItemId = new HashMap<>();
        for (ItemFormMetadataBean ifm : allIfms) {
            ifmByItemId.put(ifm.getItemId(), ifm);
        }

        List<CrfEntryDto.CrfSectionDto> sectionDtos = new ArrayList<>(sections.size());
        for (SectionBean sb : sections) {
            List<ItemBean> items = itemDAO.findAllBySectionIdOrderedByItemFormMetadataOrdinal(sb.getId());
            List<CrfEntryDto.CrfItemDto> itemDtos = new ArrayList<>(items.size());
            for (ItemBean ib : items) {
                ItemFormMetadataBean ifm = ifmByItemId.get(ib.getId());
                itemDtos.add(buildItemDto(ib, ifm));
            }
            sectionDtos.add(new CrfEntryDto.CrfSectionDto(
                    sb.getLabel(),
                    blankToNull(sb.getTitle(), sb.getLabel()),
                    blankToNull(sb.getInstructions(), null),
                    itemDtos
            ));
        }

        // Saved values keyed by item OID.
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        List<ItemDataBean> dataRows = new ArrayList<>();
        for (SectionBean sb : sections) {
            dataRows.addAll(idDAO.findAllBySectionIdAndEventCRFId(sb.getId(), ecb.getId()));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (ItemDataBean idb : dataRows) {
            ItemBean ib = (ItemBean) itemDAO.findByPK(idb.getItemId());
            if (ib == null || ib.getOid() == null) continue;
            values.put(ib.getOid(), idb.getValue());
        }

        String status = computeStatus(ecb, !dataRows.isEmpty());
        String lastSavedAt = formatIsoInstant(latestUpdate(ecb));

        CrfEntryDto.CrfSchemaDto schema = new CrfEntryDto.CrfSchemaDto(
                crfv.getOid() != null ? crfv.getOid() : "",
                crfDisplayName,
                crfv.getName() != null ? crfv.getName() : "v1",
                sectionDtos
        );

        // Phase E.6 dde — embed the DDE marker block when the parent
        // event_definition_crf has double_entry=true. {@code dde} stays
        // null for single-pass studies so legacy single-entry flows
        // pay zero cost.
        CrfEntryDto.DdeBlockDto ddeBlock = resolveDdeBlock(ecb, currentUser);

        CrfEntryDto dto = new CrfEntryDto(
                String.valueOf(ecb.getId()),
                ss.getLabel(),
                eventLabel,
                schema,
                // Server-side blinding: pass-2 callers see an empty
                // values map so they cannot copy the IDE entry.
                ddeBlock != null && "2".equals(ddeBlock.pass())
                        ? new LinkedHashMap<>() : values,
                status,
                lastSavedAt,
                ddeBlock
        );

        LOG.debug("CRF Entry adapter served event_crf {} (subject {} / event {}) for study {} (user {})",
                ecb.getId(), ss.getLabel(), eventLabel, currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.ok(dto);
    }

    /**
     * Phase E.4 M6 — incremental save. Accepts a partial values map
     * (item OID → value) and upserts each one into {@code item_data}.
     * Idempotent: same payload twice yields the same DB state. Returns
     * the updated {@code lastSavedAt} ISO instant so the SPA can
     * refresh its header.
     *
     * <p>Reject if the CRF is locked (status SIGNED or LOCKED) — once
     * signed, edits go through a different unlock flow.
     *
     * <p>Audit-log: one {@link AuditEventBean} row per changed item,
     * recording (auditTable="item_data", entityId, columnName="value",
     * oldValue, newValue). Creation-from-empty also writes one row with
     * an empty oldValue so the audit trail shows the initial entry.
     */
    @PostMapping("/{id:[0-9]+}/items")
    public ResponseEntity<?> saveItems(@PathVariable("id") int eventCrfId,
                                       @RequestBody SaveItemsRequest body,
                                       HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — POST /pages/api/v1/me/activeStudy first."
            ));
        }
        if (body == null || body.values() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing 'values' in request body"));
        }

        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean currentRoleSave = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleSave = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRoleSave);
        if (ss == null || !visibleSave.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }
        if (ecb.getStatus() == Status.SIGNED || ecb.getStatus() == Status.LOCKED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " is locked — cannot save"));
        }

        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        int saved = 0;
        int rejected = 0;
        for (Map.Entry<String, Object> entry : body.values().entrySet()) {
            String itemOid = entry.getKey();
            String newValue = entry.getValue() == null ? "" : String.valueOf(entry.getValue());

            ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
            if (candidates == null || candidates.isEmpty()) {
                LOG.warn("saveItems: unknown item OID '{}' on event_crf {} (skipped)", itemOid, eventCrfId);
                rejected++;
                continue;
            }
            ItemBean item = candidates.get(0);

            ItemDataBean existing = idDAO.findByItemIdAndEventCRFId(item.getId(), ecb.getId());
            String oldValue = "";
            boolean isCreate;
            if (existing != null && existing.getId() > 0) {
                oldValue = existing.getValue() == null ? "" : existing.getValue();
                if (oldValue.equals(newValue)) {
                    // Same value — skip the write but still count as saved.
                    saved++;
                    continue;
                }
                existing.setValue(newValue);
                existing.setUpdater(currentUser);
                existing.setUpdaterId(currentUser.getId());
                existing.setStatus(Status.AVAILABLE);
                existing.setOldStatus(Status.AVAILABLE);
                idDAO.update(existing);
                isCreate = false;
            } else {
                ItemDataBean idb = new ItemDataBean();
                idb.setEventCRFId(ecb.getId());
                idb.setItemId(item.getId());
                idb.setValue(newValue);
                idb.setOrdinal(1);
                idb.setOwnerId(currentUser.getId());
                idb.setOwner(currentUser);
                idb.setStatus(Status.AVAILABLE);
                idb.setOldStatus(Status.AVAILABLE);
                idb.setDeleted(false);
                idDAO.create(idb);
                isCreate = true;
            }

            writeAuditEvent(auditDAO, currentUser, currentStudy, ss,
                    isCreate ? "item_data_create" : "item_data_update",
                    "item_data", existing != null ? existing.getId() : 0,
                    itemOid, oldValue, newValue);
            saved++;
        }

        // Touch the EventCRF so {date_updated} reflects the save —
        // drives the SPA's lastSavedAt header.
        ecb.setUpdater(currentUser);
        ecb.setUpdatedDate(new Date());
        eventCrfDAO.update(ecb);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventCrfOid", String.valueOf(ecb.getId()));
        out.put("savedItemCount", saved);
        out.put("rejectedItemCount", rejected);
        out.put("lastSavedAt", formatIsoInstant(latestUpdate(eventCrfDAO.findByPK(ecb.getId()))));
        out.put("status", computeStatus(ecb, /* re-check after touch */ saved > 0));

        LOG.info("CRF save: event_crf {} got {} items (rejected {}); user {} study {}",
                ecb.getId(), saved, rejected, currentUser.getName(), currentStudy.getOid());

        return ResponseEntity.ok(out);
    }

    /**
     * Phase E.4 M6 — mark CRF complete. Delegates to
     * {@link EventCRFDAO#markComplete} (the IDE — initial data entry —
     * path). The SPA calls this when the user clicks "Mark complete"
     * after a clean validation pass; the SPA-side validation in
     * {@code stores/crfEntry.ts:computeItemErrors} prevents the click
     * when required items are missing, so this endpoint trusts the
     * client to have run that gate and only enforces the locked-CRF
     * gate server-side.
     *
     * <p>Reject if the CRF is locked (idempotent on already-complete
     * CRFs: returns 200 with the existing state).
     */
    @PostMapping("/{id:[0-9]+}/markComplete")
    public ResponseEntity<?> markComplete(@PathVariable("id") int eventCrfId,
                                          HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."
            ));
        }

        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean currentRoleComplete = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleComplete = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRoleComplete);
        if (ss == null || !visibleComplete.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }
        if (ecb.getStatus() == Status.SIGNED || ecb.getStatus() == Status.LOCKED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " is already locked"));
        }

        // Already complete (date_completed set) → return idempotently.
        if (ecb.getDateCompleted() == null) {
            eventCrfDAO.markComplete(ecb, /* ide */ true);

            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            writeAuditEvent(auditDAO, currentUser, currentStudy, ss,
                    "event_crf_mark_complete", "event_crf", ecb.getId(),
                    /* columnName */ "date_completed", /* old */ "",
                    /* new */ Instant.now().toString());

            // Cascade the parent study_event status when every required
            // event_definition_crf for the SED has a complete event_crf.
            // The legacy DataEntryServlet skipped this step, leaving the
            // visit pinned at DATA_ENTRY_STARTED forever — the SPA's
            // event-detail and subject-detail views then contradicted
            // each other. Idempotent: if already COMPLETED / SIGNED /
            // LOCKED we leave it alone.
            cascadeEventStatusIfAllCrfsComplete(ecb.getStudyEventId(), currentUser);
        }

        EventCRFBean refreshed = eventCrfDAO.findByPK(ecb.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventCrfOid", String.valueOf(refreshed.getId()));
        out.put("status", computeStatus(refreshed, true));
        out.put("lastSavedAt", formatIsoInstant(latestUpdate(refreshed)));

        LOG.info("CRF markComplete: event_crf {} status now {}; user {} study {}",
                refreshed.getId(), out.get("status"), currentUser.getName(), currentStudy.getOid());

        return ResponseEntity.ok(out);
    }

    /**
     * Phase E A5 — reopen a completed CRF for editing. Inverse of
     * {@link #markComplete} — clears {@code date_completed} so the
     * legacy {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage}
     * transitions from {@code INITIAL_DATA_ENTRY_COMPLETE} (3) back
     * to {@code INITIAL_DATA_ENTRY} (2), re-enabling the SPA's CRF
     * entry form.
     *
     * <p>Guards (order matters — failing earlier guards return earlier
     * status codes):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 404} — no event_crf with that id.</li>
     *   <li>{@code 403} — event_crf belongs to a study the caller's
     *       site-visibility set excludes.</li>
     *   <li>{@code 409} — event_crf is {@code LOCKED} or {@code SIGNED}
     *       (terminal states; reopen requires un-sign first via legacy
     *       admin path).</li>
     *   <li>{@code 409} — event_crf is not currently complete
     *       ({@code date_completed} already null). Idempotent return
     *       would be wrong here — the caller likely sees stale UI
     *       state and should refetch.</li>
     *   <li>{@code 403} — caller's role does not permit reopen (per
     *       {@link CrfReopenAuthorization}).</li>
     * </ol>
     *
     * <p>Mirrors the legacy {@code ResetEventCrfServlet} but with one
     * REST endpoint instead of the multi-step JSP confirmation flow.
     * The SPA renders the "Confirm reopen" dialog client-side; this
     * endpoint trusts the click.
     *
     * <p>Writes one audit_event row with action_message
     * {@code event_crf_reopen} so the M10 Audit Log view surfaces it.
     */
    @PostMapping("/{id:[0-9]+}/markIncomplete")
    public ResponseEntity<?> markIncomplete(@PathVariable("id") int eventCrfId,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."
            ));
        }

        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }

        if (ecb.getStatus() == Status.LOCKED || ecb.getStatus() == Status.SIGNED) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " is locked or signed — "
                            + "un-sign / unlock via the legacy admin path before reopening"));
        }
        if (ecb.getDateCompleted() == null) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " is not currently complete"));
        }

        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!CrfReopenAuthorization.roleMayReopen(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit reopening completed CRFs"));
        }

        String previousCompletedAt = ecb.getDateCompleted().toInstant().toString();
        eventCrfDAO.markIncomplete(ecb);

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        writeAuditEvent(auditDAO, currentUser, currentStudy, ss,
                "event_crf_reopen", "event_crf", ecb.getId(),
                /* columnName */ "date_completed",
                /* old */ previousCompletedAt,
                /* new */ "");

        // Inverse of the markComplete cascade: if the parent
        // study_event was COMPLETED because this was the last
        // required CRF, rewind it to DATA_ENTRY_STARTED. SIGNED /
        // LOCKED states are terminal and left alone (an admin
        // un-sign / un-lock flow predates this point).
        rewindEventStatusOnReopen(ecb.getStudyEventId(), currentUser);

        EventCRFBean refreshed = eventCrfDAO.findByPK(ecb.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventCrfOid", String.valueOf(refreshed.getId()));
        out.put("status", computeStatus(refreshed, true));
        out.put("lastSavedAt", formatIsoInstant(latestUpdate(refreshed)));

        LOG.info("CRF markIncomplete: event_crf {} reopened by user {} (role {}); study {}",
                refreshed.getId(), currentUser.getName(), roleId, currentStudy.getOid());

        return ResponseEntity.ok(out);
    }

    /**
     * Single audit-event row capturing the change. The legacy
     * {@link AuditEventDAO#create} only persists the {@code audit_table /
     * user_id / entity_id / reason_for_change / action_message} columns
     * — the richer {@code old_value / new_value / column_name} fields
     * exist on {@link AuditEventBean} but the DAO never writes them
     * (per the comment block in AuditEventDAO line 200ff: "new query
     * needs to be …" — never landed). We pack the before/after pair
     * into {@code actionMessage} so the Audit Log view (M10) can still
     * surface the diff once the DAO is extended.
     *
     * <p>Wrapped in try/catch because audit-write failures must not
     * roll back the user-facing save — losing an audit row is annoying
     * but losing the data is worse.
     */
    // Phase E.6 dde — promoted from `private` to `public static` so
    // {@code controller.api.service.dde.DdeService} (sub-package) can
    // emit the same packed-actionMessage audit rows when committing
    // pass-2 and resolving conflicts. Java doesn't model package
    // hierarchy so package-private isn't visible across sub-packages;
    // public is the minimum that compiles. Stable API for sibling
    // services going forward.
    public static void writeAuditEvent(AuditEventDAO dao, UserAccountBean user,
                                        StudyBean study, StudySubjectBean ss,
                                        String actionMessage, String auditTable,
                                        int entityId, String columnName,
                                        String oldValue, String newValue) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(user.getId());
            ae.setStudyId(study.getId());
            ae.setSubjectId(ss == null ? 0 : ss.getId());
            ae.setStudyName(study.getName() != null ? study.getName() : "");
            ae.setSubjectName(ss != null && ss.getLabel() != null ? ss.getLabel() : "");
            ae.setAuditTable(auditTable);
            ae.setEntityId(entityId);
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            // Pack the before/after into actionMessage so the diff is
            // discoverable even though the legacy DAO drops the
            // old_value / new_value / column_name columns. Format:
            //   "<actionMessage>: <columnName> '<old>' → '<new>'"
            String packed = actionMessage + ": " + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "'";
            ae.setActionMessage(packed);
            ae.setAuditDate(new Date());
            dao.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit-write failed for {}.{} entity {} — continuing without audit row: {}",
                    auditTable, columnName, entityId, e.getMessage());
        }
    }

    /** Body of POST /pages/api/v1/eventCrfs/{id}/items. */
    public record SaveItemsRequest(Map<String, Object> values) {}

    /**
     * Build a single item DTO. The {@code dataType} mapping prefers the
     * response-type (select/checkbox/radio) over the item's storage
     * type, because the SPA picks the input widget from {@code dataType}.
     */
    private static CrfEntryDto.CrfItemDto buildItemDto(ItemBean item, ItemFormMetadataBean ifm) {
        String label;
        boolean required = false;
        String helper = null;
        List<CrfEntryDto.ResponseOptionDto> options = null;
        ResponseType responseType = null;

        if (ifm != null) {
            required = ifm.isRequired();
            label = firstNonBlank(ifm.getLeftItemText(), ifm.getRightItemText(), item.getName());
            helper = blankToNull(ifm.getRightItemText(), null);
            ResponseSetBean rsb = ifm.getResponseSet();
            if (rsb != null) {
                responseType = ResponseType.get(rsb.getResponseTypeId());
                options = mapOptions(rsb);
            }
        } else {
            label = item.getName();
        }
        String dataType = mapDataType(responseType, item.getItemDataTypeId());
        // Only attach options for select-style widgets; null for the rest
        // keeps the JSON tidy.
        if (options != null && !"select-one".equals(dataType) && !"select-multi".equals(dataType)) {
            options = null;
        }

        // Units helper-suffix for numeric items where ifm didn't already
        // populate a right-side hint. E.g. seeded "cm", "kg", "mmHg".
        if (helper == null && item.getUnits() != null && !item.getUnits().isBlank()) {
            helper = item.getUnits();
        }

        return new CrfEntryDto.CrfItemDto(
                item.getOid(),
                label,
                dataType,
                required,
                options,
                helper,
                /* min */ null,
                /* max */ null
        );
    }

    /**
     * Translate (response_type, item_data_type) to the SPA's {@code dataType}
     * union. Select-style widgets win regardless of the underlying storage
     * type because that's what dictates the rendered input.
     */
    private static String mapDataType(ResponseType rt, int itemDataTypeId) {
        if (rt != null) {
            if (rt.equals(ResponseType.SELECT) || rt.equals(ResponseType.RADIO)) return "select-one";
            if (rt.equals(ResponseType.SELECTMULTI) || rt.equals(ResponseType.CHECKBOX)) return "select-multi";
        }
        // Item data type ids — see bean/core/ItemDataType.java.
        return switch (itemDataTypeId) {
            case 1, 2 -> "boolean";          // BL / BN
            case 6 -> "integer";             // INT
            case 7 -> "real";                // REAL
            case 9 -> "date";                // DATE
            case 10 -> "partial-date";       // PDATE
            default -> "string";             // ST / TEL / ED / SET / FILE → free text in the SPA
        };
    }

    private static List<CrfEntryDto.ResponseOptionDto> mapOptions(ResponseSetBean rsb) {
        if (rsb == null || rsb.getOptions() == null || rsb.getOptions().isEmpty()) return null;
        List<CrfEntryDto.ResponseOptionDto> out = new ArrayList<>(rsb.getOptions().size());
        for (ResponseOptionBean opt : rsb.getOptions()) {
            String code = opt.getValue() != null ? opt.getValue() : "";
            String label = opt.getText() != null ? opt.getText() : code;
            out.add(new CrfEntryDto.ResponseOptionDto(code, label));
        }
        return out;
    }

    /**
     * Format the event label: prefer the SED name; suffix with the
     * sample ordinal when the SED is repeating (matches the SPA's mock
     * convention of "V1 Inclusion", "V2 Day 30", etc.).
     */
    private static String formatEventLabel(StudyEventDefinitionBean sed, StudyEventBean se) {
        if (sed == null || sed.getId() == 0) return "Event";
        String name = sed.getName() != null ? sed.getName() : "Event";
        if (sed.isRepeating() && se != null && se.getSampleOrdinal() > 0) {
            return name + " #" + se.getSampleOrdinal();
        }
        return name;
    }

    private static String computeStatus(EventCRFBean ecb, boolean hasAnyValues) {
        // Locked-form indicator: EventCRFDAO.getEntityFromHashMap
        // doesn't read electronic_signature_status from the DB
        // (legacy gap), so we infer "locked" from the audit-hook-
        // populated status. Status.SIGNED (id=8) is the canonical
        // "subject signed" marker; Status.LOCKED (id=6) is the
        // explicit lock state. Both map to the SPA's "locked".
        Status s = ecb.getStatus();
        if (s == Status.SIGNED || s == Status.LOCKED) return "locked";
        if (ecb.getDateCompleted() != null) return "complete";
        if (hasAnyValues || ecb.getDateInterviewed() != null) return "in-progress";
        return "not-started";
    }

    private static Date latestUpdate(EventCRFBean ecb) {
        Date updated = ecb.getUpdatedDate();
        Date completed = ecb.getDateCompleted();
        if (updated == null) return completed;
        if (completed == null) return updated;
        return updated.after(completed) ? updated : completed;
    }

    private static String formatIsoInstant(Date d) {
        if (d == null) return null;
        return Instant.ofEpochMilli(d.getTime()).toString();
    }

    /** A YYYY-MM-DD helper kept for symmetry with the Subjects adapter
     * (and future date-of-birth fields the SPA may want to render). */
    @SuppressWarnings("unused")
    private static String formatIsoDate(Date d) {
        if (d == null) return null;
        return LocalDate.ofInstant(Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault()).toString();
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    private static String blankToNull(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        return s;
    }

    /* ------------------------------------------------------------------ */
    /* Phase E.6 — study_event status cascade after CRF mark/reopen.       */
    /*                                                                    */
    /* Operators saw "Abgeschlossen" on every CRF row and a parent visit  */
    /* still pinned at "In Bearbeitung" because the legacy markComplete   */
    /* never bumped the visit. Cascade now: when every required EDC has  */
    /* a complete event_crf, flip study_event → COMPLETED. The reopen    */
    /* path rewinds COMPLETED back to DATA_ENTRY_STARTED for symmetry.   */
    /*                                                                    */
    /* "Required" matches the legacy edc.required_crf flag. Optional     */
    /* CRFs don't gate the cascade; their state is operator discretion.  */
    /* SIGNED / LOCKED visits are terminal — left alone in both helpers. */
    /* ------------------------------------------------------------------ */

    private void cascadeEventStatusIfAllCrfsComplete(int studyEventId, UserAccountBean actor) {
        try {
            StudyEventDAO seDAO = new StudyEventDAO(dataSource);
            StudyEventBean ev = (StudyEventBean) seDAO.findByPK(studyEventId);
            if (ev == null || ev.getId() == 0) return;
            SubjectEventStatus current = ev.getSubjectEventStatus();
            if (current == null
                    || current.equals(SubjectEventStatus.COMPLETED)
                    || current.equals(SubjectEventStatus.SIGNED)
                    || current.equals(SubjectEventStatus.LOCKED)) {
                return;
            }
            if (!allRequiredEventCrfsComplete(ev)) return;
            ev.setSubjectEventStatus(SubjectEventStatus.COMPLETED);
            ev.setUpdater(actor);
            ev.setUpdatedDate(new Date());
            seDAO.update(ev);
        } catch (Exception cascadeEx) {
            // Best-effort — the CRF mark already committed. Don't let
            // the cascade failure surface as a 500 to the SPA.
            LOG.warn("study_event {} cascade-to-COMPLETED failed (continuing): {}",
                    studyEventId, cascadeEx.getMessage());
        }
    }

    private void rewindEventStatusOnReopen(int studyEventId, UserAccountBean actor) {
        try {
            StudyEventDAO seDAO = new StudyEventDAO(dataSource);
            StudyEventBean ev = (StudyEventBean) seDAO.findByPK(studyEventId);
            if (ev == null || ev.getId() == 0) return;
            SubjectEventStatus current = ev.getSubjectEventStatus();
            if (current == null || !current.equals(SubjectEventStatus.COMPLETED)) return;
            ev.setSubjectEventStatus(SubjectEventStatus.DATA_ENTRY_STARTED);
            ev.setUpdater(actor);
            ev.setUpdatedDate(new Date());
            seDAO.update(ev);
        } catch (Exception rewindEx) {
            LOG.warn("study_event {} rewind-to-DATA_ENTRY_STARTED failed (continuing): {}",
                    studyEventId, rewindEx.getMessage());
        }
    }

    private boolean allRequiredEventCrfsComplete(StudyEventBean ev) {
        EventDefinitionCRFDAO edcDAO = new EventDefinitionCRFDAO(dataSource);
        @SuppressWarnings("unchecked")
        List<EventDefinitionCRFBean> edcs =
                edcDAO.findAllParentsByEventDefinitionId(ev.getStudyEventDefinitionId());
        if (edcs == null || edcs.isEmpty()) return false;
        EventCRFDAO ecDAO = new EventCRFDAO(dataSource);
        @SuppressWarnings("unchecked")
        List<EventCRFBean> ecs = ecDAO.findAllByStudyEvent(ev);
        for (EventDefinitionCRFBean edc : edcs) {
            if (edc == null || !edc.isRequiredCRF()) continue;
            boolean found = false;
            if (ecs != null) {
                for (EventCRFBean ec : ecs) {
                    if (ec == null || ec.getId() == 0) continue;
                    if (ec.getStatus() != null
                            && (ec.getStatus().equals(Status.DELETED)
                                    || ec.getStatus().equals(Status.AUTO_DELETED))) continue;
                    // Match the row to the slot via its CRF id. We
                    // accept any non-deleted row for the slot's CRF —
                    // operators can swap versions via the legacy flow.
                    CRFVersionDAO cvDao = new CRFVersionDAO(dataSource);
                    CRFVersionBean cv = (CRFVersionBean) cvDao.findByPK(ec.getCRFVersionId());
                    if (cv == null || cv.getCrfId() != edc.getCrfId()) continue;
                    boolean done = ec.getDateCompleted() != null
                            || (ec.getStatus() != null
                                    && (ec.getStatus().equals(Status.SIGNED)
                                            || ec.getStatus().equals(Status.LOCKED)));
                    if (done) { found = true; break; }
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Phase E.6 dde — blind double-data-entry endpoints + helpers.        */
    /*                                                                    */
    /* Four endpoints:                                                    */
    /*   GET  /api/v1/eventCrfs/{id}/dde-pass                             */
    /*   POST /api/v1/eventCrfs/{id}/dde-commit                           */
    /*   GET  /api/v1/eventCrfs/{id}/dde-conflicts                        */
    /*   POST /api/v1/eventCrfs/{id}/dde-conflicts/{itemOid}/resolve      */
    /*                                                                    */
    /* The heavy lifting (diff, FAILEDVAL spawn, markCompleteDDE wire)    */
    /* lives in {@link service.dde.DdeService} so the controller stays    */
    /* a thin session-guard + parameter-binding layer.                    */
    /*                                                                    */
    /* Pass derivation (resolveDdeBlock):                                 */
    /*   double_entry=false on the EDC          → null block              */
    /*   IDE not yet complete (date_completed)  → pass=1                  */
    /*   IDE complete, DDE not yet complete     → pass=2 (blind)          */
    /*   IDE+DDE complete, mismatches open      → pass=reconcile          */
    /*   IDE+DDE complete, no mismatches        → null block (done)       */
    /* ------------------------------------------------------------------ */

    @GetMapping("/{id:[0-9]+}/dde-pass")
    public ResponseEntity<?> getDdePass(@PathVariable("id") int eventCrfId,
                                        HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(currentUser, currentStudy, role);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }

        if (!isDoubleEntryEnabled(ecb)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "event_crf " + eventCrfId + " is not DDE-enabled"));
        }

        CrfEntryDto.DdeBlockDto block = resolveDdeBlock(ecb, currentUser);
        if (block == null) {
            // double_entry=true on the EDC but the workflow is already
            // settled (clean DDE complete, no mismatches). Tell the SPA.
            return ResponseEntity.ok(new DdePassDto(
                    String.valueOf(ecb.getId()), "done",
                    ecb.getOwnerId(), 0));
        }
        // Same-clerk gate: pass-2 requires a different clerk than the
        // pass-1 owner. We compare against the EventCRF.owner_id.
        if ("2".equals(block.pass())
                && block.idePass1ClerkId() > 0
                && block.idePass1ClerkId() == currentUser.getId()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "You entered Pass 1 — a different clerk must perform Pass 2"));
        }
        int mismatchCount = "reconcile".equals(block.pass())
                ? countOpenFailedValForEventCrf(ecb.getId())
                : 0;
        return ResponseEntity.ok(new DdePassDto(
                String.valueOf(ecb.getId()),
                block.pass(),
                block.idePass1ClerkId(),
                mismatchCount));
    }

    @PostMapping("/{id:[0-9]+}/dde-commit")
    public ResponseEntity<?> commitDdePass2(@PathVariable("id") int eventCrfId,
                                            @RequestBody DdeCommitRequest body,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }
        if (body == null || body.values() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Missing 'values' in request body"));
        }
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(currentUser, currentStudy, role);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }
        if (!isDoubleEntryEnabled(ecb)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "event_crf " + eventCrfId + " is not DDE-enabled"));
        }
        if (ecb.getDateCompleted() == null) {
            // Pass 1 not yet complete — commit makes no sense.
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Pass 1 (IDE) is not yet complete — cannot commit Pass 2"));
        }
        if (ecb.getOwnerId() > 0 && ecb.getOwnerId() == currentUser.getId()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "You entered Pass 1 — a different clerk must perform Pass 2"));
        }
        // Delegate to DdeService for the diff + spawn + commit work.
        try {
            DdeCommitResponse resp = ddeService().commitPass2(
                    ecb, ss, currentStudy, currentUser, body.values());
            return ResponseEntity.ok(resp);
        } catch (Exception svcEx) {
            LOG.error("DDE commit failed for event_crf {} (user {})",
                    eventCrfId, currentUser.getName(), svcEx);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "DDE commit failed: " + svcEx.getMessage()));
        }
    }

    @GetMapping("/{id:[0-9]+}/dde-conflicts")
    public ResponseEntity<?> getDdeConflicts(@PathVariable("id") int eventCrfId,
                                             HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!roleMayReconcile(role)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Your role does not permit DDE reconciliation"));
        }
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(currentUser, currentStudy, role);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }
        try {
            DdeConflictsDto dto = ddeService().listConflicts(ecb, ss);
            return ResponseEntity.ok(dto);
        } catch (Exception svcEx) {
            LOG.error("DDE conflict listing failed for event_crf {}", eventCrfId, svcEx);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "DDE conflicts listing failed: " + svcEx.getMessage()));
        }
    }

    @PostMapping("/{id:[0-9]+}/dde-conflicts/{itemOid}/resolve")
    public ResponseEntity<?> resolveDdeConflict(@PathVariable("id") int eventCrfId,
                                                @PathVariable("itemOid") String itemOid,
                                                @RequestBody DdeReconcileRequest body,
                                                HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        if (!roleMayReconcile(role)) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Your role does not permit DDE reconciliation"));
        }
        if (body == null
                || body.winner() == null
                || body.reasonForChange() == null
                || body.reasonForChange().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Missing 'winner' or 'reasonForChange'"));
        }
        String w = body.winner();
        if (!"ide".equals(w) && !"dde".equals(w) && !"manual".equals(w)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "winner must be one of: ide, dde, manual"));
        }
        if ("manual".equals(w) && (body.value() == null)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "winner=manual requires a 'value'"));
        }
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(currentUser, currentStudy, role);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }
        try {
            String uri = ddeService().resolveConflict(
                    ecb, ss, currentStudy, currentUser, itemOid, body);
            // Reviewer flag: 204 + Location is unusual REST. Use
            // 303 See Other so the SPA can follow naturally OR
            // 200 + body. We pick 200 + URI body — easier for the
            // SPA fetch lifecycle than handling a redirect.
            return ResponseEntity.ok(Map.of("nextItem", uri == null ? "" : uri));
        } catch (IllegalArgumentException badArg) {
            return ResponseEntity.badRequest().body(Map.of("message", badArg.getMessage()));
        } catch (IllegalStateException badState) {
            return ResponseEntity.status(409).body(Map.of("message", badState.getMessage()));
        } catch (Exception svcEx) {
            LOG.error("DDE resolve failed for event_crf {} item {}",
                    eventCrfId, itemOid, svcEx);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "DDE resolve failed: " + svcEx.getMessage()));
        }
    }

    /* ------------------- DDE shared helpers ------------------- */

    /**
     * Resolve the DDE block for an EventCRF or {@code null} when
     * the parent EDC has {@code double_entry=false}. Heuristics:
     * <ul>
     *   <li>{@code date_completed=null} ⇒ pass=1</li>
     *   <li>{@code date_completed!=null, date_validate_completed=null}
     *       ⇒ pass=2 (blind)</li>
     *   <li>{@code both set, FAILEDVAL count > 0} ⇒ pass=reconcile</li>
     *   <li>{@code both set, no open FAILEDVAL} ⇒ null (DDE done)</li>
     * </ul>
     */
    CrfEntryDto.DdeBlockDto resolveDdeBlock(EventCRFBean ecb, UserAccountBean caller) {
        try {
            if (!isDoubleEntryEnabled(ecb)) return null;
            int idePass1ClerkId = ecb.getOwnerId();
            if (ecb.getDateCompleted() == null) {
                return new CrfEntryDto.DdeBlockDto("1", idePass1ClerkId);
            }
            if (ecb.getDateValidateCompleted() == null) {
                return new CrfEntryDto.DdeBlockDto("2", idePass1ClerkId);
            }
            int mismatches = countOpenFailedValForEventCrf(ecb.getId());
            if (mismatches > 0) {
                return new CrfEntryDto.DdeBlockDto("reconcile", idePass1ClerkId);
            }
            return null;
        } catch (Exception e) {
            LOG.warn("DDE block resolution failed for event_crf {} ({}); returning null",
                    ecb.getId(), e.getMessage());
            return null;
        }
    }

    /** True when the parent event_definition_crf has double_entry=true. */
    private boolean isDoubleEntryEnabled(EventCRFBean ecb) {
        try {
            StudyEventDAO seDAO = new StudyEventDAO(dataSource);
            StudyEventBean ev = (StudyEventBean) seDAO.findByPK(ecb.getStudyEventId());
            if (ev == null || ev.getId() == 0) return false;
            CRFVersionDAO cvDAO = new CRFVersionDAO(dataSource);
            CRFVersionBean cv = (CRFVersionBean) cvDAO.findByPK(ecb.getCRFVersionId());
            if (cv == null || cv.getId() == 0) return false;
            EventDefinitionCRFDAO edcDAO = new EventDefinitionCRFDAO(dataSource);
            @SuppressWarnings("unchecked")
            List<EventDefinitionCRFBean> edcs =
                    edcDAO.findAllParentsByEventDefinitionId(ev.getStudyEventDefinitionId());
            if (edcs == null) return false;
            for (EventDefinitionCRFBean edc : edcs) {
                if (edc != null && edc.getCrfId() == cv.getCrfId()) {
                    return edc.isDoubleEntry();
                }
            }
            return false;
        } catch (Exception e) {
            LOG.warn("isDoubleEntryEnabled lookup failed for event_crf {} ({})",
                    ecb.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Count the open FAILEDVAL discrepancy_note rows for any
     * item_data row belonging to this EventCRF. Delegates the actual
     * row hunt to {@link at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde.DdeService}
     * so the SQL stays in one place.
     */
    private int countOpenFailedValForEventCrf(int eventCrfId) {
        try {
            return ddeService().countOpenFailedVal(eventCrfId);
        } catch (Exception e) {
            LOG.warn("FAILEDVAL count failed for event_crf {} ({}); returning 0",
                    eventCrfId, e.getMessage());
            return 0;
        }
    }

    /** Lazy DdeService — avoids a constructor-injection refactor for now. */
    private at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde.DdeService ddeService() {
        return new at.ac.meduniwien.ophthalmology.libreclinica.controller.api.service.dde.DdeService(dataSource);
    }

    /**
     * DM (3), Admin (1), Investigator (4) — the GCP-recognised
     * adjudicators. CRC (2) can data-enter but the institutional
     * convention is that the data manager owns reconciliation.
     */
    private static boolean roleMayReconcile(StudyUserRoleBean role) {
        if (role == null || role.getRole() == null) return false;
        int id = role.getRole().getId();
        return id == 1 || id == 3 || id == 4;
    }
}
