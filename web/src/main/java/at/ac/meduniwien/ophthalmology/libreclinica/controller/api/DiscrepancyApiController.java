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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
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
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

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

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public DiscrepancyApiController(@Qualifier("dataSource") DataSource dataSource,
                                    SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
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
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
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

        ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        ItemDAO itemDao = new ItemDAO(dataSource);

        List<DiscrepancyNoteDto> out = new ArrayList<>(notes.size());
        for (DiscrepancyNoteBean n : notes) {
            String subjectLabel = "";
            String itemOid = "";

            if ("itemData".equalsIgnoreCase(n.getEntityType()) && n.getEntityId() > 0) {
                ItemDataBean idb = itemDataCache.computeIfAbsent(n.getEntityId(),
                        id -> (ItemDataBean) itemDataDao.findByPK(id));
                if (idb != null && idb.getId() > 0) {
                    ItemBean item = itemCache.computeIfAbsent(idb.getItemId(),
                            id -> (ItemBean) itemDao.findByPK(id));
                    if (item != null && item.getId() > 0) {
                        itemOid = nullToEmpty(item.getOid());
                    }
                    EventCRFBean ec = eventCrfCache.computeIfAbsent(idb.getEventCRFId(),
                            id -> (EventCRFBean) eventCrfDao.findByPK(id));
                    if (ec != null && ec.getId() > 0) {
                        StudySubjectBean ss = studySubjectCache.computeIfAbsent(ec.getStudySubjectId(),
                                id -> (StudySubjectBean) studySubjectDao.findByPK(id));
                        if (ss != null && ss.getId() > 0) {
                            subjectLabel = nullToEmpty(ss.getLabel());
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
                    lastActivityAt));
        }

        return ResponseEntity.ok(out);
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

        ItemDataBean target = locateItemData(items, ss.getId());
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No item_data row for subject '" + body.subjectId() + "' and item '" + body.itemOid() + "'"));
        }

        DiscrepancyNoteBean note = new DiscrepancyNoteBean();
        note.setDescription(body.description().trim());
        note.setDiscrepancyNoteTypeId(DiscrepancyNoteType.QUERY.getId());
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

        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);
        DiscrepancyNoteBean saved = dnDao.create(note);
        dnDao.createMapping(saved);

        LOG.info("Created discrepancy_note id={} type=query subject={} item={} by user={}",
                saved.getId(), body.subjectId(), body.itemOid(), ub.getName());

        DiscrepancyNoteDto dto = new DiscrepancyNoteDto(
                String.valueOf(saved.getId()),
                typeToSpa(saved.getDiscrepancyNoteTypeId()),
                statusToSpa(saved.getResolutionStatusId()),
                body.subjectId(),
                body.itemOid(),
                saved.getDescription(),
                resolveUsername(assignedUserId),
                0,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

        return ResponseEntity.status(201).body(dto);
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
     * present. Audit-event rows are written by the table trigger
     * {@code discrepancy_note_trigger}; no controller-side audit
     * emission is required.
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

        // Update the parent's resolution_status_id in place. DAO.update
        // writes description / type / status / detailed_notes —
        // preserve the parent's existing description + detailedNotes
        // so we only change the status column.
        parent.setResolutionStatusId(newStatusId);
        dnDao.update(parent);

        // Optional reassignment on the parent.
        if (assignedUserId != null && assignedUserId > 0) {
            parent.setAssignedUserId(assignedUserId);
            dnDao.updateAssignedUser(parent);
        }

        LOG.info("Appended thread entry to discrepancy_note id={} status={} by user={} role={}",
                parent.getId(), body.newStatus(), ub.getName(), roleId);

        // Refresh + project the updated parent. Use the same DAO with
        // fetchMapping=true (already set) to repopulate entity-walk
        // fields, then project to the SPA wire shape.
        DiscrepancyNoteBean refreshed = (DiscrepancyNoteBean) dnDao.findByPK(parent.getId());
        return ResponseEntity.ok(projectParentDto(refreshed));
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

        if ("itemData".equalsIgnoreCase(n.getEntityType()) && n.getEntityId() > 0) {
            ItemDataDAO itemDataDao = new ItemDataDAO(dataSource);
            ItemDataBean idb = (ItemDataBean) itemDataDao.findByPK(n.getEntityId());
            if (idb != null && idb.getId() > 0) {
                ItemDAO itemDao = new ItemDAO(dataSource);
                ItemBean item = (ItemBean) itemDao.findByPK(idb.getItemId());
                if (item != null && item.getId() > 0) {
                    itemOid = nullToEmpty(item.getOid());
                }
                EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
                EventCRFBean ec = (EventCRFBean) eventCrfDao.findByPK(idb.getEventCRFId());
                if (ec != null && ec.getId() > 0) {
                    StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
                    StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ec.getStudySubjectId());
                    if (ss != null && ss.getId() > 0) {
                        subjectLabel = nullToEmpty(ss.getLabel());
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
                lastActivityAt);
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

    /** Body of POST /pages/api/v1/discrepancies — "Add Query" form. */
    public record AddQueryRequest(
            String subjectId,
            String itemOid,
            String description,
            String assignedTo
    ) {}
}
