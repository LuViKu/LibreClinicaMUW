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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "subjectId", required = false) String subjectIdFilter,
            @RequestParam(value = "status", required = false) String statusFilter,
            @RequestParam(value = "eventDefinitionOid", required = false) String defOidFilter,
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

                if (defOidFilter != null && !defOidFilter.isBlank()
                        && !defOidFilter.equalsIgnoreCase(def.getOid())) continue;

                String status = statusForSubjectEventStatus(ev.getSubjectEventStatus());
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

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

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
}
