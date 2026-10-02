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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.SourceDataVerification;
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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M9 — SDV (Source Data Verification) adapter.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /pages/api/v1/sdv} — returns one
 *       {@link SdvRowDto} per complete event-CRF in the session-bound
 *       active study. Reuses the same filter/sort overload as the legacy
 *       {@code /viewAllSubjectSdvData} JSON endpoint
 *       ({@code SDVController.java:344}) but reshapes the row to the
 *       SPA's contract so {@code stores/sdv.ts} doesn't have to know
 *       about the legacy field set.</li>
 *   <li>{@code POST /pages/api/v1/sdv/verify} — bulk-flips the
 *       {@code sdv_status} column on a list of event-CRFs to
 *       {@code true}. Records the verifier id via
 *       {@code EventCRFDAO.setSDVStatus}. {@code verified=false} is an
 *       un-verify and is handled by {@link #unverify}: it needs a
 *       {@code reason} and an un-verifying role, like any other.</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> verifying needs an SDV role
 * ({@link ClinicalWriteAuthorization#roleMayVerifySdv}), un-verifying
 * {@link SdvUnverifyAuthorization}: the session's role, or another role
 * the caller holds on the active study
 * ({@link ClinicalWriteAuthorization#anyRoleOnTheStudyMay}). Both need a
 * session-bound active study, and report an event CRF outside the
 * caller's visible studies as rejected.
 *
 * <p><strong>Completion:</strong> only a complete event CRF can be
 * verified ({@link #completeForVerification}). The list leaves the
 * others out and verify rejects them, as the legacy SDV table does,
 * with one exception: a verified CRF stays listed, as verified, until
 * the verification is withdrawn ({@link #listed}).
 *
 * <p>Status mapping for the read endpoint:
 * <ul>
 *   <li>{@code sdv_status=true} → {@code verified}</li>
 *   <li>else if {@code openQueries > 0} → {@code query}</li>
 *   <li>else if {@code DataEntryStage = LOCKED} → {@code locked}</li>
 *   <li>else → {@code pending}</li>
 * </ul>
 *
 * <p>The {@code query} override matches the legacy "any open
 * discrepancy parks SDV" semantics — it does NOT correspond to a
 * column on event_crf. The count comes from
 * {@code DiscrepancyNoteDAO.findAllParentItemNotesByEventCRF}.
 */
@RestController
@RequestMapping("/api/v1/sdv")
@Tag(name = "SDV", description = "Source Data Verification table + bulk verify.")
public class SdvApiController {

    private static final Logger LOG = LoggerFactory.getLogger(SdvApiController.class);
    private static final SimpleDateFormat ISO_DATE = new SimpleDateFormat("yyyy-MM-dd");

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public SdvApiController(@Qualifier("dataSource") DataSource dataSource,
                            SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = SdvRowDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
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

        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        StudyEventDAO studyEventDao = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        CRFVersionDAO crfVersionDao = new CRFVersionDAO(dataSource);
        CRFDAO crfDao = new CRFDAO(dataSource);
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        DiscrepancyNoteDAO dnDao = new DiscrepancyNoteDAO(dataSource);

        // The legacy /viewAllSubjectSdvData filter restricts to
        // (status_id ∈ {2, 6} AND source_data_verification_code != 4).
        // Its status test misses a CRF completed in the SPA, which keeps
        // status 1 and carries a completion date instead, so iterate by
        // study-subject and apply completeForVerification per row. Rows
        // still in data entry are left out: nothing may verify them. A
        // verified row stays (listed).
        //
        // A4 — per-site visibility. Walk the visible study set
        // rather than the bare currentStudy.id so a Monitor with a
        // site-only grant under a multi-site parent only sees the
        // site's CRFs.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        ArrayList<StudySubjectBean> subjects = new ArrayList<>();
        for (Integer sid : visibleStudyIds) {
            ArrayList<StudySubjectBean> chunk = studySubjectDao.findAllByStudyId(sid);
            if (chunk != null) subjects.addAll(chunk);
        }
        ArrayList<EventCRFBean> beans = new ArrayList<>();
        for (StudySubjectBean ss : subjects) {
            int ssStudy = ss.getStudyId() > 0 ? ss.getStudyId() : studyId;
            beans.addAll(eventCrfDao.getEventCRFsByStudySubject(ss.getId(), ssStudy, ssStudy));
        }

        // Caches — many event-CRFs share the same subject / event /
        // CRF-version / EDC tuple, so don't re-hit the DB per row.
        Map<Integer, StudySubjectBean> ssCache = new HashMap<>();
        Map<Integer, StudyEventBean> evCache = new HashMap<>();
        Map<Integer, StudyEventDefinitionBean> sedCache = new HashMap<>();
        Map<Integer, StudyBean> studyCache = new HashMap<>();
        Map<Integer, CRFVersionBean> versionCache = new HashMap<>();
        Map<Integer, CRFBean> crfCache = new HashMap<>();

        List<SdvRowDto> out = new ArrayList<>(beans.size());
        for (EventCRFBean ec : beans) {
            StudySubjectBean ss = ssCache.computeIfAbsent(ec.getStudySubjectId(),
                    id -> (StudySubjectBean) studySubjectDao.findByPK(id));
            StudyEventBean evt = evCache.computeIfAbsent(ec.getStudyEventId(),
                    id -> (StudyEventBean) studyEventDao.findByPK(id));
            if (ss == null || ss.getId() == 0 || evt == null || evt.getId() == 0) {
                continue;
            }

            // study_event.name doesn't exist in this schema — the
            // human-readable event label lives on the definition row.
            StudyEventDefinitionBean sed = evt.getStudyEventDefinitionId() > 0
                    ? sedCache.computeIfAbsent(evt.getStudyEventDefinitionId(),
                            id -> (StudyEventDefinitionBean) sedDao.findByPK(id))
                    : null;
            String eventLabel = sed != null ? nullToEmpty(sed.getName()) : nullToEmpty(evt.getName());
            if (evt.getSampleOrdinal() > 1 && !eventLabel.isBlank()) {
                eventLabel = eventLabel + " #" + evt.getSampleOrdinal();
            }

            StudyBean ownerStudy = ss.getStudyId() > 0
                    ? studyCache.computeIfAbsent(ss.getStudyId(), id -> (StudyBean) studyDao.findByPK(id))
                    : null;
            CRFVersionBean version = versionCache.computeIfAbsent(ec.getCRFVersionId(),
                    id -> (CRFVersionBean) crfVersionDao.findByPK(id));
            CRFBean crf = (version != null && version.getCrfId() > 0)
                    ? crfCache.computeIfAbsent(version.getCrfId(), id -> (CRFBean) crfDao.findByPK(id))
                    : null;

            EventDefinitionCRFBean edc = edcDao
                    .findByStudyEventIdAndCRFVersionId(ownerStudy != null ? ownerStudy : currentStudy,
                            evt.getId(), ec.getCRFVersionId());
            if (!listed(ec, evt, ss, edc)) {
                continue;
            }

            String requirement = requirementFromEdc(edc);
            int openQueries = countOpenQueries(dnDao, ec.getId());
            String status = statusForRow(ec, openQueries);

            String eventStartDate = evt.getDateStarted() == null
                    ? "" : ISO_DATE.format(evt.getDateStarted());
            String lastUpdatedAt = ec.getUpdatedDate() == null
                    // Phase E.5 fix (2026-06-03): see CrfsApiController.toVersionDto
                    // — same java.sql.Date.toInstant() UnsupportedOperationException
                    // gotcha. Coerce via getTime() → Instant.
                    ? "" : java.time.Instant.ofEpochMilli(ec.getUpdatedDate().getTime())
                            .truncatedTo(ChronoUnit.SECONDS).toString();

            String crfName = buildCrfDisplayName(crf, version);

            out.add(new SdvRowDto(
                    String.valueOf(ec.getId()),
                    nullToEmpty(ss.getLabel()),
                    ownerStudy == null ? "" : nullToEmpty(ownerStudy.getName()),
                    eventLabel,
                    eventStartDate,
                    crfName,
                    "en",
                    status,
                    requirement,
                    openQueries,
                    lastUpdatedAt));
        }

        return ResponseEntity.ok(out);
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest body, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (body == null || body.eventCrfOids() == null || body.eventCrfOids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'eventCrfOids' is required"));
        }
        // verified=false withdraws verification. That is an un-verify on
        // every path: it needs the reason and the role /unverify needs.
        if (Boolean.FALSE.equals(body.verified())) {
            return unverify(new UnverifyRequest(body.eventCrfOids(), body.reason()), session);
        }
        if (!ClinicalWriteAuthorization.anyRoleOnTheStudyMay(session, dataSource,
                ClinicalWriteAuthorization::roleMayVerifySdv)) {
            return ClinicalWriteAuthorization.forbidden("source data verification");
        }

        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        StudyEventDAO studyEventDao = new StudyEventDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);

        // A4 — per-site visibility. The verify endpoint rejects any
        // event_crf whose study_subject sits outside the user's
        // visible study tree. For a Monitor with site-only grants
        // that means cross-site verify attempts return as rejected.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);

        List<String> verified = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (String oid : body.eventCrfOids()) {
            int id;
            try { id = Integer.parseInt(oid); }
            catch (NumberFormatException nfe) { rejected.add(oid); continue; }
            if (id <= 0) { rejected.add(oid); continue; }

            EventCRFBean ec = (EventCRFBean) eventCrfDao.findByPK(id);
            if (ec == null || ec.getId() == 0) { rejected.add(oid); continue; }

            // Cross-study guard: refuse anything not in the user's
            // visible study tree (the A4 per-site rule supersedes
            // the legacy parent-or-bare-id check).
            StudySubjectBean ss = (StudySubjectBean) studySubjectDao.findByPK(ec.getStudySubjectId());
            if (ss == null || !visibleStudyIds.contains(ss.getStudyId())) {
                rejected.add(oid);
                continue;
            }

            // Phase E A3-lock follow-up — refuse SDV flips on locked
            // subjects' CRFs. SDV is a data attestation; freezing it
            // alongside the data preserves the audit semantics.
            if (ss.getStatus() != null && ss.getStatus().equals(Status.LOCKED)) {
                rejected.add(oid);
                continue;
            }

            // Only a complete CRF can be verified; one still in data
            // entry has nothing settled to check against the source. A
            // removed one is not part of the casebook.
            StudyBean ownerStudy = (StudyBean) studyDao.findByPK(ss.getStudyId());
            EventDefinitionCRFBean edc = edcDao.findByStudyEventIdAndCRFVersionId(
                    ownerStudy != null && ownerStudy.getId() > 0 ? ownerStudy : currentStudy,
                    ec.getStudyEventId(), ec.getCRFVersionId());
            StudyEventBean evt = (StudyEventBean) studyEventDao.findByPK(ec.getStudyEventId());
            if (removed(ec, evt, ss) || !completeForVerification(ec, edc)) {
                rejected.add(oid);
                continue;
            }

            try {
                eventCrfDao.setSDVStatus(true, ub.getId(), id);
                verified.add(oid);
            } catch (Exception e) {
                LOG.warn("Failed to flip sdv_status on event_crf id={}", id, e);
                rejected.add(oid);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("verified", verified);
        response.put("rejected", rejected);
        response.put("verifiedCount", verified.size());
        response.put("verifiedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        response.put("verifiedBy", ub.getName());
        LOG.info("Bulk SDV verify by user={}: {} verified, {} rejected",
                ub.getName(), verified.size(), rejected.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Phase E A6 — un-verify a batch of {@code event_crf} rows.
     * Inverse of {@link #verify}: flips {@code sdv_status} back to
     * {@code FALSE} so a Monitor can re-verify or a DM can return
     * the row to the queue.
     *
     * <p>Guards (order matters):
     * <ol>
     *   <li>{@code 401} — no authenticated user.</li>
     *   <li>{@code 400} — no active study bound.</li>
     *   <li>{@code 400} — body missing {@code eventCrfOids} or
     *       {@code reason}.</li>
     *   <li>{@code 403} — no role the caller holds on the active study is
     *       Monitor / DM / Admin (per {@link SdvUnverifyAuthorization}).</li>
     *   <li>Per-row: {@code event_crf} outside the caller's
     *       site-visibility set → row rejected; otherwise the row's
     *       {@code sdv_status} is flipped to false.</li>
     * </ol>
     *
     * <p>Writes one {@code audit_log_event} row per successfully
     * un-verified CRF — captures the caller's id, the
     * {@code event_crf_id}, and the {@code reason} the body
     * supplied. Audit emission is wrapped in a try/catch (per the
     * {@code EventCrfsApiController.writeAuditEvent} convention)
     * so an audit-write failure does not roll back the SDV change.
     *
     * <p>Mirrors the legacy {@code handleSDVRemove} servlet but with
     * one REST endpoint per click instead of the multi-step JSP form.
     */
    @PostMapping("/unverify")
    public ResponseEntity<?> unverify(@RequestBody UnverifyRequest body, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (body == null || body.eventCrfOids() == null || body.eventCrfOids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "'eventCrfOids' is required"));
        }
        if (body.reason() == null || body.reason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "'reason' is required — un-verify must capture a justification "
                            + "for the audit trail"));
        }

        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (!ClinicalWriteAuthorization.anyRoleOnTheStudyMay(session, dataSource,
                SdvUnverifyAuthorization::roleMayUnverify)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit un-verifying CRFs"));
        }

        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);
        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                ub, currentStudy, currentRole);
        List<String> unverified = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (String oid : body.eventCrfOids()) {
            int id;
            try { id = Integer.parseInt(oid); }
            catch (NumberFormatException nfe) { rejected.add(oid); continue; }
            if (id <= 0) { rejected.add(oid); continue; }

            EventCRFBean ec = (EventCRFBean) eventCrfDao.findByPK(id);
            if (ec == null || ec.getId() == 0) { rejected.add(oid); continue; }

            StudySubjectBean ss = (StudySubjectBean) studySubjectDao.findByPK(ec.getStudySubjectId());
            if (ss == null || !visibleStudyIds.contains(ss.getStudyId())) {
                rejected.add(oid);
                continue;
            }

            // Phase E A3-lock follow-up — locked subjects' SDV state
            // is frozen alongside their data. Skip without writing.
            if (ss.getStatus() != null && ss.getStatus().equals(Status.LOCKED)) {
                rejected.add(oid);
                continue;
            }

            // Skip rows that are already unverified — the SPA may have
            // sent a stale view, no need to rewrite the row.
            if (!ec.isSdvStatus()) {
                rejected.add(oid);
                continue;
            }

            try {
                eventCrfDao.setSDVStatus(false, ub.getId(), id);
                unverified.add(oid);

                // Audit row capturing the un-verify + reason. Routed
                // through the unified writeAuditEvent helper (Phase
                // audit-unification, 2026-06-12) so the row lands in
                // audit_log_event (visible to the SPA Audit Log view).
                // The reason goes in reason_for_change: the action
                // message is not stored.
                EventCrfsApiController.writeAuditEvent(auditDAO,
                        AuditTypeIds.EVENT_CRF_SDV_UNVERIFIED,
                        ub, currentStudy, ss,
                        "event_crf_sdv_unverify",
                        "event_crf", id,
                        "sdv_status", "true", "false", body.reason().trim());
            } catch (Exception e) {
                LOG.warn("Failed to flip sdv_status to false on event_crf id={}", id, e);
                rejected.add(oid);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("unverified", unverified);
        response.put("rejected", rejected);
        response.put("unverifiedCount", unverified.size());
        response.put("unverifiedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        response.put("unverifiedBy", ub.getName());
        LOG.info("Bulk SDV unverify by user={} role={}: {} unverified, {} rejected; reason='{}'",
                ub.getName(), roleId, unverified.size(), rejected.size(), body.reason());
        return ResponseEntity.ok(response);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    /**
     * Whether an event CRF that is not removed ({@link #removed}) is
     * complete, and so may be source-data verified.
     *
     * <p>Legacy SDV lists and verifies only event CRFs whose status is
     * completed ({@link Status#UNAVAILABLE}, id 2) or locked (6): the
     * {@code /viewAllSubjectSdvData} filter and
     * {@code SDVUtil.setSDVStatusForStudySubjects}. The SPA completes a CRF
     * by date and leaves its status available: {@code date_completed} once
     * initial data entry is complete and, where double data entry applies,
     * {@code date_validate_completed} once the second pass is. Every other
     * status, signed included, goes by those dates, and double data entry
     * needs both: signing stamps {@code date_validate_completed} on each CRF
     * of the visit or subject, whether or not its first pass is done.
     * Reopening a CRF clears all of these markers
     * ({@code EventCRFDAO.markIncomplete}).
     *
     * @param edc the CRF's event definition CRF, the site's own where the
     *            subject's site has one, as double data entry reads it;
     *            {@code null} reads as single data entry
     */
    static boolean completeForVerification(EventCRFBean ec, EventDefinitionCRFBean edc) {
        Status status = ec.getStatus();
        if (Status.UNAVAILABLE.equals(status) || Status.LOCKED.equals(status)) {
            return true;
        }
        if (ec.getDateCompleted() == null) {
            return false;
        }
        boolean doubleEntry = edc != null && edc.isDoubleEntry();
        return !doubleEntry || ec.getDateValidateCompleted() != null;
    }

    /**
     * Whether the SDV list shows an event CRF: when it is complete for
     * verification, and when it is verified although it is not, or no
     * longer, complete. Such a verification was made before only complete
     * CRFs could be verified, or the CRF was reopened after it; the list
     * shows it so that it can be withdrawn, which the SDV page offers only
     * for listed rows. A removed CRF is not listed at all ({@link #removed}).
     */
    static boolean listed(EventCRFBean ec, StudyEventBean evt, StudySubjectBean ss,
                          EventDefinitionCRFBean edc) {
        if (removed(ec, evt, ss)) {
            return false;
        }
        return ec.isSdvStatus() || completeForVerification(ec, edc);
    }

    /**
     * Whether an event CRF is removed: its own status says so, or that of
     * its visit or subject. Removing a visit or a subject removes its CRFs
     * too, but signing a subject used to set every CRF of the subject to
     * signed, removed ones included; such a CRF is still known as removed
     * by its visit. A removed CRF is neither listed nor verified.
     */
    static boolean removed(EventCRFBean ec, StudyEventBean evt, StudySubjectBean ss) {
        return isRemoved(ec.getStatus())
                || (evt != null && isRemoved(evt.getStatus()))
                || (ss != null && isRemoved(ss.getStatus()));
    }

    private static boolean isRemoved(Status status) {
        return Status.DELETED.equals(status) || Status.AUTO_DELETED.equals(status);
    }

    private static String statusForRow(EventCRFBean ec, int openQueries) {
        if (ec.isSdvStatus()) return "verified";
        if (openQueries > 0) return "query";
        int stageId = ec.getStage() == null ? DataEntryStage.UNCOMPLETED.getId() : ec.getStage().getId();
        if (stageId == DataEntryStage.LOCKED.getId()) return "locked";
        return "pending";
    }

    private static String requirementFromEdc(EventDefinitionCRFBean edc) {
        if (edc == null) return "not-required";
        SourceDataVerification sdv = edc.getSourceDataVerification();
        if (sdv == null) return "not-required";
        return switch (sdv) {
            case AllREQUIRED -> "required-100";
            case PARTIALREQUIRED -> "required-partial";
            case NOTREQUIRED, NOTAPPLICABLE -> "not-required";
        };
    }

    private static int countOpenQueries(DiscrepancyNoteDAO dao, int eventCrfId) {
        ArrayList<DiscrepancyNoteBean> notes = dao.findAllParentItemNotesByEventCRF(eventCrfId);
        if (notes == null || notes.isEmpty()) return 0;
        int open = 0;
        for (DiscrepancyNoteBean n : notes) {
            int status = n.getResolutionStatusId();
            // OPEN(1), UPDATED(2), RESOLVED(3) are still actionable;
            // CLOSED(4) and NOT_APPLICABLE(5) are terminal.
            if (status >= 1 && status <= 3) open++;
        }
        return open;
    }

    private static String buildCrfDisplayName(CRFBean crf, CRFVersionBean version) {
        String crfName = (crf != null && crf.getName() != null) ? crf.getName() : "";
        String versionName = (version != null && version.getName() != null) ? version.getName() : "";
        if (crfName.isEmpty() && versionName.isEmpty()) return "";
        if (versionName.isEmpty()) return crfName;
        return (crfName + " / " + versionName).trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Body of POST /pages/api/v1/sdv/verify — bulk-flip event_crf.sdv_status. */
    public record VerifyRequest(
            List<String> eventCrfOids,
            /** Defaults to {@code true} when null; {@code false} un-verifies. */
            Boolean verified,
            /** Required when {@code verified} is {@code false}, as on /unverify. */
            String reason
    ) {}
}
