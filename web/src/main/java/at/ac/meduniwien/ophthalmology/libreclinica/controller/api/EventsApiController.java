/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M11 — Schedule-Event adapter.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /pages/api/v1/events?subjectId=…&status=…&
 *       eventDefinitionOid=…} — cross-subject study-event list for
 *       the session-bound active study, newest first. Optional
 *       server-side filters narrow before the wire; the SPA's
 *       schedule view applies the same filters client-side on top.</li>
 *   <li>{@code POST /pages/api/v1/events} — schedules a new
 *       study_event for the given subject + event-definition pair.
 *       Mirrors the legacy {@code CreateNewStudyEventServlet} state
 *       changes: fresh sample_ordinal via
 *       {@code StudyEventDAO.getMaxSampleOrdinal + 1} (so repeating
 *       events scale to many visits per subject without manual
 *       ordinal hand-tuning), status=SCHEDULED, owner=current user.</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> chain-level
 * {@code .anyRequest().hasRole("USER")} gates both. The POST endpoint
 * additionally requires a session-bound active study and refuses
 * subjects / definitions not on it (404).
 *
 * <p>Status mapping ({@link SubjectEventStatus}.id):
 * <ul>
 *   <li>1 → {@code scheduled}</li>
 *   <li>2 → {@code not-scheduled}</li>
 *   <li>3 → {@code data-entry-started}</li>
 *   <li>4 → {@code completed}</li>
 *   <li>5 → {@code stopped}</li>
 *   <li>6 → {@code skipped}</li>
 *   <li>7 → {@code locked}</li>
 *   <li>8 → {@code signed}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Cross-subject study-event list + schedule.")
public class EventsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventsApiController.class);
    private static final SimpleDateFormat ISO_DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public EventsApiController(@Qualifier("dataSource") DataSource dataSource,
                               SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * Phase E.6 — Event Detail adapter. Replaces the legacy
     * {@code /pages/EnterDataForStudyEvent?eventId=…} JSP redirect
     * the SubjectDetailView used to bridge into; the SPA now stays
     * in-shell with a Vue view that consumes this endpoint.
     *
     * <p>Returns the event metadata + one row per
     * {@code event_definition_crf} entry wired into the event
     * definition. Each row carries the matching {@code event_crf}'s
     * id when it exists (so the SPA can deep-link to CrfEntryView);
     * otherwise the row reports {@code status=not-started} with a
     * null {@code eventCrfId}, and the SPA renders a "start data
     * entry" affordance instead.
     *
     * <p>Guards: {@code 401} anonymous / {@code 400} no active
     * study / {@code 404} unknown event id / {@code 403} when the
     * event's subject lives outside the caller's visible study set.
     */
    @GetMapping("/{id:[0-9]+}")
    @Operation(operationId = "getEventDetail")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = EventDetailDto.class)))
    public ResponseEntity<?> getEventDetail(@PathVariable("id") int eventId, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }

        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyEventBean ev = (StudyEventBean) seDao.findByPK(eventId);
        if (ev == null || ev.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_event with id " + eventId));
        }

        // Resolve subject + verify it lives in the visible study tree.
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ev.getStudySubjectId());
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "study_event " + eventId + " has no resolvable study_subject"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "study_event " + eventId + " belongs to a different study"));
        }

        // Owning study (for the breadcrumb) — may be a site different
        // from the session's currentStudy when a Monitor walked into
        // it from the parent.
        StudyDAO sDao = new StudyDAO(dataSource);
        StudyBean owningStudy = (ss.getStudyId() == currentStudy.getId())
                ? currentStudy
                : (StudyBean) sDao.findByPK(ss.getStudyId());
        if (owningStudy == null || owningStudy.getId() == 0) {
            owningStudy = currentStudy;
        }

        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean def = (StudyEventDefinitionBean) sedDao.findByPK(
                ev.getStudyEventDefinitionId());

        // Enumerate the event_definition_crf rows (the catalogue of
        // CRFs the event definition wires in) + look up any existing
        // event_crf rows keyed by crf_version_id so we can mark each
        // slot started / not-started.
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        CRFVersionDAO crfvDao = new CRFVersionDAO(dataSource);
        CRFDAO crfDao = new CRFDAO(dataSource);

        List<EventDefinitionCRFBean> edcs = (def == null || def.getId() == 0)
                ? List.of()
                : edcDao.findAllByEventDefinitionId(owningStudy, def.getId());
        if (edcs == null) edcs = List.of();

        // Sort by ordinal so the SPA renders the canonical order.
        ArrayList<EventDefinitionCRFBean> orderedEdcs = new ArrayList<>(edcs);
        orderedEdcs.sort((a, b) -> Integer.compare(a.getOrdinal(), b.getOrdinal()));

        // Map crf_version_id -> matching event_crf for fast lookup.
        //
        // Phase E.6 restore-quickwins: surface AUTO_DELETED rows so the
        // Restore action can render against them. Hard-DELETED rows
        // (operator-issued, distinct from the parent-cascade
        // AUTO_DELETED) stay hidden; restore is only the inverse of
        // RemoveEventCRFServlet's soft-delete (status flip), not a
        // resurrection from a hard delete.
        Map<Integer, EventCRFBean> existingByVersion = new HashMap<>();
        ArrayList<EventCRFBean> existing = ecDao.findAllByStudyEvent(ev);
        if (existing != null) {
            for (EventCRFBean ec : existing) {
                if (ec == null || ec.getId() == 0) continue;
                if (ec.getStatus() != null
                        && ec.getStatus().equals(Status.DELETED)) continue;
                existingByVersion.put(ec.getCRFVersionId(), ec);
            }
        }

        List<EventCrfRowDto> crfRows = new ArrayList<>(orderedEdcs.size());
        for (EventDefinitionCRFBean edc : orderedEdcs) {
            CRFVersionBean cv = (edc.getDefaultVersionId() > 0)
                    ? (CRFVersionBean) crfvDao.findByPK(edc.getDefaultVersionId())
                    : null;
            String crfName = (edc.getCrfName() == null || edc.getCrfName().isBlank())
                    ? lookupCrfName(crfDao, edc.getCrfId(), cv)
                    : edc.getCrfName();
            String versionName = (cv != null && cv.getName() != null) ? cv.getName() : "";
            String versionOid = (cv != null) ? cv.getOid() : null;

            // Pick an event_crf row for this slot. Prefer the row
            // pinned to the default version; fall back to any row
            // referencing a non-default version of the same CRF
            // (operators can swap versions per row in the legacy
            // EnterDataForStudyEvent flow — we still want to surface
            // started entries here). Restore-quickwins keeps the
            // AUTO_DELETED branch reachable so the restore button can
            // render on parent-cascade-removed rows.
            EventCRFBean ec = (cv != null) ? existingByVersion.get(cv.getId()) : null;
            if (ec == null && existing != null) {
                for (EventCRFBean candidate : existing) {
                    if (candidate == null || candidate.getId() == 0) continue;
                    if (candidate.getStatus() != null
                            && candidate.getStatus().equals(Status.DELETED)) continue;
                    CRFVersionBean cvCand = (CRFVersionBean) crfvDao.findByPK(candidate.getCRFVersionId());
                    if (cvCand != null && cvCand.getCrfId() == edc.getCrfId()) {
                        ec = candidate;
                        break;
                    }
                }
            }

            Integer eventCrfId = (ec != null && ec.getId() > 0) ? ec.getId() : null;
            String eventCrfOid = (eventCrfId == null) ? null : String.valueOf(eventCrfId);
            String rowStatus = statusForEventCrf(ec);

            crfRows.add(new EventCrfRowDto(
                    eventCrfId,
                    eventCrfOid,
                    nullToEmpty(crfName),
                    versionName,
                    versionOid,
                    edc.getId(),
                    rowStatus,
                    edc.isRequiredCRF(),
                    edc.isElectronicSignature()));
        }

        EventDetailDto dto = new EventDetailDto(
                ev.getId(),
                def == null ? "" : nullToEmpty(def.getOid()),
                def == null ? "" : nullToEmpty(def.getName()),
                nullToEmpty(ss.getLabel()),
                nullToEmpty(ss.getOid()),
                nullToEmpty(owningStudy.getOid()),
                nullToEmpty(owningStudy.getName()),
                ev.getDateStarted() == null ? "" : ISO_DATE.format(ev.getDateStarted()),
                statusForSubjectEventStatus(ev.getSubjectEventStatus()),
                ev.getSampleOrdinal(),
                def != null && def.isRepeating(),
                crfRows);

        return ResponseEntity.ok(dto);
    }

    /**
     * Phase E.6 — start data entry for a CRF slot that has no
     * {@code event_crf} row yet. Replaces the legacy bridge from
     * {@code SubjectDetailView} → {@code /pages/EnterDataForStudyEvent}
     * JSP for the "unstarted CRF" case: the SPA now stays in-shell,
     * POSTs here, then routes to the existing {@code CrfEntryView} via
     * the returned {@code eventCrfOid}.
     *
     * <p>Body (all fields optional):
     * <pre>
     *   { "crfVersionId": 7 }   // override the default version
     * </pre>
     * When {@code crfVersionId} is omitted the
     * {@code event_definition_crf.default_version_id} is used.
     *
     * <p>Mirrors the legacy {@code DataEntryServlet.createEventCRF}
     * insert pattern: status=AVAILABLE, completion_status_id=1
     * (not-started), owner=current user, interviewer-name/date pulled
     * from study config + parent event. The DAO INSERT path is the
     * same one the legacy servlet uses, so audit triggers + study-event
     * status cascades stay consistent.
     *
     * <p>Guards: 401 anonymous / 400 no active study / 404 unknown
     * event id or event_definition_crf id / 403 wrong-study visibility
     * / 409 already-started slot (an event_crf for this CRF already
     * exists on the event).
     *
     * <p>Audit: writes one {@code audit_log_event} row keyed by the
     * new event_crf id ({@code audit_table=event_crf},
     * {@code action_message="event_crf_started"}). Wrapped in
     * try/catch so audit failure doesn't roll back the data insert.
     */
    @PostMapping("/{id:[0-9]+}/crfs/{edcId:[0-9]+}:start")
    @Operation(operationId = "startEventCrf")
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = StartEventCrfResponse.class)))
    public ResponseEntity<?> startEventCrf(@PathVariable("id") int eventId,
                                           @PathVariable("edcId") int edcId,
                                           @RequestBody(required = false) StartEventCrfRequest body,
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

        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyEventBean ev = (StudyEventBean) seDao.findByPK(eventId);
        if (ev == null || ev.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_event with id " + eventId));
        }

        // Resolve subject + verify the event lives in the caller's
        // visible study set (same A4 visibility chain as getEventDetail).
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ev.getStudySubjectId());
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "study_event " + eventId + " has no resolvable study_subject"));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "study_event " + eventId + " belongs to a different study"));
        }

        // Resolve the event_definition_crf slot the SPA is asking to
        // populate, and verify it belongs to the same event definition
        // the parent study_event is wired to.
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        EventDefinitionCRFBean edc = (EventDefinitionCRFBean) edcDao.findByPK(edcId);
        if (edc == null || edc.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event_definition_crf with id " + edcId));
        }
        if (edc.getStudyEventDefinitionId() != ev.getStudyEventDefinitionId()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "event_definition_crf " + edcId + " is not wired into this event's definition"));
        }

        // Resolve the CRF version. Body override wins; otherwise the
        // default version pinned on the event_definition_crf row.
        int crfVersionId = (body != null && body.crfVersionId() != null && body.crfVersionId() > 0)
                ? body.crfVersionId()
                : edc.getDefaultVersionId();
        if (crfVersionId <= 0) {
            return ResponseEntity.status(400).body(Map.of("message",
                    "event_definition_crf " + edcId + " has no default_version_id — pass 'crfVersionId' explicitly"));
        }
        CRFVersionDAO crfvDao = new CRFVersionDAO(dataSource);
        CRFVersionBean crfv = (CRFVersionBean) crfvDao.findByPK(crfVersionId);
        if (crfv == null || crfv.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No crf_version with id " + crfVersionId));
        }
        // Confirm the chosen version belongs to the CRF the slot expects.
        if (crfv.getCrfId() != edc.getCrfId()) {
            return ResponseEntity.status(400).body(Map.of("message",
                    "crf_version " + crfVersionId + " does not belong to crf " + edc.getCrfId()));
        }

        // Refuse if a non-deleted event_crf for this version already
        // exists on the event — operator should "Open" the existing
        // row, not start a duplicate. Mirrors the legacy
        // findByEventSubjectVersion guard.
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        ArrayList<EventCRFBean> existing = ecDao.findAllByStudyEvent(ev);
        if (existing != null) {
            for (EventCRFBean candidate : existing) {
                if (candidate == null || candidate.getId() == 0) continue;
                if (candidate.getStatus() != null
                        && (candidate.getStatus().equals(Status.DELETED)
                                || candidate.getStatus().equals(Status.AUTO_DELETED))) continue;
                if (candidate.getCRFVersionId() != crfVersionId) continue;
                return ResponseEntity.status(409).body(Map.of("message",
                        "event_crf already exists for this slot (id=" + candidate.getId() + ")",
                        "eventCrfId", candidate.getId(),
                        "eventCrfOid", String.valueOf(candidate.getId())));
            }
        }

        // Mirror the legacy DataEntryServlet insert: not-started,
        // owned by the current user, interviewer-name/date defaulted
        // per study config (currentStudy carries the parent study's
        // params for sites).
        EventCRFBean ecb = new EventCRFBean();
        ecb.setAnnotations("");
        ecb.setCreatedDate(new Date());
        ecb.setCRFVersionId(crfVersionId);

        String interviewDefault = currentStudy.getStudyParameterConfig() == null
                ? "blank"
                : currentStudy.getStudyParameterConfig().getInterviewerNameDefault();
        if ("blank".equals(interviewDefault)) {
            ecb.setInterviewerName("");
        } else {
            // legacy: event owner's name (we don't have it loaded — use ub).
            ecb.setInterviewerName(ub.getName() == null ? "" : ub.getName());
        }
        String interviewDateDefault = currentStudy.getStudyParameterConfig() == null
                ? "blank"
                : currentStudy.getStudyParameterConfig().getInterviewDateDefault();
        if (!"blank".equals(interviewDateDefault) && ev.getDateStarted() != null) {
            ecb.setDateInterviewed(ev.getDateStarted());
        } else {
            ecb.setDateInterviewed(null);
        }
        ecb.setOwner(ub);
        ecb.setStatus(Status.AVAILABLE);
        ecb.setCompletionStatusId(1); // not-started
        ecb.setStudySubjectId(ss.getId());
        ecb.setStudyEventId(ev.getId());
        ecb.setValidateString("");
        ecb.setValidatorAnnotations("");

        try {
            ecb = ecDao.create(ecb);
        } catch (Exception e) {
            LOG.error("Failed to create event_crf for event={} edc={} version={}",
                    eventId, edcId, crfVersionId, e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to start CRF: " + e.getMessage()));
        }
        if (ecb == null || ecb.getId() == 0) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to start CRF — DAO returned no id"));
        }

        // Cascade study_event status to data-entry-started if it was
        // still scheduled. Mirrors what the legacy entry flow does on
        // first data-entry of the visit.
        if (ev.getSubjectEventStatus() != null
                && ev.getSubjectEventStatus().equals(SubjectEventStatus.SCHEDULED)) {
            ev.setSubjectEventStatus(SubjectEventStatus.DATA_ENTRY_STARTED);
            ev.setUpdater(ub);
            ev.setUpdatedDate(new Date());
            try { seDao.update(ev); } catch (Exception ignored) { /* best-effort */ }
        }

        // Audit row — best-effort. Mirrors SdvApiController's pattern
        // (no audit_log_event_type_id setter on AuditEventDAO.create()).
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(ub.getId());
            ae.setStudyId(currentStudy.getId());
            ae.setSubjectId(ss.getId());
            ae.setStudyName(currentStudy.getName() == null ? "" : currentStudy.getName());
            ae.setSubjectName(ss.getLabel() == null ? "" : ss.getLabel());
            ae.setAuditTable("event_crf");
            ae.setEntityId(ecb.getId());
            ae.setColumnName("event_crf_id");
            ae.setOldValue("");
            ae.setNewValue(String.valueOf(ecb.getId()));
            ae.setActionMessage("event_crf_started: event=" + eventId
                    + " edc=" + edcId + " version=" + crfVersionId);
            auditDAO.create(ae);
        } catch (Exception auditEx) {
            LOG.warn("Audit write failed for event_crf_started id={} (continuing): {}",
                    ecb.getId(), auditEx.getMessage());
        }

        LOG.info("event_crf_started: id={} event={} edc={} version={} by user={}",
                ecb.getId(), eventId, edcId, crfVersionId, ub.getName());

        return ResponseEntity.status(201).body(new StartEventCrfResponse(
                ecb.getId(),
                String.valueOf(ecb.getId()),
                eventId,
                edcId,
                crfVersionId,
                "data-entry-started"));
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = StudyEventDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "status", required = false) String statusFilter,
            @RequestParam(value = "eventDefinitionOid", required = false) String defOidFilter,
            @RequestParam(value = "includeRemoved", required = false, defaultValue = "false")
                boolean includeRemoved,
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
        int studyId = currentStudy.getId();

        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyDAO sDao = new StudyDAO(dataSource);

        // A4 — per-site visibility. Walk the visible study set so a
        // Monitor with site-only grants on a multi-site parent
        // doesn't see other sites' subjects' events.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        ArrayList<StudySubjectBean> subjects = new ArrayList<>();
        Map<Integer, StudyBean> studyCache = new HashMap<>();
        studyCache.put(currentStudy.getId(), currentStudy);
        for (Integer sid : visibleStudyIds) {
            ArrayList<StudySubjectBean> chunk = ssDao.findAllByStudyId(sid);
            if (chunk != null) subjects.addAll(chunk);
        }
        Map<Integer, StudyEventDefinitionBean> defCache = new HashMap<>();

        List<StudyEventDto> out = new ArrayList<>();
        for (StudySubjectBean ss : subjects) {
            if (subjectIdFilter != null && !subjectIdFilter.isBlank()
                    && !subjectIdFilter.equalsIgnoreCase(ss.getLabel())) continue;

            // Pass the subject's actual owning study to the events
            // DAO — for a Monitor walking a site, the subjects live
            // in the site, not the parent the session was bound to.
            StudyBean scopeStudy = studyCache.computeIfAbsent(ss.getStudyId(),
                    id -> (id == currentStudy.getId()) ? currentStudy : (StudyBean) sDao.findByPK(id));
            if (scopeStudy == null || scopeStudy.getId() == 0) scopeStudy = currentStudy;
            ArrayList<StudyEventBean> events = seDao.findAllByStudyAndStudySubjectId(scopeStudy, ss.getId());
            for (StudyEventBean ev : events) {
                StudyEventDefinitionBean def = defCache.computeIfAbsent(ev.getStudyEventDefinitionId(),
                        id -> (StudyEventDefinitionBean) sedDao.findByPK(id));
                if (def == null || def.getId() == 0) continue;

                // Phase E.6 restore-quickwins: skip soft-deleted events
                // by default; the SPA opts in via ?includeRemoved=true
                // when the schedule view's "Show removed" toggle is on.
                boolean isRemoved = ev.getStatus() != null
                        && ev.getStatus().equals(Status.DELETED);
                if (isRemoved && !includeRemoved) continue;

                if (defOidFilter != null && !defOidFilter.isBlank()
                        && !defOidFilter.equalsIgnoreCase(def.getOid())) continue;

                String status = isRemoved
                        ? "removed"
                        : statusForSubjectEventStatus(ev.getSubjectEventStatus());
                if (statusFilter != null && !statusFilter.isBlank()
                        && !statusFilter.equalsIgnoreCase(status)) continue;

                out.add(new StudyEventDto(
                        String.valueOf(ev.getId()),
                        nullToEmpty(ss.getLabel()),
                        nullToEmpty(def.getOid()),
                        nullToEmpty(def.getName()),
                        ev.getSampleOrdinal(),
                        ev.getDateStarted() == null ? "" : ISO_DATE.format(ev.getDateStarted()),
                        ev.getDateEnded() == null ? null : ISO_DATE.format(ev.getDateEnded()),
                        blankToNull(ev.getLocation()),
                        status,
                        def.isRepeating()));
            }
        }

        // newest-first by dateStarted then by id descending
        out.sort((a, b) -> {
            String da = a.dateStarted() == null ? "" : a.dateStarted();
            String db = b.dateStarted() == null ? "" : b.dateStarted();
            int c = db.compareTo(da);
            if (c != 0) return c;
            return Integer.compare(Integer.parseInt(b.id()), Integer.parseInt(a.id()));
        });

        return ResponseEntity.ok(out);
    }

    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = StudyEventDto.class)))
    public ResponseEntity<?> schedule(@RequestBody ScheduleEventRequest body, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Empty request body"));
        }
        if (body.subjectId() == null || body.subjectId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'subjectId' is required"));
        }
        if (body.eventDefinitionOid() == null || body.eventDefinitionOid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'eventDefinitionOid' is required"));
        }
        if (body.dateStarted() == null || body.dateStarted().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'dateStarted' is required (YYYY-MM-DD)"));
        }

        Date startDate;
        try {
            startDate = ISO_DATE.parse(body.dateStarted());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "'dateStarted' must be YYYY-MM-DD; got '" + body.dateStarted() + "'"));
        }

        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyDAO sDao = new StudyDAO(dataSource);

        // A4 — walk visible studies and resolve the subject inside
        // any of them (the legacy findByLabelAndStudy is parent-only
        // so a Monitor with site-only grants would otherwise reject
        // every subject scheduled into the site).
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
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

        StudyEventDefinitionBean def = sedDao.findByOidAndStudy(
                body.eventDefinitionOid(), currentStudy.getId(),
                currentStudy.getParentStudyId() > 0 ? currentStudy.getParentStudyId() : currentStudy.getId());
        if (def == null || def.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No event definition with oid '" + body.eventDefinitionOid() + "' on this study"));
        }

        int nextOrdinal = seDao.getMaxSampleOrdinal(def, ss) + 1;
        if (nextOrdinal > 1 && !def.isRepeating()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Event definition '" + def.getOid() + "' is not repeating — already scheduled for this subject"));
        }

        StudyEventBean ev = new StudyEventBean();
        ev.setStudyEventDefinitionId(def.getId());
        ev.setStudySubjectId(ss.getId());
        ev.setDateStarted(startDate);
        ev.setLocation(body.location() == null ? "" : body.location());
        ev.setSubjectEventStatus(SubjectEventStatus.SCHEDULED);
        ev.setSampleOrdinal(nextOrdinal);
        ev.setOwner(ub);
        ev.setStatus(Status.AVAILABLE);

        try {
            ev = (StudyEventBean) seDao.create(ev);
        } catch (Exception e) {
            LOG.error("Failed to schedule study_event for subject={} def={} start={}",
                    body.subjectId(), body.eventDefinitionOid(), body.dateStarted(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to schedule event: " + e.getMessage()));
        }
        if (ev == null || ev.getId() == 0) {
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to schedule event — DAO returned no id"));
        }

        LOG.info("Scheduled study_event id={} subject={} def={} ordinal={} start={} by user={}",
                ev.getId(), body.subjectId(), def.getOid(), nextOrdinal, body.dateStarted(), ub.getName());

        StudyEventDto dto = new StudyEventDto(
                String.valueOf(ev.getId()),
                ss.getLabel(),
                def.getOid(),
                def.getName(),
                nextOrdinal,
                ISO_DATE.format(startDate),
                null,
                blankToNull(ev.getLocation()),
                "scheduled",
                def.isRepeating());

        return ResponseEntity.status(201).body(dto);
    }

    /**
     * Phase E A4 — edit an existing study event.
     *
     * <p>Editable fields: {@code dateStarted}, {@code dateEnded},
     * {@code location}, {@code status}. The event's
     * {@code study_subject_id} and {@code study_event_definition_id}
     * are immutable (FK anchors for event_crf joins).
     *
     * <p>Status is restricted to user-controlled transitions:
     * {@code scheduled | stopped | skipped}. Derived statuses
     * ({@code data-entry-started}, {@code completed},
     * {@code signed}, {@code locked}) cannot be set via this
     * endpoint — they come from CRF completion / signing flows.
     *
     * <p>Guards: 401 / 400 (no study) / 403 (role) /
     * 400 (validation) / 404 (visibility) / 409 (event is
     * {@link Status#DELETED} or in a terminal SubjectEventStatus
     * — {@code signed} or {@code locked}).
     *
     * <p>Audit: one {@code audit_log_event} row per changed
     * column with old/new values. Wrapped in try/catch so audit
     * failures don't roll back the data update.
     */
    @PutMapping("/{id:[0-9]+}")
    @Operation(operationId = "updateEvent")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyEventDto.class)))
    public ResponseEntity<?> update(@PathVariable("id") int eventId,
                                    @RequestBody(required = false) UpdateEventRequest body,
                                    HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound to the session."));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!EventEditAuthorization.roleMayEdit(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing study events"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Empty request body"));
        }
        // Status validation (limited set).
        Integer newStatusId = null;
        if (body.status() != null) {
            switch (body.status()) {
                case "scheduled": newStatusId = SubjectEventStatus.SCHEDULED.getId(); break;
                case "stopped":   newStatusId = SubjectEventStatus.STOPPED.getId();   break;
                case "skipped":   newStatusId = SubjectEventStatus.SKIPPED.getId();   break;
                default:
                    return ResponseEntity.badRequest().body(Map.of("message",
                            "'status' must be one of: scheduled | stopped | skipped "
                                    + "(derived statuses are not user-editable)"));
            }
        }
        // Date validation — pre-parse so we can write back with the
        // same SimpleDateFormat the rest of the controller uses.
        Date newStart = null;
        boolean clearStart = false;
        if (body.dateStarted() != null) {
            if (body.dateStarted().isBlank()) {
                clearStart = true;
            } else {
                try { newStart = ISO_DATE.parse(body.dateStarted()); }
                catch (Exception e) {
                    return ResponseEntity.badRequest().body(Map.of("message",
                            "'dateStarted' must be YYYY-MM-DD; got '" + body.dateStarted() + "'"));
                }
            }
        }
        Date newEnd = null;
        boolean clearEnd = false;
        if (body.dateEnded() != null) {
            if (body.dateEnded().isBlank()) {
                clearEnd = true;
            } else {
                try { newEnd = ISO_DATE.parse(body.dateEnded()); }
                catch (Exception e) {
                    return ResponseEntity.badRequest().body(Map.of("message",
                            "'dateEnded' must be YYYY-MM-DD; got '" + body.dateEnded() + "'"));
                }
            }
        }

        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyEventBean ev = (StudyEventBean) seDao.findByPK(eventId);
        if (ev == null || ev.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_event with id " + eventId));
        }

        // Site visibility — resolve the subject behind the event
        // and confirm it sits in the caller's visible study set.
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ev.getStudySubjectId());
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "study_event " + eventId + " belongs to a different study"));
        }

        ResponseEntity<?> lockRefusal = SubjectLockGuard.refuseIfLocked(ss, "editing event " + eventId);
        if (lockRefusal != null) {
            return lockRefusal;
        }

        // State guards — refuse terminal SubjectEventStatus values,
        // refuse already-deleted events.
        if (ev.getStatus() != null && ev.getStatus().equals(Status.DELETED)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "study_event " + eventId + " is removed"));
        }
        SubjectEventStatus current = ev.getSubjectEventStatus();
        if (current != null && (current.equals(SubjectEventStatus.SIGNED)
                || current.equals(SubjectEventStatus.LOCKED))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "study_event " + eventId + " is " + current.getName()
                            + " — edit blocked until un-signed / unlocked"));
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        java.util.Date now = new java.util.Date();

        if (body.dateStarted() != null) {
            Date oldStart = ev.getDateStarted();
            Date target = clearStart ? null : newStart;
            if (!java.util.Objects.equals(oldStart, target)) {
                ev.setDateStarted(target);
                writeEventFieldAudit(auditDAO, ub, currentStudy, ss, ev,
                        "date_start",
                        oldStart == null ? "" : ISO_DATE.format(oldStart),
                        target == null ? "" : ISO_DATE.format(target));
            }
        }
        if (body.dateEnded() != null) {
            Date oldEnd = ev.getDateEnded();
            Date target = clearEnd ? null : newEnd;
            if (!java.util.Objects.equals(oldEnd, target)) {
                ev.setDateEnded(target);
                writeEventFieldAudit(auditDAO, ub, currentStudy, ss, ev,
                        "date_end",
                        oldEnd == null ? "" : ISO_DATE.format(oldEnd),
                        target == null ? "" : ISO_DATE.format(target));
            }
        }
        if (body.location() != null) {
            String oldLoc = ev.getLocation() == null ? "" : ev.getLocation();
            String newLoc = body.location().trim();
            if (!oldLoc.equals(newLoc)) {
                ev.setLocation(newLoc);
                writeEventFieldAudit(auditDAO, ub, currentStudy, ss, ev,
                        "location", oldLoc, newLoc);
            }
        }
        if (newStatusId != null) {
            int oldStatusId = ev.getSubjectEventStatus() == null
                    ? 0 : ev.getSubjectEventStatus().getId();
            if (oldStatusId != newStatusId) {
                ev.setSubjectEventStatus(SubjectEventStatus.get(newStatusId));
                writeEventFieldAudit(auditDAO, ub, currentStudy, ss, ev,
                        "subject_event_status_id",
                        String.valueOf(oldStatusId), String.valueOf(newStatusId));
            }
        }

        ev.setUpdater(ub);
        ev.setUpdatedDate(now);
        seDao.update(ev);

        LOG.info("Study event update: id={} subject={} by user={} role={}",
                ev.getId(), ss.getLabel(), ub.getName(), roleId);

        StudyEventBean refreshed = (StudyEventBean) seDao.findByPK(ev.getId());
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean def =
                (StudyEventDefinitionBean) sedDao.findByPK(refreshed.getStudyEventDefinitionId());
        StudyEventDto dto = new StudyEventDto(
                String.valueOf(refreshed.getId()),
                ss.getLabel(),
                def == null ? "" : def.getOid(),
                def == null ? "" : def.getName(),
                refreshed.getSampleOrdinal(),
                refreshed.getDateStarted() == null ? null : ISO_DATE.format(refreshed.getDateStarted()),
                refreshed.getDateEnded() == null ? null : ISO_DATE.format(refreshed.getDateEnded()),
                blankToNull(refreshed.getLocation()),
                statusForSubjectEventStatus(refreshed.getSubjectEventStatus()),
                def != null && def.isRepeating());
        return ResponseEntity.ok(dto);
    }

    /**
     * Phase E A4 — soft-cancel a study event. Mirrors the legacy
     * {@code RemoveStudyEventServlet} cascade: the event flips to
     * {@link Status#DELETED}; nested {@code event_crf} → {@code item_data}
     * rows cascade to {@link Status#AUTO_DELETED}.
     *
     * <p>Guards: 401 / 400 (no study) / 403 (DM/Admin only) / 404
     * (visibility) / 409 (event already DELETED, or SubjectEventStatus
     * is SIGNED/LOCKED — terminal).
     *
     * <p>Audit: handled by the existing {@code study_event_trigger};
     * no controller-side emission needed.
     */
    @DeleteMapping("/{id:[0-9]+}")
    public ResponseEntity<?> cancel(@PathVariable("id") int eventId,
                                    HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound to the session."));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!EventEditAuthorization.roleMayCancel(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit cancelling study events"));
        }

        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyEventBean ev = (StudyEventBean) seDao.findByPK(eventId);
        if (ev == null || ev.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_event with id " + eventId));
        }

        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ev.getStudySubjectId());
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "study_event " + eventId + " belongs to a different study"));
        }
        ResponseEntity<?> lockRefusal = SubjectLockGuard.refuseIfLocked(ss, "cancelling event " + eventId);
        if (lockRefusal != null) {
            return lockRefusal;
        }
        if (ev.getStatus() != null && ev.getStatus().equals(Status.DELETED)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "study_event " + eventId + " is already cancelled"));
        }
        SubjectEventStatus seStatus = ev.getSubjectEventStatus();
        if (seStatus != null && (seStatus.equals(SubjectEventStatus.SIGNED)
                || seStatus.equals(SubjectEventStatus.LOCKED))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "study_event " + eventId + " is " + seStatus.getName()
                            + " — un-sign / unlock before cancelling"));
        }

        // Parent: study_event → DELETED. Children: event_crf +
        // item_data → AUTO_DELETED. Mirrors RemoveStudyEventServlet.
        ev.setStatus(Status.DELETED);
        ev.setUpdater(ub);
        ev.setUpdatedDate(new java.util.Date());
        seDao.update(ev);

        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        ItemDataDAO idDao = new ItemDataDAO(dataSource);
        java.util.ArrayList<EventCRFBean> ecs = ecDao.findAllByStudyEvent(ev);
        for (EventCRFBean ec : ecs) {
            if (ec.getStatus() != null && ec.getStatus().equals(Status.DELETED)) continue;
            ec.setStatus(Status.AUTO_DELETED);
            ec.setUpdater(ub);
            ec.setUpdatedDate(new java.util.Date());
            ecDao.update(ec);

            java.util.ArrayList<ItemDataBean> items = idDao.findAllByEventCRFId(ec.getId());
            for (ItemDataBean it : items) {
                if (it.getStatus() != null && it.getStatus().equals(Status.DELETED)) continue;
                it.setStatus(Status.AUTO_DELETED);
                it.setUpdater(ub);
                it.setUpdatedDate(new java.util.Date());
                idDao.update(it);
            }
        }

        LOG.info("Study event cancel: id={} subject={} by user={} role={}",
                ev.getId(), ss.getLabel(), ub.getName(), roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Phase E.6 restore-quickwins — inverse of {@link #cancel}. Restores
     * a previously soft-deleted study event: parent flips back to
     * {@link Status#AVAILABLE}; nested {@code event_crf} +
     * {@code item_data} rows currently in {@link Status#AUTO_DELETED}
     * cascade back to {@link Status#AVAILABLE}.
     *
     * <p>Mirrors {@code RestoreStudyEventServlet} (the post-confirm
     * branch) but expressed as a single REST endpoint — the SPA renders
     * its own confirm dialog and trusts the click.
     *
     * <p>Guards (order matters):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 403} — caller's role is not DM/Admin (same gate as
     *       {@link #cancel}; restore is the inverse half).</li>
     *   <li>{@code 404} — no study_event with that id.</li>
     *   <li>{@code 403} — event's subject lives outside the visible
     *       study set.</li>
     *   <li>{@code 409} — event is not currently DELETED (idempotent
     *       restore would be wrong here — caller likely has stale UI
     *       state and should refetch).</li>
     *   <li>{@code 409} — study_subject is removed; legacy
     *       {@code RestoreStudyEventServlet} blocks the restore in that
     *       case ("study_subject_has_been_deleted") and the SPA
     *       surfaces the same error.</li>
     * </ol>
     *
     * <p>Returns the restored {@link StudyEventDto} so the SPA can
     * splice it back into the events store without a list refetch.
     */
    @PostMapping("/{id:[0-9]+}/restore")
    public ResponseEntity<?> restore(@PathVariable("id") int eventId,
                                     HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound to the session."));
        }
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!EventEditAuthorization.roleMayCancel(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit restoring study events"));
        }

        StudyEventDAO seDao = new StudyEventDAO(dataSource);
        StudyEventBean ev = (StudyEventBean) seDao.findByPK(eventId);
        if (ev == null || ev.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study_event with id " + eventId));
        }

        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = (StudySubjectBean) ssDao.findByPK(ev.getStudySubjectId());
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        if (ss == null || !visible.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "study_event " + eventId + " belongs to a different study"));
        }
        if (ev.getStatus() == null || !ev.getStatus().equals(Status.DELETED)) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "study_event " + eventId + " is not currently removed"));
        }
        // Mirror RestoreStudyEventServlet's parent-state check — a
        // removed subject blocks the event restore so the matrix
        // doesn't end up with live events under a dead parent.
        if (ss.getStatus() != null
                && (ss.getStatus().equals(Status.DELETED)
                        || ss.getStatus().equals(Status.AUTO_DELETED))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "study_event " + eventId + " cannot be restored — "
                            + "study subject is removed (restore subject first)"));
        }

        ev.setStatus(Status.AVAILABLE);
        ev.setUpdater(ub);
        ev.setUpdatedDate(new java.util.Date());
        seDao.update(ev);

        // Cascade AUTO_DELETED children back to AVAILABLE. Hard-DELETED
        // children stay put — that's the operator-issued delete, not
        // the parent cascade.
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);
        ItemDataDAO idDao = new ItemDataDAO(dataSource);
        java.util.ArrayList<EventCRFBean> ecs = ecDao.findAllByStudyEvent(ev);
        for (EventCRFBean ec : ecs) {
            if (ec.getStatus() == null || !ec.getStatus().equals(Status.AUTO_DELETED)) continue;
            ec.setStatus(Status.AVAILABLE);
            ec.setUpdater(ub);
            ec.setUpdatedDate(new java.util.Date());
            ecDao.update(ec);

            java.util.ArrayList<ItemDataBean> items = idDao.findAllByEventCRFId(ec.getId());
            for (ItemDataBean it : items) {
                if (it.getStatus() == null || !it.getStatus().equals(Status.AUTO_DELETED)) continue;
                it.setStatus(Status.AVAILABLE);
                it.setUpdater(ub);
                it.setUpdatedDate(new java.util.Date());
                idDao.update(it);
            }
        }

        LOG.info("Study event restore: id={} subject={} by user={} role={}",
                ev.getId(), ss.getLabel(), ub.getName(), roleId);

        StudyEventBean refreshed = (StudyEventBean) seDao.findByPK(eventId);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean def =
                (StudyEventDefinitionBean) sedDao.findByPK(refreshed.getStudyEventDefinitionId());
        StudyEventDto dto = new StudyEventDto(
                String.valueOf(refreshed.getId()),
                ss.getLabel(),
                def == null ? "" : def.getOid(),
                def == null ? "" : def.getName(),
                refreshed.getSampleOrdinal(),
                refreshed.getDateStarted() == null ? null : ISO_DATE.format(refreshed.getDateStarted()),
                refreshed.getDateEnded() == null ? null : ISO_DATE.format(refreshed.getDateEnded()),
                blankToNull(refreshed.getLocation()),
                statusForSubjectEventStatus(refreshed.getSubjectEventStatus()),
                def != null && def.isRepeating());
        return ResponseEntity.ok(dto);
    }

    private static void writeEventFieldAudit(AuditEventDAO dao,
                                             UserAccountBean ub,
                                             StudyBean study,
                                             StudySubjectBean ss,
                                             StudyEventBean ev,
                                             String columnName,
                                             String oldValue,
                                             String newValue) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(ub.getId());
            ae.setStudyId(study.getId());
            ae.setSubjectId(ss == null ? 0 : ss.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setSubjectName(ss != null && ss.getLabel() != null ? ss.getLabel() : "");
            ae.setAuditTable("study_event");
            ae.setEntityId(ev.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("study_event_update: " + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "'");
            dao.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for study_event {} field {} (continuing): {}",
                    ev.getId(), columnName, e.getMessage());
        }
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Resolve a CRF display name when {@link EventDefinitionCRFBean#getCrfName()}
     * comes back blank. Falls back through the CRF version's parent
     * CRF row, then the version name itself. Used by Phase E.6's
     * {@code getEventDetail} adapter.
     */
    private static String lookupCrfName(CRFDAO crfDao, int crfId, CRFVersionBean cv) {
        if (crfId > 0) {
            CRFBean crf = (CRFBean) crfDao.findByPK(crfId);
            if (crf != null && crf.getName() != null && !crf.getName().isBlank()) {
                return crf.getName();
            }
        }
        if (cv != null && cv.getName() != null && !cv.getName().isBlank()) {
            return cv.getName();
        }
        return "";
    }

    /**
     * Map an {@link EventCRFBean}'s lifecycle into a stable wire
     * vocabulary the SPA consumes. Returns {@code "not-started"} for
     * a null bean (i.e. the event_definition_crf slot is unstarted).
     *
     * <p>Order of precedence (mirrors the legacy
     * {@code ViewSectionDataEntryServlet}):
     * <ol>
     *   <li>Status flips to LOCKED (event_crf.status_id 2)
     *       → {@code signed}</li>
     *   <li>completion_status_id covers the rest:
     *       1=not-started, 2=data-being-entered, 3/4/5=completed,
     *       6=stopped, 7=signed</li>
     *   <li>Otherwise: date_completed set → completed; else
     *       not-started.</li>
     * </ol>
     */
    private static String statusForEventCrf(EventCRFBean ec) {
        if (ec == null || ec.getId() == 0) return "not-started";
        // Phase E.6 restore-quickwins: a parent-cascaded soft-delete
        // (AUTO_DELETED) leaves the event_crf row present in the
        // EventDetail surface so the Restore action can render against
        // it. We surface a dedicated 'removed' status so the SPA can
        // distinguish "restorable" from "in progress".
        if (ec.getStatus() != null && ec.getStatus().equals(Status.AUTO_DELETED)) {
            return "removed";
        }
        if (ec.getStatus() != null && ec.getStatus().equals(Status.UNAVAILABLE)) {
            return "signed";
        }
        // date_completed is the legacy "operator marked complete" stamp;
        // honour it before the completion_status_id switch. The Mark
        // Complete handler stamps date_completed but does NOT always
        // bump completion_status_id (legacy DataEntryServlet leaves it
        // at 1 in the demo seed + some happy paths), so trusting the
        // switch alone surfaces a complete CRF as "Nicht begonnen" in
        // the event-detail view. Mirror the precedence the
        // dataEntryStage column already uses on SubjectsApiController.
        if (ec.getDateCompleted() != null) return "completed";
        int csi = ec.getCompletionStatusId();
        return switch (csi) {
            case 1 -> "not-started";
            case 2 -> "data-entry-started";
            case 3, 4, 5 -> "completed";
            case 6 -> "stopped";
            case 7 -> "signed";
            default -> "not-started";
        };
    }

    private static String statusForSubjectEventStatus(SubjectEventStatus s) {
        if (s == null) return "not-scheduled";
        return switch (s.getId()) {
            case 1 -> "scheduled";
            case 2 -> "not-scheduled";
            case 3 -> "data-entry-started";
            case 4 -> "completed";
            case 5 -> "stopped";
            case 6 -> "skipped";
            case 7 -> "locked";
            case 8 -> "signed";
            default -> "not-scheduled";
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Body of POST /pages/api/v1/events — schedule a new study event. */
    public record ScheduleEventRequest(
            String subjectId,
            String eventDefinitionOid,
            String dateStarted,
            String location
    ) {}

    /**
     * Body of POST /pages/api/v1/events/{id}/crfs/{edcId}:start.
     * Both fields optional; {@code crfVersionId} omitted falls back
     * to the {@code event_definition_crf.default_version_id}.
     */
    public record StartEventCrfRequest(Integer crfVersionId) {}

    /**
     * Response of POST /pages/api/v1/events/{id}/crfs/{edcId}:start.
     * The SPA uses {@code eventCrfOid} to route into the existing
     * CrfEntryView; the numeric {@code eventCrfId} is included so
     * deep links (audit log → CRF) can build their own URLs without
     * a follow-up GET.
     */
    public record StartEventCrfResponse(
            int eventCrfId,
            String eventCrfOid,
            int eventId,
            int eventDefinitionCrfId,
            int crfVersionId,
            String status
    ) {}
}
