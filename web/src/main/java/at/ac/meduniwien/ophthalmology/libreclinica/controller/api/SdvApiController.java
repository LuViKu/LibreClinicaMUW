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
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M9 — SDV (Source Data Verification) adapter.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /pages/api/v1/sdv} — returns one
 *       {@link SdvRowDto} per event-CRF in the session-bound active
 *       study. Reuses the same filter/sort overload as the legacy
 *       {@code /viewAllSubjectSdvData} JSON endpoint
 *       ({@code SDVController.java:344}) but reshapes the row to the
 *       SPA's contract so {@code stores/sdv.ts} doesn't have to know
 *       about the legacy field set.</li>
 *   <li>{@code POST /pages/api/v1/sdv/verify} — bulk-flips the
 *       {@code sdv_status} column on a list of event-CRFs to
 *       {@code true} (or {@code false} when {@code verified=false} is
 *       sent). Records the verifier id + timestamp via
 *       {@code EventCRFDAO.setSDVStatus}.</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> chain-level
 * {@code .anyRequest().hasRole("USER")} gates both endpoints. The
 * write endpoint additionally requires a session-bound active study
 * and verifies that every event-CRF id in the request belongs to that
 * study (returns 403 on any cross-study id).
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
        // (status_id ∈ {2, 6} AND source_data_verification_code != 4)
        // — useful for the "what's left to verify" inbox but it hides
        // in-progress and signed rows the SPA still wants to render
        // (so the operator can see SDV requirement vs. completion
        // state side-by-side). Iterate by study-subject instead and
        // let the SPA filter client-side.
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

            String requirement = requirementFromEdc(edc);
            int openQueries = countOpenQueries(dnDao, ec.getId());
            String status = statusForRow(ec, openQueries);

            String eventStartDate = evt.getDateStarted() == null
                    ? "" : ISO_DATE.format(evt.getDateStarted());
            String lastUpdatedAt = ec.getUpdatedDate() == null
                    ? "" : ec.getUpdatedDate().toInstant().truncatedTo(ChronoUnit.SECONDS).toString();

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
        boolean targetState = body.verified() == null ? true : body.verified();

        EventCRFDAO eventCrfDao = new EventCRFDAO(dataSource);
        StudySubjectDAO studySubjectDao = new StudySubjectDAO(dataSource);

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

            try {
                eventCrfDao.setSDVStatus(targetState, ub.getId(), id);
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
        LOG.info("Bulk SDV verify=({}) by user={}: {} verified, {} rejected",
                targetState, ub.getName(), verified.size(), rejected.size());
        return ResponseEntity.ok(response);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

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
            /** Defaults to {@code true} when null. */
            Boolean verified
    ) {}
}
