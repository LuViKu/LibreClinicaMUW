/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.audit.FailureAuditTemplate;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 — Subject Matrix + Subject Detail adapter.
 *
 * <p>Exposes the Investigator's Subject Matrix at
 * {@code GET /pages/api/v1/subjects} and the Subject Detail view at
 * {@code GET /pages/api/v1/subjects/{oid}} +
 * {@code GET /pages/api/v1/subjects/{oid}/preflightForSign} as JSON
 * for the Vue SPA. The legacy JSP path at {@code /ListStudySubjects}
 * keeps working in parallel during the bake-in window per DR-018.
 *
 * <p><strong>Milestone 2 (subject-matrix completion).</strong> The
 * slice #1 adapter shipped identity columns only ({@code id},
 * {@code secondaryId}, {@code siteOid}, {@code siteLabel},
 * {@code gender}, {@code yearOfBirth}, {@code enrolledOn}). M2
 * populates the three stubbed fields from real DB data:
 * <ul>
 *   <li>{@code events[]} — walks
 *       {@link StudyEventDAO#findAllByStudySubject(StudySubjectBean)} per
 *       subject, joining each event to its {@link StudyEventDefinitionBean}
 *       for the OID + display label. Ordered by definition ordinal so
 *       V1 / V2 / V3 render in protocol order.</li>
 *   <li>{@code signed} — true iff the {@code study_subject.status_id}
 *       is {@code 8 (SIGNED)}. The legacy
 *       {@code SignStudySubjectServlet} updates both the
 *       {@code study_subject} row and every {@code study_event} row in
 *       one transaction, so the {@code study_subject} status is the
 *       authoritative indicator and isn't subject to drift from later
 *       event-status flips. (Brief's converse guard: an unsigned
 *       {@code study_subject} never reports signed regardless of the
 *       per-event statuses.)</li>
 *   <li>{@code openQueries} — count of {@code discrepancy_note} rows
 *       attached (via {@code dn_item_data_map}) to an {@code item_data}
 *       belonging to an {@code event_crf} of the event, where the note
 *       is a parent (parent_dn_id IS NULL) and the resolution status is
 *       open ({@code resolution_status_id IN (1,2,3)} — New / Updated /
 *       Resolution Proposed). Closed (4) and Not Applicable (5) do not
 *       count. Computed in a single batch query per study (one round
 *       trip total, not N×M).</li>
 * </ul>
 *
 * <p><strong>Active study selection:</strong> reads from
 * {@code session.getAttribute("study")} which is set by the legacy
 * {@code /MainMenu} flow or by the M1
 * {@code POST /pages/api/v1/me/activeStudy} endpoint. If no study is
 * bound the endpoint returns {@code 400 Bad Request}.
 *
 * <p><strong>Authorization:</strong> The chain-level
 * {@code .anyRequest().hasRole("USER")} rule in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig}
 * gates this controller. Per-site filtering for non-investigator
 * roles is handled implicitly via the legacy
 * {@link StudySubjectDAO#findAllByStudyId(int)} call's study scoping;
 * cross-site authorization is a separate compliance slice.
 */
@RestController
@RequestMapping("/api/v1/subjects")
@Tag(name = "Subjects", description = "Subject Matrix + Add/Sign Subject.")
public class SubjectsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectsApiController.class);

    private final DataSource dataSource;
    private final SecurityManager securityManager;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public SubjectsApiController(@Qualifier("dataSource") DataSource dataSource,
                                 @Qualifier("securityManager") SecurityManager securityManager,
                                 SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.securityManager = securityManager;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = SubjectListItemDto.class)))
    public ResponseEntity<?> list(
            @org.springframework.web.bind.annotation.RequestParam(
                    value = "includeRemoved", required = false, defaultValue = "false")
            boolean includeRemoved,
            HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            // SecurityConfig should have blocked this already; defence-in-depth.
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);

        // A4 — per-site visibility. For a top-level study with role
        // narrowing, this returns the user-grant-narrowed sub-tree;
        // for a site, just the site itself. Loop strategy: one DAO
        // hit per visible study id and merge. Acceptable for typical
        // studies (≤ 10 sites); see SiteVisibilityFilter perf note.
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        List<StudySubjectBean> rows = new ArrayList<>();
        for (Integer sid : visibleStudyIds) {
            List<StudySubjectBean> chunk = studySubjectDAO.findAllByStudyId(sid);
            if (chunk != null) rows.addAll(chunk);
        }

        // Phase E.6 subject-lifecycle — when includeRemoved=false (the
        // SPA's default), strip soft-deleted rows so the matrix matches
        // the legacy ListStudySubjects JSP behaviour. The DAO returns
        // every row regardless of status; the visibility filter
        // upstream cares about study scope, not lifecycle state.
        // includeRemoved=true keeps DELETED / AUTO_DELETED rows so the
        // DM / Admin "Show removed" toggle can render them with a
        // distinct style + Restore button.
        if (!includeRemoved) {
            List<StudySubjectBean> filteredRows = new ArrayList<>(rows.size());
            for (StudySubjectBean ss : rows) {
                Status st = ss.getStatus();
                if (st == null || (!Status.DELETED.equals(st) && !Status.AUTO_DELETED.equals(st))) {
                    filteredRows.add(ss);
                }
            }
            rows = filteredRows;
        }

        // Cache SubjectBean lookups so a study with multiple participations
        // for the same person doesn't re-hit subject table N times.
        Map<Integer, SubjectBean> subjectCache = new HashMap<>();
        // Cache StudyEventDefinitionBean lookups across subjects — the
        // matrix typically has very few definitions per study (3 in the
        // demo seed) and N×M lookups would be wasteful.
        Map<Integer, StudyEventDefinitionBean> definitionCache = new HashMap<>();

        // Single-query open-query aggregation per study_event_id so the
        // per-event openQueries cell is filled in O(1) per cell instead
        // of one DAO round trip per event.
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());

        // Phase E.6 subject-lifecycle — single-query group-assignment
        // aggregation per study_subject.id so the matrix cell is filled
        // in O(1) per row instead of one DAO round trip per subject.
        Map<Integer, List<GroupAssignmentSnapshot>> groupAssignmentsBySubject =
                loadGroupAssignmentsForStudy(visibleStudyIds);

        List<SubjectListItemDto> out = new ArrayList<>(rows.size());
        for (StudySubjectBean ss : rows) {
            SubjectBean subj = subjectCache.computeIfAbsent(ss.getSubjectId(), subjectDAO::findByPK);
            out.add(toDto(ss, subj, currentStudy, studyEventDAO, studyEventDefinitionDAO,
                    definitionCache, openQueriesByEvent, groupAssignmentsBySubject));
        }

        LOG.debug("Subject Matrix adapter served {} rows for study {} (user {})",
                out.size(), currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.ok(out);
    }

    /**
     * Phase E.4 M3 — Subject Detail endpoint.
     *
     * <p>Returns the same identity + matrix-overlap fields the list
     * endpoint returns, plus per-event date_start / date_end /
     * location / dataEntryStage and subject-level studyOid / studyName.
     *
     * <p>Lookup is by {@code study_subject.oc_oid} (human-readable OID
     * like {@code "SS_M001"}) via {@link StudySubjectDAO#findByOid}.
     * The DAO returns a bean with all numeric IDs but {@code studyId=0}
     * — we cross-check the returned bean's {@code studyId} against the
     * currently-bound study and 404 if there's no match (single status
     * code for "not in your study" + "doesn't exist" — same as the
     * legacy {@code /ViewStudySubject} servlet).
     *
     * <p>If the OID does exist but belongs to a different study, we
     * return {@code 403 Forbidden} rather than 404 — exposing the
     * existence of subjects in other studies is a separate compliance
     * decision, and the legacy JSP renders a 403 page in the same
     * situation.
     */
    @GetMapping("/{studySubjectOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = SubjectDetailDto.class)))
    public ResponseEntity<?> getOne(@PathVariable("studySubjectOid") String studySubjectOid,
                                    HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = studySubjectDAO.findByOid(studySubjectOid);
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with OID '" + studySubjectOid + "' not found."
            ));
        }
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            // The subject exists but is outside the user's visible
            // study sub-tree. 403 not 404 — matches the legacy
            // /ViewStudySubject behaviour and gives the SPA a clear
            // "you don't have access" signal without leaking whether
            // the OID exists elsewhere.
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Subject is not in the currently active study."
            ));
        }

        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);

        SubjectBean subj = subjectDAO.findByPK(ss.getSubjectId());
        Map<Integer, StudyEventDefinitionBean> definitionCache = new HashMap<>();
        // Reuse the matrix's single-shot aggregation — for a one-subject
        // call this is overkill (a smaller per-subject query would do)
        // but the cost is negligible against the convenience of reusing
        // the same code path + the same correctness guarantees.
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());

        SubjectDetailDto dto = toDetailDto(ss, subj, currentStudy, studyEventDAO,
                studyEventDefinitionDAO, eventCRFDAO, definitionCache, openQueriesByEvent);

        LOG.debug("Subject Detail adapter served {} (study {}, user {})",
                ss.getOid(), currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.ok(dto);
    }

    /**
     * Phase E.4 M3 — Sign Subject preflight checks endpoint.
     *
     * <p>Returns five checks the SPA's Sign Subject view consumes (M8
     * lands the UI; M3 ships the endpoint so M8 doesn't need backend
     * changes). Status semantics per the mockup at
     * {@code docs/development/modernization/phase-e/ux-mockups/investigator-sign-subject.html}.
     *
     * <p>Convenience booleans {@code subjectAlreadySigned} +
     * {@code userRoleCanSign} are surfaced top-level so the SPA can
     * render the primary-action button without re-walking the check
     * list.
     */
    @GetMapping("/{studySubjectOid}/preflightForSign")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = SignPreflightDto.class)))
    public ResponseEntity<?> preflightForSign(@PathVariable("studySubjectOid") String studySubjectOid,
                                              HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = studySubjectDAO.findByOid(studySubjectOid);
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with OID '" + studySubjectOid + "' not found."
            ));
        }
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Subject is not in the currently active study."
            ));
        }

        SignPreflightDto out = computePreflight(ss, currentStudy, currentRole, currentUser);

        LOG.debug("Sign-preflight for {} (study {}, user {}): {} blocking, {} warnings",
                ss.getOid(), currentStudy.getOid(), currentUser.getName(),
                out.blockingFailures(), out.warnings());

        return ResponseEntity.ok(out);
    }

    /**
     * Phase E.4 M3 + M8 — shared preflight computation.
     *
     * <p>Extracted so both the {@code GET /preflightForSign} endpoint
     * (M3 — read-only inspection) and the {@code POST /sign} endpoint
     * (M8 — gating check before persistence) consume the same five
     * named checks. The M3 endpoint serialises the result verbatim; the
     * M8 endpoint inspects {@link SignPreflightDto#blockingFailures()}
     * to decide whether to proceed (and explicitly tolerates the
     * {@code subject-not-signed} fail because the converse is the
     * EXPECTED state when invoking sign — see M8 brief).
     *
     * <p>Check semantics (per M3 + the mockup at
     * {@code docs/development/modernization/phase-e/ux-mockups/investigator-sign-subject.html}):
     * <ul>
     *   <li>{@code events-complete} — pass if all scheduled events
     *       (status ∈ {4, 8}); warn if any in-progress (3); fail if any
     *       still scheduled with no data (1).</li>
     *   <li>{@code crfs-complete} — pass if all event_crfs have a
     *       date_completed; warn if some in initial data entry; fail
     *       if zero CRFs started.</li>
     *   <li>{@code open-queries} — warn-only (open queries do NOT
     *       block signing per the mockup).</li>
     *   <li>{@code subject-not-signed} — pass if not yet signed; fail
     *       if already signed. M8 ignores this fail when computing the
     *       proceed/abort decision (signing requires this to be a
     *       pass — but the unsigned state is the precondition, not a
     *       blocker, for the sign action itself).</li>
     *   <li>{@code user-role-can-sign} — pass if Investigator or
     *       Study Director; fail otherwise.</li>
     * </ul>
     */
    private SignPreflightDto computePreflight(StudySubjectBean ss, StudyBean currentStudy,
                                              StudyUserRoleBean currentRole,
                                              UserAccountBean currentUser) {
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);

        List<StudyEventBean> events = studyEventDAO.findAllByStudySubject(ss);
        if (events == null) events = Collections.emptyList();

        // ------ Check 1: events-complete ------
        // Pass if every scheduled (id != 2) event has status ∈ {4, 8};
        // warn if any in-progress (3) or scheduled (1) but not started;
        // fail if any "scheduled but never started" — which by the brief
        // means subject_event_status_id=1 (scheduled, no data yet).
        //
        // Implementation: scheduled (1) with no event_crf rows = fail;
        // in-progress (3) = warn; all completed/signed = pass.
        int completedCount = 0;
        int scheduledNotStarted = 0;
        int inProgressEvents = 0;
        int totalSchedulable = 0;
        for (StudyEventBean ev : events) {
            int st = ev.getSubjectEventStatus() == null ? 0 : ev.getSubjectEventStatus().getId();
            if (st == 2) continue; // not-scheduled — not part of the casebook
            totalSchedulable++;
            if (st == 4 || st == 8) {
                completedCount++;
            } else if (st == 3) {
                inProgressEvents++;
            } else if (st == 1) {
                scheduledNotStarted++;
            }
        }
        SignPreflightDto.CheckRow eventsCheck;
        if (scheduledNotStarted > 0) {
            eventsCheck = new SignPreflightDto.CheckRow(
                    "events-complete", "fail",
                    "Not all scheduled events have data entry",
                    scheduledNotStarted + " of " + totalSchedulable + " events still scheduled with no data."
            );
        } else if (inProgressEvents > 0) {
            eventsCheck = new SignPreflightDto.CheckRow(
                    "events-complete", "warn",
                    "Some scheduled events are still in progress",
                    inProgressEvents + " of " + totalSchedulable + " events have data entry in progress."
            );
        } else {
            eventsCheck = new SignPreflightDto.CheckRow(
                    "events-complete", "pass",
                    "All scheduled events have at least one CRF completed",
                    completedCount + " of " + totalSchedulable + " events complete or signed."
            );
        }

        // ------ Check 2: crfs-complete ------
        // Pass if every event_crf has date_completed set; warn if some
        // are still in initial-data-entry (date_completed null but
        // completion_status_id=1 — the seed convention). The
        // DataEntryStage taxonomy formally has UNCOMPLETED at id=1 and
        // INITIAL_DATA_ENTRY_COMPLETE at id=3, but the seed treats
        // {completion_status_id=1 AND date_completed IS NOT NULL} as
        // complete — we honour that.
        int totalCrfs = 0;
        int completedCrfs = 0;
        int inProgressCrfs = 0;
        for (StudyEventBean ev : events) {
            List<EventCRFBean> ecs = eventCRFDAO.findAllByStudyEvent(ev);
            if (ecs == null) continue;
            for (EventCRFBean ec : ecs) {
                totalCrfs++;
                if (ec.getDateCompleted() != null) {
                    completedCrfs++;
                } else {
                    inProgressCrfs++;
                }
            }
        }
        SignPreflightDto.CheckRow crfsCheck;
        if (totalCrfs == 0) {
            crfsCheck = new SignPreflightDto.CheckRow(
                    "crfs-complete", "fail",
                    "No CRFs have been started yet",
                    "Sign Subject requires at least one CRF with data entry complete."
            );
        } else if (inProgressCrfs > 0) {
            crfsCheck = new SignPreflightDto.CheckRow(
                    "crfs-complete", "warn",
                    "Some CRFs still in initial data entry",
                    completedCrfs + " of " + totalCrfs + " required CRFs marked complete."
            );
        } else {
            crfsCheck = new SignPreflightDto.CheckRow(
                    "crfs-complete", "pass",
                    "All required CRFs are marked complete",
                    completedCrfs + " of " + totalCrfs + " required CRFs in status Data entry complete."
            );
        }

        // ------ Check 3: open-queries (warn-only — never blocks) ------
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());
        int subjectOpenQueries = 0;
        for (StudyEventBean ev : events) {
            subjectOpenQueries += openQueriesByEvent.getOrDefault(ev.getId(), 0);
        }
        SignPreflightDto.CheckRow openQueriesCheck;
        if (subjectOpenQueries == 0) {
            openQueriesCheck = new SignPreflightDto.CheckRow(
                    "open-queries", "pass",
                    "No open discrepancies attached to this subject",
                    "All queries resolved."
            );
        } else {
            openQueriesCheck = new SignPreflightDto.CheckRow(
                    "open-queries", "warn",
                    subjectOpenQueries + " open discrepancies attached to this subject",
                    "Open queries do not block signing, but you should reconcile them first when possible."
            );
        }

        // ------ Check 4: subject-not-signed ------
        boolean alreadySigned = ss.getStatus() != null && ss.getStatus().equals(Status.SIGNED);
        SignPreflightDto.CheckRow signedCheck;
        if (alreadySigned) {
            signedCheck = new SignPreflightDto.CheckRow(
                    "subject-not-signed", "fail",
                    "Subject is already signed",
                    "Signing is a one-way action. To re-sign, an administrator must reset the subject first."
            );
        } else {
            signedCheck = new SignPreflightDto.CheckRow(
                    "subject-not-signed", "pass",
                    "No previous signature on this subject",
                    "This is a first signature, not a re-sign after administrator reset."
            );
        }

        // ------ Check 5: user-role-can-sign ------
        boolean canSign = false;
        String legacyRoleName = null;
        if (currentRole != null && currentRole.getRole() != null) {
            Role r = currentRole.getRole();
            legacyRoleName = r.getName();
            // Investigator (id=4) or Study Director (id=3) can sign;
            // ra / ra2 / coordinator / monitor cannot.
            canSign = (r.getId() == Role.INVESTIGATOR.getId()
                    || r.getId() == Role.STUDYDIRECTOR.getId());
        }
        SignPreflightDto.CheckRow roleCheck;
        if (canSign) {
            roleCheck = new SignPreflightDto.CheckRow(
                    "user-role-can-sign", "pass",
                    "Your role allows signing this subject",
                    "Signed in as " + currentUser.getName() + " (" + legacyRoleName + ")."
            );
        } else {
            String detail = (legacyRoleName == null)
                    ? "No study-scoped role bound — pick a study first."
                    : "Your role (" + legacyRoleName + ") cannot sign subjects. Only Investigator and Study Director may sign.";
            roleCheck = new SignPreflightDto.CheckRow(
                    "user-role-can-sign", "fail",
                    "Your role does not allow signing",
                    detail
            );
        }

        List<SignPreflightDto.CheckRow> checks = List.of(
                eventsCheck, crfsCheck, openQueriesCheck, signedCheck, roleCheck);
        int blocking = 0;
        int warnings = 0;
        for (SignPreflightDto.CheckRow c : checks) {
            if ("fail".equals(c.status())) blocking++;
            else if ("warn".equals(c.status())) warnings++;
        }

        return new SignPreflightDto(
                checks,
                blocking,
                warnings,
                alreadySigned,
                canSign
        );
    }

    /**
     * Phase E.4 M4 — Add Subject endpoint.
     *
     * <p>Enrols a new subject in the currently-active study. Mirrors the
     * persistence flow of the legacy {@code /AddNewSubject} servlet
     * (subject row → study_subject row, both with {@code status_id=1}
     * AVAILABLE and {@code owner_id} from the session user) but emits a
     * pure-JSON response shape suitable for the SPA's
     * {@code AddSubjectView}.
     *
     * <p><strong>Authorization:</strong> chain-level
     * {@code .anyRequest().hasRole("USER")} gates the endpoint. The
     * legacy servlet additionally checks for Investigator / CRC /
     * Coordinator on the bound study before allowing the action — that
     * compliance slice is deferred until per-role authorization lands
     * uniformly across the SPA endpoints (out of M4 scope).
     *
     * <p><strong>Validation:</strong> all rules are enforced server-side
     * and the controller returns ALL failing rules in a single 400
     * response rather than first-fail. The SPA's
     * {@code validateAddSubject} mirror is kept as instant client-side
     * UX feedback only — the server remains authoritative (DR-008).
     *
     * <p><strong>Persistence:</strong> bypasses {@link SubjectDAO#create}
     * because that DAO silently {@code null}s any gender code outside
     * {@code 'm'/'f'} (its {@code switch} only handles those two cases).
     * Inline SQL keeps full control over the {@code O}/{@code U} codes
     * the SPA accepts. The {@code study_subject} insert reuses
     * {@link StudySubjectDAO#create} so OID generation + collision
     * randomisation come for free.
     *
     * <p><strong>No transaction wrapping:</strong> matches the legacy
     * servlet's pattern (subject insert + study_subject insert without
     * a shared transaction). A future hardening slice can add
     * {@code @Transactional} once the data-access layer stops opening
     * its own connections per DAO call.
     *
     * @param body JSON body matching {@link AddSubjectRequest}
     * @param session HTTP session (active study + user bean)
     * @return 201 + {@link SubjectDetailDto} on success;
     *         400 + {@link ValidationErrorBody} on field errors;
     *         400 if no study is bound;
     *         401 if no userBean (defence-in-depth — SecurityConfig
     *         should have blocked already);
     *         500 if persistence fails after validation passes.
     */
    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = SubjectDetailDto.class)))
    public ResponseEntity<?> create(@RequestBody(required = false) AddSubjectRequest body,
                                    HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed",
                    List.of(new ValidationErrorBody.FieldError(
                            "id", "Request body is required."))));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);

        List<ValidationErrorBody.FieldError> errors =
                validateAddSubject(body, currentStudy, studySubjectDAO);

        // Phase E.6 subject-lifecycle — Person-ID re-enrol validation +
        // group-assignment validation gate fold into the same errors
        // list so the SPA renders one consolidated error envelope.
        SubjectGroupAssignmentService groupService =
                new SubjectGroupAssignmentService(dataSource);
        SubjectBean existingByPersonId = null;
        String personId = body.personId() == null ? null : body.personId().trim();
        if (personId != null && !personId.isEmpty()) {
            SubjectDAO subjectDAO = new SubjectDAO(dataSource);
            existingByPersonId = subjectDAO.findByUniqueIdentifier(personId);
            if (existingByPersonId != null && existingByPersonId.getId() != 0) {
                // 409-style conflict surfaced as a validation error so the
                // SPA can flag the field directly: re-enrolling the same
                // person in the same study is a hard refusal (one
                // study_subject row per person per study).
                List<StudySubjectBean> peers = studySubjectDAO.findAllBySubjectId(existingByPersonId.getId());
                if (peers != null) {
                    for (StudySubjectBean peer : peers) {
                        if (peer.getStudyId() == currentStudy.getId()) {
                            errors.add(new ValidationErrorBody.FieldError(
                                    "personId",
                                    "Person-ID '" + personId
                                            + "' is already enrolled in this study"));
                            break;
                        }
                    }
                }
            }
        }

        if (body.groupAssignments() != null) {
            errors.addAll(groupService.validate(currentStudy, body.groupAssignments()));
        }

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        // Phase A1 (2026-06-10) — failure-audit wrap. Any Throwable that
        // leaks out of the persistence block below lands in
        // audit_log_event as an OPERATION_FAILED row (type 61) tagged
        // with the actor, request id, and operation label before the
        // exception propagates to Spring's default error handler. The
        // DAO uses a separate JDBC connection so the row survives any
        // rollback of the in-flight subject + study_subject inserts.
        final SubjectBean existingByPersonIdRef = existingByPersonId;
        final SubjectGroupAssignmentService groupServiceRef = groupService;
        final String personIdRef = personId;
        final String reqId = MDC.get("reqId");
        try {
            return FailureAuditTemplate.runOrAudit(
                    new AuditEventDAO(dataSource),
                    currentUser.getId(),
                    "study_subject",
                    null,
                    "SubjectsApiController.create",
                    reqId,
                    () -> doCreatePersist(body, currentStudy, currentUser,
                            studySubjectDAO, existingByPersonIdRef,
                            groupServiceRef, personIdRef));
        } catch (Exception e) {
            // FailureAuditTemplate has already written the audit row.
            // Surface the same 500 envelope existing callers expect.
            LOG.error("SubjectsApiController.create failed for label={} study={}",
                    body.id(), currentStudy.getOid(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to create subject — see server log."));
        }
    }

    /**
     * Phase A1 (2026-06-10) — the persistence block extracted from
     * {@link #create} so {@link FailureAuditTemplate#runOrAudit} can
     * wrap it cleanly. Throws on any failure so the template's catch
     * lands an OPERATION_FAILED audit row. Returns the success
     * ResponseEntity (201 + SubjectDetailDto) on the happy path.
     */
    private ResponseEntity<?> doCreatePersist(AddSubjectRequest body,
                                              StudyBean currentStudy,
                                              UserAccountBean currentUser,
                                              StudySubjectDAO studySubjectDAO,
                                              SubjectBean existingByPersonId,
                                              SubjectGroupAssignmentService groupService,
                                              String personId) throws Exception {
        // ----- Persist subject row (direct SQL to preserve O/U gender codes) -----
        String trimmedId = body.id().trim();
        String trimmedSecondary = body.secondaryId() == null ? null : body.secondaryId().trim();
        if (trimmedSecondary != null && trimmedSecondary.isEmpty()) trimmedSecondary = null;
        char genderChar = Character.toLowerCase(body.gender().charAt(0));

        // Phase E.6 retrospective-backfill — prefer the new ISO
        // dateOfBirth field when present, fall back to the legacy
        // yearOfBirth → Jan-1 conversion for back-compat with SPA
        // call sites that haven't migrated to the new form yet.
        java.sql.Date dob;
        boolean dobCollected;
        String rawDob = body.dateOfBirth() == null ? "" : body.dateOfBirth().trim();
        if (!rawDob.isEmpty()) {
            dob = java.sql.Date.valueOf(LocalDate.parse(rawDob));
            dobCollected = true;
        } else if (body.yearOfBirth() != null) {
            dob = java.sql.Date.valueOf(LocalDate.of(body.yearOfBirth(), 1, 1));
            dobCollected = false;
        } else {
            dob = null;
            dobCollected = false;
        }
        String trimmedFirstName = body.firstName() == null ? null : body.firstName().trim();
        if (trimmedFirstName != null && trimmedFirstName.isEmpty()) trimmedFirstName = null;
        String trimmedLastName = body.lastName() == null ? null : body.lastName().trim();
        if (trimmedLastName != null && trimmedLastName.isEmpty()) trimmedLastName = null;

        java.sql.Date enrolledOn = java.sql.Date.valueOf(LocalDate.parse(body.enrolledOn()));

        // Phase E.6 subject-lifecycle — Person-ID re-enrol branch.
        // Reuse an existing subject_id rather than inserting a new
        // subject row. The SPA flagged "Reusing existing record" in
        // the AddSubject form when this matched.
        int newSubjectId;
        if (existingByPersonId != null && existingByPersonId.getId() != 0) {
            newSubjectId = existingByPersonId.getId();
            LOG.info("Person-ID re-enrol: reusing subject_id={} for personId={} study={}",
                    newSubjectId, personId, currentStudy.getOid());
        } else {
            // Phase A1 — rethrow so FailureAuditTemplate writes the
            // OPERATION_FAILED row. The outer create() catches Exception
            // and surfaces the legacy 500 envelope.
            newSubjectId = insertSubjectRow(genderChar, dob, dobCollected, currentUser.getId(),
                    personId == null || personId.isEmpty() ? null : personId,
                    trimmedFirstName, trimmedLastName);
        }

        // ----- Persist study_subject row via DAO (reuse OID generator) -----
        StudySubjectBean ssb = new StudySubjectBean();
        ssb.setLabel(trimmedId);
        ssb.setSecondaryLabel(trimmedSecondary == null ? "" : trimmedSecondary);
        ssb.setSubjectId(newSubjectId);
        ssb.setStudyId(currentStudy.getId());
        ssb.setStatus(Status.AVAILABLE);
        ssb.setEnrollmentDate(enrolledOn);
        ssb.setOwner(currentUser);
        // groupLabel intentionally ignored per M4 scope.

        // Phase E.6 Tier 1 — ophthalmology domain fields. Both optional;
        // pre-validated by validateAddSubject (studyEye normalized to
        // upper-case for storage; empty strings become null).
        if (body.studyEye() != null) {
            String eyeRaw = body.studyEye().trim();
            ssb.setStudyEye(eyeRaw.isEmpty() ? null : eyeRaw.toUpperCase());
        }
        if (body.screeningDate() != null && !body.screeningDate().trim().isEmpty()) {
            ssb.setScreeningDate(java.sql.Date.valueOf(LocalDate.parse(body.screeningDate().trim())));
        }

        // Phase A1 — rethrow on study_subject insert failure so
        // FailureAuditTemplate audits the OPERATION_FAILED row.
        ssb = studySubjectDAO.create(ssb);
        if (ssb == null || ssb.getId() == 0) {
            throw new SQLException("study_subject insert returned no PK for label="
                    + trimmedId + " study=" + currentStudy.getOid());
        }

        // Phase E.6 subject-lifecycle — apply the SPA's group-assignment
        // picks. Validation already ran above, so this only enforces
        // the reconciliation algorithm. Failures roll the audit log
        // forward without rolling the study_subject row back — matches
        // legacy semantics where each insert is its own connection.
        if (body.groupAssignments() != null && !body.groupAssignments().isEmpty()) {
            try {
                groupService.reconcile(ssb.getId(), currentUser, body.groupAssignments(),
                        new at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO(dataSource));
            } catch (RuntimeException e) {
                LOG.error("Initial group assignment failed for study_subject={} oid={} (subject enrolled but unassigned)",
                        ssb.getId(), ssb.getOid(), e);
            }
        }

        // ----- Build the response DTO -----
        // No events scheduled yet — they're created via M11 (Schedule Event).
        // No queries either. Reuse the M3 DTO shape so the SPA can drop the
        // new subject straight into `rows` without a refetch.
        // Phase E.6 subject-lifecycle — load any group assignments
        // that the create-flow's group-assignment branch may have
        // written. For freshly-created subjects with no assignments,
        // this returns an empty list (not null) so the SPA can render
        // an "unassigned" row consistently.
        List<GroupAssignmentSnapshot> initialAssignments = loadActiveGroupAssignments(ssb.getId());

        SubjectDetailDto dto = new SubjectDetailDto(
                ssb.getLabel(),
                blankToNull(ssb.getSecondaryLabel()),
                currentStudy.getOid(),
                currentStudy.getName(),
                currentStudy.getOid(),
                currentStudy.getName(),
                mapGender(genderChar),
                body.yearOfBirth(),
                /* groupLabel */ null,
                body.enrolledOn(),
                Collections.emptyList(),
                /* signed */ false,
                /* locked */ false,
                /* openQueries */ 0,
                ssb.getStudyEye(),
                formatIsoDate(ssb.getScreeningDate()),
                mapStudySubjectStatus(ssb.getStatus()),
                initialAssignments,
                /* eyeTransitions — fresh subject, none yet */ null
        );

        LOG.info("Add Subject: created study_subject id={} oid={} label={} (study {}, user {})",
                ssb.getId(), ssb.getOid(), ssb.getLabel(), currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.status(201).body(dto);
    }

    /* =============================================================== */
    /* Phase E.6 — label-availability preflight                         */
    /* =============================================================== */

    /**
     * Response shape for {@link #checkLabel}.
     *
     * <p>The SPA fires this on debounced input of the Study Subject ID
     * field; a {@code false} {@code available} flag flips the inline
     * error before the operator clicks submit, sparing the 400 round
     * trip + the AddSubject's serverFieldErrors retry cycle. The
     * structured shape (vs a plain HTTP status) keeps the response
     * cacheable at the SPA layer and leaves room for surfacing the
     * existing subject's OID for a "Open existing" affordance in
     * a future iteration.
     */
    @Schema(name = "SubjectLabelAvailability")
    public record SubjectLabelAvailability(
            boolean available,
            String existingSubjectOid
    ) {}

    /**
     * Live label-availability check — does the operator-typed Study
     * Subject ID already exist in the bound study?
     *
     * <p>{@code 200 + available=true} when no row matches; {@code 200
     * + available=false + existingSubjectOid} when a row exists.
     * {@code 400} for missing/blank label. {@code 401 / 4xx} when no
     * study is bound. The SPA debounces input by ~350ms before
     * firing.
     */
    @GetMapping("/check-label")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = SubjectLabelAvailability.class)))
    public ResponseEntity<?> checkLabel(@org.springframework.web.bind.annotation.RequestParam("label") String labelRaw,
                                        HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }
        String label = labelRaw == null ? "" : labelRaw.trim();
        if (label.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "label is required."));
        }
        StudySubjectDAO dao = new StudySubjectDAO(dataSource);
        StudySubjectBean existing = dao.findByLabelAndStudy(label, currentStudy);
        if (existing == null || existing.getId() == 0) {
            return ResponseEntity.ok(new SubjectLabelAvailability(true, null));
        }
        return ResponseEntity.ok(new SubjectLabelAvailability(false, existing.getOid()));
    }

    /* =============================================================== */
    /* Phase E.6 retrospective-backfill — match-preflight              */
    /* =============================================================== */

    /**
     * Request body for {@link #matchPreflight}.
     *
     * <p>Only the patient-identity triplet matters here — the form
     * hasn't yet committed an enrolment, so no study-level fields
     * accompany the lookup. The backend is liberal about partial
     * fields (matches need all three) and renders a permissive 400
     * with explicit reasons rather than leaking match counts.
     */
    public record SubjectMatchPreflightRequest(
            String firstName,
            String lastName,
            String dateOfBirth
    ) {}

    /**
     * Single match candidate row.
     *
     * <p>The frontend renders one card per candidate with the
     * operator-typed PHI triplet pre-filled (no PHI leaks back from
     * this DTO — the operator already typed it). {@code studyOids} is
     * filtered to studies the operator can see; {@code otherStudyCount}
     * surfaces the count of additional active enrolments in studies
     * the operator lacks access to so the "this human is already in
     * the system somewhere" signal isn't suppressed for privacy.
     */
    public record SubjectMatchCandidate(
            int subjectId,
            String uniqueIdentifier,
            String gender,
            String dateOfBirth,
            List<String> studyOids,
            int otherStudyCount
    ) {}

    /**
     * Phase E.6 retrospective-backfill — dedup preflight before the
     * SPA POSTs to {@link #create}.
     *
     * <p>Operator types first name + last name + DoB on the AddSubject
     * form. The SPA fires this endpoint as the operator finishes the
     * DoB field so the match candidates can render in a dialog before
     * commit. Match strategy is exact, case-insensitive on the full
     * triplet (locked 2026-06-08 with the user) — the dedup index on
     * subject (LOWER(first_name), LOWER(last_name), date_of_birth)
     * makes the lookup an index-only scan.
     *
     * <p>Returns 200 always (zero candidates → empty array). The 400
     * branch only fires for missing fields, never for "no matches" —
     * that's a normal flow.
     *
     * <p>The endpoint walks the full active-grant set the operator has
     * (mirroring {@link StudiesApiController}'s list) to populate
     * {@code studyOids}; non-visible enrolments only contribute to
     * {@code otherStudyCount}. Soft-deleted (status_id=5) subjects are
     * excluded by the partial dedup index.
     */
    @PostMapping(value = "/match-preflight", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = SubjectMatchCandidate.class)))
    public ResponseEntity<?> matchPreflight(@RequestBody(required = false) SubjectMatchPreflightRequest body,
                                            HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Request body is required."));
        }
        String firstName = body.firstName() == null ? "" : body.firstName().trim();
        String lastName  = body.lastName()  == null ? "" : body.lastName().trim();
        String dobRaw    = body.dateOfBirth() == null ? "" : body.dateOfBirth().trim();
        if (firstName.isEmpty() || lastName.isEmpty() || dobRaw.isEmpty()) {
            // Not an error per se — the SPA fires this once all three
            // are populated, but a defensive 400 helps surface misuses.
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "firstName, lastName, dateOfBirth are all required."));
        }
        java.sql.Date dob;
        try {
            dob = java.sql.Date.valueOf(LocalDate.parse(dobRaw));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "dateOfBirth must be an ISO date (yyyy-MM-dd)."));
        }

        // Build the visible-study OID set for this operator. Mirrors
        // the patient-overview list endpoint's visibility model.
        Set<String> visibleStudyOids = loadVisibleStudyOids(currentUser);

        List<SubjectMatchCandidate> out = new ArrayList<>();
        // Triplet match — partial index handles status_id != 5 + all
        // three columns NOT NULL. study_subject is joined to surface
        // active enrolments.
        String sql =
                "WITH matched AS (" +
                "  SELECT s.subject_id, s.unique_identifier, s.gender, s.date_of_birth " +
                "    FROM subject s " +
                "   WHERE LOWER(s.first_name) = LOWER(?) " +
                "     AND LOWER(s.last_name)  = LOWER(?) " +
                "     AND s.date_of_birth     = ?         " +
                "     AND s.status_id IS DISTINCT FROM 5  " +
                ") " +
                "SELECT m.subject_id, m.unique_identifier, m.gender, m.date_of_birth, " +
                "       st.unique_identifier AS study_oid " +
                "  FROM matched m " +
                "  LEFT JOIN study_subject ss ON ss.subject_id = m.subject_id " +
                "                            AND ss.status_id  IS DISTINCT FROM 5 " +
                "  LEFT JOIN study st         ON st.study_id   = ss.study_id " +
                " ORDER BY m.subject_id, st.unique_identifier";

        // Mutable accumulator keyed by subject_id. The row stream
        // joins study_subject many-to-one, so we fold per subject as
        // we walk.
        record Acc(String uniqueIdentifier, String gender, String dobIso,
                   List<String> visibleStudies, int[] otherCount) {}
        java.util.LinkedHashMap<Integer, Acc> bySubject = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, firstName);
            ps.setString(2, lastName);
            ps.setDate(3, dob);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int subjectId = rs.getInt("subject_id");
                    Acc acc = bySubject.get(subjectId);
                    if (acc == null) {
                        java.sql.Date d = rs.getDate("date_of_birth");
                        acc = new Acc(
                                rs.getString("unique_identifier"),
                                rs.getString("gender"),
                                d == null ? null : d.toLocalDate().toString(),
                                new ArrayList<>(),
                                new int[1]);
                        bySubject.put(subjectId, acc);
                    }
                    String studyOid = rs.getString("study_oid");
                    if (studyOid != null) {
                        if (visibleStudyOids.contains(studyOid)) acc.visibleStudies().add(studyOid);
                        else acc.otherCount()[0]++;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.error("Match-preflight lookup failed for user={}", currentUser.getName(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Match preflight failed — see server log."));
        }
        for (Map.Entry<Integer, Acc> e : bySubject.entrySet()) {
            Acc a = e.getValue();
            out.add(new SubjectMatchCandidate(
                    e.getKey(),
                    a.uniqueIdentifier(),
                    a.gender(),
                    a.dobIso(),
                    a.visibleStudies(),
                    a.otherCount()[0]));
        }

        LOG.info("Match-preflight: {} candidate(s) for user={}", out.size(), currentUser.getName());
        return ResponseEntity.ok(out);
    }

    /**
     * Cached helper — the visible-study OID set for this operator.
     * Walks every active study_user_role binding (StudyDAO has the
     * same lookup the patients-list controller uses).
     */
    private Set<String> loadVisibleStudyOids(UserAccountBean user) {
        Set<String> oids = new java.util.HashSet<>();
        ArrayList<StudyBean> studies = new StudyDAO(dataSource).findAllByUser(user.getName());
        for (StudyBean s : studies) {
            if (s != null && s.getOid() != null) oids.add(s.getOid());
        }
        return oids;
    }

    /**
     * Phase E.4 M8 — Sign Subject endpoint.
     *
     * <p>Records a regulatory e-signature on the subject and flips the
     * subject + every event + every event_crf into the SIGNED state in
     * one (best-effort transactional) JDBC connection.
     *
     * <p><strong>Signature event:</strong> For first-cut M8, password
     * re-entry is the §11.50 signature event. DR-014's proxy-mediated
     * SSO reauth is production-deferred per its memo; local password
     * reauth is the path everywhere this codebase has shipped so far.
     * The {@code attestation} flag is the click-through "I understand
     * this is binding" acknowledgment surfaced in
     * {@code ESignatureBlock.vue}.
     *
     * <p><strong>Persistence sequence</strong> (matches the legacy
     * {@code SignStudySubjectServlet#processRequest} reference plus the
     * extra event_crf flip the M8 brief requires):
     * <ol>
     *   <li>Update {@code study_subject} status to SIGNED (status_id=8),
     *       date_updated NOW, update_id current user. The
     *       {@code study_subject_trigger} PL/pgSQL function fires on
     *       UPDATE and inserts {@code audit_log_event_type_id=3}
     *       ("Study subject status changed") automatically — we do NOT
     *       write the audit row from Java; the DB trigger owns it.</li>
     *   <li>Update every {@code event_crf} for this subject to
     *       status_id=8, electronic_signature_status=true,
     *       date_validate_completed=NOW, validator_id=user,
     *       date_updated=NOW, update_id=user. The {@code event_crf_trigger}
     *       function only writes audit rows for specific status
     *       transitions (1→2, 1→4, 4→2, 11); the 1→8 / 4→8 transitions
     *       are silent. That matches the legacy behaviour — the
     *       study_subject status-change row is the canonical audit
     *       evidence for the signing event.</li>
     *   <li>Update every {@code study_event} for this subject whose
     *       {@code subject_event_status_id ∈ {3, 4}} (data entry
     *       started, data entry completed) to status=8 SIGNED.
     *       Not-scheduled (2) and scheduled-but-empty (1) are left
     *       untouched — the legacy
     *       {@code SignStudySubjectServlet#signSubjectEvents} flips ALL
     *       events to SIGNED regardless of prior state, but per the M8
     *       brief we honour the more conservative {3,4} restriction so
     *       SS_M005's V3 "scheduled" event doesn't silently jump to
     *       signed without ever having data.</li>
     * </ol>
     *
     * <p><strong>Preflight reuse:</strong> The M3
     * {@link #computePreflight} helper is invoked verbatim. If any
     * check reports {@code status: "fail"} other than
     * {@code subject-not-signed} — which is the EXPECTED converse of a
     * sign action — the endpoint returns 412 with the failed checks as
     * the body. Open queries are warnings, not blockers, per the
     * mockup.
     *
     * <p><strong>Security:</strong>
     * <ul>
     *   <li>Never log the raw password from the request body. Log lines
     *       only carry the subject OID + the authenticated user's name.</li>
     *   <li>Password compare uses {@link SecurityManager#verifyPassword}
     *       which delegates to the {@code DaoAuthenticationProvider}'s
     *       {@code PasswordEncoder#matches} — same path the
     *       {@code OpenClinicaUsernamePasswordAuthenticationFilter} uses
     *       at login. Never compare hashes via {@code String.equals}.</li>
     *   <li>Bad-password 401 leaks nothing about whether the user is
     *       even authenticated — the chain-level
     *       {@code .anyRequest().hasRole("USER")} gate has already
     *       verified that.</li>
     * </ul>
     *
     * @param studySubjectOid path-style OID like {@code "SS_M005"}
     * @param body password + attestation; required
     * @param session HTTP session (study + user bean)
     * @return 200 + updated {@link SubjectDetailDto} on success;
     *         400 if attestation is false or body fields are missing;
     *         401 if password doesn't match;
     *         403 if the subject isn't in the user's current study;
     *         404 if the subject doesn't exist anywhere;
     *         409 if the subject is already signed;
     *         412 + failed checks if preflight has any non-subject-not-signed
     *         {@code fail}.
     */
    @PostMapping("/{studySubjectOid}/sign")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = SubjectDetailDto.class)))
    public ResponseEntity<?> sign(@PathVariable("studySubjectOid") String studySubjectOid,
                                  @RequestBody(required = false) SignSubjectRequest body,
                                  HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        // ---- Body validation (no password / attestation logging) ----
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Request body is required (fields: password, attestation)."
            ));
        }
        if (body.attestation() == null || !body.attestation()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Attestation must be explicitly acknowledged before signing."
            ));
        }
        if (body.password() == null || body.password().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Password is required for e-signature re-authentication."
            ));
        }

        // ---- Subject resolution + scope guard ----
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = studySubjectDAO.findByOid(studySubjectOid);
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with OID '" + studySubjectOid + "' not found."
            ));
        }
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            // Same scope guard as M3 getOne: subject exists outside
            // the user's visible study tree → 403 (no OID leakage
            // across studies).
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Subject is not in the currently active study."
            ));
        }

        // ---- Already-signed guard (one-way action) ----
        if (ss.getStatus() != null && ss.getStatus().equals(Status.SIGNED)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Subject is already signed — signing is a one-way action.",
                    "studySubjectOid", ss.getOid()
            ));
        }

        // ---- Password re-auth ----
        // We deliberately fetch UserDetails from the SecurityContext
        // rather than constructing it from the session's UserAccountBean
        // because the SecurityManager.verifyPassword wraps the call in
        // an AuthenticationManager round trip — passing a UserDetails
        // that doesn't match the principal would be an integrity break.
        UserDetails userDetails = currentUserDetails();
        if (userDetails == null || !userDetails.getUsername().equals(currentUser.getName())) {
            // Defence in depth — should never happen because the chain
            // gate has already authenticated the request. If it does,
            // refuse rather than open the path.
            return ResponseEntity.status(401).body(Map.of(
                    "message", "Could not re-authenticate the current user for signing."
            ));
        }
        if (!securityManager.verifyPassword(body.password(), userDetails)) {
            // Audit-worthy event — log without the password.
            LOG.info("Sign Subject: password re-auth failed for user={} subject={} (study {})",
                    currentUser.getName(), ss.getOid(), currentStudy.getOid());
            return ResponseEntity.status(401).body(Map.of(
                    "message", "Password does not match — signature not recorded."
            ));
        }

        // ---- Preflight gate (reuse M3 helper) ----
        SignPreflightDto preflight = computePreflight(ss, currentStudy, currentRole, currentUser);
        List<SignPreflightDto.CheckRow> blockingFails = new ArrayList<>();
        for (SignPreflightDto.CheckRow c : preflight.checks()) {
            // subject-not-signed:fail is the converse precondition of a
            // sign action — signing requires the subject to be
            // unsigned. The 409 guard above has already excluded the
            // already-signed case, so any subject-not-signed:fail at
            // this point is an unreachable artifact; defensive skip.
            if (!"fail".equals(c.status())) continue;
            if ("subject-not-signed".equals(c.id())) continue;
            blockingFails.add(c);
        }
        if (!blockingFails.isEmpty()) {
            return ResponseEntity.status(412).body(Map.of(
                    "message", "Preflight blocked the sign action — resolve the failed checks first.",
                    "failedChecks", blockingFails,
                    "preflight", preflight
            ));
        }

        // ---- Persistence ----
        try {
            applySignaturePersistence(ss.getId(), currentUser.getId());
        } catch (SQLException e) {
            LOG.error("Sign Subject: persistence failed for subject={} (study {}, user {})",
                    ss.getOid(), currentStudy.getOid(), currentUser.getName(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to persist signature — see server log."
            ));
        }

        // ---- Audit log ----
        // The study_subject_trigger PL/pgSQL function inserts the
        // audit_log_event row automatically when status_id changes; no
        // Java-side audit write needed. The legacy
        // SignStudySubjectServlet relies on the same trigger.
        LOG.info("Sign Subject: signed subject={} by user={} (study {})",
                ss.getOid(), currentUser.getName(), currentStudy.getOid());

        // ---- Refetch + return ----
        StudySubjectBean refreshed = studySubjectDAO.findByOid(studySubjectOid);
        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        SubjectBean subj = subjectDAO.findByPK(refreshed.getSubjectId());
        Map<Integer, StudyEventDefinitionBean> definitionCache = new HashMap<>();
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());

        SubjectDetailDto dto = toDetailDto(refreshed, subj, currentStudy, studyEventDAO,
                studyEventDefinitionDAO, eventCRFDAO, definitionCache, openQueriesByEvent);

        return ResponseEntity.ok(dto);
    }

    /**
     * Phase E A2 — edit a study subject's demographics.
     *
     * <p>Editable fields: {@code secondaryId}, {@code gender},
     * {@code yearOfBirth}. The subject's identifier ({@code id} /
     * {@code label}) and {@code enrolledOn} are intentionally NOT
     * editable here — both are foreign-key anchors for event_crfs +
     * audit_log_event rows and changing them would invalidate study
     * scheduling invariants. {@code groupLabel} is also out of scope
     * (see the create endpoint's M4 scope note about subject_group_map).
     *
     * <p>Guards (order matters):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 400 / errors[]} — validation failure on any
     *       editable field (mirrors {@code POST /subjects} error
     *       shape so the SPA's per-field error handler works
     *       unchanged).</li>
     *   <li>{@code 403} — caller's role is not Investigator / CRC /
     *       Data Manager / Admin (per {@link SubjectEditAuthorization}).</li>
     *   <li>{@code 404} — no study_subject with that OID in the
     *       caller's visible-studies set.</li>
     *   <li>{@code 409} — subject is in {@link Status#DELETED} or
     *       {@link Status#SIGNED}: removed subjects must be restored
     *       first; signed subjects need to be un-signed via an
     *       admin path before re-editing.</li>
     * </ol>
     *
     * <p>Audit emission: one {@code audit_log_event} row per
     * changed field with old/new values, mirroring the M8 profile-
     * update pattern (audit_event_type_id 33 — column changes).
     * Wrapped in try/catch so an audit-write failure does not roll
     * back the user-facing update.
     */
    @PutMapping("/{studySubjectOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = SubjectDetailDto.class)))
    public ResponseEntity<?> update(@PathVariable("studySubjectOid") String studySubjectOid,
                                    @RequestBody(required = false) UpdateSubjectRequest body,
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

        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!SubjectEditAuthorization.roleMayEdit(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing study-subject demographics"));
        }

        // Validate input before touching the DB.
        List<ValidationErrorBody.FieldError> errors = validateUpdateSubject(body);
        if (!errors.isEmpty()) {
            ValidationErrorBody errResponse = new ValidationErrorBody(
                    "Validation failed", errors);
            return ResponseEntity.badRequest().body(errResponse);
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);

        StudySubjectBean ss = null;
        for (Integer sid : visible) {
            StudyBean scope = (sid == currentStudy.getId())
                    ? currentStudy
                    : (StudyBean) new StudyDAO(dataSource).findByPK(sid);
            if (scope == null || scope.getId() == 0) continue;
            StudySubjectBean candidate = studySubjectDAO.findByLabelAndStudy(studySubjectOid, scope);
            if (candidate != null && candidate.getId() > 0) {
                ss = candidate;
                break;
            }
        }
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study subject '" + studySubjectOid + "' in your visible study set"));
        }

        ResponseEntity<?> lockRefusal = SubjectLockGuard.refuseIfLocked(ss, "editing subject demographics");
        if (lockRefusal != null) {
            return lockRefusal;
        }

        if (ss.getStatus() != null
                && (ss.getStatus().equals(Status.DELETED)
                    || ss.getStatus().equals(Status.SIGNED))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Subject is " + ss.getStatus().getName().toLowerCase()
                            + " — restore or un-sign first"));
        }

        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        SubjectBean subj = (SubjectBean) subjectDAO.findByPK(ss.getSubjectId());
        if (subj == null || subj.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "Subject record missing for " + studySubjectOid));
        }

        // Capture old values for audit. Field-by-field diff so we
        // only write an audit row per changed column.
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
        java.util.Date now = new java.util.Date();

        // ---- secondaryId — null leaves unchanged; empty clears ----
        if (body.secondaryId() != null) {
            String oldSec = subj.getUniqueIdentifier() == null ? "" : subj.getUniqueIdentifier();
            String newSec = body.secondaryId().trim();
            if (!oldSec.equals(newSec)) {
                subj.setUniqueIdentifier(newSec);
                writeSubjectFieldAudit(auditDAO, currentUser, currentStudy, ss,
                        "secondary_id", oldSec, newSec);
            }
        }

        // ---- gender ----
        char newGenderChar = Character.toLowerCase(body.gender().charAt(0));
        char oldGenderChar = subj.getGender();
        if (oldGenderChar != newGenderChar) {
            subj.setGender(newGenderChar);
            writeSubjectFieldAudit(auditDAO, currentUser, currentStudy, ss,
                    "gender", String.valueOf(oldGenderChar), String.valueOf(newGenderChar));
        }

        // ---- yearOfBirth: stored on subject.date_of_birth as Jan-1 ----
        if (body.yearOfBirth() != null) {
            int newYob = body.yearOfBirth();
            int oldYob = subj.getDateOfBirth() != null
                    ? new java.util.Date(subj.getDateOfBirth().getTime()).toInstant()
                            .atZone(java.time.ZoneId.systemDefault()).getYear()
                    : 0;
            if (oldYob != newYob) {
                subj.setDateOfBirth(java.sql.Date.valueOf(java.time.LocalDate.of(newYob, 1, 1)));
                subj.setDobCollected(true);
                writeSubjectFieldAudit(auditDAO, currentUser, currentStudy, ss,
                        "year_of_birth", String.valueOf(oldYob), String.valueOf(newYob));
            }
        }

        // Persist the subject row (single update covering all three
        // editable columns; idempotent if no field changed).
        subj.setUpdater(currentUser);
        subj.setUpdatedDate(now);
        subjectDAO.update(subj);

        LOG.info("Subject demographics update: study_subject {} (label={}) by user={} role={}",
                ss.getId(), ss.getLabel(), currentUser.getName(), roleId);

        // Refresh + project.
        SubjectBean refreshedSubj = (SubjectBean) subjectDAO.findByPK(subj.getId());
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        Map<Integer, StudyEventDefinitionBean> definitionCache = new HashMap<>();
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());
        SubjectDetailDto dto = toDetailDto(ss, refreshedSubj, currentStudy, studyEventDAO,
                studyEventDefinitionDAO, eventCRFDAO, definitionCache, openQueriesByEvent);
        return ResponseEntity.ok(dto);
    }

    private static List<ValidationErrorBody.FieldError> validateUpdateSubject(
            UpdateSubjectRequest body) {
        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        if (body == null) {
            errors.add(new ValidationErrorBody.FieldError("body", "Request body is required."));
            return errors;
        }

        // ---- secondaryId: optional, ≤30 chars when present ----
        if (body.secondaryId() != null && body.secondaryId().trim().length() > 30) {
            errors.add(new ValidationErrorBody.FieldError("secondaryId",
                    "Secondary ID is too long (max 30 characters)."));
        }

        // ---- gender: required, in {F, M, O, U} (case-insensitive) ----
        String gender = body.gender() == null ? "" : body.gender().trim().toUpperCase();
        if (gender.isEmpty()) {
            errors.add(new ValidationErrorBody.FieldError("gender", "Gender is required."));
        } else if (!gender.equals("F") && !gender.equals("M") && !gender.equals("O") && !gender.equals("U")) {
            errors.add(new ValidationErrorBody.FieldError("gender",
                    "'" + body.gender() + "' is not a valid gender code."));
        }

        // ---- yearOfBirth: optional; 1900..currentYear when present ----
        if (body.yearOfBirth() != null) {
            int yob = body.yearOfBirth();
            int thisYear = java.time.LocalDate.now().getYear();
            if (yob < 1900 || yob > thisYear) {
                errors.add(new ValidationErrorBody.FieldError("yearOfBirth",
                        "Year of birth must be between 1900 and " + thisYear + "."));
            }
        }
        return errors;
    }

    private static void writeSubjectFieldAudit(AuditEventDAO auditDAO,
                                               UserAccountBean ub,
                                               StudyBean study,
                                               StudySubjectBean ss,
                                               String columnName,
                                               String oldValue,
                                               String newValue) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(ub.getId());
            ae.setStudyId(study.getId());
            ae.setSubjectId(ss.getId());
            ae.setStudyName(study.getName() == null ? "" : study.getName());
            ae.setSubjectName(ss.getLabel() == null ? "" : ss.getLabel());
            ae.setAuditTable("subject");
            ae.setEntityId(ss.getSubjectId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("subject_demographics_update: " + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "'");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for subject field {}={} (continuing): {}",
                    columnName, newValue, e.getMessage());
        }
    }

    /**
     * Phase E A3 — soft-delete a study subject (the row remains for
     * audit but disappears from active queries). Mirrors the legacy
     * {@code RemoveSubjectServlet} cascade: this study_subject's
     * status flips to {@link Status#DELETED}; its child rows
     * (study_events → event_crfs → item_data) cascade to
     * {@link Status#AUTO_DELETED}.
     *
     * <p>Guards (order matters):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 404} — no study_subject with that OID in the
     *       caller's visible-studies set.</li>
     *   <li>{@code 403} — caller's role is not Data Manager / Admin
     *       (per {@link SubjectLifecycleAuthorization}).</li>
     *   <li>{@code 409} — subject is already in {@link Status#DELETED}.
     *       Restoring uses the sibling endpoint; this one is for
     *       active → removed transitions.</li>
     * </ol>
     *
     * <p>Unlike subject-create (which audits via
     * {@code AuditEventDAO}) and sign (which uses a JDBC INSERT for
     * the {@code subject_event_audit} compound primary key), this
     * action relies on the existing {@code study_subject_trigger}
     * table-trigger that captures status transitions automatically.
     * No controller-side audit emission is required.
     */
    @PostMapping("/{studySubjectOid}/remove")
    public ResponseEntity<?> remove(@PathVariable("studySubjectOid") String studySubjectOid,
                                    HttpSession session) {
        return transitionLifecycle(studySubjectOid, session,
                /* expectedCurrentStatus */ Status.AVAILABLE,
                /* newSubjectStatus */ Status.DELETED,
                /* cascadeChildStatus */ Status.AUTO_DELETED,
                /* opName */ "remove",
                /* alreadyMessage */ "Subject is already removed");
    }

    /**
     * Phase E A3 — inverse of {@link #remove}. Restores a previously
     * soft-deleted subject and its cascade. Status flips back to
     * {@link Status#AVAILABLE}; child rows in
     * {@link Status#AUTO_DELETED} flip back to {@link Status#AVAILABLE}.
     *
     * <p>409 if subject is not currently in {@code DELETED} — restore
     * is the inverse of remove and not a generic "reset to available".
     */
    @PostMapping("/{studySubjectOid}/restore")
    public ResponseEntity<?> restore(@PathVariable("studySubjectOid") String studySubjectOid,
                                     HttpSession session) {
        return transitionLifecycle(studySubjectOid, session,
                /* expectedCurrentStatus */ Status.DELETED,
                /* newSubjectStatus */ Status.AVAILABLE,
                /* cascadeChildStatus */ Status.AVAILABLE,
                /* opName */ "restore",
                /* alreadyMessage */ "Subject is not currently removed");
    }

    /**
     * Phase E A3 (lock half) — freeze a study subject so that
     * downstream edit / data-entry endpoints refuse with 409 until
     * unlocked. The subject's row stays AVAILABLE in the matrix
     * (so the SPA still renders it normally); the LOCKED marker is
     * enforced as a per-endpoint precondition.
     *
     * <p>Distinct from {@link #remove}:
     * <ul>
     *   <li>Lock is a temporary halt; remove is a soft-delete.</li>
     *   <li>Lock leaves child rows untouched (status=AVAILABLE);
     *       remove cascades to AUTO_DELETED.</li>
     *   <li>Lock is reversible via {@link #unlock}; remove is reversible
     *       via {@link #restore}.</li>
     * </ul>
     *
     * <p><strong>Downstream enforcement deferred:</strong> the SPA's
     * other write endpoints (subject-edit, event-edit/cancel, CRF
     * save, query-thread, SDV verify) don't yet check
     * {@code ss.getStatus() == LOCKED}. The lock marker is recorded
     * here so the audit trail captures intent; a follow-up slice
     * adds the {@code refuse-if-locked} guard to each. The SPA
     * surfaces a locked badge so the UI nudges users away from
     * locked subjects in the meantime.
     *
     * <p>Role: DM / Admin only (same as remove).
     */
    @PostMapping("/{studySubjectOid}/lock")
    public ResponseEntity<?> lock(@PathVariable("studySubjectOid") String studySubjectOid,
                                  HttpSession session) {
        return transitionLifecycle(studySubjectOid, session,
                /* expectedCurrentStatus */ Status.AVAILABLE,
                /* newSubjectStatus */ Status.LOCKED,
                /* cascadeChildStatus */ null,
                /* opName */ "lock",
                /* alreadyMessage */ "Subject is not currently available — must be AVAILABLE to lock");
    }

    /**
     * Phase E A3 (lock half) — inverse of {@link #lock}.
     * Subject's status flips back to {@link Status#AVAILABLE}.
     */
    @PostMapping("/{studySubjectOid}/unlock")
    public ResponseEntity<?> unlock(@PathVariable("studySubjectOid") String studySubjectOid,
                                    HttpSession session) {
        return transitionLifecycle(studySubjectOid, session,
                /* expectedCurrentStatus */ Status.LOCKED,
                /* newSubjectStatus */ Status.AVAILABLE,
                /* cascadeChildStatus */ null,
                /* opName */ "unlock",
                /* alreadyMessage */ "Subject is not currently locked");
    }

    /**
     * Phase E.6 subject-lifecycle — replace a subject's full
     * {@code subject_group_map} state.
     *
     * <p>Accepts the desired final assignment list. The service
     * reconciles inserts + soft-deletes + group switches in a single
     * call, returning the refreshed subject detail (the SPA replaces
     * its in-memory copy on success).
     *
     * <p>Authorization mirrors {@link SubjectEditAuthorization}:
     * Investigator, CRC, Data Manager, Administrator may write;
     * Monitor / RA / RA2 are refused with 403. Locked or signed
     * subjects are refused with 409 — group assignment changes
     * during a signed window would invalidate the e-signature.
     *
     * <p>404 on unknown OID; 403 on the subject belonging to a study
     * outside the user's grant tree; 400 on validation failure (per-
     * assignment field errors via {@link ValidationErrorBody}).
     */
    @PutMapping("/{studySubjectOid}/groups")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = SubjectDetailDto.class)))
    public ResponseEntity<?> replaceGroups(@PathVariable("studySubjectOid") String studySubjectOid,
                                           @RequestBody(required = false) UpdateSubjectGroupsRequest body,
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
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!SubjectEditAuthorization.roleMayEdit(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing subject group assignments"));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = studySubjectDAO.findByOid(studySubjectOid);
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with OID '" + studySubjectOid + "' not found."));
        }

        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visible.contains(Integer.valueOf(ss.getStudyId()))) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Subject belongs to a study outside your grant tree"));
        }

        if (ss.getStatus() != null && Status.SIGNED.equals(ss.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Subject is signed — un-sign before changing group assignments"));
        }
        if (ss.getStatus() != null && Status.LOCKED.equals(ss.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Subject is locked — unlock before changing group assignments"));
        }
        if (ss.getStatus() != null
                && (Status.DELETED.equals(ss.getStatus())
                    || Status.AUTO_DELETED.equals(ss.getStatus()))) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "Subject is removed — restore before changing group assignments"));
        }

        // Look up the actual study the subject lives in (not the
        // session-bound study, which can be the top-level parent in a
        // multi-site setup). Group classes live at the top level only,
        // so we walk up if needed via StudyDAO.
        StudyBean assignmentStudy = currentStudy;
        if (currentStudy.getParentStudyId() != 0
                || currentStudy.getId() != ss.getStudyId()) {
            StudyBean ssStudy = new StudyDAO(dataSource).findByPK(ss.getStudyId());
            if (ssStudy != null && ssStudy.getId() != 0) {
                if (ssStudy.getParentStudyId() != 0) {
                    StudyBean parent = new StudyDAO(dataSource).findByPK(ssStudy.getParentStudyId());
                    if (parent != null && parent.getId() != 0) assignmentStudy = parent;
                } else {
                    assignmentStudy = ssStudy;
                }
            }
        }

        SubjectGroupAssignmentService service = new SubjectGroupAssignmentService(dataSource);
        List<UpdateSubjectGroupsRequest.Assignment> desired = (body == null) ? null : body.assignments();
        List<ValidationErrorBody.FieldError> errors = service.validate(assignmentStudy, desired);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        int touched;
        try {
            touched = service.reconcile(ss.getId(), currentUser, desired,
                    new at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO(dataSource));
        } catch (RuntimeException e) {
            LOG.error("Group-assignment reconcile failed for ss={} oid={} by user={}",
                    ss.getId(), studySubjectOid, currentUser.getName(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Group assignment update failed — see server log."));
        }

        LOG.info("Group assignments updated for subject {} (study_subject_id={}): "
                        + "{} row(s) touched by user {}",
                studySubjectOid, ss.getId(), touched, currentUser.getName());

        // Refresh detail for the response. Reload the subject so the
        // status / refreshed audit columns are up to date.
        StudySubjectBean refreshed = studySubjectDAO.findByPK(ss.getId());
        SubjectBean subj = new SubjectDAO(dataSource).findByPK(refreshed.getSubjectId());
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        Map<Integer, StudyEventDefinitionBean> defCache = new HashMap<>();
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(refreshed.getStudyId());
        SubjectDetailDto dto = toDetailDto(refreshed, subj, currentStudy, studyEventDAO,
                studyEventDefinitionDAO, eventCRFDAO, defCache, openQueriesByEvent);
        return ResponseEntity.ok(dto);
    }

    /**
     * Shared body for remove / restore / lock / unlock. The
     * transitions are structurally identical — flip the parent
     * status, optionally cascade the child rows — only the status
     * pair differs.
     *
     * @param expectedCurrentStatus parent's status must equal this
     *        before the transition; otherwise 409.
     * @param newSubjectStatus new {@link StudySubjectBean#getStatus()}.
     * @param cascadeChildStatus new status for cascaded child rows,
     *        or {@code null} to skip the cascade entirely. Remove
     *        uses {@link Status#AUTO_DELETED}; restore uses
     *        {@link Status#AVAILABLE}; lock / unlock pass {@code null}
     *        (status is a parent-only marker).
     */
    private ResponseEntity<?> transitionLifecycle(String studySubjectOid,
                                                  HttpSession session,
                                                  Status expectedCurrentStatus,
                                                  Status newSubjectStatus,
                                                  Status cascadeChildStatus,
                                                  String opName,
                                                  String alreadyMessage) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session."));
        }

        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!SubjectLifecycleAuthorization.roleMayManageLifecycle(roleId)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit " + opName + " on study subjects"));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        Set<Integer> visible = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);

        StudySubjectBean ss = null;
        for (Integer sid : visible) {
            StudyBean scope;
            if (sid == currentStudy.getId()) {
                scope = currentStudy;
            } else {
                StudyDAO sdao = new StudyDAO(dataSource);
                scope = (StudyBean) sdao.findByPK(sid);
            }
            if (scope == null || scope.getId() == 0) continue;
            StudySubjectBean candidate = studySubjectDAO.findByLabelAndStudy(studySubjectOid, scope);
            if (candidate != null && candidate.getId() > 0) {
                ss = candidate;
                break;
            }
        }
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study subject '" + studySubjectOid + "' in your visible study set"));
        }

        if (ss.getStatus() == null || !ss.getStatus().equals(expectedCurrentStatus)) {
            return ResponseEntity.status(409).body(Map.of("message", alreadyMessage));
        }

        // Flip the parent, then cascade. The legacy
        // RemoveSubjectServlet walks the chain inline; we do the
        // same here. Each DAO.update() is its own auto-committed
        // statement — there's no service-layer transaction wrapping
        // them, mirroring legacy behaviour (failures partway through
        // leave the system in a mixed state, which is consistent
        // with what BUR-* error codes already produce).
        ss.setStatus(newSubjectStatus);
        ss.setUpdater(currentUser);
        ss.setUpdatedDate(new java.util.Date());
        studySubjectDAO.update(ss);

        if (cascadeChildStatus != null) {
            cascadeChildren(ss, currentUser, cascadeChildStatus);
        }

        LOG.info("Subject lifecycle {}: study_subject {} (label={}) by user={} role={}; "
                        + "parent.status={} cascade.status={}",
                opName, ss.getId(), ss.getLabel(), currentUser.getName(), roleId,
                newSubjectStatus.getName(),
                cascadeChildStatus == null ? "none" : cascadeChildStatus.getName());

        // Refresh + project. The detail DTO drives the SPA's
        // SubjectDetailView; even after a remove the view stays open
        // (showing the removed banner) until the user navigates back
        // to the matrix.
        StudySubjectBean refreshed = (StudySubjectBean) studySubjectDAO.findByPK(ss.getId());
        SubjectBean subj = (SubjectBean) new SubjectDAO(dataSource).findByPK(refreshed.getSubjectId());
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        Map<Integer, StudyEventDefinitionBean> definitionCache = new HashMap<>();
        Map<Integer, Integer> openQueriesByEvent = loadOpenQueryCountsForStudy(currentStudy.getId());
        SubjectDetailDto dto = toDetailDto(refreshed, subj, currentStudy, studyEventDAO,
                studyEventDefinitionDAO, eventCRFDAO, definitionCache, openQueriesByEvent);
        return ResponseEntity.ok(dto);
    }

    /**
     * Cascade the StudySubject's status change to its child rows
     * (study_events → event_crfs → item_data). Mirrors the legacy
     * {@code RemoveSubjectServlet.processRequest} loop verbatim:
     * skip rows already in {@code DELETED} (those were removed
     * outside this cascade and shouldn't be touched).
     */
    private void cascadeChildren(StudySubjectBean ss, UserAccountBean currentUser,
                                 Status cascadeChildStatus) {
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        ItemDataDAO itemDataDAO = new ItemDataDAO(dataSource);
        java.util.Date now = new java.util.Date();

        java.util.ArrayList<StudyEventBean> events = studyEventDAO.findAllByStudySubject(ss);
        for (StudyEventBean event : events) {
            if (event.getStatus() != null && event.getStatus().equals(Status.DELETED)) continue;
            event.setStatus(cascadeChildStatus);
            event.setUpdater(currentUser);
            event.setUpdatedDate(now);
            studyEventDAO.update(event);

            java.util.ArrayList<EventCRFBean> eventCrfs = eventCRFDAO.findAllByStudyEvent(event);
            for (EventCRFBean ec : eventCrfs) {
                if (ec.getStatus() != null && ec.getStatus().equals(Status.DELETED)) continue;
                ec.setStatus(cascadeChildStatus);
                ec.setUpdater(currentUser);
                ec.setUpdatedDate(now);
                eventCRFDAO.update(ec);

                java.util.ArrayList<ItemDataBean> items = itemDataDAO.findAllByEventCRFId(ec.getId());
                for (ItemDataBean it : items) {
                    if (it.getStatus() != null && it.getStatus().equals(Status.DELETED)) continue;
                    it.setStatus(cascadeChildStatus);
                    it.setUpdater(currentUser);
                    it.setUpdatedDate(now);
                    itemDataDAO.update(it);
                }
            }
        }
    }

    /**
     * Pull the {@link UserDetails} principal off the
     * {@link SecurityContextHolder} for password re-auth.
     *
     * <p>The principal is set by {@code OpenClinicaUsernamePasswordAuthenticationFilter}
     * on successful login. Returning {@code null} here means the chain
     * is mis-configured — the legacy
     * {@code SecureController#getUserDetails} uses the same lookup.
     */
    private static UserDetails currentUserDetails() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return (principal instanceof UserDetails) ? (UserDetails) principal : null;
    }

    /**
     * Run the three signature UPDATEs in a single JDBC connection.
     *
     * <p>Wrapped in a manual transaction (auto-commit off + commit / rollback)
     * because the per-DAO connection pattern doesn't compose into a
     * Spring {@code @Transactional} without first hardening the
     * data-access layer. The atomic guarantee we need is: either every
     * row flips to SIGNED or none do. A partial flip would leave the
     * subject's casebook in an inconsistent state.
     *
     * <p>SQL is inline (rather than via the existing DAO {@code update}
     * methods) for two reasons: (1) the DAO methods cycle their own
     * connection per call, breaking transaction scope; (2) the legacy
     * {@code StudySubjectDAO.update} writes seven columns including
     * label, secondary_label, subject_id etc. — we only want to flip
     * status. Direct SQL keeps the audit trigger's
     * {@code OLD.status_id <> NEW.status_id} branch the only firing
     * condition.
     *
     * @param studySubjectId PK of the study_subject row to sign
     * @param userId         current user's PK (for update_id +
     *                       validator_id columns)
     */
    private void applySignaturePersistence(int studySubjectId, int userId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // ---- (1) study_subject ----
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE study_subject "
                                + "   SET status_id = ?, "
                                + "       date_updated = NOW(), "
                                + "       update_id = ? "
                                + " WHERE study_subject_id = ?")) {
                    ps.setInt(1, Status.SIGNED.getId());
                    ps.setInt(2, userId);
                    ps.setInt(3, studySubjectId);
                    ps.executeUpdate();
                }

                // ---- (2) event_crf — flip every CRF row for the subject ----
                // Path: study_event.study_subject_id → event_crf.study_event_id.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE event_crf "
                                + "   SET status_id = ?, "
                                + "       electronic_signature_status = TRUE, "
                                + "       date_validate_completed = NOW(), "
                                + "       validator_id = ?, "
                                + "       date_updated = NOW(), "
                                + "       update_id = ? "
                                + " WHERE study_event_id IN ("
                                + "         SELECT study_event_id FROM study_event WHERE study_subject_id = ?"
                                + "       )")) {
                    ps.setInt(1, Status.SIGNED.getId());
                    ps.setInt(2, userId);
                    ps.setInt(3, userId);
                    ps.setInt(4, studySubjectId);
                    ps.executeUpdate();
                }

                // ---- (3) study_event — flip data-entry-started + completed events ----
                // subject_event_status_id ∈ {3, 4} → 8 SIGNED. Per the M8
                // brief we don't touch scheduled-but-empty (1) or
                // not-scheduled (2) events — see method JavaDoc.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE study_event "
                                + "   SET subject_event_status_id = ?, "
                                + "       date_updated = NOW(), "
                                + "       update_id = ? "
                                + " WHERE study_subject_id = ? "
                                + "   AND subject_event_status_id IN (3, 4)")) {
                    ps.setInt(1, 8); // SubjectEventStatus.SIGNED
                    ps.setInt(2, userId);
                    ps.setInt(3, studySubjectId);
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Request body for {@link #sign(String, SignSubjectRequest, HttpSession)}.
     *
     * <p>{@code password} carries the user's plaintext local password
     * (over HTTPS in production; the SPA never persists it client-side).
     * {@code attestation} must be explicitly {@code true}; sending false
     * or omitting the field is a 400.
     *
     * <p><strong>SECURITY:</strong> The controller MUST NEVER log
     * {@code password()}. Toast / audit messages reference subject OID +
     * authenticated username only.
     */
    public record SignSubjectRequest(
            String password,
            Boolean attestation
    ) {
        @Override
        public String toString() {
            // Defence against accidental log-line interpolation —
            // toString never leaks the password even if someone writes
            // `LOG.info("body = {}", body)`.
            return "SignSubjectRequest{password=*REDACTED*, attestation=" + attestation + "}";
        }
    }

    /**
     * Server-side validation for {@link AddSubjectRequest}.
     *
     * <p>Returns ALL failing rules at once (not first-fail) so the SPA
     * can surface every issue in a single submit cycle. Order in the
     * returned list mirrors the request body for predictable rendering.
     */
    private static List<ValidationErrorBody.FieldError> validateAddSubject(
            AddSubjectRequest body, StudyBean currentStudy, StudySubjectDAO studySubjectDAO) {
        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();

        // ---- id: required, trimmed, ≤30 chars, unique within study ----
        String id = body.id() == null ? "" : body.id().trim();
        if (id.isEmpty()) {
            errors.add(new ValidationErrorBody.FieldError("id", "Subject ID is required."));
        } else if (id.length() > 30) {
            errors.add(new ValidationErrorBody.FieldError("id",
                    "Subject ID is too long (max 30 characters)."));
        } else {
            StudySubjectBean existing = studySubjectDAO.findByLabelAndStudy(id, currentStudy);
            if (existing != null && existing.getId() != 0) {
                errors.add(new ValidationErrorBody.FieldError("id",
                        "Subject ID '" + id + "' already exists at this site."));
            }
        }

        // ---- secondaryId: optional, trimmed, ≤30 chars ----
        if (body.secondaryId() != null) {
            String sec = body.secondaryId().trim();
            if (sec.length() > 30) {
                errors.add(new ValidationErrorBody.FieldError("secondaryId",
                        "Secondary ID is too long (max 30 characters)."));
            }
            // No PHI server-side check — SPA's soft check remains.
        }

        // ---- gender: required, in {F, M, O, U} (case-insensitive) ----
        String gender = body.gender() == null ? "" : body.gender().trim().toUpperCase();
        if (gender.isEmpty()) {
            errors.add(new ValidationErrorBody.FieldError("gender", "Gender is required."));
        } else if (!gender.equals("F") && !gender.equals("M") && !gender.equals("O") && !gender.equals("U")) {
            errors.add(new ValidationErrorBody.FieldError("gender",
                    "'" + body.gender() + "' is not a valid gender code."));
        }

        // ---- yearOfBirth: optional; if present 1900..currentYear ----
        if (body.yearOfBirth() != null) {
            int yob = body.yearOfBirth();
            int thisYear = LocalDate.now().getYear();
            if (yob < 1900 || yob > thisYear) {
                errors.add(new ValidationErrorBody.FieldError("yearOfBirth",
                        "Year of birth must be between 1900 and " + thisYear + "."));
            }
        }

        // ---- enrolledOn: required, ISO date, not in the future ----
        String enrolledOnStr = body.enrolledOn();
        if (enrolledOnStr == null || enrolledOnStr.trim().isEmpty()) {
            errors.add(new ValidationErrorBody.FieldError("enrolledOn",
                    "Enrolment date is required."));
        } else {
            LocalDate parsed = null;
            try {
                parsed = LocalDate.parse(enrolledOnStr.trim());
            } catch (java.time.format.DateTimeParseException e) {
                errors.add(new ValidationErrorBody.FieldError("enrolledOn",
                        "Enrolment date must be a valid ISO date (YYYY-MM-DD)."));
            }
            if (parsed != null && parsed.isAfter(LocalDate.now())) {
                errors.add(new ValidationErrorBody.FieldError("enrolledOn",
                        "Enrolment date must not be in the future."));
            }
        }

        // ---- groupLabel: ignored (out of scope for M4) ----

        // ---- studyEye (Phase E.6 Tier 1): optional; one of OD/OS/OU ----
        if (body.studyEye() != null) {
            String eye = body.studyEye().trim().toUpperCase();
            if (!eye.isEmpty() && !eye.equals("OD") && !eye.equals("OS") && !eye.equals("OU")) {
                errors.add(new ValidationErrorBody.FieldError("studyEye",
                        "'" + body.studyEye() + "' is not a valid study-eye code (OD / OS / OU)."));
            }
        }

        // ---- screeningDate (Phase E.6 Tier 1): optional; ISO date ----
        // Logical screeningDate <= enrolledOn rule is NOT enforced at
        // the controller layer — legacy imports sometimes flip the two
        // due to administrative-dating quirks. SPA surfaces a soft
        // warning; data managers reconcile after import.
        if (body.screeningDate() != null && !body.screeningDate().trim().isEmpty()) {
            try {
                LocalDate.parse(body.screeningDate().trim());
            } catch (java.time.format.DateTimeParseException e) {
                errors.add(new ValidationErrorBody.FieldError("screeningDate",
                        "Screening date must be a valid ISO date (YYYY-MM-DD)."));
            }
        }

        return errors;
    }

    /**
     * Insert a {@code subject} row via direct SQL.
     *
     * <p>{@link SubjectDAO#create} is unsuitable here because its
     * gender-handling {@code switch} only writes {@code 'm'} or
     * {@code 'f'}; any other code (including the SPA's {@code O} for
     * Other and {@code U} for Unknown) is silently coerced to NULL,
     * losing data we need to round-trip. Inline SQL keeps the encoding
     * direct.
     *
     * <p>{@code Statement.RETURN_GENERATED_KEYS} returns the
     * auto-incremented PK, matching the {@code SubjectDAO.create}
     * convention of {@code getLatestPK} after the insert.
     */
    private int insertSubjectRow(char gender, java.sql.Date dob, boolean dobCollected, int ownerId,
                                 String personId, String firstName, String lastName)
            throws SQLException {
        String sql = "INSERT INTO subject (status_id, date_of_birth, gender, unique_identifier, "
                + "owner_id, date_created, dob_collected, first_name, last_name) "
                + "VALUES (?, ?, ?, ?, ?, NOW(), ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, Status.AVAILABLE.getId());
            if (dob == null) {
                ps.setNull(2, Types.DATE);
            } else {
                ps.setDate(2, dob);
            }
            ps.setString(3, String.valueOf(gender));
            // Phase E.6 subject-lifecycle — Person-ID stored in
            // subject.unique_identifier when provided. The legacy
            // FindSubjectsServlet keys re-enrol lookups off this
            // column.
            if (personId == null || personId.isEmpty()) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, personId);
            }
            ps.setInt(5, ownerId);
            ps.setBoolean(6, dobCollected);
            // Phase E.6 retrospective-backfill — PHI captured at
            // enrolment time. Null when omitted (legacy SPA calls
            // pre-dating the form expansion); the subject row stays
            // valid without it.
            if (firstName == null || firstName.isEmpty()) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, firstName);
            }
            if (lastName == null || lastName.isEmpty()) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, lastName);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("subject insert returned no generated key");
            }
        }
    }

    /**
     * Request body for {@link #create(AddSubjectRequest, HttpSession)}.
     *
     * <p>Fields mirror the SPA's {@code AddSubjectInput} TS interface
     * minus {@code siteOid} / {@code siteLabel} which are derived
     * server-side from the active study. {@code groupLabel} is
     * accepted but ignored — {@code groupAssignments} is the structured
     * replacement.
     *
     * <p>Phase E.6 Tier 1: {@code studyEye} + {@code screeningDate}
     * persist the ophthalmology-domain extension. Both optional;
     * {@code studyEye} must be one of {@code "OD" / "OS" / "OU"} when
     * present (validated in {@link #validateAddSubject}).
     *
     * <p>Phase E.6 subject-lifecycle: {@code personId} and
     * {@code groupAssignments} are optional.
     * <ul>
     *   <li>{@code personId} — the {@code subject.unique_identifier}
     *       value. When present and a matching subject exists in the
     *       study tree, the new study_subject row reuses the existing
     *       subject_id (Person-ID re-enrol branch — one human, multiple
     *       study participations). When absent or unmatched, a fresh
     *       subject row is created.</li>
     *   <li>{@code groupAssignments} — desired
     *       {@code subject_group_map} state for the new subject. Same
     *       shape as the PUT body; validation runs against the study's
     *       active group classes; REQUIRED classes must be covered.</li>
     * </ul>
     *
     * <p>Spring's JSON binding deserializes by field name (not
     * position), so adding fields at the end is back-compat with
     * existing SPA call sites that omit them.
     */
    public record AddSubjectRequest(
            String id,
            String secondaryId,
            String gender,
            Integer yearOfBirth,
            String enrolledOn,
            String groupLabel,
            String studyEye,
            String screeningDate,
            String personId,
            List<UpdateSubjectGroupsRequest.Assignment> groupAssignments,
            /*
             * Phase E.6 retrospective-backfill — operator-supplied
             * patient identity. The MUW workflow captures the full
             * triplet on every new enrolment so the dedup query can
             * find an existing subject regardless of which study they
             * were first entered in. All three fields are optional on
             * the wire (legacy SPA call sites still post without them),
             * but the SPA enforces required=true at the form layer.
             *
             * dateOfBirth (ISO yyyy-MM-dd) is the canonical DoB. When
             * present it overrides the legacy yearOfBirth → Jan-1
             * mapping; when absent the controller falls back to the
             * yearOfBirth path so old payloads still work.
             *
             * acknowledgeMatchSubjectId: when the SPA's match-preflight
             * surfaced a duplicate and the operator picked "different
             * person, create new anyway", the SPA echoes the seen
             * subject_id(s) so the backend can audit the decision.
             */
            String firstName,
            String lastName,
            String dateOfBirth,
            Integer acknowledgeMatchSubjectId
    ) {}

    private SubjectDetailDto toDetailDto(StudySubjectBean ss, SubjectBean subj, StudyBean study,
                                         StudyEventDAO studyEventDAO,
                                         StudyEventDefinitionDAO studyEventDefinitionDAO,
                                         EventCRFDAO eventCRFDAO,
                                         Map<Integer, StudyEventDefinitionBean> definitionCache,
                                         Map<Integer, Integer> openQueriesByEvent) {
        String secondaryId = (ss.getSecondaryLabel() == null || ss.getSecondaryLabel().isBlank())
                ? null : ss.getSecondaryLabel();
        String gender = mapGender(subj == null ? '\0' : subj.getGender());
        Integer yearOfBirth = extractYear(subj);
        String enrolledOn = formatIsoDate(ss.getEnrollmentDate());

        List<StudyEventBean> events = studyEventDAO.findAllByStudySubject(ss);
        if (events == null) events = Collections.emptyList();

        List<SubjectDetailDto.EventCellDetailDto> eventCells = new ArrayList<>(events.size());
        int subjectOpenQueries = 0;
        for (StudyEventBean ev : events) {
            StudyEventDefinitionBean def = definitionCache.computeIfAbsent(
                    ev.getStudyEventDefinitionId(), studyEventDefinitionDAO::findByPK);
            int statusId = ev.getSubjectEventStatus() == null
                    ? 0
                    : ev.getSubjectEventStatus().getId();
            String status = mapSubjectEventStatus(statusId);
            int eventOpenQueries = openQueriesByEvent.getOrDefault(ev.getId(), 0);
            subjectOpenQueries += eventOpenQueries;

            // Pick the primary CRF for the dataEntryStage column. Most
            // events have a single CRF in the demo; if multiple are
            // attached we use the first one for the stage indicator —
            // the SPA's detail view shows aggregate state, not per-CRF
            // breakdown (that's the M5 read endpoint's job).
            String stage = null;
            List<EventCRFBean> ecs = eventCRFDAO.findAllByStudyEvent(ev);
            if (ecs != null && !ecs.isEmpty()) {
                EventCRFBean primary = ecs.get(0);
                stage = mapDataEntryStage(primary);
            }

            eventCells.add(new SubjectDetailDto.EventCellDetailDto(
                    String.valueOf(ev.getId()),
                    def == null ? null : def.getOid(),
                    def == null ? null : def.getName(),
                    status,
                    eventOpenQueries,
                    formatIsoDate(ev.getDateStarted()),
                    formatIsoDate(ev.getDateEnded()),
                    blankToNull(ev.getLocation()),
                    stage
            ));
        }
        eventCells.sort(Comparator.comparingInt((SubjectDetailDto.EventCellDetailDto cell) -> {
            if (cell.eventDefinitionOid() == null) return Integer.MAX_VALUE;
            StudyEventDefinitionBean d = findDefinitionByOid(definitionCache, cell.eventDefinitionOid());
            return d == null ? Integer.MAX_VALUE : d.getOrdinal();
        }));

        boolean signed = ss.getStatus() != null && ss.getStatus().equals(Status.SIGNED);
        boolean locked = ss.getStatus() != null && ss.getStatus().equals(Status.LOCKED);

        // Phase E.6 subject-lifecycle — load active subject_group_map
        // rows. Detail view always populates this (unlike the matrix,
        // which can defer the per-row fetch via the N+1 mitigation).
        List<GroupAssignmentSnapshot> groupAssignments = loadActiveGroupAssignments(ss.getId());

        // Phase E.6 eye-cohort transition — flatten the per-eye
        // hand-off history so the SPA's banner renders in a single
        // call (no extra round trip to /eye-transitions on first
        // load). Returns null when the subject has no transitions.
        List<SubjectDetailDto.EyeTransitionSummary> eyeTransitions =
                loadEyeTransitionSummaries(ss);

        return new SubjectDetailDto(
                ss.getLabel(),
                secondaryId,
                study.getOid(),
                study.getName(),
                study.getOid(),
                study.getName(),
                gender,
                yearOfBirth,
                /* groupLabel */ null,
                enrolledOn,
                eventCells,
                signed,
                locked,
                subjectOpenQueries,
                ss.getStudyEye(),
                formatIsoDate(ss.getScreeningDate()),
                mapStudySubjectStatus(ss.getStatus()),
                groupAssignments,
                eyeTransitions
        );
    }

    /**
     * Phase E.6 eye-cohort transition — load per-eye transition history
     * for embedding on {@link SubjectDetailDto}. Walks both directions:
     * a row where the subject is the source becomes
     * {@code side='source'} (the subject GAVE the eye away — typically
     * iAMD downgrading on transition to GA), and a row where the
     * subject is the target becomes {@code side='target'} (the subject
     * RECEIVED the eye — typically GA on the receiving side of an iAMD
     * hand-off).
     *
     * <p>Returns {@code null} when the subject has no transitions on
     * record so the SPA can branch banner rendering on null vs empty;
     * a present but empty array would suggest "explicitly empty" which
     * may confuse front-end caching.
     *
     * <p>Best-effort: any SQLException is logged + suppressed; we
     * return null rather than failing the whole detail load. Losing
     * the cross-reference banner is annoying but not as bad as
     * refusing to render the subject at all.
     */
    private List<SubjectDetailDto.EyeTransitionSummary> loadEyeTransitionSummaries(StudySubjectBean ss) {
        if (ss == null || ss.getId() == 0) return null;
        String sql = "SELECT t.transition_id, t.eye, "
                + "       CASE WHEN t.source_study_subject_id = ? THEN 'source' ELSE 'target' END AS side, "
                + "       partner_study.oc_oid AS partner_study_oid, "
                + "       partner_study.name AS partner_study_name, "
                + "       partner_ss.label AS partner_label, "
                + "       t.transitioned_at, t.reason "
                + "  FROM eye_cohort_transition t "
                + "  JOIN study_subject partner_ss "
                + "    ON partner_ss.study_subject_id = CASE WHEN t.source_study_subject_id = ? "
                + "                                          THEN t.target_study_subject_id "
                + "                                          ELSE t.source_study_subject_id END "
                + "  JOIN study partner_study "
                + "    ON partner_study.study_id = CASE WHEN t.source_study_subject_id = ? "
                + "                                     THEN t.target_study_id "
                + "                                     ELSE t.source_study_id END "
                + " WHERE t.source_study_subject_id = ? OR t.target_study_subject_id = ? "
                + " ORDER BY t.transitioned_at ASC, t.transition_id ASC";
        List<SubjectDetailDto.EyeTransitionSummary> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int ssId = ss.getId();
            ps.setInt(1, ssId);
            ps.setInt(2, ssId);
            ps.setInt(3, ssId);
            ps.setInt(4, ssId);
            ps.setInt(5, ssId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("transitioned_at");
                    out.add(new SubjectDetailDto.EyeTransitionSummary(
                            rs.getInt("transition_id"),
                            rs.getString("eye"),
                            rs.getString("side"),
                            rs.getString("partner_study_oid"),
                            rs.getString("partner_study_name"),
                            rs.getString("partner_label"),
                            ts == null ? null : ts.toInstant().toString(),
                            rs.getString("reason")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to load eye_cohort_transition history for study_subject_id={}: {}",
                    ss.getId(), e.getMessage());
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Map an {@link EventCRFBean} to the SPA's {@code dataEntryStage}
     * union value. Returns {@code null} if the CRF isn't started.
     *
     * <p>The legacy convention in the demo seed treats {@code
     * completion_status_id=1 AND date_completed IS NOT NULL} as
     * "complete" — that's the same rule the legacy
     * {@code ViewSectionDataEntryServlet} uses. We honour that:
     * <pre>
     *   date_completed != null && status_id == 2 → "validation-completed"  (locked/e-signed)
     *   date_completed != null                    → "initial-data-entry-completed"
     *   completion_status_id == 0                 → null                    (not started)
     *   completion_status_id == 1                 → "not-started"
     *   completion_status_id == 2                 → "data-being-entered"
     *   completion_status_id == 3                 → "initial-data-entry-completed"
     *   completion_status_id == 4 or 5            → "validation-completed"
     *   completion_status_id == 7                 → "locked"
     *   otherwise                                  → "data-being-entered"
     * </pre>
     */
    private static String mapDataEntryStage(EventCRFBean ec) {
        if (ec == null) return null;
        // E-signed / SDV'd CRFs report a higher Status (2 unavailable)
        // — they're complete from the SPA's perspective regardless of
        // the formal completion stage id.
        boolean hasCompletion = ec.getDateCompleted() != null;
        int csi = ec.getCompletionStatusId();
        if (hasCompletion) {
            // Locked event_crf status (2) means signed/closed in legacy
            // OpenClinica vocabulary — surface as validation-completed.
            if (ec.getStatus() != null && ec.getStatus().equals(Status.UNAVAILABLE)) {
                return "validation-completed";
            }
            return "initial-data-entry-completed";
        }
        return switch (csi) {
            case 0 -> null;
            case 1 -> "not-started";
            case 2 -> "data-being-entered";
            case 3 -> "initial-data-entry-completed";
            case 4, 5 -> "validation-completed";
            case 7 -> "locked";
            default -> "data-being-entered";
        };
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private SubjectListItemDto toDto(StudySubjectBean ss, SubjectBean subj, StudyBean study,
                                     StudyEventDAO studyEventDAO,
                                     StudyEventDefinitionDAO studyEventDefinitionDAO,
                                     Map<Integer, StudyEventDefinitionBean> definitionCache,
                                     Map<Integer, Integer> openQueriesByEvent,
                                     Map<Integer, List<GroupAssignmentSnapshot>> groupAssignmentsByStudySubjectId) {
        String secondaryId = (ss.getSecondaryLabel() == null || ss.getSecondaryLabel().isBlank())
                ? null : ss.getSecondaryLabel();
        String gender = mapGender(subj == null ? '\0' : subj.getGender());
        Integer yearOfBirth = extractYear(subj);
        String enrolledOn = formatIsoDate(ss.getEnrollmentDate());

        // Build per-event status cells. Order by definition ordinal so V1
        // appears before V2 etc. — the SPA renders them column-by-column.
        List<StudyEventBean> events = studyEventDAO.findAllByStudySubject(ss);
        if (events == null) events = Collections.emptyList();

        List<SubjectListItemDto.EventCellDto> eventCells = new ArrayList<>(events.size());
        int subjectOpenQueries = 0;
        for (StudyEventBean ev : events) {
            StudyEventDefinitionBean def = definitionCache.computeIfAbsent(
                    ev.getStudyEventDefinitionId(), studyEventDefinitionDAO::findByPK);
            int statusId = ev.getSubjectEventStatus() == null
                    ? 0
                    : ev.getSubjectEventStatus().getId();
            String status = mapSubjectEventStatus(statusId);
            int eventOpenQueries = openQueriesByEvent.getOrDefault(ev.getId(), 0);
            subjectOpenQueries += eventOpenQueries;

            eventCells.add(new SubjectListItemDto.EventCellDto(
                    def == null ? null : def.getOid(),
                    def == null ? null : def.getName(),
                    status,
                    eventOpenQueries
            ));
        }
        // Order by the definition's ordinal so the matrix columns line up
        // with the protocol's visit order. Null-ordinal events (shouldn't
        // happen in practice) sort last.
        eventCells.sort(Comparator.comparingInt((SubjectListItemDto.EventCellDto cell) -> {
            if (cell.eventDefinitionOid() == null) return Integer.MAX_VALUE;
            StudyEventDefinitionBean d = findDefinitionByOid(definitionCache, cell.eventDefinitionOid());
            return d == null ? Integer.MAX_VALUE : d.getOrdinal();
        }));

        // Subject is signed iff the study_subject row's status is SIGNED.
        // The legacy SignStudySubjectServlet sets BOTH the study_subject
        // status and every event's subject_event_status_id to SIGNED in
        // one transaction (see SignStudySubjectServlet#processRequest →
        // signSubjectEvents + studySub.setStatus(Status.SIGNED)), so a
        // SIGNED study_subject is the authoritative indicator. We do NOT
        // additionally require every event to also report SIGNED — that
        // would re-derive the same fact from a less reliable source and
        // make the flag flicker if event statuses get reset (e.g. a CRF
        // mid-edit). Per the brief's converse guard: an unsigned
        // study_subject row never reports signed regardless of events.
        boolean signed = ss.getStatus() != null && ss.getStatus().equals(Status.SIGNED);

        // Phase E.6 subject-lifecycle — null-safe lookup against the
        // batched per-study group-assignment map (mitigates N+1).
        // Empty list when the subject is enrolled but unassigned; null
        // is reserved for "not loaded" which never happens on this
        // path (we always pass the map in from list()).
        List<GroupAssignmentSnapshot> groupAssignments = groupAssignmentsByStudySubjectId == null
                ? null
                : groupAssignmentsByStudySubjectId.getOrDefault(ss.getId(), Collections.emptyList());

        return new SubjectListItemDto(
                ss.getLabel(),
                secondaryId,
                study.getOid(),
                study.getName(),
                gender,
                yearOfBirth,
                /* groupLabel */ null,
                enrolledOn,
                eventCells,
                signed,
                subjectOpenQueries,
                ss.getStudyEye(),
                mapStudySubjectStatus(ss.getStatus()),
                groupAssignments
        );
    }

    /** Lookup helper for the cache reverse-lookup during cell ordering. */
    private static StudyEventDefinitionBean findDefinitionByOid(
            Map<Integer, StudyEventDefinitionBean> cache, String oid) {
        for (StudyEventDefinitionBean def : cache.values()) {
            if (def != null && oid.equals(def.getOid())) return def;
        }
        return null;
    }

    /**
     * Single-shot open-query aggregation for an entire study.
     *
     * <p>The legacy {@code DiscrepancyNoteDAO#findByStudyEvent} query joins
     * {@code discrepancy_note → dn_item_data_map → item_data → event_crf
     * → study_event}, but groups by {@code resolution_status_id} and
     * scopes to one event at a time. For the matrix we'd issue 21
     * queries to fill the cells in the demo seed and many more in
     * production studies — pure N×M waste.
     *
     * <p>Instead we issue one query per study, returning {@code (study_event_id,
     * count)} pairs for parent notes whose resolution status is open
     * (1 New / 2 Updated / 3 Resolution Proposed). Closed (4) and Not
     * Applicable (5) are excluded — matches the legacy "open queries"
     * definition the JSP uses.
     *
     * <p>Inline SQL because:
     * <ol>
     *   <li>The legacy DAO only exposes per-event variants — there's no
     *       findOpenItemDataByStudy method to wrap.</li>
     *   <li>Adding one to {@code DiscrepancyNoteDAO} is out of scope for
     *       M2 (the plan says "wraps an existing DAO method" — refactoring
     *       the DAO surface belongs in a separate slice).</li>
     * </ol>
     */
    private Map<Integer, Integer> loadOpenQueryCountsForStudy(int studyId) {
        String sql = "SELECT se.study_event_id, COUNT(DISTINCT dn.discrepancy_note_id) "
                + "FROM study_event se "
                + "JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "JOIN event_crf ec ON ec.study_event_id = se.study_event_id "
                + "JOIN item_data id_ ON id_.event_crf_id = ec.event_crf_id "
                + "JOIN dn_item_data_map didm ON didm.item_data_id = id_.item_data_id "
                + "JOIN discrepancy_note dn ON dn.discrepancy_note_id = didm.discrepancy_note_id "
                + "WHERE ss.study_id = ? "
                + "  AND dn.parent_dn_id IS NULL "
                + "  AND dn.resolution_status_id IN (1, 2, 3) "
                + "GROUP BY se.study_event_id";
        Map<Integer, Integer> counts = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getInt(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            // Degrade gracefully — if the aggregation fails (e.g. DB hiccup)
            // the matrix still renders with openQueries=0 for every cell
            // rather than 500ing the entire page. Logged at WARN so it
            // surfaces in monitoring without spamming.
            LOG.warn("Open-query aggregation failed for study {} — falling back to zeros", studyId, e);
        }
        return counts;
    }

    /**
     * Phase E.6 subject-lifecycle — single-shot
     * {@code subject_group_map} aggregation for an entire study (or a
     * site-scoped set). Mitigates the matrix N+1 risk (reviewer flag):
     * issuing one DAO call per {@link StudySubjectBean} would scale
     * O(N×K) with N subjects × K group classes; the matrix instead
     * fans out once per {@code visibleStudyIds} entry.
     *
     * <p>Only ACTIVE group-class rows are returned — both
     * {@code subject_group_map.status_id} and the parent
     * {@code study_group_class.status_id} must be available (status 1).
     * Disabled / removed group classes don't leak into the SPA's
     * matrix nor into the audit-trail-driven views; the legacy
     * {@code ListStudySubjectsServlet} silently filters them too.
     *
     * <p>{@code group_id} is null-tolerant — an
     * {@code OPTIONAL not-now} row carries a NULL {@code study_group_id}
     * in the legacy schema (LEFT JOIN against {@code study_group}).
     */
    private Map<Integer, List<GroupAssignmentSnapshot>> loadGroupAssignmentsForStudy(
            Set<Integer> studyIds) {
        Map<Integer, List<GroupAssignmentSnapshot>> out = new HashMap<>();
        if (studyIds == null || studyIds.isEmpty()) return out;

        // Build a comma list — small N (≤ 10 sites typical), values are
        // ints so SQL-injection-safe. Avoids prepared-statement param
        // expansion for variable-length IN lists.
        StringBuilder ids = new StringBuilder();
        for (Integer sid : studyIds) {
            if (sid == null) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(sid.intValue());
        }
        if (ids.length() == 0) return out;

        String sql = "SELECT sgm.study_subject_id, sgc.study_group_class_id, sgc.name, "
                + "       sgm.study_group_id, sg.name AS group_name, sgc.subject_assignment "
                + "FROM subject_group_map sgm "
                + "JOIN study_group_class sgc ON sgc.study_group_class_id = sgm.study_group_class_id "
                + "LEFT JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                + "JOIN study_subject ss ON ss.study_subject_id = sgm.study_subject_id "
                + "WHERE ss.study_id IN (" + ids + ") "
                + "  AND sgm.status_id = 1 "
                + "  AND sgc.status_id = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int studySubjectId = rs.getInt(1);
                int groupClassId = rs.getInt(2);
                String groupClassName = rs.getString(3);
                int rawGroupId = rs.getInt(4);
                Integer groupId = rs.wasNull() ? null : Integer.valueOf(rawGroupId);
                String groupName = rs.getString(5);
                String subjectAssignment = rs.getString(6);
                out.computeIfAbsent(studySubjectId, k -> new ArrayList<>())
                   .add(new GroupAssignmentSnapshot(
                           groupClassId, groupClassName, groupId, groupName, subjectAssignment));
            }
        } catch (SQLException e) {
            LOG.warn("Subject-group-map aggregation failed for studies {} — matrix will show null groupAssignments",
                    studyIds, e);
        }
        return out;
    }

    /**
     * Phase E.6 subject-lifecycle — single-subject group-assignment load.
     *
     * <p>Used by {@code toDetailDto} and by the create endpoint's
     * response builder. Filtering rules match
     * {@link #loadGroupAssignmentsForStudy} byte-for-byte.
     */
    private List<GroupAssignmentSnapshot> loadActiveGroupAssignments(int studySubjectId) {
        List<GroupAssignmentSnapshot> out = new ArrayList<>();
        if (studySubjectId <= 0) return out;
        String sql = "SELECT sgc.study_group_class_id, sgc.name, "
                + "       sgm.study_group_id, sg.name AS group_name, sgc.subject_assignment "
                + "FROM subject_group_map sgm "
                + "JOIN study_group_class sgc ON sgc.study_group_class_id = sgm.study_group_class_id "
                + "LEFT JOIN study_group sg ON sg.study_group_id = sgm.study_group_id "
                + "WHERE sgm.study_subject_id = ? "
                + "  AND sgm.status_id = 1 "
                + "  AND sgc.status_id = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int groupClassId = rs.getInt(1);
                    String groupClassName = rs.getString(2);
                    int rawGroupId = rs.getInt(3);
                    Integer groupId = rs.wasNull() ? null : Integer.valueOf(rawGroupId);
                    String groupName = rs.getString(4);
                    String subjectAssignment = rs.getString(5);
                    out.add(new GroupAssignmentSnapshot(
                            groupClassId, groupClassName, groupId, groupName, subjectAssignment));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Group-assignment lookup failed for study_subject={} — surfacing empty list",
                    studySubjectId, e);
        }
        return out;
    }

    /**
     * Phase E.6 subject-lifecycle — map {@link Status} to the SPA's
     * coarse string. Mirrors the SPA's {@code SubjectStatus} TS union.
     * Null Status (defensive) and any code outside the legacy
     * AVAILABLE/REMOVED/AUTO_DELETED/LOCKED/SIGNED set fall through
     * to {@code "available"} — the matrix's safest default.
     */
    private static String mapStudySubjectStatus(Status status) {
        if (status == null) return "available";
        int id = status.getId();
        return switch (id) {
            case 1 -> "available";
            case 5 -> "removed";
            case 6 -> "locked";
            case 7 -> "auto-removed";
            case 8 -> "signed";
            default -> "available";
        };
    }

    /** Map the single-char DB encoding to the SPA's `Gender` union. */
    private static String mapGender(char g) {
        return switch (g) {
            case 'f', 'F' -> "F";
            case 'm', 'M' -> "M";
            case 'o', 'O' -> "O";
            default -> "U";
        };
    }

    /**
     * Map {@code subject_event_status_id} → SPA {@code EventStatus} union.
     *
     * <p>The SPA union has six values; the bean enum has nine. Stopped /
     * Skipped don't render in the matrix (they're a follow-up workflow);
     * Invalid shouldn't occur in production data. All three collapse to
     * "not-scheduled" — a safe default that produces an empty grey cell
     * rather than a confusing label.
     */
    private static String mapSubjectEventStatus(int subjectEventStatusId) {
        return switch (subjectEventStatusId) {
            case 1 -> "scheduled";
            case 2 -> "not-scheduled";
            case 3 -> "in-progress";
            case 4 -> "complete";
            case 7 -> "locked";
            case 8 -> "signed";
            default -> "not-scheduled";
        };
    }

    /** Extract YoB if the study collects DoB and the subject has one. */
    private static Integer extractYear(SubjectBean subj) {
        if (subj == null || !subj.isDobCollected() || subj.getDateOfBirth() == null) return null;
        // SubjectDAO returns java.sql.Date for the date_of_birth column, and
        // java.sql.Date#toInstant() throws UnsupportedOperationException by
        // design (the sql Date has no time component). Convert via epoch ms.
        return java.time.Instant.ofEpochMilli(subj.getDateOfBirth().getTime())
                .atZone(ZoneId.systemDefault())
                .getYear();
    }

    /** Format `Date` → ISO YYYY-MM-DD. */
    private static String formatIsoDate(Date d) {
        if (d == null) return null;
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault()).toString();
    }
}
