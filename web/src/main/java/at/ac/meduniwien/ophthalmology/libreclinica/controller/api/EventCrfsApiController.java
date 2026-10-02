/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseOptionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SectionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.ReasonForChangeWriter;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemGroupDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemGroupMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SectionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
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
// 2026-06-28 — heritage null-analysis suppress; per-site
// null-safety review is the deferred follow-up.
@SuppressWarnings({"null"})
public class EventCrfsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventCrfsApiController.class);

    /** #26 binding store — parses the terminology fill map (jsonb) at entry. */
    private static final ObjectMapper TERMINOLOGY_JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final CrfFileStorageService fileStorageService;
    private final EventCrfPresenceRegistry presenceRegistry;
    private final at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator
            retinalAutoPopulator;

    @Autowired
    public EventCrfsApiController(@Qualifier("dataSource") DataSource dataSource,
                                  SiteVisibilityFilter siteVisibilityFilter,
                                  CrfFileStorageService fileStorageService,
                                  EventCrfPresenceRegistry presenceRegistry,
                                  at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator
                                          retinalAutoPopulator) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.fileStorageService = fileStorageService;
        this.presenceRegistry = presenceRegistry;
        this.retinalAutoPopulator = retinalAutoPopulator;
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

        Map<Integer, ItemFormMetadataBean> ifmByItemId = new HashMap<>();
        for (ItemFormMetadataBean ifm : allIfms) {
            ifmByItemId.put(ifm.getItemId(), ifm);
        }

        // Phase E.6: resolve item → group + group metadata so we can
        // mark items in repeating groups and emit the groups[] payload.
        // Non-repeating groups (the "ungrouped" buckets) intentionally
        // leave their items' groupOid null — the SPA keeps rendering
        // them in the top-level values map.
        ItemGroupDAO igDAO = new ItemGroupDAO(dataSource);
        ItemGroupMetadataDAO igmDAO = new ItemGroupMetadataDAO(dataSource);
        Map<Integer, ItemGroupBean> groupByItemId = new HashMap<>();
        Map<Integer, ItemGroupBean> repeatingGroupsByItemGroupId = new LinkedHashMap<>();
        Map<Integer, ItemGroupMetadataBean> metaByItemGroupId = new HashMap<>();
        Map<Integer, List<String>> itemOidsByItemGroupId = new LinkedHashMap<>();
        for (ItemFormMetadataBean ifm : allIfms) {
            ItemGroupBean grp;
            try {
                grp = igDAO.findGroupByItemIdCrfVersionId(ifm.getItemId(), crfv.getId());
            } catch (Exception e) {
                continue;
            }
            if (grp == null || grp.getId() == 0) continue;
            groupByItemId.put(ifm.getItemId(), grp);
            ItemGroupMetadataBean grpMeta = metaByItemGroupId.get(grp.getId());
            if (grpMeta == null) {
                try {
                    ArrayList<ItemGroupMetadataBean> metas =
                            igmDAO.findMetaByGroupAndCrfVersion(grp.getId(), crfv.getId());
                    if (metas != null && !metas.isEmpty()) {
                        grpMeta = metas.get(0);
                        metaByItemGroupId.put(grp.getId(), grpMeta);
                    }
                } catch (Exception e) {
                    // Best-effort — fall through; item stays ungrouped.
                }
            }
            if (grpMeta != null && grpMeta.isRepeatingGroup()) {
                repeatingGroupsByItemGroupId.put(grp.getId(), grp);
            }
        }

        // Phase E.6 polish-runtime — load conditional-display rules for
        // every item on this CRF version up-front. The map is keyed by
        // item_id (the entity the SCD table targets via item_form_metadata)
        // and carries the stringified-JSON `showWhen` the SPA's runtime
        // parses to drive hide/show. See {@link #loadShowWhenByItemId}.
        Map<Integer, String> showWhenByItemId = loadShowWhenByItemId(crfv.getId());

        // #26 binding store — terminology bindings (system + fill map) keyed by
        // item name, so a picked concept auto-fills sibling cells at entry.
        Map<String, CrfEntryDto.AutocompleteDto> termByName = loadTerminologyByItemName(crfv.getId());

        List<CrfEntryDto.CrfSectionDto> sectionDtos = new ArrayList<>(sections.size());
        for (SectionBean sb : sections) {
            List<ItemBean> items = itemDAO.findAllBySectionIdOrderedByItemFormMetadataOrdinal(sb.getId());
            List<CrfEntryDto.CrfItemDto> itemDtos = new ArrayList<>(items.size());
            for (ItemBean ib : items) {
                ItemFormMetadataBean ifm = ifmByItemId.get(ib.getId());
                ItemGroupBean grp = groupByItemId.get(ib.getId());
                String groupOid = null;
                if (grp != null && repeatingGroupsByItemGroupId.containsKey(grp.getId())
                        && grp.getOid() != null) {
                    groupOid = grp.getOid();
                    itemOidsByItemGroupId
                            .computeIfAbsent(grp.getId(), _ -> new ArrayList<>())
                            .add(ib.getOid());
                }
                itemDtos.add(buildItemDto(ib, ifm, groupOid, showWhenByItemId.get(ib.getId()),
                        termByName.get(ib.getName())));
            }
            sectionDtos.add(new CrfEntryDto.CrfSectionDto(
                    sb.getLabel(),
                    blankToNull(sb.getTitle(), sb.getLabel()),
                    blankToNull(sb.getInstructions(), null),
                    itemDtos
            ));
        }

        // Saved values keyed by item OID. Repeating-group rows are
        // routed into the groups[] payload below; here we only keep
        // single-row items so the SPA's top-level values map stays
        // legible.
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        List<ItemDataBean> dataRows = new ArrayList<>();
        for (SectionBean sb : sections) {
            dataRows.addAll(idDAO.findAllBySectionIdAndEventCRFId(sb.getId(), ecb.getId()));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<Integer, Map<Integer, Map<String, Object>>> groupRowValues = new LinkedHashMap<>();
        for (ItemDataBean idb : dataRows) {
            ItemBean ib = (ItemBean) itemDAO.findByPK(idb.getItemId());
            if (ib == null || ib.getOid() == null) continue;
            ItemGroupBean grp = groupByItemId.get(ib.getId());
            boolean repeating = grp != null && repeatingGroupsByItemGroupId.containsKey(grp.getId());
            if (repeating) {
                int ordinal = Math.max(1, idb.getOrdinal());
                groupRowValues
                        .computeIfAbsent(grp.getId(), _ -> new LinkedHashMap<>())
                        .computeIfAbsent(ordinal, _ -> new LinkedHashMap<>())
                        .put(ib.getOid(), parseStoredValue(idb.getValue(), findDataType(ib, ifmByItemId)));
            } else {
                values.put(ib.getOid(), parseStoredValue(idb.getValue(), findDataType(ib, ifmByItemId)));
            }
        }

        List<CrfEntryDto.CrfItemGroupDto> groupDtos = new ArrayList<>();
        for (Map.Entry<Integer, ItemGroupBean> grpEntry : repeatingGroupsByItemGroupId.entrySet()) {
            ItemGroupBean grp = grpEntry.getValue();
            ItemGroupMetadataBean grpMeta = metaByItemGroupId.get(grp.getId());
            int repeatMax = (grpMeta != null && grpMeta.getRepeatMax() != null && grpMeta.getRepeatMax() > 0)
                    ? grpMeta.getRepeatMax()
                    : 40; // upstream default per item_group_metadata seeds
            String label = (grpMeta != null && grpMeta.getHeader() != null && !grpMeta.getHeader().isBlank())
                    ? grpMeta.getHeader()
                    : (grp.getName() != null ? grp.getName() : grp.getOid());
            List<String> itemOids = itemOidsByItemGroupId.getOrDefault(
                    grp.getId(), new ArrayList<>());
            Map<Integer, Map<String, Object>> rowsByOrd = groupRowValues.getOrDefault(
                    grp.getId(), new LinkedHashMap<>());
            List<CrfEntryDto.CrfGroupRowDto> rows = new ArrayList<>(rowsByOrd.size());
            rowsByOrd.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> rows.add(new CrfEntryDto.CrfGroupRowDto(e.getKey(), e.getValue())));
            groupDtos.add(new CrfEntryDto.CrfItemGroupDto(
                    grp.getOid() != null ? grp.getOid() : ("GRP_" + grp.getId()),
                    label,
                    repeatMax,
                    itemOids,
                    rows
            ));
        }

        String status = computeStatus(ecb, !dataRows.isEmpty());
        String lastSavedAt = formatIsoInstant(latestUpdate(ecb));

        CrfEntryDto.CrfSchemaDto schema = new CrfEntryDto.CrfSchemaDto(
                crfv.getOid() != null ? crfv.getOid() : "",
                crfDisplayName,
                crfv.getName() != null ? crfv.getName() : "v1",
                sectionDtos
        );

        // Phase E.6 admin-rfc — `requiresReasonForChange` true once the
        // CRF has ever been completed, reopened or not, and the study
        // forces a reason (AdministrativeEditing). The SPA reads this flag
        // to mount the ReasonForChangeModal before Save fires. SIGNED /
        // LOCKED rows still 409 on save (legacy unlock path), so we don't
        // need a third state here.
        boolean requiresRfc = AdministrativeEditing.reasonRequired(dataSource, currentStudy, ecb);
        // Phase E.6 dde — embed the DDE marker block when the parent
        // event_definition_crf has double_entry=true. {@code dde} stays
        // null for single-pass studies so legacy single-entry flows
        // pay zero cost.
        CrfEntryDto.DdeBlockDto ddeBlock = resolveDdeBlock(ecb, currentUser);

        CrfEntryDto dto = new CrfEntryDto(
                String.valueOf(ecb.getId()),
                ss.getLabel(),
                eventLabel,
                // 2026-06-21 user-feedback round 5 — surfaced so the SPA
                // can deep-link back to the parent visit on completion.
                se != null && se.getId() != 0 ? se.getId() : null,
                schema,
                // Server-side blinding: pass-2 callers see an empty
                // values map so they cannot copy the IDE entry.
                ddeBlock != null && "2".equals(ddeBlock.pass())
                        ? new LinkedHashMap<>() : values,
                groupDtos,
                fileMaxBytes(),
                fileExtensionsAllowlist(),
                status,
                lastSavedAt,
                requiresRfc,
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
     * signed, edits go through a different unlock flow. Reject a role
     * that may not enter data ({@link ClinicalWriteAuthorization}).
     *
     * <p>A save that changes a value of a source-data-verified CRF
     * withdraws the verification ({@link SdvRevocation}).
     *
     * <p>A CRF that has ever been completed is under administrative
     * editing ({@link AdministrativeEditing}): when the study forces a
     * reason for change, every changed value, top-level or in a row of a
     * repeating group, needs one, or nothing is saved (400,
     * {@code missingReasonItemOids}). A reason given is recorded as a
     * Reason for Change note whether forced or not.
     *
     * <p>Audit-log: one {@link AuditEventBean} row per changed item,
     * recording (auditTable="item_data", entityId, columnName="value",
     * oldValue, newValue). Creation-from-empty also writes one row with
     * an empty oldValue so the audit trail shows the initial entry.
     */
    @PostMapping("/{id:[0-9]+}/items")
    public ResponseEntity<?> saveItems(@PathVariable("id") int eventCrfId,
                                       @RequestBody SaveItemsRequest body,
                                       HttpSession session,
                                       HttpServletRequest request) {
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
        ResponseEntity<?> noStudyRole = DataEntryRoleGuard.refuseWithoutStudyRole(session);
        if (noStudyRole != null) {
            return noStudyRole;
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "entering CRF data");
        if (roleRefusal != null) {
            return roleRefusal;
        }
        if (body == null
                || ((body.values() == null || body.values().isEmpty())
                        && (body.groups() == null || body.groups().isEmpty()))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Empty request body — supply 'values' and/or 'groups'."));
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "saving CRF data");
        if (closed != null) {
            return closed;
        }

        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        // Phase E.6 polish-runtime — server-side ghost-data guard. Filter
        // out values for items whose show-when condition evaluates to
        // false against the payload (the SPA already filters; this is the
        // defense-in-depth for direct API callers). Hidden items'
        // values are NOT written to item_data and NO audit entries are
        // emitted, matching the "ghost data" prevention rationale.
        //
        // We filter FIRST so the post-complete RFC pre-check below
        // doesn't require an RFC for a hidden item — required-when-shown
        // is the runtime semantics the SPA exposes, and the backend
        // needs to agree.
        Map<String, String> showWhenByItemOid = loadShowWhenByItemOid(ecb.getCRFVersionId());
        Map<String, Object> visibleTopLevelValues = filterByVisibility(
                body.values(), showWhenByItemOid);
        int hiddenItemCount = (body.values() == null ? 0 : body.values().size())
                - visibleTopLevelValues.size();
        if (hiddenItemCount > 0) {
            LOG.info("saveItems: dropped {} hidden item value(s) on event_crf {} (show-when=false)",
                    hiddenItemCount, ecb.getId());
        }

        // A value that fails its item's CRF validation (func: / regexp:) is
        // refused, with the CRF author's message, as the legacy form refuses
        // it; nothing is written. A value already carrying a discrepancy
        // note stands (CrfItemValidation).
        List<ValidationErrorBody.FieldError> invalid = crfValidationFailures(
                request, visibleTopLevelValues, body.groups(), ecb, itemDAO, idDAO);
        if (!invalid.isEmpty()) {
            List<String> messages = new ArrayList<>();
            for (ValidationErrorBody.FieldError e : invalid) messages.add(e.message());
            LOG.info("saveItems: refused {} value(s) failing their CRF validation on event_crf {}",
                    invalid.size(), ecb.getId());
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    String.join(" ", messages), invalid));
        }

        // Phase E.6 admin-rfc — a CRF that has ever been completed is under
        // administrative editing for good; reopening it does not end that
        // (AdministrativeEditing). When the study forces a reason, every
        // changed value MUST carry a non-blank one: keyed by item OID, or by
        // OID[row] for a row of a repeating group. We do a pre-pass against
        // the existing values so we can fail-fast with
        // `missingReasonItemOids` before we touch any rows.
        boolean administrativeEditing = AdministrativeEditing.everCompleted(dataSource, ecb);
        boolean reasonRequired = administrativeEditing
                && StudyParameters.adminForcedReasonForChange(dataSource, currentStudy);
        Map<String, String> reasons = body.reasons() == null ? Map.of() : body.reasons();
        List<String> missingReasonItemOids = new ArrayList<>();
        Map<String, ItemBean> resolvedItems = new HashMap<>();
        Map<String, ItemDataBean> existingByOid = new HashMap<>();
        if (reasonRequired) {
            for (Map.Entry<String, Object> entry : visibleTopLevelValues.entrySet()) {
                String itemOid = entry.getKey();
                String newValue = serialiseValueForStorage(entry.getValue());
                ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
                if (candidates == null || candidates.isEmpty()) continue;
                ItemBean item = candidates.get(0);
                resolvedItems.put(itemOid, item);
                ItemDataBean existing = idDAO.findByItemIdAndEventCRFId(item.getId(), ecb.getId());
                existingByOid.put(itemOid, existing);
                String oldValue = (existing != null && existing.getId() > 0 && existing.getValue() != null)
                        ? existing.getValue() : "";
                if (!oldValue.equals(newValue) && !hasReason(reasons, itemOid)) {
                    missingReasonItemOids.add(itemOid);
                }
            }
            if (body.groups() != null) {
                for (SaveItemsRequest.GroupRowSavePayload row : body.groups()) {
                    if (row == null || row.values() == null) continue;
                    int ordinal = Math.max(1, row.rowOrdinal());
                    for (Map.Entry<String, Object> rowVal : row.values().entrySet()) {
                        String itemOid = rowVal.getKey();
                        ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
                        if (candidates == null || candidates.isEmpty()) continue;
                        ItemDataBean existing = idDAO.findByItemIdAndEventCRFIdAndOrdinal(
                                candidates.get(0).getId(), ecb.getId(), ordinal);
                        String oldValue = (existing != null && existing.getId() > 0 && existing.getValue() != null)
                                ? existing.getValue() : "";
                        String rowKey = groupRowReasonKey(itemOid, ordinal);
                        if (!oldValue.equals(serialiseValueForStorage(rowVal.getValue()))
                                && !hasReason(reasons, rowKey)) {
                            missingReasonItemOids.add(rowKey);
                        }
                    }
                }
            }
            if (!missingReasonItemOids.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("message", "Reason-for-change required for post-complete edits");
                err.put("missingReasonItemOids", missingReasonItemOids);
                LOG.info("saveItems: blocked post-complete edit on event_crf {} — missing reasons for {} items",
                        ecb.getId(), missingReasonItemOids.size());
                return ResponseEntity.status(400).body(err);
            }
        }

        ReasonForChangeWriter rfcWriter = administrativeEditing
                ? new ReasonForChangeWriter(new DiscrepancyNoteDAO(dataSource))
                : null;

        int saved = 0;
        int rejected = 0;
        int rfcCreatedCount = 0;
        int groupRowsSaved = 0;
        // Whether any stored value actually changed; a changed value ends
        // an SDV attestation (SdvRevocation).
        boolean dataChanged = false;
        // 2026-06-30 — nAMD treat-and-extend decision summary capture.
        // The save loop emits one type-1 (item value updated) audit row
        // per changed item, which is fine for the audit-log table but
        // produces N rows for one logical "decision saved" event. We
        // collect the salient values inside the loop and emit a single
        // TREATMENT_DECISION_RECORDED summary row after — keyed on
        // event_crf, packed with action/drug/interval/agreed so the
        // timeline UI doesn't need to join back to item_data.
        Map<String, String> namdDecisionSummary = new LinkedHashMap<>();
        Map<String, Object> topLevelValues = visibleTopLevelValues;
        for (Map.Entry<String, Object> entry : topLevelValues.entrySet()) {
            String itemOid = entry.getKey();
            // Phase E.6: route arrays through the select-multi serializer
            // so the persisted column stays as a comma-joined string.
            String newValue = serialiseValueForStorage(entry.getValue());

            ItemBean item = resolvedItems.get(itemOid);
            if (item == null) {
                ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
                if (candidates == null || candidates.isEmpty()) {
                    LOG.warn("saveItems: unknown item OID '{}' on event_crf {} (skipped)", itemOid, eventCrfId);
                    rejected++;
                    continue;
                }
                item = candidates.get(0);
            }

            ItemDataBean existing = existingByOid.containsKey(itemOid)
                    ? existingByOid.get(itemOid)
                    : idDAO.findByItemIdAndEventCRFId(item.getId(), ecb.getId());
            String oldValue = "";
            boolean isCreate;
            int itemDataIdAfter;
            if (existing != null && existing.getId() > 0) {
                oldValue = existing.getValue() == null ? "" : existing.getValue();
                if (oldValue.equals(newValue)) {
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
                itemDataIdAfter = existing.getId();
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
                ItemDataBean createdRow = idDAO.create(idb);
                isCreate = true;
                itemDataIdAfter = createdRow != null ? createdRow.getId() : idb.getId();
            }
            if (!oldValue.equals(newValue)) {
                dataChanged = true;
            }

            writeAuditEvent(auditDAO, /* type=1 Item value updated */ 1,
                    currentUser, currentStudy, ss,
                    isCreate ? "item_data_create" : "item_data_update",
                    "item_data", itemDataIdAfter,
                    itemOid, oldValue, newValue);

            // 2026-06-30 — capture nAMD treat-and-extend decision values
            // for the summary audit row emitted after the loop.
            switch (itemOid) {
                case "I_NAMD_DECISION_ACTION":        namdDecisionSummary.put("action", newValue); break;
                case "I_NAMD_DECISION_DRUG":          namdDecisionSummary.put("drug", newValue); break;
                case "I_NAMD_DECISION_INTERVAL_WEEKS":namdDecisionSummary.put("interval", newValue); break;
                case "I_NAMD_AI_AGREED":              namdDecisionSummary.put("agreedWithAi", newValue); break;
                case "I_NAMD_DECISION_RATIONALE_CODE":namdDecisionSummary.put("rationale", newValue); break;
                default: /* not a summary field */ break;
            }

            // Phase E.6 admin-rfc — write the RFC discrepancy note + mapping
            // when this is an edit under administrative editing.
            if (recordReasonForChange(rfcWriter, auditDAO, reasons.get(itemOid), itemDataIdAfter,
                    itemOid, oldValue, newValue, currentUser, currentStudy, ss)) {
                rfcCreatedCount++;
            }

            saved++;
        }

        // Phase E.6: per-row save into repeating item groups. Each
        // GroupRowSavePayload carries a (groupOid, rowOrdinal, values)
        // tuple; the controller fans the values out into per-item
        // upserts against item_data with the matching ordinal. The
        // ordinal is taken at face value from the client; the
        // create-row endpoint is the authoritative ordinal allocator
        // and the SPA always re-fetches after a row create so the
        // client and server agree on the ordinal range.
        if (body.groups() != null) {
            for (SaveItemsRequest.GroupRowSavePayload row : body.groups()) {
                if (row == null || row.values() == null) continue;
                int ordinal = Math.max(1, row.rowOrdinal());
                for (Map.Entry<String, Object> rowVal : row.values().entrySet()) {
                    String itemOid = rowVal.getKey();
                    String newValue = serialiseValueForStorage(rowVal.getValue());
                    ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
                    if (candidates == null || candidates.isEmpty()) {
                        rejected++;
                        continue;
                    }
                    ItemBean item = candidates.get(0);
                    ItemDataBean existing = idDAO.findByItemIdAndEventCRFIdAndOrdinal(
                            item.getId(), ecb.getId(), ordinal);
                    String oldValue = "";
                    boolean isCreate;
                    int rowItemDataId;
                    if (existing != null && existing.getId() > 0) {
                        oldValue = existing.getValue() == null ? "" : existing.getValue();
                        if (oldValue.equals(newValue)) { groupRowsSaved++; continue; }
                        existing.setValue(newValue);
                        existing.setUpdater(currentUser);
                        existing.setUpdaterId(currentUser.getId());
                        existing.setStatus(Status.AVAILABLE);
                        existing.setOldStatus(Status.AVAILABLE);
                        idDAO.update(existing);
                        isCreate = false;
                        rowItemDataId = existing.getId();
                    } else {
                        ItemDataBean idb = new ItemDataBean();
                        idb.setEventCRFId(ecb.getId());
                        idb.setItemId(item.getId());
                        idb.setValue(newValue);
                        idb.setOrdinal(ordinal);
                        idb.setOwnerId(currentUser.getId());
                        idb.setOwner(currentUser);
                        idb.setStatus(Status.AVAILABLE);
                        idb.setOldStatus(Status.AVAILABLE);
                        idb.setDeleted(false);
                        ItemDataBean createdRow = idDAO.create(idb);
                        isCreate = true;
                        rowItemDataId = createdRow != null ? createdRow.getId() : idb.getId();
                    }
                    if (!oldValue.equals(newValue)) {
                        dataChanged = true;
                    }
                    String rowKey = groupRowReasonKey(itemOid, ordinal);
                    writeAuditEvent(auditDAO, /* type=1 Item value updated */ 1,
                            currentUser, currentStudy, ss,
                            isCreate ? "item_data_create" : "item_data_update",
                            "item_data", rowItemDataId,
                            rowKey, oldValue, newValue);
                    if (recordReasonForChange(rfcWriter, auditDAO, reasons.get(rowKey), rowItemDataId,
                            rowKey, oldValue, newValue, currentUser, currentStudy, ss)) {
                        rfcCreatedCount++;
                    }
                    groupRowsSaved++;
                }
            }
        }

        // 2026-06-30 — TREATMENT_DECISION_RECORDED summary audit row.
        // Emit only when at least one decision-significant item changed
        // (i.e. namdDecisionSummary is non-empty). Packs the chosen
        // action/drug/interval + AI-agreement flag so the audit timeline
        // reads as one logical event instead of N item_data updates.
        if (!namdDecisionSummary.isEmpty()) {
            StringBuilder newVal = new StringBuilder();
            for (Map.Entry<String, String> e : namdDecisionSummary.entrySet()) {
                if (newVal.length() > 0) newVal.append(',');
                newVal.append(e.getKey()).append(':').append(e.getValue() == null ? "" : e.getValue());
            }
            writeAuditEvent(auditDAO, AuditTypeIds.TREATMENT_DECISION_RECORDED,
                    currentUser, currentStudy, ss,
                    "namd_treatment_decision_recorded",
                    "event_crf", ecb.getId(),
                    "F_NAMD_VISIT", "", newVal.toString());
        }

        // A verified CRF whose data changed is no longer verified.
        if (dataChanged) {
            SdvRevocation.revokeIfVerified(ecb, currentUser, eventCrfDAO);
        }

        // Touch the EventCRF so {date_updated} reflects the save —
        // drives the SPA's lastSavedAt header. Only that: the bean dates
        // from the start of the request, and writing it back would undo
        // an SDV change or a completion made meanwhile (EventCRFDAO.touch).
        eventCrfDAO.touch(ecb.getId(), currentUser.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventCrfOid", String.valueOf(ecb.getId()));
        out.put("savedItemCount", saved);
        out.put("rejectedItemCount", rejected);
        out.put("rfcCreatedCount", rfcCreatedCount);
        out.put("groupRowsSaved", groupRowsSaved);
        out.put("lastSavedAt", formatIsoInstant(latestUpdate(eventCrfDAO.findByPK(ecb.getId()))));
        out.put("status", computeStatus(ecb, saved > 0 || groupRowsSaved > 0));

        LOG.info("CRF save: event_crf {} got {} items + {} group-rows (rejected {}, rfc {}); user {} study {}",
                ecb.getId(), saved, groupRowsSaved, rejected, rfcCreatedCount, currentUser.getName(), currentStudy.getOid());

        return ResponseEntity.ok(out);
    }

    /**
     * The {@code reasons} key of a value in a row of a repeating group:
     * {@code OID[row]}, the column marker its audit row carries.
     */
    static String groupRowReasonKey(String itemOid, int rowOrdinal) {
        return itemOid + "[" + rowOrdinal + "]";
    }

    private static boolean hasReason(Map<String, String> reasons, String key) {
        String reason = reasons.get(key);
        return reason != null && !reason.trim().isEmpty();
    }

    /**
     * Phase E.6 admin-rfc — record the reason for a changed value: a Reason
     * for Change note on its {@code item_data} row, and an audit row that
     * carries the reason itself, so the audit log says why the value
     * changed and not only that a note exists. The note writer is
     * best-effort: it logs + swallows DAO failures so a flaky RFC write
     * never rolls back the item_data save.
     *
     * @param writer null outside administrative editing: nothing is recorded
     * @return true when a note was written
     */
    private static boolean recordReasonForChange(ReasonForChangeWriter writer, AuditEventDAO auditDAO,
                                                 String reason, int itemDataId, String columnName,
                                                 String oldValue, String newValue,
                                                 UserAccountBean user, StudyBean study,
                                                 StudySubjectBean ss) {
        if (writer == null || itemDataId <= 0 || reason == null || reason.trim().isEmpty()) {
            return false;
        }
        DiscrepancyNoteBean rfcDn = writer.writeRfc(itemDataId, study, user, reason);
        if (rfcDn == null) {
            return false;
        }
        // Its own type since 2026-09-27: it was written under 27, the
        // study_subject trigger's "moved to another site".
        writeAuditEvent(auditDAO, AuditTypeIds.ITEM_DATA_REASON_FOR_CHANGE,
                user, study, ss,
                "item_data_rfc", "item_data", itemDataId,
                columnName, oldValue, newValue, reason);
        return true;
    }

    /**
     * The values of a save that fail their item's CRF validation, keyed as
     * the RFC keys are (item OID, or {@code OID[row]}). Only values the save
     * changes are checked; a blank one is the required check's business, and
     * one whose row already carries an active discrepancy note stands, as
     * {@code DiscrepancyValidator} lets it in legacy.
     */
    private List<ValidationErrorBody.FieldError> crfValidationFailures(
            HttpServletRequest request, Map<String, Object> topLevelValues,
            List<SaveItemsRequest.GroupRowSavePayload> groups, EventCRFBean ecb,
            ItemDAO itemDAO, ItemDataDAO idDAO) {
        Map<Integer, String[]> validations = loadCrfValidations(ecb.getCRFVersionId());
        if (validations.isEmpty()) {
            return List.of();
        }
        DiscrepancyNoteDAO notes = new DiscrepancyNoteDAO(dataSource);
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        if (topLevelValues != null) {
            for (Map.Entry<String, Object> entry : topLevelValues.entrySet()) {
                checkCrfValidation(request, entry.getKey(), 0, entry.getValue(), ecb,
                        itemDAO, idDAO, validations, notes, out);
            }
        }
        if (groups != null) {
            for (SaveItemsRequest.GroupRowSavePayload row : groups) {
                if (row == null || row.values() == null) continue;
                int ordinal = Math.max(1, row.rowOrdinal());
                for (Map.Entry<String, Object> rowVal : row.values().entrySet()) {
                    checkCrfValidation(request, rowVal.getKey(), ordinal, rowVal.getValue(), ecb,
                            itemDAO, idDAO, validations, notes, out);
                }
            }
        }
        return out;
    }

    /**
     * item_id → (validation, author's message, label) of every item of the
     * version that has a validation. Read uncached: the item-metadata DAO
     * caches its lookups.
     */
    private Map<Integer, String[]> loadCrfValidations(int crfVersionId) {
        Map<Integer, String[]> out = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ifm.item_id, ifm.regexp, ifm.regexp_error_msg, "
                             + "       COALESCE(NULLIF(TRIM(ifm.left_item_text), ''), i.name) "
                             + "  FROM item_form_metadata ifm JOIN item i ON i.item_id = ifm.item_id "
                             + " WHERE ifm.crf_version_id = ? "
                             + "   AND COALESCE(TRIM(ifm.regexp), '') <> ''")) {
            ps.setInt(1, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), new String[] {rs.getString(2), rs.getString(3), rs.getString(4)});
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not read the CRF validations of crf_version " + crfVersionId, e);
        }
        return out;
    }

    /** @param rowOrdinal 0 for a top-level item */
    private static void checkCrfValidation(HttpServletRequest request, String itemOid, int rowOrdinal,
                                           Object rawValue, EventCRFBean ecb, ItemDAO itemDAO,
                                           ItemDataDAO idDAO, Map<Integer, String[]> validations,
                                           DiscrepancyNoteDAO notes,
                                           List<ValidationErrorBody.FieldError> out) {
        String newValue = serialiseValueForStorage(rawValue);
        if (newValue.trim().isEmpty()) return;
        ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
        if (candidates == null || candidates.isEmpty()) return;
        ItemBean item = candidates.get(0);
        String[] validation = validations.get(item.getId());
        if (validation == null) return;
        ItemDataBean existing = rowOrdinal == 0
                ? idDAO.findByItemIdAndEventCRFId(item.getId(), ecb.getId())
                : idDAO.findByItemIdAndEventCRFIdAndOrdinal(item.getId(), ecb.getId(), rowOrdinal);
        boolean stored = existing != null && existing.getId() > 0;
        if (stored && newValue.equals(existing.getValue())) return;

        String failure = CrfItemValidation.failure(request,
                CrfItemValidation.of(validation[0], validation[1]), newValue);
        if (failure == null) return;
        if (stored && notes.findNumOfActiveExistingNotesForItemData(existing.getId()) > 0) return;

        String key = rowOrdinal == 0 ? itemOid : groupRowReasonKey(itemOid, rowOrdinal);
        out.add(new ValidationErrorBody.FieldError(key, validation[2] + ": " + failure));
    }

    /**
     * Phase E.4 M6 — mark CRF complete. Delegates to
     * {@link EventCRFDAO#markComplete} (the IDE — initial data entry —
     * path). The SPA calls this when the user clicks "Mark complete"
     * after a clean validation pass ({@code stores/crfEntry.ts:computeItemErrors}).
     * The server does not rely on that: as legacy data entry does, it
     * refuses to complete a CRF while a required item that is shown has
     * no value (400, {@link ValidationErrorBody} listing them;
     * {@link RequiredItemsCheck}). It also enforces the locked-CRF gate
     * and the role ({@link ClinicalWriteAuthorization}).
     *
     * <p>Reject if the CRF is locked (idempotent on already-complete
     * CRFs: returns 200 with the existing state).
     */
    @PostMapping("/{id:[0-9]+}/markComplete")
    public ResponseEntity<?> markComplete(@PathVariable("id") int eventCrfId,
                                          HttpSession session,
                                          HttpServletRequest request) {
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
        ResponseEntity<?> noStudyRole = DataEntryRoleGuard.refuseWithoutStudyRole(session);
        if (noStudyRole != null) {
            return noStudyRole;
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "completing CRFs");
        if (roleRefusal != null) {
            return roleRefusal;
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "completing the CRF");
        if (closed != null) {
            return closed;
        }

        // Legacy data entry does not complete a CRF while a required item
        // that is shown is empty. A CRF that is complete already stays so.
        if (ecb.getDateCompleted() == null) {
            List<RequiredItemsCheck.Missing> missing = RequiredItemsCheck.missing(dataSource, ecb);
            if (!missing.isEmpty()) {
                String notBlank = ResourceBundleProvider
                        .getExceptionsBundle(LocaleResolver.getLocale(request)).getString("field_not_blank");
                List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (RequiredItemsCheck.Missing m : missing) {
                    errors.add(new ValidationErrorBody.FieldError(m.key(), m.label() + ": " + notBlank));
                    labels.add(m.label());
                }
                LOG.info("CRF markComplete: event_crf {} refused, {} required item(s) empty",
                        ecb.getId(), missing.size());
                return ResponseEntity.badRequest().body(new ValidationErrorBody(
                        "Required items are missing: " + String.join(", ", labels), errors));
            }
        }

        // Phase B2 (2026-06-10) — failure-audit wrap. Any Throwable from
        // the markComplete cascade lands an OPERATION_FAILED (type 61)
        // row keyed by event_crf id + reqId before the exception
        // surfaces to the SPA as the same 500 envelope existing callers
        // expect. Validation guards above (401/400/403/404/409) bypass
        // the wrap and flow through unchanged.
        final UserAccountBean userRef = currentUser;
        final StudyBean studyRef = currentStudy;
        final StudySubjectBean ssRef = ss;
        final EventCRFBean ecbRef = ecb;
        final EventCRFDAO eventCrfDAORef = eventCrfDAO;
        final String reqId = MDC.get("reqId");
        try {
            return FailureAuditTemplate.runOrAudit(
                    new AuditEventDAO(dataSource),
                    currentUser.getId(),
                    "event_crf",
                    ecb.getId(),
                    "MARK_COMPLETE",
                    reqId,
                    () -> {
                        // Already complete (date_completed set) → return
                        // idempotently — but still run the cascade. Operators
                        // who completed CRFs before the cascade shipped need
                        // a way to reconcile their visit pill, and the
                        // cascade helper is idempotent (it early-returns on
                        // COMPLETED / SIGNED / LOCKED).
                        if (ecbRef.getDateCompleted() == null) {
                            eventCrfDAORef.markComplete(ecbRef, /* ide */ true);

                            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
                            writeAuditEvent(auditDAO, /* type=8 CRF marked complete */ 8,
                                    userRef, studyRef, ssRef,
                                    "event_crf_mark_complete", "event_crf", ecbRef.getId(),
                                    /* columnName */ "date_completed", /* old */ "",
                                    /* new */ Instant.now().toString());
                        }

                        EventCRFBean refreshed = eventCrfDAORef.findByPK(ecbRef.getId());

                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("eventCrfOid", String.valueOf(refreshed.getId()));
                        out.put("status", computeStatus(refreshed, true));
                        out.put("lastSavedAt", formatIsoInstant(latestUpdate(refreshed)));

                        LOG.info("CRF markComplete: event_crf {} status now {}; user {} study {}",
                                refreshed.getId(), out.get("status"), userRef.getName(),
                                studyRef.getOid());

                        return ResponseEntity.ok(out);
                    });
        } catch (Exception e) {
            LOG.error("event_crf markComplete failed for id={} user={}",
                    ecb.getId(), currentUser.getName(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to mark CRF complete — see server log."));
        }
    }

    /**
     * Phase E A5 — reopen a completed CRF for editing. Inverse of
     * {@link #markComplete} — clears every completion marker the CRF
     * carries ({@link EventCRFDAO#markIncomplete}): {@code date_completed},
     * the second pass's {@code date_validate_completed}, and a legacy
     * completed status. The SPA's CRF entry form, legacy screens and SDV
     * then all read the CRF as in data entry: it is no longer offered for
     * verification, and double data entry needs its second pass again.
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
     * {@code event_crf_reopen} for each marker it clears, so the M10
     * Audit Log view surfaces it.
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

        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "reopening the CRF");
        if (closed != null) {
            return closed;
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
        writeAuditEvent(auditDAO, AuditTypeIds.EVENT_CRF_REOPENED,
                currentUser, currentStudy, ss,
                "event_crf_reopen", "event_crf", ecb.getId(),
                /* columnName */ "date_completed",
                /* old */ previousCompletedAt,
                /* new */ "");
        // The bean still holds the markers markIncomplete cleared.
        if (ecb.getDateValidateCompleted() != null) {
            writeAuditEvent(auditDAO, AuditTypeIds.EVENT_CRF_REOPENED,
                    currentUser, currentStudy, ss,
                    "event_crf_reopen", "event_crf", ecb.getId(),
                    "date_validate_completed", formatIsoInstant(ecb.getDateValidateCompleted()), "");
        }
        if (Status.UNAVAILABLE.equals(ecb.getStatus()) || Status.PENDING.equals(ecb.getStatus())) {
            writeAuditEvent(auditDAO, AuditTypeIds.EVENT_CRF_REOPENED,
                    currentUser, currentStudy, ss,
                    "event_crf_reopen", "event_crf", ecb.getId(),
                    "status_id", Status.UNAVAILABLE.equals(ecb.getStatus()) ? "UNAVAILABLE" : "PENDING",
                    "AVAILABLE");
        }

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
     * App-feedback Wave 1D (2026-06-19) — "Vom letzten Besuch
     * übernehmen". Resolves the most recent COMPLETED event_crf for
     * the same study_subject + same crf_version (i.e. same CRF
     * definition) and returns its persisted item values so the SPA's
     * prefill modal can offer them to the operator for the current
     * entry.
     *
     * <p>Match rules:
     * <ol>
     *   <li>Same {@code study_subject_id} (resolved via the current
     *       event_crf's {@code study_event});</li>
     *   <li>Same {@code crf_version_id} (so the schema matches —
     *       upgrades to a newer CRF version legitimately reset the
     *       prefill chain);</li>
     *   <li>{@code date_completed IS NOT NULL} (only completed CRFs
     *       are trusted as prefill sources — in-progress drafts can
     *       contain partial / placeholder data);</li>
     *   <li>{@code event_crf_id != current} (defensive — operators
     *       shouldn't be able to pre-fill from themselves).</li>
     * </ol>
     *
     * <p>Pick the row with the latest {@code date_completed} (then by
     * descending {@code event_crf_id} as a stable tiebreaker), then
     * fetch its {@code item_data} rows and join against
     * {@code item_form_metadata} for the display label.
     *
     * <p>Guards (order matters):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 404} — no event_crf with that id.</li>
     *   <li>{@code 403} — event_crf belongs to a study the caller's
     *       site-visibility set excludes.</li>
     *   <li>{@code 404} — no prior completed CRF of the same
     *       definition exists (SPA renders the "Keine vorherige
     *       Visite gefunden" empty state).</li>
     * </ol>
     */
    @GetMapping("/{id:[0-9]+}/previous-values")
    public ResponseEntity<?> previousValues(@PathVariable("id") int currentEventCrfId,
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
        EventCRFBean currentEcb = eventCrfDAO.findByPK(currentEventCrfId);
        if (currentEcb == null || currentEcb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + currentEventCrfId));
        }

        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(currentEcb.getStudySubjectId());
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (ss == null || !visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + currentEventCrfId + " belongs to a different study"));
        }

        // Look up the prior completed CRF of the same definition for
        // the same subject. The query walks back through study_event so
        // we land the right study_subject + scope to the matching
        // crf_version_id.
        Integer sourceEventCrfId = null;
        Date sourceCompletedAt = null;
        final String sql =
                "SELECT ec.event_crf_id, ec.date_completed " +
                "  FROM event_crf ec " +
                "  JOIN study_event se ON se.study_event_id = ec.study_event_id " +
                " WHERE se.study_subject_id = (SELECT se2.study_subject_id " +
                "                                FROM event_crf ec2 " +
                "                                JOIN study_event se2 ON se2.study_event_id = ec2.study_event_id " +
                "                               WHERE ec2.event_crf_id = ?) " +
                "   AND ec.crf_version_id = (SELECT crf_version_id FROM event_crf WHERE event_crf_id = ?) " +
                "   AND ec.date_completed IS NOT NULL " +
                "   AND ec.event_crf_id <> ? " +
                " ORDER BY ec.date_completed DESC, ec.event_crf_id DESC " +
                " LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, currentEventCrfId);
            ps.setInt(2, currentEventCrfId);
            ps.setInt(3, currentEventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sourceEventCrfId = rs.getInt(1);
                    java.sql.Timestamp ts = rs.getTimestamp(2);
                    sourceCompletedAt = ts == null ? null : new Date(ts.getTime());
                }
            }
        } catch (SQLException ex) {
            LOG.error("previousValues: lookup failed for event_crf {} ({})",
                    currentEventCrfId, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Previous-values lookup failed."));
        }

        if (sourceEventCrfId == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No prior completed CRF of the same definition for this subject."));
        }

        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemFormMetadataDAO ifmDAO = new ItemFormMetadataDAO(dataSource);
        ArrayList<ItemDataBean> rows = idDAO.findAllByEventCRFId(sourceEventCrfId);
        Map<Integer, ItemFormMetadataBean> ifmByItemId = new HashMap<>();
        try {
            List<ItemFormMetadataBean> allIfms = ifmDAO.findAllByCRFVersionId(currentEcb.getCRFVersionId());
            for (ItemFormMetadataBean ifm : allIfms) {
                ifmByItemId.put(ifm.getItemId(), ifm);
            }
        } catch (Exception ignored) {
            // Best-effort label lookup — falls back to the item OID.
        }

        List<Map<String, Object>> values = new ArrayList<>(rows.size());
        for (ItemDataBean idb : rows) {
            if (idb == null || idb.getValue() == null) continue;
            // Skip rows belonging to soft-deleted item_data — operators
            // shouldn't be carrying-forward administratively-removed
            // measurements.
            if (idb.isDeleted()) continue;
            ItemBean item = (ItemBean) itemDAO.findByPK(idb.getItemId());
            if (item == null || item.getOid() == null) continue;
            ItemFormMetadataBean ifm = ifmByItemId.get(item.getId());
            String label = (ifm != null) ? firstNonBlank(
                    ifm.getLeftItemText(), ifm.getRightItemText(), item.getName()) : item.getName();
            if (label == null || label.isBlank()) label = item.getOid();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemOid", item.getOid());
            row.put("value", idb.getValue());
            row.put("itemLabel", label);
            values.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceEventCrfId", sourceEventCrfId);
        out.put("sourceCompletedAt", formatIsoInstant(sourceCompletedAt));
        out.put("values", values);

        LOG.info("previousValues: served {} item(s) from event_crf {} as prefill source for {} (user {} / study {})",
                values.size(), sourceEventCrfId, currentEventCrfId,
                currentUser.getName(), currentStudy.getOid());

        return ResponseEntity.ok(out);
    }

    /* ====================================================================== */
    /* Phase E.6 crf-entry-advanced — TOC badges + concurrent-edit probe +    */
    /* per-item notes roll-up. Four read endpoints + one heartbeat write.     */
    /*                                                                        */
    /* All four are session-guard + site-visibility-filtered like the rest of */
    /* this controller. The presence registry is in-memory (no Liquibase).    */
    /* ====================================================================== */

    /**
     * Phase E.6 — read-only soft-lock probe. Returns the freshest
     * non-stale {@link EventCrfPresenceRegistry.PresenceEntry} for
     * the event_crf as an {@link EventCrfLockProbeDto}. The SPA
     * shows the {@code ConcurrentEditBanner} when {@code sameUser =
     * false}; otherwise it silently starts its heartbeat.
     */
    @GetMapping("/{id:[0-9]+}/lock-status")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfLockProbeDto.class)))
    public ResponseEntity<?> lockStatus(@PathVariable("id") int eventCrfId,
                                        HttpSession session) {
        ResponseEntity<?> guard = guardEventCrfAccess(eventCrfId, session);
        if (guard != null) return guard;
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");

        Optional<EventCrfPresenceRegistry.PresenceEntry> freshest =
                presenceRegistry.freshestEntry(eventCrfId);
        boolean sameUser = freshest.map(p -> p.userId() == currentUser.getId()).orElse(true);
        String editorName = freshest.map(EventCrfPresenceRegistry.PresenceEntry::userName).orElse(null);
        String lastSeenAt = freshest
                .map(EventCrfPresenceRegistry.PresenceEntry::lastSeenAt)
                .map(i -> i.truncatedTo(ChronoUnit.SECONDS).toString())
                .orElse(null);

        return ResponseEntity.ok(new EventCrfLockProbeDto(
                String.valueOf(eventCrfId),
                sameUser,
                editorName,
                lastSeenAt,
                EventCrfPresenceRegistry.TTL_SECONDS));
    }

    /**
     * Phase E.6 — heartbeat. The SPA POSTs this every
     * {@link EventCrfPresenceRegistry#TTL_SECONDS} / 2 seconds while
     * the entry view is mounted. Records/refreshes the caller's
     * presence and returns the freshest non-stale entry (which may
     * differ from the caller's own if another user heartbeated in
     * the same window).
     *
     * <p>Always 200 — even when a concurrent editor is detected,
     * {@code sameUser = false} is the only signal. The original
     * playbook listed "409 collision" but that conflated the soft
     * lock with a hard write conflict; we use a non-error response
     * to keep the SPA's heartbeat loop trivially retriable.
     */
    @PostMapping("/{id:[0-9]+}/heartbeat")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfLockProbeDto.class)))
    public ResponseEntity<?> heartbeat(@PathVariable("id") int eventCrfId,
                                       HttpSession session) {
        ResponseEntity<?> guard = guardEventCrfAccess(eventCrfId, session);
        if (guard != null) return guard;
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");

        EventCrfPresenceRegistry.PresenceEntry freshest = presenceRegistry.heartbeat(
                eventCrfId, currentUser.getId(),
                currentUser.getName() != null ? currentUser.getName() : "");
        boolean sameUser = freshest.userId() == currentUser.getId();
        return ResponseEntity.ok(new EventCrfLockProbeDto(
                String.valueOf(eventCrfId),
                sameUser,
                freshest.userName(),
                freshest.lastSeenAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                EventCrfPresenceRegistry.TTL_SECONDS));
    }

    /**
     * Phase E.6 — per-item discrepancy roll-up. The SPA renders the
     * {@code ItemNoteIndicator} chip on each item with at least one
     * parent note attached. The popover lazily loads the full thread
     * via {@code GET /pages/api/v1/discrepancies?…} when the user
     * clicks the chip — this endpoint returns only the summary
     * needed for badge counts + tooltips.
     */
    @GetMapping("/{id:[0-9]+}/notes")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventCrfNotesRollupDto.class)))
    public ResponseEntity<?> notesRollup(@PathVariable("id") int eventCrfId,
                                         HttpSession session) {
        ResponseEntity<?> guard = guardEventCrfAccess(eventCrfId, session);
        if (guard != null) return guard;

        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
        dnDao.setFetchMapping(true);
        List<DiscrepancyNoteBean> notes = dnDao.findAllParentItemNotesByEventCRF(eventCrfId);
        if (notes == null) notes = new ArrayList<>();

        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        Map<Integer, String> oidByItemId = new HashMap<>();
        // entityId on a parent note is the item_data id; resolve back to item_oid.
        Map<String, Integer> openByOid = new HashMap<>();
        Map<String, Integer> totalByOid = new HashMap<>();
        Map<String, Instant> latestByOid = new HashMap<>();
        Map<String, List<String>> noteIdsByOid = new HashMap<>();
        int totalCount = 0;
        int openCount = 0;
        for (DiscrepancyNoteBean n : notes) {
            if (n == null || n.getId() == 0) continue;
            totalCount++;
            boolean isOpen = isOpenQuery(n);
            if (isOpen) openCount++;
            String itemOid = resolveItemOidForParentNote(n, idDAO, itemDAO, oidByItemId);
            if (itemOid == null || itemOid.isBlank()) continue;
            totalByOid.merge(itemOid, 1, Integer::sum);
            if (isOpen) openByOid.merge(itemOid, 1, Integer::sum);
            Instant when = latestActivityInstant(n);
            latestByOid.merge(itemOid, when, (a, b) -> a.isAfter(b) ? a : b);
            noteIdsByOid.computeIfAbsent(itemOid, _ -> new ArrayList<>())
                    .add(String.valueOf(n.getId()));
        }

        Map<String, EventCrfNotesRollupDto.ItemNoteSummary> byItem = new LinkedHashMap<>();
        for (String oid : totalByOid.keySet()) {
            int t = totalByOid.getOrDefault(oid, 0);
            int o = openByOid.getOrDefault(oid, 0);
            Instant ts = latestByOid.get(oid);
            byItem.put(oid, new EventCrfNotesRollupDto.ItemNoteSummary(
                    t, o,
                    o > 0 ? "open" : "resolved",
                    ts != null ? ts.truncatedTo(ChronoUnit.SECONDS).toString() : null,
                    noteIdsByOid.getOrDefault(oid, List.of())));
        }

        return ResponseEntity.ok(new EventCrfNotesRollupDto(
                String.valueOf(eventCrfId), totalCount, openCount, byItem));
    }

    /**
     * Phase E.6 — per-section TOC roll-up.
     *
     * <p>For each section in the event_crf's CRF version, count
     * (required, filled-required, error, open-queries). The error
     * count is currently 0 in this first cut — server-side typed
     * validation runs at save time; the SPA's client-side validator
     * already covers the entry-time guidance. A future revision can
     * extend this to surface persisted invalid-because-rules state.
     */
    @GetMapping("/{id:[0-9]+}/section-status")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = SectionStatusDto.class)))
    public ResponseEntity<?> sectionStatus(@PathVariable("id") int eventCrfId,
                                           HttpSession session) {
        ResponseEntity<?> guard = guardEventCrfAccess(eventCrfId, session);
        if (guard != null) return guard;
        EventCRFDAO eventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = eventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        CRFVersionDAO crfvDAO = new CRFVersionDAO(dataSource);
        CRFVersionBean crfv = (CRFVersionBean) crfvDAO.findByPK(ecb.getCRFVersionId());
        if (crfv == null || crfv.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "event_crf " + eventCrfId + " references missing crf_version"));
        }

        ItemFormMetadataDAO ifmDAO = new ItemFormMetadataDAO(dataSource);
        Map<Integer, Boolean> requiredByItemId = new HashMap<>();
        try {
            List<ItemFormMetadataBean> allIfms = ifmDAO.findAllByCRFVersionId(crfv.getId());
            for (ItemFormMetadataBean ifm : allIfms) {
                requiredByItemId.put(ifm.getItemId(), ifm.isRequired());
            }
        } catch (Exception ifmEx) {
            LOG.error("section-status: failed to load IFMs for crf_version {}", crfv.getId(), ifmEx);
            return ResponseEntity.internalServerError().body(Map.of("message",
                    "Schema load failed: " + ifmEx.getMessage()));
        }

        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
        dnDao.setFetchMapping(true);
        List<DiscrepancyNoteBean> notes = dnDao.findAllParentItemNotesByEventCRF(eventCrfId);
        if (notes == null) notes = new ArrayList<>();
        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);

        // Resolve each note's section_id via item_data → item → item.section_id.
        // Cache item lookups so a section's notes share the work.
        Map<Integer, Integer> sectionIdByItemDataId = new HashMap<>();
        Map<Integer, Boolean> openByNoteId = new HashMap<>();
        for (DiscrepancyNoteBean n : notes) {
            if (n == null || n.getId() == 0) continue;
            if (!"itemData".equalsIgnoreCase(n.getEntityType())) continue;
            int idbId = n.getEntityId();
            if (idbId <= 0) continue;
            Integer secId = sectionIdByItemDataId.get(idbId);
            if (secId == null) {
                ItemDataBean idb = (ItemDataBean) idDAO.findByPK(idbId);
                if (idb == null || idb.getId() == 0) continue;
                ItemBean item = (ItemBean) itemDAO.findByPK(idb.getItemId());
                if (item == null || item.getId() == 0) continue;
                // Item bean's section_id isn't a direct column on the
                // legacy schema; item-form-metadata carries it. Fall
                // through with -1 if no IFM row exists (defensive).
                ItemFormMetadataBean ifm = findIfmForItem(item.getId(), crfv.getId(), ifmDAO);
                secId = (ifm != null ? ifm.getSectionId() : -1);
                sectionIdByItemDataId.put(idbId, secId);
            }
            openByNoteId.put(n.getId(), isOpenQuery(n));
        }

        List<SectionStatusDto> out = new ArrayList<>();
        SectionDAO sectionDAO = new SectionDAO(dataSource);
        List<SectionBean> sections = sectionDAO.findAllByCRFVersionId(crfv.getId());
        sections.sort(Comparator.comparingInt(SectionBean::getOrdinal));
        for (SectionBean sb : sections) {
            int requiredCount = 0;
            int filledCount = 0;
            List<ItemBean> items = itemDAO.findAllBySectionIdOrderedByItemFormMetadataOrdinal(sb.getId());
            Set<Integer> requiredItemIds = new HashSet<>();
            for (ItemBean ib : items) {
                if (Boolean.TRUE.equals(requiredByItemId.get(ib.getId()))) {
                    requiredCount++;
                    requiredItemIds.add(ib.getId());
                }
            }
            List<ItemDataBean> dataRows = idDAO.findAllBySectionIdAndEventCRFId(sb.getId(), ecb.getId());
            for (ItemDataBean idb : dataRows) {
                if (idb == null) continue;
                if (!requiredItemIds.contains(idb.getItemId())) continue;
                String v = idb.getValue();
                if (v != null && !v.isBlank()) filledCount++;
            }
            int openQueries = 0;
            for (DiscrepancyNoteBean n : notes) {
                if (n == null || n.getId() == 0) continue;
                if (!"itemData".equalsIgnoreCase(n.getEntityType())) continue;
                Integer secId = sectionIdByItemDataId.get(n.getEntityId());
                if (secId == null || secId != sb.getId()) continue;
                if (Boolean.TRUE.equals(openByNoteId.get(n.getId()))) openQueries++;
            }

            out.add(new SectionStatusDto(
                    sb.getLabel(),
                    blankToNull(sb.getTitle(), sb.getLabel()),
                    requiredCount,
                    filledCount,
                    /* errorCount */ 0,
                    openQueries));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Centralised guard: 401 if no user, 400 if no active study, 404
     * if no event_crf, 403 if the event_crf belongs to a different
     * study. Mirrors the inline guards in {@link #getEventCrf} but
     * pulled out so the four new endpoints stay terse.
     *
     * @return non-null {@link ResponseEntity} when the request must
     *         be rejected; {@code null} when the caller may proceed
     */
    private ResponseEntity<?> guardEventCrfAccess(int eventCrfId, HttpSession session) {
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
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        EventCRFBean ecb = ecDao.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDAO.findByPK(ecb.getStudySubjectId());
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (ss == null || !visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "event_crf " + eventCrfId + " belongs to a different study"));
        }
        return null;
    }

    /**
     * An open query or failed validation check, as the SDV page counts
     * them. An annotation or a reason for change asks nobody anything, even
     * one the SPA stored as New before it stored them Not Applicable.
     */
    private static boolean isOpenQuery(DiscrepancyNoteBean n) {
        int type = n.getDiscrepancyNoteTypeId();
        if (type != DiscrepancyNoteType.QUERY.getId() && type != DiscrepancyNoteType.FAILEDVAL.getId()) {
            return false;
        }
        int resolutionStatusId = n.getResolutionStatusId();
        // 1=new, 2=updated, 3=resolution-proposed are "open"; 4=closed,
        // 5=not-applicable are "resolved" (per the SPA's
        // resolutionStatus union).
        return resolutionStatusId == ResolutionStatus.OPEN.getId()
                || resolutionStatusId == ResolutionStatus.UPDATED.getId()
                || resolutionStatusId == ResolutionStatus.RESOLVED.getId();
    }

    private static Instant latestActivityInstant(DiscrepancyNoteBean n) {
        Date updated = n.getUpdatedDate();
        Date created = n.getCreatedDate();
        Date pick = (updated != null) ? updated : created;
        if (pick != null) return pick.toInstant();
        // Final fallback: derive from getDays() (set by the legacy DAO).
        int days = n.getDays() == null ? 0 : Math.max(n.getDays(), 0);
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private String resolveItemOidForParentNote(DiscrepancyNoteBean note,
                                               ItemDataDAO idDAO, ItemDAO itemDAO,
                                               Map<Integer, String> oidByItemId) {
        if (!"itemData".equalsIgnoreCase(note.getEntityType())) return null;
        int idbId = note.getEntityId();
        if (idbId <= 0) return null;
        ItemDataBean idb = (ItemDataBean) idDAO.findByPK(idbId);
        if (idb == null || idb.getId() == 0) return null;
        String cached = oidByItemId.get(idb.getItemId());
        if (cached != null) return cached;
        ItemBean item = (ItemBean) itemDAO.findByPK(idb.getItemId());
        if (item == null || item.getOid() == null) return null;
        oidByItemId.put(idb.getItemId(), item.getOid());
        return item.getOid();
    }

    private static ItemFormMetadataBean findIfmForItem(int itemId, int crfVersionId,
                                                       ItemFormMetadataDAO ifmDAO) {
        try {
            List<ItemFormMetadataBean> all = ifmDAO.findAllByCRFVersionId(crfVersionId);
            for (ItemFormMetadataBean ifm : all) {
                if (ifm.getItemId() == itemId) return ifm;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Phase E.6 restore-quickwins — restore a soft-deleted event_crf.
     * Inverse of the legacy {@code RemoveEventCRFServlet} +
     * {@code RestoreEventCRFServlet}: flips the event_crf status from
     * {@link Status#AUTO_DELETED} (or {@link Status#DELETED}) back, and
     * cascades its AUTO_DELETED item_data rows back. A CRF whose removal
     * recorded its status, and which nothing has changed since, gets that
     * status back, and its values theirs ({@link EventDataStatusCascade});
     * otherwise the CRF and its values become {@link Status#AVAILABLE}, as
     * the legacy servlet makes them.
     *
     * <p>Guards (order matters):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 404} — no event_crf with that id.</li>
     *   <li>{@code 403} — event_crf belongs to a study the caller's
     *       site-visibility set excludes.</li>
     *   <li>{@code 403} — caller's role does not permit restore
     *       (DM/Admin only — same gate as remove).</li>
     *   <li>{@code 409} — event_crf is not currently removed.</li>
     *   <li>{@code 409} — parent study_event is currently DELETED;
     *       legacy {@code RestoreEventCRFServlet} blocks the per-CRF
     *       restore in that case (restore the event first).</li>
     *   <li>{@code 409} — its CRF or its CRF version is removed. That
     *       removal took the row and recorded its status; its restore
     *       brings it back at that status (see
     *       {@code CrfLifecycleCascade}), where this one would make a
     *       signed or locked event CRF available.</li>
     * </ol>
     *
     * <p>Returns 204 on success — the SPA refetches the event-detail
     * view to pick up the new row state.
     */
    @PostMapping("/{id:[0-9]+}/restore")
    public ResponseEntity<?> restore(@PathVariable("id") int eventCrfId,
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

        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        EventCRFBean ecb = ecDao.findByPK(eventCrfId);
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
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!EventCrfRestoreAuthorization.roleMayRestore(currentUser, roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit restoring event_crf rows"));
        }
        // Mirror the legacy 409 path on a removed parent subject.
        if (ss.getStatus() != null
                && (ss.getStatus().equals(Status.DELETED)
                        || ss.getStatus().equals(Status.AUTO_DELETED))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " cannot be restored — "
                            + "study subject is removed (restore subject first)"));
        }
        if (ecb.getStatus() == null
                || !(ecb.getStatus().equals(Status.AUTO_DELETED)
                        || ecb.getStatus().equals(Status.DELETED))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " is not currently removed"));
        }
        // Parent guard — legacy RestoreEventCRFServlet refuses if the
        // owning study_event is itself DELETED.
        StudyEventDAO seDAO = new StudyEventDAO(dataSource);
        StudyEventBean ev = (StudyEventBean) seDAO.findByPK(ecb.getStudyEventId());
        if (ev != null && ev.getStatus() != null && ev.getStatus().equals(Status.DELETED)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " cannot be restored — "
                            + "study_event is removed (restore the event first)"));
        }
        CRFVersionBean version = new CRFVersionDAO(dataSource).findByPK(ecb.getCRFVersionId());
        CRFBean crf = version == null || version.getId() == 0 ? null
                : new CRFDAO(dataSource).findByPK(version.getCrfId());
        if (crf != null && crf.getStatus() != null && crf.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " cannot be restored on its own — its CRF '"
                            + crf.getName() + "' is removed (restoring the CRF brings it back)"));
        }
        if (version != null && version.getStatus() != null && version.getStatus().isDeleted()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "event_crf " + eventCrfId + " cannot be restored on its own — its CRF version '"
                            + version.getName() + "' is removed (restoring the version brings it back)"));
        }

        // The status the CRF's removal recorded, while nothing has changed
        // the CRF since (a completed CRF comes back completed); otherwise
        // available, as legacy RestoreEventCRFServlet makes it.
        Status removedAs = ecb.getStatus();
        Integer recorded;
        try (Connection c = dataSource.getConnection()) {
            recorded = EventDataStatusCascade.recordedStatus(c, ecb.getId());
        } catch (SQLException e) {
            LOG.error("event_crf restore: id={} could not read its removal record: {}",
                    ecb.getId(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "The CRF could not be restored; nothing was changed. See the server log."));
        }
        Status restoredAs = recorded == null ? Status.AVAILABLE : Status.get(recorded);
        ecb.setStatus(restoredAs);
        ecb.setUpdater(currentUser);
        ecb.setUpdatedDate(new Date());
        ecDao.update(ecb);

        // Cascade AUTO_DELETED item_data rows back. Hard DELETED rows stay
        // put (legacy semantics — they're operator delete, not parent
        // cascade). Only the status changes: a value keeps its provenance,
        // which ItemDataDAO.update would clear.
        try (Connection c = dataSource.getConnection()) {
            ItemDataStatusCascade.restore(c, ecb.getId(), currentUser.getId(), recorded != null);
            EventDataStatusCascade.forget(c, List.of(ecb.getId()));
        } catch (SQLException e) {
            LOG.error("event_crf restore: id={} is restored but its values are not: {}",
                    ecb.getId(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "The CRF was restored but its values were not; see the server log."));
        }

        AuditEventDAO auditDao = new AuditEventDAO(dataSource);
        writeAuditEvent(auditDao, AuditTypeIds.EVENT_CRF_RESTORED,
                currentUser, currentStudy, ss,
                "event_crf_restore", "event_crf", ecb.getId(),
                "status_id", statusConstant(removedAs), statusConstant(restoredAs));

        LOG.info("event_crf restore: id={} subject={} by user={} role={}",
                ecb.getId(), ss.getLabel(), currentUser.getName(), roleId);
        return ResponseEntity.noContent().build();
    }

    /** A status as the restore's audit row names it: the {@link Status} constant's name. */
    private static String statusConstant(Status status) {
        return switch (status.getId()) {
            case 1 -> "AVAILABLE";
            case 2 -> "UNAVAILABLE";
            case 4 -> "PENDING";
            case 5 -> "DELETED";
            case 6 -> "LOCKED";
            case 7 -> "AUTO_DELETED";
            case 8 -> "SIGNED";
            default -> status.getName();
        };
    }

    /**
     * Single {@code audit_log_event} row capturing the change. Phase
     * audit-unification (2026-06-12) — migrated from the legacy
     * {@link AuditEventDAO#create} path (which wrote to
     * {@code audit_event}, a table the SPA Audit Log view never
     * surfaced) to a direct JDBC INSERT into {@code audit_log_event}.
     *
     * <p>The {@code auditTypeId} is a constant from
     * {@link AuditTypeIds} (or one of the legacy 1-35 ids documented
     * in {@link AuditApiController#variantForType}); it routes the row
     * into the right filter bucket on the SPA Audit Log view and
     * controls the user-facing label.
     *
     * <p>The {@code AuditEventDAO dao} parameter is kept (instead of
     * being replaced with {@code DataSource}) purely so the dozen
     * existing callers don't need to swap their constructor pattern;
     * the body no longer calls {@code dao.create()} but uses
     * {@link org.akaza.openclinica.dao.core.EntityDAO#getDs()} to reach
     * the DataSource the DAO already holds.
     *
     * <p>Wrapped in try/catch because audit-write failures must not
     * roll back the user-facing save — losing an audit row is annoying
     * but losing the data is worse. Mirrors the convention used by
     * {@code StudiesApiController.writeStudyFieldAudit}.
     */
    // Phase E.6 dde — promoted from `private` to `public static` so
    // {@code controller.api.service.dde.DdeService} (sub-package) can
    // emit the same audit rows when committing pass-2 and resolving
    // conflicts. Java doesn't model package hierarchy so
    // package-private isn't visible across sub-packages; public is the
    // minimum that compiles. Stable API for sibling services going
    // forward.
    public static void writeAuditEvent(AuditEventDAO dao, int auditTypeId,
                                        UserAccountBean user,
                                        StudyBean study, StudySubjectBean ss,
                                        String actionMessage, String auditTable,
                                        int entityId, String columnName,
                                        String oldValue, String newValue) {
        writeAuditEvent(dao, auditTypeId, user, study, ss, actionMessage, auditTable,
                entityId, columnName, oldValue, newValue, null);
    }

    /**
     * As above, recording why: {@code reason} goes into
     * {@code reason_for_change}, which the audit views show beneath the row.
     * Capped at the column's 1000 characters. A null user writes a NULL
     * {@code user_id}, the convention for rows no person performed.
     */
    public static void writeAuditEvent(AuditEventDAO dao, int auditTypeId,
                                        UserAccountBean user,
                                        StudyBean study, StudySubjectBean ss,
                                        String actionMessage, String auditTable,
                                        int entityId, String columnName,
                                        String oldValue, String newValue,
                                        String reason) {
        writeAuditEvent(dao, auditTypeId, user, study, ss, actionMessage, auditTable,
                entityId, columnName, oldValue, newValue, reason, null);
    }

    /**
     * As above, recording the visit the row concerns in {@code study_event_id}.
     * The per-study audit log places rows about things that are not themselves
     * study records by that column, such as a file filed to or taken off a
     * visit. Null or not positive leaves it NULL.
     */
    public static void writeAuditEvent(AuditEventDAO dao, int auditTypeId,
                                        UserAccountBean user,
                                        StudyBean study, StudySubjectBean ss,
                                        String actionMessage, String auditTable,
                                        int entityId, String columnName,
                                        String oldValue, String newValue,
                                        String reason, Integer studyEventId) {
        // `study`/`ss` are accepted (and intentionally unused on the
        // write path) so callers can keep their existing signatures —
        // study + subject context is reconstructed at read time by
        // AuditApiController.resolveSubjectLabel from auditTable +
        // entityId. `actionMessage` is similarly preserved as a
        // human-readable hint logged on failure but not persisted —
        // the audit_log_event vocabulary keys on auditTypeId instead.
        DataSource ds = dao.getDs();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value, "
                             + "reason_for_change, study_event_id) "
                             + "VALUES (?, now(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            if (user == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, user.getId());
            }
            ps.setString(3, auditTable);
            ps.setInt(4, entityId);
            ps.setString(5, columnName == null ? "" : columnName);
            ps.setString(6, oldValue == null ? "" : oldValue);
            ps.setString(7, newValue == null ? "" : newValue);
            if (reason == null || reason.isBlank()) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                String r = reason.trim();
                ps.setString(8, r.length() > 1000 ? r.substring(0, 1000) : r);
            }
            if (studyEventId == null || studyEventId <= 0) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(9, studyEventId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Audit-write failed for {} ({}.{} entity {}) — continuing without audit row: {}",
                    actionMessage, auditTable, columnName, entityId, e.getMessage());
        }
    }

    /* Phase E.6 — SaveItemsRequest promoted to a top-level DTO
     * ({@link SaveItemsRequest}). Carries the {@code reasons} map for
     * Reason-For-Change capture (admin-rfc cluster) plus the
     * {@code groups} payload for repeating-group rows (crf-data-types
     * cluster). See SaveItemsRequest.java (reviewer flag in the build
     * playbook). */

    /**
     * Build a single item DTO. The {@code dataType} mapping prefers the
     * response-type (select/checkbox/radio) over the item's storage
     * type, because the SPA picks the input widget from {@code dataType}.
     *
     * <p>Phase E.6: the optional {@code groupOid} arg tags items that
     * belong to a repeating item group; the SPA routes those into the
     * group's row template instead of the top-level values map.
     */
    private static CrfEntryDto.CrfItemDto buildItemDto(ItemBean item, ItemFormMetadataBean ifm,
                                                       String groupOid, String showWhen,
                                                       CrfEntryDto.AutocompleteDto autocomplete) {
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
                /* max */ null,
                groupOid,
                blankToNull(showWhen, null),
                autocomplete
        );
    }

    /**
     * #26 binding store — read terminology bindings (system + fill map) for a
     * version, keyed by item name, so live entry surfaces the autocomplete +
     * auto-fills sibling cells. Best-effort: a missing table or SQL failure
     * logs at WARN and yields an empty map (fields stay plain).
     */
    private Map<String, CrfEntryDto.AutocompleteDto> loadTerminologyByItemName(int crfVersionId) {
        Map<String, CrfEntryDto.AutocompleteDto> out = new HashMap<>();
        final String sql = "SELECT item_name, code_system, fill_map "
                + "FROM crf_item_terminology WHERE crf_version_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String system = rs.getString(2);
                    String fillJson = rs.getString(3);
                    if (name == null || name.isBlank() || system == null || system.isBlank()) continue;
                    List<CrfEntryDto.AutocompleteFillDto> fills = new ArrayList<>();
                    if (fillJson != null && !fillJson.isBlank()) {
                        try {
                            fills = TERMINOLOGY_JSON.readValue(fillJson,
                                    TERMINOLOGY_JSON.getTypeFactory().constructCollectionType(
                                            List.class, CrfEntryDto.AutocompleteFillDto.class));
                        } catch (Exception e) {
                            String logItemName = name.replace('\r', ' ').replace('\n', ' ');
                            LOG.warn("loadTerminologyByItemName: crf_version {} item {} has malformed fill_map JSON; using system-only binding.",
                                    crfVersionId, logItemName);
                        }
                    }
                    out.put(name, new CrfEntryDto.AutocompleteDto(system, fills));
                }
            }
        } catch (SQLException ex) {
            LOG.warn("loadTerminologyByItemName: crf_version {} — skipping bindings ({}).",
                    crfVersionId, ex.getMessage());
        }
        return out;
    }

    /**
     * Phase E.6 polish-runtime — load conditional-display rules for every
     * item on a CRF version, encoded into the stringified-JSON wire form
     * the SPA's runtime expects.
     *
     * <p>The legacy schema stores show-when rules in {@code scd_item_metadata}
     * (one row per controlled item) with three columns:
     * <ul>
     *   <li>{@code control_item_name} — OID of the source item;</li>
     *   <li>{@code option_value} — the literal value that triggers visibility;</li>
     *   <li>{@code message} — operator-facing hint (ignored on the wire).</li>
     * </ul>
     * The legacy comparator is always equality (=), so we emit
     * {@code "=="} on the wire and leave {@code !=} / {@code &lt;} / etc.
     * for SPA-authored items that would JSON-encode the comparator
     * explicitly (mixed wire format — see {@link CrfEntryDto.CrfItemDto}).
     *
     * <p>SPA-authored items that already carry a JSON show-when string in
     * the legacy {@code message} column (i.e. operators who switched to
     * the structured wizard) are surfaced as-is via a separate code path
     * once the wizard persists JSON natively; for now the controller
     * always synthesises the JSON from the legacy triple.
     *
     * <p>Best-effort: a missing table or a SQL failure logs at WARN and
     * returns an empty map — visibility defaults to "always show", which
     * is the legacy behaviour anyway.
     *
     * @return immutable map keyed by item_id; value is the
     *         stringified-JSON {@code {"sourceItemOid":...,"comparator":"==","literal":...}}
     */
    private Map<Integer, String> loadShowWhenByItemId(int crfVersionId) {
        Map<Integer, String> out = new HashMap<>();
        final String sql =
                "SELECT ifm.item_id, scd.control_item_name, scd.option_value " +
                "FROM scd_item_metadata scd " +
                "JOIN item_form_metadata ifm " +
                "  ON ifm.item_form_metadata_id = scd.scd_item_form_metadata_id " +
                "WHERE ifm.crf_version_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt(1);
                    String sourceOid = rs.getString(2);
                    String literal = rs.getString(3);
                    if (sourceOid == null || sourceOid.isBlank()) continue;
                    out.put(itemId, encodeShowWhenJson(sourceOid, "==",
                            literal == null ? "" : literal));
                }
            }
        } catch (SQLException ex) {
            LOG.warn("loadShowWhenByItemId: crf_version {} — skipping show-when ({}). "
                    + "Items will fall back to always-shown.", crfVersionId, ex.getMessage());
        }
        return out;
    }

    /**
     * Encode a (sourceOid, comparator, literal) triple as the JSON
     * shape the SPA renderer parses. Inline rather than depending on
     * Jackson — the payload is tiny and escaping is well-defined.
     */
    private static String encodeShowWhenJson(String sourceOid, String comparator, String literal) {
        return "{\"sourceItemOid\":\"" + jsonEscape(sourceOid)
                + "\",\"comparator\":\"" + jsonEscape(comparator)
                + "\",\"literal\":\"" + jsonEscape(literal) + "\"}";
    }

    /**
     * Phase E.6 polish-runtime — keyed-by-item-OID variant of
     * {@link #loadShowWhenByItemId} used by the save path so we can
     * filter the incoming values map directly without resolving each
     * OID back to an item_id.
     */
    private Map<String, String> loadShowWhenByItemOid(int crfVersionId) {
        Map<String, String> out = new HashMap<>();
        final String sql =
                "SELECT i.oid, scd.control_item_name, scd.option_value " +
                "FROM scd_item_metadata scd " +
                "JOIN item_form_metadata ifm " +
                "  ON ifm.item_form_metadata_id = scd.scd_item_form_metadata_id " +
                "JOIN item i " +
                "  ON i.item_id = ifm.item_id " +
                "WHERE ifm.crf_version_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String oid = rs.getString(1);
                    String sourceOid = rs.getString(2);
                    String literal = rs.getString(3);
                    if (oid == null || oid.isBlank()) continue;
                    if (sourceOid == null || sourceOid.isBlank()) continue;
                    out.put(oid, encodeShowWhenJson(sourceOid, "==",
                            literal == null ? "" : literal));
                }
            }
        } catch (SQLException ex) {
            LOG.warn("loadShowWhenByItemOid: crf_version {} — falling back to no-filter ({})",
                    crfVersionId, ex.getMessage());
        }
        return out;
    }

    /**
     * Phase E.6 polish-runtime — strip out items whose show-when rule
     * resolves to {@code false} given the supplied values. Always-shown
     * items (no rule, blank rule, dangling source) pass through unchanged.
     *
     * <p>The matcher only supports the legacy equality comparator
     * (string-equal). SPA-authored items with structured comparators
     * (!=, &lt;, &gt;=, etc.) still surface their JSON on the wire; the
     * server-side filter conservatively keeps them visible — the SPA's
     * client-side evaluator is the authority for the non-equality cases.
     * This matches the "the SPA already filters" rationale: the backend
     * filter is a guard against malicious or buggy direct API callers,
     * not the source of truth.
     */
    static Map<String, Object> filterByVisibility(Map<String, Object> values,
                                                  Map<String, String> showWhenByItemOid) {
        if (values == null || values.isEmpty()) return Map.of();
        if (showWhenByItemOid == null || showWhenByItemOid.isEmpty()) {
            return values;
        }
        Map<String, Object> out = new LinkedHashMap<>(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String oid = entry.getKey();
            String rule = showWhenByItemOid.get(oid);
            if (rule == null) {
                out.put(oid, entry.getValue());
                continue;
            }
            // Parse the legacy JSON triple. If parsing fails (e.g.
            // operator stored a raw "item_X eq Y" string), default to
            // visible — the SPA evaluator decides for those.
            String sourceOid = extractJsonField(rule, "sourceItemOid");
            String comparator = extractJsonField(rule, "comparator");
            String literal = extractJsonField(rule, "literal");
            if (sourceOid == null) {
                out.put(oid, entry.getValue());
                continue;
            }
            if (!"==".equals(comparator)) {
                // Non-equality comparators are out of scope for the
                // server filter (see Javadoc). Keep visible.
                out.put(oid, entry.getValue());
                continue;
            }
            Object sourceValue = values.get(sourceOid);
            String sourceStr = sourceValue == null ? "" : String.valueOf(sourceValue);
            if (sourceStr.equals(literal == null ? "" : literal)) {
                out.put(oid, entry.getValue());
            }
            // else: hidden — drop
        }
        return out;
    }

    /**
     * Tiny JSON field extractor for the show-when triple. Avoids pulling
     * in Jackson at this layer; the encoded shape is fixed.
     *
     * <p>Returns {@code null} when the field is absent or the input is
     * not the expected JSON shape — callers fall back to "visible".
     */
    static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{")) return null;
        String needle = "\"" + field + "\":\"";
        int from = trimmed.indexOf(needle);
        if (from < 0) return null;
        int valueStart = from + needle.length();
        // Walk until unescaped closing quote.
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '\\' && i + 1 < trimmed.length()) {
                char next = trimmed.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    default: sb.append(ch); break;
                }
                continue;
            }
            if (ch == '"') return sb.toString();
            sb.append(ch);
        }
        return null;
    }

    /** Minimal JSON string escape — covers the legal characters in OIDs +
     *  values without pulling in Jackson at this layer. */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /** Resolve an item's SPA dataType when emitting values. Skip the
     *  groupOid lookup — only the response-type / storage-type mapping
     *  matters for the value's wire shape. */
    private static String findDataType(ItemBean item, Map<Integer, ItemFormMetadataBean> ifmByItemId) {
        ItemFormMetadataBean ifm = ifmByItemId.get(item.getId());
        ResponseType rt = null;
        if (ifm != null && ifm.getResponseSet() != null) {
            rt = ResponseType.get(ifm.getResponseSet().getResponseTypeId());
        }
        return mapDataType(rt, item.getItemDataTypeId());
    }

    /**
     * Phase E.6: parse the persisted {@code item_data.value} into the
     * shape the SPA expects on the wire. The DB stores a single string;
     * select-multi values are comma-joined (the legacy CRF Data Entry
     * convention). All other data types are passed through as-is —
     * Jackson serialises them as JSON strings and the SPA's per-item
     * input bindings cast back to the expected primitive.
     */
    private static Object parseStoredValue(String stored, String dataType) {
        if (stored == null) return "";
        if (!"select-multi".equals(dataType)) return stored;
        if (stored.isBlank()) return new ArrayList<String>();
        // Comma-joined per the legacy DataEntryServlet convention.
        // Tokens are trimmed; empty tokens are dropped so a trailing
        // comma doesn't round-trip as a phantom empty option.
        String[] parts = stored.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /**
     * Phase E.6: invert {@link #parseStoredValue} for the saveItems path.
     * Arrays land as comma-joined strings so the legacy item_data column
     * keeps its existing format and the audit-log diff stays legible.
     */
    private static String serialiseValueForStorage(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object o : list) {
                if (o == null) continue;
                String token = String.valueOf(o).trim();
                if (token.isEmpty()) continue;
                if (!first) sb.append(',');
                sb.append(token);
                first = false;
            }
            return sb.toString();
        }
        return String.valueOf(value);
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
    /* Phase E.6 — file-upload caps sourced from datainfo.properties.      */
    /*                                                                    */
    /* The SPA caches these in the CrfEntryDto payload so the dropzone    */
    /* can show the cap in its helper text + pre-validate before the      */
    /* multipart POST. The server still enforces them; the SPA copy is    */
    /* a UX nicety.                                                       */
    /*                                                                    */
    /* Keys (added to docker/config/datainfo.properties):                 */
    /*   crf.file.maxBytes      default 50 MiB                            */
    /*   crf.file.extensions    default "pdf,jpg,jpeg,png,tif,tiff"       */
    /* ------------------------------------------------------------------ */

    static final long DEFAULT_CRF_FILE_MAX_BYTES = 52_428_800L; // 50 MiB
    static final String DEFAULT_CRF_FILE_EXTENSIONS = "pdf,jpg,jpeg,png,tif,tiff";

    private static long fileMaxBytes() {
        try {
            String raw = at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources
                    .getField("crf.file.maxBytes");
            if (raw == null || raw.isBlank()) return DEFAULT_CRF_FILE_MAX_BYTES;
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_CRF_FILE_MAX_BYTES;
        } catch (Exception e) {
            return DEFAULT_CRF_FILE_MAX_BYTES;
        }
    }

    private static String fileExtensionsAllowlist() {
        try {
            String raw = at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources
                    .getField("crf.file.extensions");
            if (raw == null || raw.isBlank()) return DEFAULT_CRF_FILE_EXTENSIONS;
            return raw.trim();
        } catch (Exception e) {
            return DEFAULT_CRF_FILE_EXTENSIONS;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Phase E.6 — repeating item group row create + delete endpoints.    */
    /*                                                                    */
    /* The SPA shows an "Add row" button per repeating group and a small  */
    /* trash icon on each row. The endpoints fan out to per-item upserts  */
    /* with the matching ordinal, just like saveItems does — the only    */
    /* difference is that create allocates the next ordinal and delete   */
    /* nukes every item_data row with that (event_crf, group, ordinal)   */
    /* tuple. Both write audit events for the M10 audit log view.        */
    /* ------------------------------------------------------------------ */

    /**
     * Allocate a new row inside a repeating item group. The response
     * carries the newly-allocated ordinal so the SPA can hydrate its
     * row template without re-fetching the whole CRF.
     *
     * <p>Returns 409 if the group's {@code repeatMax} is already met.
     */
    @PostMapping("/{id:[0-9]+}/groups/{groupOid}/rows")
    public ResponseEntity<?> addGroupRow(@PathVariable("id") int eventCrfId,
                                         @PathVariable("groupOid") String groupOid,
                                         HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No active study bound."));
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "adding repeating-group rows");
        if (roleRefusal != null) {
            return roleRefusal;
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "adding a row");
        if (closed != null) {
            return closed;
        }

        ItemGroupDAO igDAO = new ItemGroupDAO(dataSource);
        ItemGroupBean grp = igDAO.findByOid(groupOid);
        if (grp == null || grp.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item_group with oid " + groupOid));
        }

        ItemGroupMetadataDAO igmDAO = new ItemGroupMetadataDAO(dataSource);
        ArrayList<ItemGroupMetadataBean> metas =
                igmDAO.findMetaByGroupAndCrfVersion(grp.getId(), ecb.getCRFVersionId());
        if (metas == null || metas.isEmpty() || !metas.get(0).isRepeatingGroup()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "item_group " + groupOid + " is not a repeating group"));
        }
        ItemGroupMetadataBean grpMeta = metas.get(0);
        int repeatMax = (grpMeta.getRepeatMax() != null && grpMeta.getRepeatMax() > 0)
                ? grpMeta.getRepeatMax() : 40;

        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        int maxOrd = idDAO.getMaxOrdinalForGroupByGroupOID(groupOid, ecb.getId());
        int nextOrd = Math.max(0, maxOrd) + 1;
        if (nextOrd > repeatMax) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Repeating group at repeatMax (" + repeatMax + ")",
                    "code", "REPEAT_MAX_REACHED"));
        }

        // Write audit row for the create. We deliberately do NOT
        // pre-seed item_data with empty values — the next saveItems
        // call will insert them when the user types. This keeps the
        // DB free of orphan empty rows when a user clicks Add then
        // cancels via deleteGroupRow.
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        writeAuditEvent(auditDAO, /* type=1 Item value updated */ 1,
                currentUser, currentStudy, ss,
                "item_group_row_create", "item_data", ecb.getId(),
                groupOid + "[ordinal]", "", String.valueOf(nextOrd));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupOid", groupOid);
        out.put("rowOrdinal", nextOrd);
        out.put("values", new LinkedHashMap<>());
        return ResponseEntity.ok(out);
    }

    /**
     * Delete every item_data row tied to (event_crf, item_group, ordinal).
     * The SPA calls this when the user clicks the row's trash icon. A row
     * that is already deleted is left as it is: deleting it again changes
     * no data, so it writes no audit row and keeps an SDV verification.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id:[0-9]+}/groups/{groupOid}/rows/{ordinal:[0-9]+}")
    public ResponseEntity<?> deleteGroupRow(@PathVariable("id") int eventCrfId,
                                            @PathVariable("groupOid") String groupOid,
                                            @PathVariable("ordinal") int ordinal,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No active study bound."));
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "deleting repeating-group rows");
        if (roleRefusal != null) {
            return roleRefusal;
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "deleting a row");
        if (closed != null) {
            return closed;
        }

        ItemGroupDAO igDAO = new ItemGroupDAO(dataSource);
        ItemGroupBean grp = igDAO.findByOid(groupOid);
        if (grp == null || grp.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item_group with oid " + groupOid));
        }

        // Walk every item in the group; soft-delete each item_data row at
        // the matching ordinal. We rely on Status.DELETED rather than a
        // hard DELETE so the audit trail survives and partial saves
        // remain recoverable through the legacy admin path.
        ItemFormMetadataDAO ifmDAO = new ItemFormMetadataDAO(dataSource);
        List<ItemFormMetadataBean> ifms;
        try {
            ifms = ifmDAO.findAllByCRFVersionId(ecb.getCRFVersionId());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Schema load failed: " + e.getMessage()));
        }
        ItemDAO itemDAO = new ItemDAO(dataSource);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        int deleted = 0;
        for (ItemFormMetadataBean ifm : ifms) {
            ItemGroupBean itemGrp;
            try {
                itemGrp = igDAO.findGroupByItemIdCrfVersionId(ifm.getItemId(), ecb.getCRFVersionId());
            } catch (Exception e) { continue; }
            if (itemGrp == null || itemGrp.getId() != grp.getId()) continue;
            ItemDataBean idb = idDAO.findByItemIdAndEventCRFIdAndOrdinal(
                    ifm.getItemId(), ecb.getId(), ordinal);
            if (idb == null || idb.getId() == 0) continue;
            if (Status.DELETED.equals(idb.getStatus()) || Status.AUTO_DELETED.equals(idb.getStatus())) {
                continue;
            }
            String oldValue = idb.getValue() == null ? "" : idb.getValue();
            idb.setStatus(Status.DELETED);
            idb.setUpdater(currentUser);
            idb.setUpdaterId(currentUser.getId());
            idDAO.update(idb);
            ItemBean ib = (ItemBean) itemDAO.findByPK(ifm.getItemId());
            String itemOid = ib != null ? ib.getOid() : "";
            writeAuditEvent(auditDAO, /* type=13 Item value deleted */ 13,
                    currentUser, currentStudy, ss,
                    "item_group_row_delete", "item_data", idb.getId(),
                    itemOid + "[" + ordinal + "]", oldValue, "");
            deleted++;
        }
        if (deleted > 0) {
            SdvRevocation.revokeIfVerified(ecb, currentUser, eventCrfDAO);
        }

        return ResponseEntity.ok(Map.of(
                "groupOid", groupOid,
                "rowOrdinal", ordinal,
                "itemDataRowsDeleted", deleted
        ));
    }

    // On reopen, rewind COMPLETED back to DATA_ENTRY_STARTED for symmetry.
    // "Required" matches the legacy edc.required_crf flag; optional CRFs
    // don't gate the status, their state is operator discretion. SIGNED /
    // LOCKED visits are terminal — left alone in both helpers.
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

    @SuppressWarnings("unused") // companion to the removed auto-cascade; retained for a future per-study opt-in
    private boolean allRequiredEventCrfsComplete(StudyEventBean ev) {
        EventDefinitionCRFDAO edcDAO = new EventDefinitionCRFDAO(dataSource);
        List<EventDefinitionCRFBean> edcs =
                edcDAO.findAllParentsByEventDefinitionId(ev.getStudyEventDefinitionId());
        if (edcs == null || edcs.isEmpty()) return false;
        EventCRFDAO ecDAO = new EventCRFDAO(dataSource);
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

    /* ================================================================== */
    /* Phase E.6 PR (b) -- file-upload item endpoints.                    */
    /*                                                                    */
    /* The SPA's FileUploadInput widget hits three endpoints:             */
    /*                                                                    */
    /*   POST   /eventCrfs/{id}/items/{itemOid}/file    (multipart)       */
    /*          -> CrfFileUploadResponseDto                               */
    /*   GET    /eventCrfs/{id}/items/{itemOid}/file?rowOrdinal=N         */
    /*          -> application/octet-stream                               */
    /*   DELETE /eventCrfs/{id}/items/{itemOid}/file?rowOrdinal=N -> 204  */
    /*                                                                    */
    /* The legacy storage convention (absolute path string written into   */
    /* item_data.value) is preserved -- the SPA renders the filename      */
    /* component back from the stored ref, the audit trail keeps the      */
    /* path, and the legacy /pages/UploadFileServlet read-back keeps      */
    /* working for backwards compatibility.                               */
    /* ================================================================== */

    @PostMapping(path = "/{id:[0-9]+}/items/{itemOid}/file",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadItemFile(@PathVariable("id") int eventCrfId,
                                            @PathVariable("itemOid") String itemOid,
                                            @RequestPart("file") MultipartFile file,
                                            @RequestParam(value = "rowOrdinal", defaultValue = "1") int rowOrdinal,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No active study bound."));
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "uploading CRF files");
        if (roleRefusal != null) {
            return roleRefusal;
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "file part is required"));
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "uploading a file");
        if (closed != null) {
            return closed;
        }

        if (!fileStorageService.checkSize(file)) {
            return ResponseEntity.status(413).body(Map.of(
                    "message", "File exceeds cap of " + fileStorageService.maxBytes() + " bytes",
                    "code", "FILE_TOO_LARGE"));
        }
        if (fileStorageService.isPotentiallyExecutable(file)
                || !fileStorageService.checkExtension(file)) {
            return ResponseEntity.status(415).body(Map.of(
                    "message", "File extension is not in the allowlist: "
                            + fileStorageService.allowedExtensionsCsv(),
                    "code", "FILE_BAD_EXTENSION"));
        }

        ItemDAO itemDAO = new ItemDAO(dataSource);
        ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
        if (candidates == null || candidates.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item with oid " + itemOid));
        }
        ItemBean item = candidates.get(0);

        // Build the on-disk write path. crfOid + crfVersionOid let the
        // legacy admin tools recover an uploaded file even after the
        // item_data row is soft-deleted.
        CRFVersionDAO cvDao = new CRFVersionDAO(dataSource);
        CRFVersionBean cv = (CRFVersionBean) cvDao.findByPK(ecb.getCRFVersionId());
        CRFDAO crfDao = new CRFDAO(dataSource);
        CRFBean crf = cv != null ? (CRFBean) crfDao.findByPK(cv.getCrfId()) : null;
        String crfOid = crf != null && crf.getOid() != null ? crf.getOid() : "CRF" + (crf == null ? 0 : crf.getId());
        String crfVersionOid = cv != null && cv.getOid() != null ? cv.getOid() : "CV" + (cv == null ? 0 : cv.getId());

        Path target;
        try {
            target = fileStorageService.store(crfOid, crfVersionOid, file);
        } catch (IOException ioEx) {
            LOG.error("Failed to persist upload for event_crf {} item {}: {}",
                    eventCrfId, itemOid, ioEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to persist file: " + ioEx.getMessage()));
        }
        String absolutePath = target.toString();
        String storedFilename = target.getFileName().toString();

        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        int ordinal = Math.max(1, rowOrdinal);
        ItemDataBean existing = idDAO.findByItemIdAndEventCRFIdAndOrdinal(
                item.getId(), ecb.getId(), ordinal);
        String oldValue = "";
        boolean isCreate;
        if (existing != null && existing.getId() > 0) {
            oldValue = existing.getValue() == null ? "" : existing.getValue();
            // Best-effort: drop the previous on-disk blob so we don't
            // accumulate dead bytes on the filesystem when a user
            // replaces an uploaded file.
            if (!oldValue.isBlank() && !oldValue.equals(absolutePath)) {
                fileStorageService.delete(oldValue);
            }
            existing.setValue(absolutePath);
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
            idb.setValue(absolutePath);
            idb.setOrdinal(ordinal);
            idb.setOwnerId(currentUser.getId());
            idb.setOwner(currentUser);
            idb.setStatus(Status.AVAILABLE);
            idb.setOldStatus(Status.AVAILABLE);
            idb.setDeleted(false);
            idDAO.create(idb);
            existing = idb;
            isCreate = true;
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        writeAuditEvent(auditDAO, /* type=1 Item value updated */ 1,
                currentUser, currentStudy, ss,
                isCreate ? "item_data_file_upload" : "item_data_file_replace",
                "item_data", existing.getId(),
                itemOid + "[" + ordinal + "]", oldValue, absolutePath);

        SdvRevocation.revokeIfVerified(ecb, currentUser, eventCrfDAO);
        eventCrfDAO.touch(ecb.getId(), currentUser.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemOid", itemOid);
        out.put("rowOrdinal", ordinal);
        out.put("filename", storedFilename);
        out.put("bytes", file.getSize());
        out.put("contentType", file.getContentType());
        out.put("storedPath", absolutePath);
        out.put("lastSavedAt", Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    @org.springframework.web.bind.annotation.GetMapping(
            path = "/{id:[0-9]+}/items/{itemOid}/file")
    public ResponseEntity<?> downloadItemFile(@PathVariable("id") int eventCrfId,
                                              @PathVariable("itemOid") String itemOid,
                                              @RequestParam(value = "rowOrdinal", defaultValue = "1") int rowOrdinal,
                                              HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No active study bound."));
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

        ItemDAO itemDAO = new ItemDAO(dataSource);
        ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
        if (candidates == null || candidates.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item with oid " + itemOid));
        }
        ItemBean item = candidates.get(0);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        ItemDataBean idb = idDAO.findByItemIdAndEventCRFIdAndOrdinal(
                item.getId(), ecb.getId(), Math.max(1, rowOrdinal));
        if (idb == null || idb.getId() == 0
                || idb.getValue() == null || idb.getValue().isBlank()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No file attached for item " + itemOid));
        }
        String absolutePath = idb.getValue();
        Path target = Paths.get(absolutePath).toAbsolutePath().normalize();
        Path root = fileStorageService.baseDir().toAbsolutePath().normalize();
        if (!target.startsWith(root) || !Files.isReadable(target)) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "Attached file no longer accessible"));
        }
        String filename = target.getFileName().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment()
                        .filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(target));
    }

    @org.springframework.web.bind.annotation.DeleteMapping(
            path = "/{id:[0-9]+}/items/{itemOid}/file")
    public ResponseEntity<?> deleteItemFile(@PathVariable("id") int eventCrfId,
                                            @PathVariable("itemOid") String itemOid,
                                            @RequestParam(value = "rowOrdinal", defaultValue = "1") int rowOrdinal,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No active study bound."));
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "deleting CRF files");
        if (roleRefusal != null) {
            return roleRefusal;
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "deleting a file");
        if (closed != null) {
            return closed;
        }

        ItemDAO itemDAO = new ItemDAO(dataSource);
        ArrayList<ItemBean> candidates = itemDAO.findByOid(itemOid);
        if (candidates == null || candidates.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item with oid " + itemOid));
        }
        ItemBean item = candidates.get(0);
        ItemDataDAO idDAO = new ItemDataDAO(dataSource);
        ItemDataBean idb = idDAO.findByItemIdAndEventCRFIdAndOrdinal(
                item.getId(), ecb.getId(), Math.max(1, rowOrdinal));
        if (idb == null || idb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No file attached for item " + itemOid));
        }
        String oldValue = idb.getValue() == null ? "" : idb.getValue();
        // Drop the on-disk blob first; if that fails the value stays
        // pointing at a now-missing path -- safer than the inverse
        // (orphan bytes after a DB success).
        if (!oldValue.isBlank()) fileStorageService.delete(oldValue);

        idb.setValue("");
        idb.setStatus(Status.DELETED);
        idb.setUpdater(currentUser);
        idb.setUpdaterId(currentUser.getId());
        idDAO.update(idb);

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        writeAuditEvent(auditDAO, /* type=13 Item value deleted */ 13,
                currentUser, currentStudy, ss,
                "item_data_file_delete", "item_data", idb.getId(),
                itemOid + "[" + Math.max(1, rowOrdinal) + "]", oldValue, "");

        if (!oldValue.isEmpty()) {
            SdvRevocation.revokeIfVerified(ecb, currentUser, eventCrfDAO);
        }
        eventCrfDAO.touch(ecb.getId(), currentUser.getId());

        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* Phase E.6 dde — blind double-data-entry endpoints + helpers.       */
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
        EventCRFDAO ddeEventCrfDAO = new EventCRFDAO(dataSource);
        EventCRFBean ecb = ddeEventCrfDAO.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ddeSsDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ddeSsDAO.findByPK(ecb.getStudySubjectId());
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
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "entering CRF data");
        if (roleRefusal != null) {
            return roleRefusal;
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "committing the second pass");
        if (closed != null) {
            return closed;
        }
        if (!isDoubleEntryEnabled(ecb)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "event_crf " + eventCrfId + " is not DDE-enabled"));
        }
        if (ecb.getDateCompleted() == null) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Pass 1 (IDE) is not yet complete — cannot commit Pass 2"));
        }
        if (ecb.getOwnerId() > 0 && ecb.getOwnerId() == currentUser.getId()) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "You entered Pass 1 — a different clerk must perform Pass 2"));
        }
        // Phase B2 (2026-06-10) — failure-audit wrap. DDE Pass-2 commit
        // is the load-bearing GxP write for double-data-entry studies;
        // a silent failure here means the reconciliation step left
        // item_data + audit_log out of sync. The wrap captures any
        // Throwable into an OPERATION_FAILED audit row keyed by
        // event_crf id before the 500 propagates to the SPA.
        final EventCRFBean ecbRef = ecb;
        final StudySubjectBean ssRef = ss;
        final StudyBean studyRef = currentStudy;
        final UserAccountBean userRef = currentUser;
        final Map<String, Object> valuesRef = body.values();
        final String reqId = MDC.get("reqId");
        try {
            return FailureAuditTemplate.runOrAudit(
                    new AuditEventDAO(dataSource),
                    currentUser.getId(),
                    "event_crf",
                    ecb.getId(),
                    "DDE_COMMIT",
                    reqId,
                    () -> {
                        DdeCommitResponse resp = ddeService().commitPass2(
                                ecbRef, ssRef, studyRef, userRef, valuesRef);
                        return ResponseEntity.ok(resp);
                    });
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
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(
                dataSource, currentStudy, ss, null, ecb, "resolving a double-data-entry conflict");
        if (closed != null) {
            return closed;
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
     * its event definition CRF has {@code double_entry=false}
     * ({@link #isDoubleEntryEnabled}). Heuristics:
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

    /**
     * True when the event CRF's event definition CRF has
     * {@code double_entry=true}: for a subject at a site, the site's own
     * row where the site overrides the definition, else the study's. This
     * is the row legacy data entry reads, and SDV (whose completeness test
     * needs the second pass where this says so).
     */
    private boolean isDoubleEntryEnabled(EventCRFBean ecb) {
        try {
            StudySubjectBean ss = (StudySubjectBean) new StudySubjectDAO(dataSource)
                    .findByPK(ecb.getStudySubjectId());
            if (ss == null || ss.getId() == 0) return false;
            StudyBean subjectStudy = (StudyBean) new StudyDAO(dataSource).findByPK(ss.getStudyId());
            if (subjectStudy == null || subjectStudy.getId() == 0) return false;
            EventDefinitionCRFBean edc = new EventDefinitionCRFDAO(dataSource)
                    .findByStudyEventIdAndCRFVersionId(subjectStudy,
                            ecb.getStudyEventId(), ecb.getCRFVersionId());
            return edc != null && edc.getId() > 0 && edc.isDoubleEntry();
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

    /* ====================================================================== */
    /* POST /event-crfs/{id}:autoPopulateRetinal — nAMD Slice 3              */
    /* ====================================================================== */

    /**
     * Copy AI fluid metrics from every completed
     * {@code retinal_inference_result} linked to {@code eventCrfId}
     * into the matching CRF item_data rows. Idempotent — re-running
     * updates rows from the same source job; operator-entered rows
     * are never overwritten.
     *
     * <p>Response body carries {@link RetinalAutoPopulateResponse}
     * with the per-run counts so the SPA can render a toast
     * ("Populated 4 items from 1 job").
     *
     * <p>401 when not authenticated, 404 when the event_crf is
     * outside the caller's visibility, 200 on success (including
     * the "no jobs to populate" case — the SPA renders that as
     * "Nothing to populate yet" rather than as an error).
     */
    @org.springframework.web.bind.annotation.PostMapping(path = "/{id:[0-9]+}:autoPopulateRetinal",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> autoPopulateRetinal(@PathVariable("id") int eventCrfId,
                                                 HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        ResponseEntity<?> roleRefusal = ClinicalWriteAuthorization.refuseUnlessMayEnterData(
                session, "entering CRF data");
        if (roleRefusal != null) {
            return roleRefusal;
        }
        // Reuse the existing visibility check from this controller's
        // getEventCrf — if the user can't see the row, the populate
        // call must 404 too.
        ResponseEntity<?> visibility = guardEventCrfVisibility(eventCrfId, currentUser, session);
        if (visibility != null) return visibility;
        EventCRFBean target = new EventCRFDAO(dataSource).findByPK(eventCrfId);
        ResponseEntity<?> closed = ClinicalRecordGuard.refuseIfClosed(dataSource,
                (StudyBean) session.getAttribute("study"),
                (StudySubjectBean) new StudySubjectDAO(dataSource).findByPK(target.getStudySubjectId()),
                null, target, "populating retinal values");
        if (closed != null) return closed;

        at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator.PopulateResult result;
        try {
            result = retinalAutoPopulator.populateForEventCrf(eventCrfId, currentUser.getId());
        } catch (IllegalStateException isEx) {
            LOG.error("autoPopulateRetinal failed for ecrf={}: {}", eventCrfId, isEx.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to populate retinal values: " + isEx.getMessage()));
        }
        return ResponseEntity.ok(new RetinalAutoPopulateResponse(
                result.eventCrfId(),
                result.jobsProcessed(),
                result.rowsWritten(),
                result.warnings()));
    }

    /**
     * Reuse the visibility logic from {@code getEventCrf} for the
     * auto-populate endpoint without duplicating ~50 LOC of session
     * + DAO walks. Returns null when the caller can see the row,
     * or a ready-to-return ResponseEntity (401 / 404) otherwise.
     */
    private ResponseEntity<?> guardEventCrfVisibility(int eventCrfId,
                                                     UserAccountBean currentUser,
                                                     HttpSession session) {
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        EventCRFBean ecb = ecDao.findByPK(eventCrfId);
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "No event_crf with id " + eventCrfId));
        }
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ecb.getStudySubjectId());
        if (ss == null || ss.getStudyId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "event_crf " + eventCrfId + " has no resolvable study"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        java.util.Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "event_crf " + eventCrfId + " belongs to a study outside your scope"));
        }
        return null;
    }

    /**
     * Response body of {@code POST /event-crfs/{id}:autoPopulateRetinal}.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RetinalAutoPopulateResponse")
    public record RetinalAutoPopulateResponse(
            int eventCrfId,
            int jobsProcessed,
            int rowsWritten,
            java.util.List<String> warnings) {}
}
