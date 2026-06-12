/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.export.CsvWriter;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.discrepancy.DiscrepancyEmailNotifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Phase E.4 M7 — Notes &amp; Discrepancies adapter.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /pages/api/v1/discrepancies?status=…&subjectId=…&assignedTo=…}
 *       — returns the parent-level discrepancy notes for the
 *       session-bound active study. The SPA's discrepancy list view
 *       hydrates from this response and applies its filters
 *       client-side over the result; the server-side filters above
 *       narrow the SQL before it hits the wire.</li>
 *   <li>{@code POST /pages/api/v1/discrepancies} — adds a new
 *       parent-level Query (DiscrepancyNoteType=3) attached to a
 *       given item via {@code subjectId} + {@code itemOid}. The
 *       controller resolves both to the matching {@code item_data}
 *       row inside the active study and stores it as
 *       {@code entity_type=itemData} with the corresponding
 *       {@code entity_id} via the discrepancy-note mapping table.</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> chain-level
 * {@code .anyRequest().hasRole("USER")} gates both endpoints. They
 * additionally require a session-bound active study (returns 400 if
 * absent — the SPA's auth bootstrap is expected to have established
 * one before hitting this view).
 *
 * <p>Mappings:
 * <ul>
 *   <li>DB {@code discrepancy_note_type_id}: 1→{@code failed-validation},
 *       2→{@code annotation}, 3→{@code query}, 4→{@code reason-for-change}.</li>
 *   <li>DB {@code resolution_status_id}: 1→{@code new}, 2→{@code updated},
 *       3→{@code resolution-proposed}, 4→{@code closed},
 *       5→{@code not-applicable}.</li>
 * </ul>
 *
 * <p>The {@code lastActivityAt} returned here is approximated as
 * {@code now() - days} until the audit-trail thread is surfaced
 * separately (M10 — Audit log). That keeps the SPA's "last activity"
 * column sortable without requiring a separate query.
 */
@RestController
@RequestMapping("/api/v1/discrepancies")
@Tag(name = "Discrepancy", description = "Queries & discrepancy notes.")
public class DiscrepancyApiController {

    private static final Logger LOG = LoggerFactory.getLogger(DiscrepancyApiController.class);

    /**
     * Phase E.6 — audit-log event-type id for discrepancy-list CSV
     * exports. Seeded by Liquibase
     * {@code lc-muw-2026-06-06-audit-event-type-discrepancy-export.xml}.
     * Maps to the "admin" variant in {@code AuditApiController.variantForType}.
     */
    static final int AUDIT_TYPE_DISCREPANCY_LOG_EXPORTED = 56;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final DiscrepancyEmailNotifier emailNotifier;

    @Autowired
    public DiscrepancyApiController(@Qualifier("dataSource") DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter,
                                    DiscrepancyEmailNotifier emailNotifier) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.emailNotifier = emailNotifier;
    }

    /**
     * Phase E.6 test-friendly constructor — Phase E.5 MockMvc IT scaffold
     * doesn't have a configured {@link DiscrepancyEmailNotifier} bean.
     * The notifier is optional; null disables email side-effects.
     */
    public DiscrepancyApiController(DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter) {
        this(dataSource, siteVisibilityFilter, null);
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = DiscrepancyNoteDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "status", required = false) String statusFilter,
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "assignedTo", required = false) String assignedToFilter,
            HttpSession session) {

        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        return ResponseEntity.ok(collectFilteredRows(ub, currentStudy, currentRole,
                statusFilter, subjectIdFilter, assignedToFilter));
    }

    /**
     * Phase E.6 — CSV hand-off of the discrepancy list. Mirrors the
     * same filter set as {@link #list} so sponsor / inspector
     * downloads match the on-screen row count. Emits one
     * {@code audit_log_event} row (type 56) per successful download
     * so the GxP audit trail records who took the egress + which
     * filters were active.
     *
     * <p>Output is UTF-8 with a BOM preamble + CRLF line endings + RFC 4180
     * quoting — see {@link CsvWriter} for the byte-level contract.
     *
     * <p>Filename: {@code discrepancies_&lt;studyOid&gt;[_&lt;subject&gt;]_&lt;yyyyMMdd&gt;.csv}.
     * The optional subject segment is added when the request was scoped to
     * a single subject so a sponsor reviewing several per-subject hand-offs
     * can keep them apart on disk.
     */
    @GetMapping("/export.csv")
    @ApiResponse(responseCode = "200",
                 content = @Content(mediaType = "text/csv"))
    public ResponseEntity<?> exportCsv(
            @RequestParam(value = "status", required = false) String statusFilter,
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "assignedTo", required = false) String assignedToFilter,
            HttpSession session) {

        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        List<DiscrepancyNoteDto> rows = collectFilteredRows(ub, currentStudy, currentRole,
                statusFilter, subjectIdFilter, assignedToFilter);

        byte[] csv;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (CsvWriter w = new CsvWriter(buf)) {
                w.writeHeader();
                w.writeRow("ID", "Type", "Status", "Subject", "Item OID",
                        "Description", "Assigned to", "Days open", "Last activity (UTC)");
                for (DiscrepancyNoteDto r : rows) {
                    w.writeRow(
                            nz(r.id()),
                            nz(r.type()),
                            nz(r.status()),
                            nz(r.subjectId()),
                            nz(r.itemOid()),
                            nz(r.description()),
                            nz(r.assignedTo()),
                            String.valueOf(r.daysOpen()),
                            nz(r.lastActivityAt()));
                }
            }
            csv = buf.toByteArray();
        } catch (IOException e) {
            LOG.error("Failed to render discrepancy-export CSV for study_id={}",
                    currentStudy.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to render discrepancy-export CSV: " + e.getMessage()));
        }

        String filterSummary = describeFilters(statusFilter, subjectIdFilter, assignedToFilter, rows.size());
        emitExportAudit(ub.getId(), currentStudy, AUDIT_TYPE_DISCREPANCY_LOG_EXPORTED, filterSummary);

        StringBuilder filename = new StringBuilder("discrepancies_")
                .append(safeOid(currentStudy.getOid()));
        if (subjectIdFilter != null && !subjectIdFilter.isBlank()) {
            filename.append('_').append(safeOid(subjectIdFilter));
        }
        filename.append('_')
                .append(LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE))
                .append(".csv");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDispositionFormData("attachment", filename.toString());
        headers.setContentLength(csv.length);
        return new ResponseEntity<>(csv, headers, 200);
    }

    /**
     * Shared row collection — used by both {@link #list} and
     * {@link #exportCsv}. Walks the SiteVisibilityFilter visible-study
     * set, joins the entity_id chain (item_data → event_crf →
     * study_subject), then applies the status / subject / assignedTo
     * filters server-side so the CSV row count matches the SPA list.
     */
    private List<DiscrepancyNoteDto> collectFilteredRows(
            UserAccountBean ub, StudyBean currentStudy, StudyUserRoleBean currentRole,
            String statusFilter, String subjectIdFilter, String assignedToFilter) {

        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
        // Without fetchMapping the DAO doesn't follow the per-entity-type
        // mapping table (dn_item_data_map etc.), so entity_id stays 0 and
        // the SPA gets blank subjectId / itemOid columns.
        dnDao.setFetchMapping(true);

        // A4 — per-site visibility. The DN DAO has no
        // multi-study-id method, so loop over visible studies and
        // merge. For a top-level Admin/Director this is the
        // unchanged behaviour (single hit on the parent); a Monitor
        // with site-only grants ends up with site-only results.
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        ArrayList<DiscrepancyNoteBean> notes = new ArrayList<>();
        StudyDAO studyDao = new StudyDAO(dataSource);
        for (Integer sid : visibleStudyIds) {
            StudyBean scope = (sid == currentStudy.getId())
                    ? currentStudy
                    : (StudyBean) studyDao.findByPK(sid);
            if (scope == null || scope.getId() == 0) continue;
            ArrayList<DiscrepancyNoteBean> chunk = dnDao.findAllParentsByStudy(scope);
            if (chunk != null) notes.addAll(chunk);
        }

        // Caches to amortise the entity-id walk across repeated lookups.
        Map<Integer, ItemDataBean> itemDataCache = new HashMap<>();
        Map<Integer, EventCRFBean> eventCrfCache = new HashMap<>();
        Map<Integer, StudySubjectBean> studySubjectCache = new HashMap<>();
        Map<Integer, ItemBean> itemCache = new HashMap<>();
        Map<Integer, StudyEventBean> studyEventCache = new HashMap<>();
        Map<Integer, StudyEventDefinitionBean> seDefCache = new HashMap<>();

        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        ItemDAO itemDao = new ItemDAO(dataSource);
        StudyEventDAO studyEventDao = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO seDefDao = new StudyEventDefinitionDAO(dataSource);

        List<DiscrepancyNoteDto> out = new ArrayList<>(notes.size());
        for (DiscrepancyNoteBean n : notes) {
            String subjectLabel = "";
            String itemOid = "";
            // notes-deeplink (2026-06-11) — additional context the SPA row needs
            // to render the value the DM is asking about + deep-link to the CRF.
            String itemLabel = null;
            String itemValue = null;
            String eventCrfOid = null;
            String eventName = null;

            if ("itemData".equalsIgnoreCase(n.getEntityType()) && n.getEntityId() > 0) {
                ItemDataBean idb = itemDataCache.computeIfAbsent(n.getEntityId(),
                        id -> (ItemDataBean) itemDataDao.findByPK(id));
                if (idb != null && idb.getId() > 0) {
                    itemValue = idb.getValue();
                    ItemBean item = itemCache.computeIfAbsent(idb.getItemId(),
                            id -> (ItemBean) itemDao.findByPK(id));
                    if (item != null && item.getId() > 0) {
                        itemOid = nullToEmpty(item.getOid());
                        itemLabel = pickItemLabel(item);
                    }
                    EventCRFBean ec = eventCrfCache.computeIfAbsent(idb.getEventCRFId(),
                            id -> (EventCRFBean) eventCrfDao.findByPK(id));
                    if (ec != null && ec.getId() > 0) {
                        eventCrfOid = String.valueOf(ec.getId());
                        StudySubjectBean ss = studySubjectCache.computeIfAbsent(ec.getStudySubjectId(),
                                id -> (StudySubjectBean) studySubjectDao.findByPK(id));
                        if (ss != null && ss.getId() > 0) {
                            subjectLabel = nullToEmpty(ss.getLabel());
                        }
                        // event_crf → study_event → study_event_definition.name
                        StudyEventBean se = studyEventCache.computeIfAbsent(ec.getStudyEventId(),
                                id -> (StudyEventBean) studyEventDao.findByPK(id));
                        if (se != null && se.getId() > 0) {
                            StudyEventDefinitionBean def = seDefCache.computeIfAbsent(
                                    se.getStudyEventDefinitionId(),
                                    id -> (StudyEventDefinitionBean) seDefDao.findByPK(id));
                            if (def != null && def.getId() > 0) {
                                eventName = def.getName();
                            }
                        }
                    }
                }
            }

            String statusStr = statusToSpa(n.getResolutionStatusId());
            String typeStr = typeToSpa(n.getDiscrepancyNoteTypeId());
            String assignedTo = (n.getAssignedUser() != null && n.getAssignedUser().getId() > 0)
                    ? n.getAssignedUser().getName() : null;

            // Apply server-side filters before serialising.
            if (statusFilter != null && !statusFilter.isBlank() && !statusFilter.equalsIgnoreCase(statusStr)) {
                continue;
            }
            if (subjectIdFilter != null && !subjectIdFilter.isBlank()
                    && !subjectIdFilter.equalsIgnoreCase(subjectLabel)) {
                continue;
            }
            if (assignedToFilter != null && !assignedToFilter.isBlank()) {
                if (assignedTo == null || !assignedToFilter.equalsIgnoreCase(assignedTo)) {
                    continue;
                }
            }

            int daysOpen = Math.max(n.getDays(), 0);
            String lastActivityAt = Instant.now()
                    .minus(daysOpen, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toString();

            out.add(new DiscrepancyNoteDto(
                    String.valueOf(n.getId()),
                    typeStr,
                    statusStr,
                    subjectLabel,
                    itemOid,
                    nullToEmpty(n.getDescription()),
                    assignedTo,
                    daysOpen,
                    lastActivityAt,
                    List.of(),
                    itemLabel,
                    itemValue,
                    eventCrfOid,
                    eventName));
        }

        return out;
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = DiscrepancyNoteDto.class)))
    public ResponseEntity<?> add(@RequestBody AddQueryRequest body, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (body == null || body.description() == null || body.description().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'description' is required"));
        }
        if (body.subjectId() == null || body.subjectId().isBlank()
                || body.itemOid() == null || body.itemOid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "'subjectId' and 'itemOid' are required to attach the query to a data point"));
        }

        // Phase E.6 dn — parse the optional eventCrfOid up-front so a
        // bad shape (non-numeric) is rejected with 400 before any DB
        // round-trip. The DAO-backed subject-belongs-to check happens
        // later, after the subject lookup, since it needs ss.getId().
        Integer parsedEventCrfId = null;
        if (body.eventCrfOid() != null && !body.eventCrfOid().isBlank()) {
            try {
                parsedEventCrfId = Integer.parseInt(body.eventCrfOid().trim());
            } catch (NumberFormatException nfe) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "Invalid eventCrfOid '" + body.eventCrfOid() + "'"));
            }
        }

        // Type-name input validation runs up-front too so an unknown
        // type short-circuits with 400 before any DB round-trip. The
        // role gate (canCreateType) still runs after the role lookup.
        String typeName = (body.type() == null || body.type().isBlank()) ? "query" : body.type();
        int typeId = NoteTransitionMatrix.typeIdForSpaName(typeName);
        if (typeId == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Unknown type '" + typeName + "' — expected one of: "
                            + "query | failed-validation | annotation | reason-for-change"));
        }

        // A4 — per-site visibility. The legacy findByLabelAndStudy
        // call is parent-only; for top-level Monitors with site-only
        // grants we'd otherwise reject every subject by label. Walk
        // the visible study set and stop at the first label match.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudyDAO sDao = new StudyDAO(dataSource);
        StudySubjectBean ss = null;
        for (Integer sid : visibleStudyIds) {
            StudyBean scope = (sid == currentStudy.getId())
                    ? currentStudy
                    : (StudyBean) sDao.findByPK(sid);
            if (scope == null || scope.getId() == 0) continue;
            StudySubjectBean candidate = ssDao.findByLabelAndStudy(body.subjectId(), scope);
            if (candidate != null && candidate.getId() > 0) {
                ss = candidate;
                break;
            }
        }
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study subject with label '" + body.subjectId() + "' in study '" + currentStudy.getOid() + "'"));
        }

        ItemDAO itemDao = new ItemDAO(dataSource);
        ArrayList<ItemBean> items = itemDao.findByOid(body.itemOid());
        if (items == null || items.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item with oid '" + body.itemOid() + "'"));
        }

        // Phase E.6 dn — when the SPA pins an event_crf, scope the
        // item_data lookup to that row so repeating events (same OID
        // across V3 + V4 + V5 …) attach the note to the chosen visit
        // and not the latest-inserted sibling. The shape-validity check
        // ran earlier (parsedEventCrfId is non-null here iff the input
        // was numeric); this block resolves the row + confirms it
        // belongs to the located subject.
        Integer scopedEventCrfId = null;
        if (parsedEventCrfId != null) {
            EventCRFDAO ecDao = new EventCRFDAO(dataSource);
            EventCRFBean ec = (EventCRFBean) ecDao.findByPK(parsedEventCrfId);
            if (ec == null || ec.getId() == 0) {
                return ResponseEntity.status(404).body(Map.of("message",
                        "No event_crf with id '" + body.eventCrfOid() + "'"));
            }
            if (ec.getStudySubjectId() != ss.getId()) {
                return ResponseEntity.status(404).body(Map.of("message",
                        "event_crf does not belong to subject '" + body.subjectId() + "'"));
            }
            scopedEventCrfId = parsedEventCrfId;
        }

        ItemDataBean target = (scopedEventCrfId == null)
                ? locateItemData(items, ss.getId())
                : locateItemData(items, ss.getId(), scopedEventCrfId);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item_data row for subject '" + body.subjectId() + "' and item '" + body.itemOid() + "'"));
        }

        // typeName + typeId resolved earlier; role gate runs here so
        // it can read currentRole (which was loaded for the visibility
        // filter above).
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!NoteTransitionMatrix.canCreateType(typeId, roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit creating notes of type '" + typeName + "'"));
        }

        // Phase B2 (2026-06-10) — failure-audit wrap on the parent
        // discrepancy_note insert + mapping + (best-effort) email
        // notification. The row doesn't exist yet on entry so entity_id
        // is the attached item_data id — the closest stable anchor for
        // post-mortem correlation. Email-failure exceptions are
        // intentionally caught downstream so an SMTP blip won't
        // surface as a 500 here; THIS wrap only fires when the DAO
        // write or mapping insert fails.
        final DiscrepancyNoteBean noteRef;
        {
            DiscrepancyNoteBean note = new DiscrepancyNoteBean();
            note.setDescription(body.description().trim());
            note.setDiscrepancyNoteTypeId(typeId);
            note.setResolutionStatusId(ResolutionStatus.OPEN.getId());
            note.setStudyId(currentStudy.getId());
            note.setEntityType("itemData");
            note.setEntityId(target.getId());
            note.setColumn("value");
            note.setOwner(ub);

            Integer assignedUserId = resolveAssignee(body.assignedTo());
            if (assignedUserId != null && assignedUserId > 0) {
                note.setAssignedUserId(assignedUserId);
            }
            noteRef = note;
        }
        final Integer assignedUserIdRef = noteRef.getAssignedUserId() > 0
                ? noteRef.getAssignedUserId() : null;
        final StudyBean studyRef = currentStudy;
        final UserAccountBean ubRef = ub;
        final AddQueryRequest bodyRef = body;
        final String typeNameRef = typeName;
        final int itemDataId = target.getId();
        final String reqId = MDC.get("reqId");
        try {
            return FailureAuditTemplate.runOrAudit(
                    new AuditEventDAO(dataSource),
                    ub.getId(),
                    "discrepancy_note",
                    itemDataId,
                    "CREATE_DISCREPANCY",
                    reqId,
                    () -> {
                        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
                        DiscrepancyNoteBean saved = dnDao.create(noteRef);
                        dnDao.createMapping(saved);
                        writeDnAudit(AUDIT_TYPE_DN_CREATED, ubRef, saved, "", "");

                        LOG.info("Created discrepancy_note id={} type={} subject={} item={} by user={}",
                                saved.getId(), typeNameRef, bodyRef.subjectId(), bodyRef.itemOid(),
                                ubRef.getName());

                        // Phase E.6 — email notification on create
                        // (when an assignee is set). Best-effort.
                        if (emailNotifier != null && assignedUserIdRef != null && assignedUserIdRef > 0) {
                            UserAccountBean assignee =
                                    new UserAccountDAO(dataSource).findByPK(assignedUserIdRef);
                            emailNotifier.notifyCreated(saved, assignee, studyRef);
                        }

                        DiscrepancyNoteDto dto = new DiscrepancyNoteDto(
                                String.valueOf(saved.getId()),
                                typeToSpa(saved.getDiscrepancyNoteTypeId()),
                                statusToSpa(saved.getResolutionStatusId()),
                                bodyRef.subjectId(),
                                bodyRef.itemOid(),
                                saved.getDescription(),
                                resolveUsername(assignedUserIdRef),
                                0,
                                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

                        return ResponseEntity.status(201).body(dto);
                    });
        } catch (Exception e) {
            LOG.error("Discrepancy create failed for subject={} item={} user={}",
                    body.subjectId(), body.itemOid(), ub.getName(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to create discrepancy note — see server log."));
        }
    }

    /**
     * Phase E A1 — append a child note to an existing parent and
     * transition the parent's status. Mirrors the legacy
     * {@code ResolveDiscrepancyServlet} + {@code
     * CreateOneDiscrepancyNoteServlet} pair.
     *
     * <p>Authorization layers:
     * <ol>
     *   <li>Session must carry an authenticated {@code userBean}
     *       and a bound {@code study} attribute.</li>
     *   <li>Parent note must exist; otherwise 404.</li>
     *   <li>Parent's {@code studyId} must be in the caller's
     *       site-visibility set; otherwise 403.</li>
     *   <li>{@link NoteTransitionMatrix} validates the (current →
     *       new) pair AND the caller's role; illegal pairs return
     *       400, role mismatches return 403.</li>
     * </ol>
     *
     * <p>Side effects: inserts a child {@code discrepancy_note} row
     * with {@code parent_dn_id = parentId}; updates the parent's
     * {@code resolution_status_id}; optionally updates the parent's
     * {@code assigned_user_id} when {@code body.assignedTo} is
     * present. Each side effect emits one {@code audit_log_event}
     * row via {@link #writeDnAudit} (types 72/73/74), seeded by
     * {@code lc-muw-2026-06-11-audit-event-types-gap-coverage.xml}
     * and closing the §11.10(e) gaps catalogued in
     * {@code docs/development/audit-coverage-2026-06-11.md}.
     *
     * <p>Earlier revisions of this docstring claimed audit rows were
     * written by a {@code discrepancy_note_trigger} — that trigger
     * has never existed in the migrations. The misleading comment
     * was a regulatory landmine and was removed when this method
     * was wired to {@link #writeDnAudit} directly.
     */
    @PostMapping("/{parentId}/thread")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = DiscrepancyNoteDto.class)))
    public ResponseEntity<?> appendThread(@PathVariable("parentId") long parentId,
                                          @RequestBody AddThreadEntryRequest body,
                                          HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (body == null || body.newStatus() == null || body.newStatus().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'newStatus' is required"));
        }

        int newStatusId = NoteTransitionMatrix.statusIdForSpaName(body.newStatus());
        if (newStatusId == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Unknown newStatus '" + body.newStatus() + "' — expected one of: "
                            + "updated | resolution-proposed | closed | not-applicable"));
        }
        // 'closed' may be wordless; every other transition requires a comment.
        boolean closing = newStatusId == ResolutionStatus.CLOSED.getId();
        if (!closing && (body.description() == null || body.description().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "'description' is required for status '" + body.newStatus() + "'"));
        }

        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
        dnDao.setFetchMapping(true);
        DiscrepancyNoteBean parent = (DiscrepancyNoteBean) dnDao.findByPK((int) parentId);
        if (parent == null || parent.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No discrepancy_note with id " + parentId));
        }

        // Site-visibility: the parent's study must be visible to the
        // caller. SiteVisibilityFilter returns the set of study ids
        // the caller's role can see; a Monitor with site-only grants
        // gets only those sites.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (!visibleStudyIds.contains(parent.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Parent note " + parentId + " is not in your visible study set"));
        }

        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        NoteTransitionMatrix.Decision decision = NoteTransitionMatrix.check(
                parent.getResolutionStatusId(), newStatusId, roleId);
        switch (decision) {
            case ILLEGAL_TRANSITION:
                return ResponseEntity.badRequest().body(Map.of("message",
                        "Illegal status transition: "
                                + statusToSpa(parent.getResolutionStatusId())
                                + " → " + body.newStatus()));
            case FORBIDDEN_FOR_ROLE:
                return ResponseEntity.status(403).body(Map.of("message",
                        "Your role does not permit this transition"));
            case OK:
                /* fall through */
                break;
        }

        // Resolve optional reassignment before mutating the parent.
        Integer assignedUserId = resolveAssignee(body.assignedTo());
        int previousStatusId = parent.getResolutionStatusId();
        int previousAssignedUserId = parent.getAssignedUserId();

        // Insert the child note. parent_dn_id is set via the bean's
        // setParentDnId(). entity_type / entity_id / column are
        // copied from the parent (the child shares the same data
        // point); study_id is inherited from the parent (not the
        // session's currentStudy — a Monitor with a site-only grant
        // may be acting on a parent in a sibling site).
        DiscrepancyNoteBean child = new DiscrepancyNoteBean();
        child.setParentDnId(parent.getId());
        child.setDiscrepancyNoteTypeId(parent.getDiscrepancyNoteTypeId());
        child.setResolutionStatusId(newStatusId);
        child.setDescription(closing && (body.description() == null || body.description().isBlank())
                ? "" : body.description().trim());
        child.setStudyId(parent.getStudyId());
        child.setEntityType(parent.getEntityType());
        child.setEntityId(parent.getEntityId());
        child.setColumn(parent.getColumn());
        child.setOwner(ub);
        if (assignedUserId != null && assignedUserId > 0) {
            child.setAssignedUserId(assignedUserId);
        }
        dnDao.create(child);
        writeDnAudit(AUDIT_TYPE_DN_THREAD_APPENDED, ub, parent, "", "");

        // Update the parent's resolution_status_id in place. DAO.update
        // writes description / type / status / detailed_notes —
        // preserve the parent's existing description + detailedNotes
        // so we only change the status column.
        parent.setResolutionStatusId(newStatusId);
        dnDao.update(parent);
        if (previousStatusId != newStatusId) {
            writeDnAudit(AUDIT_TYPE_DN_STATUS_CHANGED, ub, parent,
                    statusToSpa(previousStatusId), statusToSpa(newStatusId));
        }

        // Optional reassignment on the parent.
        if (assignedUserId != null && assignedUserId > 0) {
            parent.setAssignedUserId(assignedUserId);
            dnDao.updateAssignedUser(parent);
            if (previousAssignedUserId != assignedUserId.intValue()) {
                writeDnAudit(AUDIT_TYPE_DN_REASSIGNED, ub, parent,
                        previousAssignedUserId > 0 ? resolveUsername(previousAssignedUserId) : "",
                        resolveUsername(assignedUserId));
            }
        }

        LOG.info("Appended thread entry to discrepancy_note id={} status={} by user={} role={}",
                parent.getId(), body.newStatus(), ub.getName(), roleId);

        // Phase E.6 — fire emails on state change + reassignment. Use the
        // parent's study (not the session's currentStudy — may differ for
        // a Monitor with multi-site grants).
        if (emailNotifier != null) {
            StudyBean parentStudy = (parent.getStudyId() == currentStudy.getId())
                    ? currentStudy
                    : (StudyBean) new StudyDAO(dataSource).findByPK(parent.getStudyId());
            // State change: notify the current (post-reassignment) assignee.
            int effectiveAssigneeId = (assignedUserId != null && assignedUserId > 0)
                    ? assignedUserId : previousAssignedUserId;
            if (effectiveAssigneeId > 0 && previousStatusId != newStatusId) {
                UserAccountBean assignee = new UserAccountDAO(dataSource).findByPK(effectiveAssigneeId);
                emailNotifier.notifyStateChanged(parent, assignee,
                        statusToSpa(previousStatusId), statusToSpa(newStatusId), parentStudy);
            }
            // Reassignment: notify the new assignee distinctly when changed.
            if (assignedUserId != null && assignedUserId > 0
                    && assignedUserId != previousAssignedUserId) {
                UserAccountBean newAssignee = new UserAccountDAO(dataSource).findByPK(assignedUserId);
                emailNotifier.notifyReassigned(parent, newAssignee, parentStudy);
            }
        }

        // Refresh + project the updated parent. Use the same DAO with
        // fetchMapping=true (already set) to repopulate entity-walk
        // fields, then project to the SPA wire shape.
        DiscrepancyNoteBean refreshed = (DiscrepancyNoteBean) dnDao.findByPK(parent.getId());
        return ResponseEntity.ok(projectParentDto(refreshed));
    }

    /**
     * Phase E.6 {@code discrepancy-full} — return a parent note with
     * its full child thread (parent + every child in insertion order).
     *
     * <p>Authorization: same as {@code appendThread} — session-bound
     * authenticated user, active study, parent must be in the
     * caller's visible study set.
     */
    @GetMapping("/{parentId}/thread")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = DiscrepancyNoteDto.class)))
    public ResponseEntity<?> getThread(@PathVariable("parentId") long parentId,
                                       HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }

        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
        dnDao.setFetchMapping(true);
        DiscrepancyNoteBean parent = (DiscrepancyNoteBean) dnDao.findByPK((int) parentId);
        if (parent == null || parent.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No discrepancy_note with id " + parentId));
        }

        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (!visibleStudyIds.contains(parent.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Parent note " + parentId + " is not in your visible study set"));
        }

        DiscrepancyNoteDto parentDto = projectParentDto(parent);

        // The parent itself is the first thread entry; children follow
        // in insertion order (DAO returns by primary key ASC).
        List<DiscrepancyThreadEntryDto> entries = new ArrayList<>();
        entries.add(toThreadEntry(parent));
        ArrayList<DiscrepancyNoteBean> children = dnDao.findAllByParent(parent);
        if (children != null) {
            for (DiscrepancyNoteBean c : children) {
                entries.add(toThreadEntry(c));
            }
        }

        // Re-project with the populated thread field. Preserve the
        // notes-deeplink context fields (itemLabel / itemValue /
        // eventCrfOid / eventName) so the SPA thread modal keeps the
        // same context the list row already showed.
        DiscrepancyNoteDto hydrated = new DiscrepancyNoteDto(
                parentDto.id(),
                parentDto.type(),
                parentDto.status(),
                parentDto.subjectId(),
                parentDto.itemOid(),
                parentDto.description(),
                parentDto.assignedTo(),
                parentDto.daysOpen(),
                parentDto.lastActivityAt(),
                entries,
                parentDto.itemLabel(),
                parentDto.itemValue(),
                parentDto.eventCrfOid(),
                parentDto.eventName());
        return ResponseEntity.ok(hydrated);
    }

    /* Phase E.6 harmonize — the discrepancy-full cluster's exportCsv
     * (using DiscrepancyExportCsv.render + filenameFor) was superseded
     * by the audit-discrepancy-export cluster's version at the top of
     * this controller, which adds shared filter row collection +
     * audit_log_event_type 56 emission. DiscrepancyExportCsv stays
     * around for the unit tests (RFC 4180 quoting + filename helpers). */

    /** Project a single bean (parent or child) to a thread-entry DTO. */
    private DiscrepancyThreadEntryDto toThreadEntry(DiscrepancyNoteBean n) {
        String author = (n.getOwner() != null && n.getOwner().getId() > 0)
                ? n.getOwner().getName() : "";
        String createdAt = n.getCreatedDate() != null
                ? n.getCreatedDate().toInstant().truncatedTo(ChronoUnit.SECONDS).toString()
                : "";
        return new DiscrepancyThreadEntryDto(
                String.valueOf(n.getId()),
                statusToSpa(n.getResolutionStatusId()),
                nullToEmpty(n.getDescription()),
                author,
                createdAt);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Project a single parent {@link DiscrepancyNoteBean} to the
     * SPA wire shape. Mirrors the projection loop in {@link
     * #list(String, String, String, jakarta.servlet.http.HttpSession)}
     * but for one note — used by {@link #appendThread}.
     */
    private DiscrepancyNoteDto projectParentDto(DiscrepancyNoteBean n) {
        String subjectLabel = "";
        String itemOid = "";
        String itemLabel = null;
        String itemValue = null;
        String eventCrfOid = null;
        String eventName = null;

        if ("itemData".equalsIgnoreCase(n.getEntityType()) && n.getEntityId() > 0) {
            ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
            ItemDataBean idb = (ItemDataBean) itemDataDao.findByPK(n.getEntityId());
            if (idb != null && idb.getId() > 0) {
                itemValue = idb.getValue();
                ItemDAO itemDao = new ItemDAO(dataSource);
                ItemBean item = (ItemBean) itemDao.findByPK(idb.getItemId());
                if (item != null && item.getId() > 0) {
                    itemOid = nullToEmpty(item.getOid());
                    itemLabel = pickItemLabel(item);
                }
                EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
                EventCRFBean ec = (EventCRFBean) eventCrfDao.findByPK(idb.getEventCRFId());
                if (ec != null && ec.getId() > 0) {
                    eventCrfOid = String.valueOf(ec.getId());
                    StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
                    StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ec.getStudySubjectId());
                    if (ss != null && ss.getId() > 0) {
                        subjectLabel = nullToEmpty(ss.getLabel());
                    }
                    StudyEventDAO studyEventDao = new StudyEventDAO(dataSource);
                    StudyEventBean se = (StudyEventBean) studyEventDao.findByPK(ec.getStudyEventId());
                    if (se != null && se.getId() > 0) {
                        StudyEventDefinitionDAO seDefDao = new StudyEventDefinitionDAO(dataSource);
                        StudyEventDefinitionBean def =
                                (StudyEventDefinitionBean) seDefDao.findByPK(se.getStudyEventDefinitionId());
                        if (def != null && def.getId() > 0) {
                            eventName = def.getName();
                        }
                    }
                }
            }
        }

        String assignedTo = (n.getAssignedUser() != null && n.getAssignedUser().getId() > 0)
                ? n.getAssignedUser().getName() : null;

        int daysOpen = Math.max(n.getDays(), 0);
        String lastActivityAt = Instant.now()
                .minus(daysOpen, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();

        return new DiscrepancyNoteDto(
                String.valueOf(n.getId()),
                typeToSpa(n.getDiscrepancyNoteTypeId()),
                statusToSpa(n.getResolutionStatusId()),
                subjectLabel,
                itemOid,
                nullToEmpty(n.getDescription()),
                assignedTo,
                daysOpen,
                lastActivityAt,
                List.of(),
                itemLabel,
                itemValue,
                eventCrfOid,
                eventName);
    }

    /**
     * notes-deeplink (2026-06-11) — pick the operator-facing item label.
     * {@code item.description} is the human-readable label captured at
     * CRF build time (see the demo seed: "Height (cm)", "Weight (kg)",
     * …); {@code item.name} is the OID-shaped identifier the operator
     * already sees. Falls back to the OID-ish name when description is
     * empty, then to {@code null} so the SPA renders the bare OID.
     */
    private static String pickItemLabel(ItemBean item) {
        String desc = item.getDescription();
        if (desc != null && !desc.isBlank()) return desc;
        String name = item.getName();
        if (name != null && !name.isBlank()) return name;
        return null;
    }


    /**
     * Walks the subject's event_crfs and asks {@code item_data} for a
     * row matching any of the candidate item ids (a single OID can map
     * to multiple item rows across CRF versions). Returns the most
     * recently inserted match, or null when nothing aligns.
     */
    private ItemDataBean locateItemData(List<ItemBean> candidates, int studySubjectId) {
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        ArrayList<EventCRFBean> ecs = eventCrfDao.findAllByStudySubject(studySubjectId);
        ItemDataBean best = null;
        for (EventCRFBean ec : ecs) {
            for (ItemBean it : candidates) {
                ItemDataBean idb = itemDataDao.findByItemIdAndEventCRFId(it.getId(), ec.getId());
                if (idb != null && idb.getId() > 0) {
                    if (best == null || idb.getId() > best.getId()) {
                        best = idb;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Phase E.6 dn — event-CRF-scoped variant. When the SPA carries the
     * caller's chosen event_crf id (repeating events surface multiple
     * rows for the same item OID — V3 + V4 + V5 …), bypass the
     * walk-every-event_crf loop and pin the lookup to the exact row.
     * When {@code eventCrfId} is null the unscoped helper is used
     * (legacy notes-list "+ Add query" path stays on M7 behaviour).
     */
    private ItemDataBean locateItemData(List<ItemBean> candidates, int studySubjectId,
                                        Integer eventCrfId) {
        if (eventCrfId == null || eventCrfId <= 0) {
            return locateItemData(candidates, studySubjectId);
        }
        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        for (ItemBean it : candidates) {
            ItemDataBean idb = itemDataDao.findByItemIdAndEventCRFId(it.getId(), eventCrfId);
            if (idb != null && idb.getId() > 0) {
                return idb;
            }
        }
        return null;
    }

    private Integer resolveAssignee(String username) {
        if (username == null || username.isBlank()) return null;
        UserAccountDAO udao = new UserAccountDAO(dataSource);
        UserAccountBean ua = (UserAccountBean) udao.findByUserName(username);
        return (ua != null && ua.getId() > 0) ? ua.getId() : null;
    }

    private String resolveUsername(Integer userId) {
        if (userId == null || userId <= 0) return null;
        UserAccountDAO udao = new UserAccountDAO(dataSource);
        UserAccountBean ua = udao.findByPK(userId);
        return (ua != null && ua.getId() > 0) ? ua.getName() : null;
    }

    private static String statusToSpa(int id) {
        return switch (id) {
            case 1 -> "new";
            case 2 -> "updated";
            case 3 -> "resolution-proposed";
            case 4 -> "closed";
            case 5 -> "not-applicable";
            default -> "new";
        };
    }

    private static String typeToSpa(int id) {
        return switch (id) {
            case 1 -> "failed-validation";
            case 2 -> "annotation";
            case 3 -> "query";
            case 4 -> "reason-for-change";
            default -> "query";
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /* ------------------------------------------------------------------ */
    /* Export helpers                                                     */
    /* ------------------------------------------------------------------ */

    /** Null-safe coalesce for {@link CsvWriter} cell input. */
    static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Render the safe StudyOID / subject slug for the export filename —
     * alphanumerics + dash + underscore only so the filename is portable
     * across the Windows / macOS / Linux clients sponsors typically use.
     */
    static String safeOid(String oid) {
        if (oid == null || oid.isBlank()) return "study";
        StringBuilder sb = new StringBuilder(oid.length());
        for (int i = 0; i < oid.length(); i++) {
            char c = oid.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Build a one-line filter summary stored in {@code audit_log_event.new_value}
     * so the audit-trail row records what the operator selected when they
     * pulled the export (matches the SPA filter chip set).
     */
    static String describeFilters(String status, String subjectId, String assignedTo, int rowCount) {
        StringBuilder sb = new StringBuilder("rows=").append(rowCount);
        if (status != null && !status.isBlank()) sb.append(" status=").append(status);
        if (subjectId != null && !subjectId.isBlank()) sb.append(" subjectId=").append(subjectId);
        if (assignedTo != null && !assignedTo.isBlank()) sb.append(" assignedTo=").append(assignedTo);
        return sb.toString();
    }

    /**
     * Type ids for the DN-threading §11.10(e) audit-coverage gaps
     * closed by
     * {@code lc-muw-2026-06-11-audit-event-types-gap-coverage.xml}.
     *
     * <p>Type 71 (DN created) is the SUCCESS-path companion to the
     * existing FailureAuditTemplate (type 61) wrapper around the
     * DN-create lambda. Before this row, success-path DN creation
     * was silent; only failures were audited.
     */
    private static final int AUDIT_TYPE_DN_CREATED         = 71;
    private static final int AUDIT_TYPE_DN_THREAD_APPENDED = 72;
    private static final int AUDIT_TYPE_DN_STATUS_CHANGED  = 73;
    private static final int AUDIT_TYPE_DN_REASSIGNED      = 74;

    /**
     * Direct INSERT into {@code audit_log_event} for discrepancy-note
     * state mutations. Mirrors {@link #emitExportAudit} and
     * {@code StudiesApiController.writeStudyFieldAudit} — bypasses the
     * legacy {@code AuditEventDAO.create} path which writes to
     * {@code audit_event} (invisible to the SPA Audit Log view).
     *
     * <p>{@code oldValue} / {@code newValue} carry the meaningful diff
     * per type:
     * <ul>
     *   <li>{@code 71} DN created — both empty.</li>
     *   <li>{@code 72} thread appended — both empty (the child's content
     *       is captured in the row's {@code entity_id} pointer).</li>
     *   <li>{@code 73} status changed — old / new SPA-form status name.</li>
     *   <li>{@code 74} reassigned — old / new username; either may be
     *       empty when there was no prior or new assignee.</li>
     * </ul>
     *
     * <p>Failures are swallowed: the DN mutation has already persisted,
     * so a missed audit row should NOT roll back the user's action.
     */
    private void writeDnAudit(int auditTypeId, UserAccountBean actor,
                              DiscrepancyNoteBean dn,
                              String oldValue, String newValue) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'discrepancy_note', ?, ?, ?, ?)")) {
            ps.setInt(1, auditTypeId);
            ps.setInt(2, actor.getId());
            ps.setInt(3, dn.getId());
            ps.setString(4, dn.getDescription() == null ? "" : dn.getDescription());
            ps.setString(5, oldValue == null ? "" : oldValue);
            ps.setString(6, newValue == null ? "" : newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write audit_log_event row for discrepancy_note id={} type={}: {}",
                    dn.getId(), auditTypeId, e.getMessage());
        }
    }

    /**
     * Phase E.6 — best-effort INSERT into {@code audit_log_event} so the
     * GxP audit trail captures the egress event. Mirrors
     * {@code AuditApiController.emitExportAudit} — failures log at WARN
     * but never roll back the rendered download.
     */
    private void emitExportAudit(int userId, StudyBean study, int typeId, String filterSummary) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study', ?, ?, ?, ?)")) {
            ps.setInt(1, typeId);
            ps.setInt(2, userId);
            ps.setInt(3, study.getId());
            ps.setString(4, study.getOid() == null ? "" : study.getOid());
            ps.setString(5, "");
            ps.setString(6, filterSummary == null ? "" : filterSummary);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write discrepancy-export audit row for study_id={} type={}: {}",
                    study.getId(), typeId, e.getMessage());
        }
    }

    /**
     * Body of POST /pages/api/v1/discrepancies — "Add Query" form.
     *
     * <p>Phase E.6 {@code discrepancy-full}: added optional {@code type}
     * field. Accepts the four SPA-side type strings; defaults to
     * {@code "query"} when null/blank to preserve the M7 baseline
     * behaviour. {@code "reason-for-change"} is gated to DM/Admin per
     * {@link NoteTransitionMatrix#canCreateType(int, int)}.
     */
    public record AddQueryRequest(
            String subjectId,
            String itemOid,
            String description,
            String assignedTo,
            String type,
            String eventCrfOid
    ) {
        /** Backwards-compat ctor — defaults type to "query". */
        public AddQueryRequest(String subjectId, String itemOid,
                               String description, String assignedTo) {
            this(subjectId, itemOid, description, assignedTo, "query", null);
        }

        /** Phase E.6 discrepancy-full ctor — preserves the 5-arg shape. */
        public AddQueryRequest(String subjectId, String itemOid,
                               String description, String assignedTo,
                               String type) {
            this(subjectId, itemOid, description, assignedTo, type, null);
        }
    }
}
