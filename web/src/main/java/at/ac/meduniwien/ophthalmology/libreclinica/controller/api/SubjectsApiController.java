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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
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
    public ResponseEntity<?> list(HttpSession session) {
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

        List<SubjectListItemDto> out = new ArrayList<>(rows.size());
        for (StudySubjectBean ss : rows) {
            SubjectBean subj = subjectCache.computeIfAbsent(ss.getSubjectId(), subjectDAO::findByPK);
            out.add(toDto(ss, subj, currentStudy, studyEventDAO, studyEventDefinitionDAO,
                    definitionCache, openQueriesByEvent));
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

        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Validation failed", errors));
        }

        // ----- Persist subject row (direct SQL to preserve O/U gender codes) -----
        String trimmedId = body.id().trim();
        String trimmedSecondary = body.secondaryId() == null ? null : body.secondaryId().trim();
        if (trimmedSecondary != null && trimmedSecondary.isEmpty()) trimmedSecondary = null;
        char genderChar = Character.toLowerCase(body.gender().charAt(0));
        java.sql.Date dob = (body.yearOfBirth() == null) ? null
                : java.sql.Date.valueOf(LocalDate.of(body.yearOfBirth(), 1, 1));
        boolean dobCollected = body.yearOfBirth() != null;
        java.sql.Date enrolledOn = java.sql.Date.valueOf(LocalDate.parse(body.enrolledOn()));

        int newSubjectId;
        try {
            newSubjectId = insertSubjectRow(genderChar, dob, dobCollected, currentUser.getId());
        } catch (SQLException e) {
            LOG.error("Failed to insert subject row for label={} study={}", trimmedId, currentStudy.getOid(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to create subject — see server log."));
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

        try {
            ssb = studySubjectDAO.create(ssb);
        } catch (Exception e) {
            LOG.error("Failed to insert study_subject row for label={} study={}", trimmedId, currentStudy.getOid(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to enrol subject — see server log."));
        }
        if (ssb == null || ssb.getId() == 0) {
            LOG.error("study_subject insert returned no PK for label={} study={}", trimmedId, currentStudy.getOid());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to enrol subject — no PK returned."));
        }

        // ----- Build the response DTO -----
        // No events scheduled yet — they're created via M11 (Schedule Event).
        // No queries either. Reuse the M3 DTO shape so the SPA can drop the
        // new subject straight into `rows` without a refetch.
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
                /* openQueries */ 0
        );

        LOG.info("Add Subject: created study_subject id={} oid={} label={} (study {}, user {})",
                ssb.getId(), ssb.getOid(), ssb.getLabel(), currentStudy.getOid(), currentUser.getName());

        return ResponseEntity.status(201).body(dto);
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
    private int insertSubjectRow(char gender, java.sql.Date dob, boolean dobCollected, int ownerId)
            throws SQLException {
        String sql = "INSERT INTO subject (status_id, date_of_birth, gender, unique_identifier, "
                + "owner_id, date_created, dob_collected) "
                + "VALUES (?, ?, ?, ?, ?, NOW(), ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, Status.AVAILABLE.getId());
            if (dob == null) {
                ps.setNull(2, Types.DATE);
            } else {
                ps.setDate(2, dob);
            }
            ps.setString(3, String.valueOf(gender));
            ps.setNull(4, Types.VARCHAR); // unique_identifier nullable, not used by M4
            ps.setInt(5, ownerId);
            ps.setBoolean(6, dobCollected);
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
     * accepted but ignored in M4 — its plumbing arrives in a later
     * compliance slice.
     */
    public record AddSubjectRequest(
            String id,
            String secondaryId,
            String gender,
            Integer yearOfBirth,
            String enrolledOn,
            String groupLabel
    ) {}

    /**
     * 400-response body shape for validation failures. Matches the
     * SPA's existing {@code AddSubjectError} TS shape byte-for-byte:
     * an {@code errors} array of {field, message} pairs plus a
     * top-level {@code message} summary.
     */
    public record ValidationErrorBody(String message, List<FieldError> errors) {
        public record FieldError(String field, String message) {}
    }

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
                subjectOpenQueries
        );
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
                                     Map<Integer, Integer> openQueriesByEvent) {
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
                subjectOpenQueries
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
