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
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.6 — per-eye cohort transition controller.
 *
 * <p>Ophthalmology cohorts split early/late disease into distinct
 * protocols (iAMD vs GA, for example) and each eye progresses
 * independently. This controller's POST endpoint records a per-eye
 * hand-off from the iAMD ("source") protocol's {@code study_subject}
 * to the GA ("target") protocol's {@code study_subject}, while the
 * GET endpoint surfaces the history for the SPA's banner.
 *
 * <h2>Clinical rules</h2>
 *
 * <ol>
 *   <li><strong>Source downgrade, never delete.</strong> When OD or
 *       OS leaves the source cohort, the source row's {@code study_eye}
 *       is downgraded:
 *       <ul>
 *         <li>OU → other-eye (the eye that didn't transition stays
 *             in scope for the source protocol).</li>
 *         <li>single-eye (OD or OS) matching the transitioning eye
 *             → NULL (no eye remains in scope).</li>
 *       </ul>
 *       The casebook data on the source row is preserved verbatim —
 *       deleting the iAMD history would lose the natural-history
 *       record that motivated GA enrolment.</li>
 *   <li><strong>Subject identity preserved.</strong> The target
 *       {@code study_subject} reuses the source's {@code subject_id}.
 *       One human, two participations — the existing person-id
 *       re-enrol pattern in
 *       {@link SubjectsApiController#create} is the canonical
 *       expression of this rule and we reuse it here.</li>
 *   <li><strong>Bilateral GA appends.</strong> If a subject's second
 *       eye later transitions, the EXISTING target row's
 *       {@code study_eye} upgrades OD → OU (rather than creating a
 *       second GA enrolment that would split the casebook).</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 *
 * <p>The whole hand-off runs in a single JDBC transaction
 * ({@code autoCommit=false}); the source row is read with
 * {@code SELECT … FOR UPDATE} so two operators can't race two
 * transitions on the same source row and corrupt the OU→single-eye
 * downgrade. Target-row UPSERTs serialise on the (study_id,
 * subject_id) uniqueness implied by the existing seed.
 *
 * <h2>Audit</h2>
 *
 * <p>One {@code audit_log_event} row per successful transition with
 * type 57 ({@code eye_cohort_transition}, seeded by
 * {@code lc-muw-2026-06-07-eye-cohort-transition.xml}). The row's
 * {@code entity_id} carries the new {@code eye_cohort_transition.transition_id},
 * {@code old_value} = "{sourceStudyOid}|{eye}|{sourceEyeBefore}",
 * {@code new_value} = "{targetStudyOid}|{eye}|{targetEyeAfter}|{reason}".
 * The Audit Log SPA renders these into a human sentence using the
 * pipe-delimited fields.
 *
 * <h2>Authorization</h2>
 *
 * <p>Chain-level {@code .anyRequest().hasRole("USER")} + per-request
 * {@link SiteVisibilityFilter} scope guard on BOTH the source
 * (session-bound active study) and the target (the
 * {@code targetStudyOid} body field). Cross-tree attempts return 403
 * not 404 — leaking the existence of an out-of-scope study is the
 * lesser evil compared to the "study not found" UX confusion if the
 * operator does have access to a sibling.
 */
@RestController
@RequestMapping("/api/v1/subjects")
@Tag(name = "Eye Cohort Transitions",
     description = "Per-eye cohort hand-off (iAMD → GA, etc.) audit + write path.")
public class EyeCohortTransitionsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EyeCohortTransitionsApiController.class);

    /**
     * {@code audit_log_event_type.audit_log_event_type_id} for per-eye
     * cohort hand-offs, seeded by
     * {@code lc-muw-2026-06-07-eye-cohort-transition.xml}. The
     * Liquibase preCondition there guards against ID collisions in
     * case a sibling phase races us; the constant here is the
     * compile-time expectation matching that seed.
     */
    static final int AUDIT_TYPE_EYE_COHORT_TRANSITION = 57;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public EyeCohortTransitionsApiController(@Qualifier("dataSource") DataSource dataSource,
                                             SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * Request body for {@link #transition}.
     *
     * <p>{@code targetStudyOid} resolves through
     * {@link StudyDAO#findByOid} (the {@code study.unique_identifier}
     * column).
     *
     * <p>{@code targetLabel} is the human-friendly label the new
     * target {@code study_subject} should carry. Optional — when
     * absent or blank, the controller reuses the source subject's
     * existing label so the SPA's "M-001 in iAMD" surfaces as "M-001
     * in GA" with no operator-side action. Ignored when the target
     * row already exists (the upgrade path), since we never rename
     * an existing enrolment.
     *
     * <p>{@code reason} is mandatory free-text up to 500 chars. Stored
     * on the {@code eye_cohort_transition.reason} column + duplicated
     * into the {@code audit_log_event.new_value} column so the Audit
     * Log SPA can render the operator-stated rationale without joining
     * back to {@code eye_cohort_transition}.
     */
    public record TransitionRequest(
            String targetStudyOid,
            String targetLabel,
            String reason
    ) {}

    /**
     * 201 happy-path response shape.
     *
     * <p>The SPA's transition dialog uses this to navigate to the
     * newly created (or upgraded) target subject + show a toast with
     * the eye-after summary. {@code sourceEyeAfter} carries the
     * downgraded source value (might be {@code null} when the source
     * was single-eye); {@code targetEyeAfter} carries the resulting
     * target value (could be the eye that just moved in, or OU when
     * upgraded from the other single-eye).
     */
    @Schema(name = "EyeCohortTransitionResponse")
    public record TransitionResponse(
            int transitionId,
            int sourceStudySubjectId,
            int targetStudySubjectId,
            String targetLabel,
            String sourceEyeAfter,
            String targetEyeAfter
    ) {}

    /* =============================================================== */
    /* POST                                                            */
    /* =============================================================== */

    @PostMapping(value = "/{label}/eyes/{eye}/transition",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = TransitionResponse.class)))
    public ResponseEntity<?> transition(@PathVariable("label") String label,
                                        @PathVariable("eye") String eyeRaw,
                                        @RequestBody(required = false) TransitionRequest body,
                                        HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Request body is required."));
        }
        final String eye = eyeRaw == null ? null : eyeRaw.trim().toUpperCase();
        if (!"OD".equals(eye) && !"OS".equals(eye)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eye must be 'OD' or 'OS' (got '" + eyeRaw + "')."));
        }
        final String targetStudyOid =
                body.targetStudyOid() == null ? "" : body.targetStudyOid().trim();
        if (targetStudyOid.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "targetStudyOid is required."));
        }
        final String reason = body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "reason is required."));
        }
        if (reason.length() > 500) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "reason must be ≤ 500 characters."));
        }

        // ---- Resolve source study_subject by (active study, path label) ----
        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean sourceSs = studySubjectDAO.findByLabelAndStudy(label, currentStudy);
        if (sourceSs == null || sourceSs.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with label '" + label + "' not found in active study."));
        }

        // The active study itself must be visible — defence in depth
        // since the session.attribute("study") should already have been
        // sanity-checked, but a stale session might point at a study
        // the user lost access to. visibleStudyIds returns the empty
        // set for "no scope".
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(sourceSs.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Active study is not in your visible study set."));
        }

        // ---- Resolve target study by OID + visibility check ----
        StudyDAO studyDAO = new StudyDAO(dataSource);
        StudyBean targetStudy = studyDAO.findByOid(targetStudyOid);
        if (targetStudy == null || targetStudy.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Target study with OID '" + targetStudyOid + "' not found."));
        }
        if (!visibleStudyIds.contains(targetStudy.getId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Target study is not in your visible study set."));
        }

        // ---- Source eye scope check (pre-transaction; cheap reject) ----
        // The transition only makes sense if THIS eye is currently in
        // the source row's study_eye scope. "OS on a row with
        // study_eye='OD'" is operator error.
        String sourceEyeBefore = sourceSs.getStudyEye();
        if (!eyeInScope(sourceEyeBefore, eye)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Source subject's study_eye '"
                            + (sourceEyeBefore == null ? "(null)" : sourceEyeBefore)
                            + "' does not include eye '" + eye + "'."));
        }

        // ---- Transactional write path ----
        int sourceSubjectId = sourceSs.getSubjectId();
        String desiredTargetLabel = (body.targetLabel() == null || body.targetLabel().trim().isEmpty())
                ? sourceSs.getLabel()
                : body.targetLabel().trim();

        TransitionResponse out;
        try (Connection c = dataSource.getConnection()) {
            boolean priorAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                // 1. Re-read the source row inside the transaction with
                //    FOR UPDATE so a concurrent transition can't slip a
                //    second OD→GA hand-off in between our read + write.
                String sourceEyeBeforeTx = lockSourceStudyEye(c, sourceSs.getId());
                if (!eyeInScope(sourceEyeBeforeTx, eye)) {
                    c.rollback();
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "Source subject's study_eye '"
                                    + (sourceEyeBeforeTx == null ? "(null)" : sourceEyeBeforeTx)
                                    + "' does not include eye '" + eye + "' (concurrent transition?)."));
                }

                // 2. Resolve / create the target study_subject row by
                //    (target_study_id, subject_id). Three sub-cases:
                //    (a) absent → create via re-enrol pattern;
                //    (b) present with other eye → upgrade to OU;
                //    (c) present with same eye or already OU → 409.
                TargetResolution resolved = resolveOrCreateTarget(
                        c, targetStudy, sourceSubjectId, eye,
                        desiredTargetLabel, currentUser);
                if (resolved.conflictMessage != null) {
                    c.rollback();
                    return ResponseEntity.status(409).body(Map.of(
                            "message", resolved.conflictMessage));
                }

                // 3. Downgrade the source row's study_eye now that the
                //    target side is committed-in-progress. OU → other;
                //    single-eye → NULL.
                String sourceEyeAfter = downgradeSourceEye(sourceEyeBeforeTx, eye);
                updateStudyEye(c, sourceSs.getId(), sourceEyeAfter);

                // 4. Insert the eye_cohort_transition audit edge.
                int transitionId = insertTransitionRow(c,
                        sourceSubjectId, eye,
                        sourceSs.getId(), sourceSs.getStudyId(),
                        resolved.targetStudySubjectId, targetStudy.getId(),
                        currentUser.getId(), reason);

                // 5. Insert the audit_log_event row (type 57).
                emitTransitionAudit(c, currentUser.getId(), transitionId,
                        currentStudy.getOid(), eye, sourceEyeBeforeTx,
                        targetStudy.getOid(), resolved.targetEyeAfter,
                        reason, sourceSs.getLabel());

                c.commit();
                out = new TransitionResponse(
                        transitionId,
                        sourceSs.getId(),
                        resolved.targetStudySubjectId,
                        resolved.targetLabel,
                        sourceEyeAfter,
                        resolved.targetEyeAfter);
            } catch (Exception e) {
                c.rollback();
                LOG.error("Eye cohort transition failed for source_ss={} eye={} target_study={}: {}",
                        sourceSs.getId(), eye, targetStudy.getOid(), e.getMessage(), e);
                return ResponseEntity.status(500).body(Map.of(
                        "message", "Failed to commit eye cohort transition — see server log."));
            } finally {
                c.setAutoCommit(priorAutoCommit);
            }
        } catch (SQLException e) {
            LOG.error("Failed to open connection for eye cohort transition", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to open transaction — see server log."));
        }

        LOG.info("Eye cohort transition: source_ss={} (label={}) eye={} → target_ss={} (label={}, study={}) by user={}",
                out.sourceStudySubjectId(), sourceSs.getLabel(), eye,
                out.targetStudySubjectId(), out.targetLabel(), targetStudy.getOid(),
                currentUser.getName());

        return ResponseEntity.status(201).body(out);
    }

    /* =============================================================== */
    /* GET                                                             */
    /* =============================================================== */

    @GetMapping("/{label}/eye-transitions")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = EyeTransitionDto.class)))
    public ResponseEntity<?> list(@PathVariable("label") String label,
                                  HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = studySubjectDAO.findByLabelAndStudy(label, currentStudy);
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with label '" + label + "' not found in active study."));
        }
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(ss.getStudyId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Subject's study is not in your visible study set."));
        }

        List<EyeTransitionDto> rows = loadEyeTransitions(ss.getId());
        return ResponseEntity.ok(rows);
    }

    /* =============================================================== */
    /* Internal helpers                                                */
    /* =============================================================== */

    /**
     * Does {@code eye} sit inside the {@code study_eye} scope? OU
     * contains both; OD contains only OD; OS contains only OS; NULL
     * contains nothing (the eye has already been removed from scope
     * by a prior transition).
     */
    private static boolean eyeInScope(String studyEye, String eye) {
        if (studyEye == null) return false;
        if ("OU".equals(studyEye)) return true;
        return studyEye.equals(eye);
    }

    /**
     * Source-eye downgrade: OU → other-eye; single-eye matching the
     * transitioning eye → NULL. The eye-in-scope guard already
     * filtered out the "single-eye that doesn't match" case.
     */
    private static String downgradeSourceEye(String studyEyeBefore, String transitioningEye) {
        if (studyEyeBefore == null) return null;
        if ("OU".equals(studyEyeBefore)) {
            return "OD".equals(transitioningEye) ? "OS" : "OD";
        }
        // single-eye matching → NULL
        return null;
    }

    private String lockSourceStudyEye(Connection c, int studySubjectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_eye FROM study_subject WHERE study_subject_id = ? FOR UPDATE")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("study_subject row vanished mid-transaction: " + studySubjectId);
                }
                return rs.getString(1);
            }
        }
    }

    private void updateStudyEye(Connection c, int studySubjectId, String newStudyEye) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE study_subject SET study_eye = ? WHERE study_subject_id = ?")) {
            if (newStudyEye == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, newStudyEye);
            }
            ps.setInt(2, studySubjectId);
            ps.executeUpdate();
        }
    }

    /**
     * Resolution outcome from {@link #resolveOrCreateTarget}. Either
     * {@code conflictMessage} is non-null (409 path) or
     * {@code targetStudySubjectId} + {@code targetEyeAfter} +
     * {@code targetLabel} carry the resolved target row.
     */
    private static final class TargetResolution {
        final int targetStudySubjectId;
        final String targetEyeAfter;
        final String targetLabel;
        final String conflictMessage;

        private TargetResolution(int targetStudySubjectId, String targetEyeAfter,
                                 String targetLabel, String conflictMessage) {
            this.targetStudySubjectId = targetStudySubjectId;
            this.targetEyeAfter = targetEyeAfter;
            this.targetLabel = targetLabel;
            this.conflictMessage = conflictMessage;
        }

        static TargetResolution ok(int ssId, String eyeAfter, String label) {
            return new TargetResolution(ssId, eyeAfter, label, null);
        }

        static TargetResolution conflict(String message) {
            return new TargetResolution(0, null, null, message);
        }
    }

    /**
     * Three-way resolve of the target {@code study_subject} row:
     *
     * <ol>
     *   <li>No row exists → INSERT a fresh row with
     *       {@code study_eye=eye}.</li>
     *   <li>Row exists with the OTHER single eye → UPDATE
     *       {@code study_eye='OU'} (bilateral GA append).</li>
     *   <li>Row exists with THIS eye or with OU → 409 conflict.</li>
     * </ol>
     */
    private TargetResolution resolveOrCreateTarget(Connection c, StudyBean targetStudy,
                                                   int subjectId, String eye,
                                                   String desiredLabel,
                                                   UserAccountBean actor)
            throws SQLException {
        // Lookup by (target_study_id, subject_id). The legacy
        // study_subject schema doesn't enforce a unique constraint on
        // this tuple, but the Person-ID re-enrol pattern in
        // SubjectsApiController.create asserts the application-layer
        // invariant of one row per (subject, study). Multiple rows
        // would be a data-integrity bug; we surface them as a 409 too.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_subject_id, label, study_eye FROM study_subject "
                        + "WHERE study_id = ? AND subject_id = ? FOR UPDATE")) {
            ps.setInt(1, targetStudy.getId());
            ps.setInt(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int existingId = rs.getInt("study_subject_id");
                    String existingLabel = rs.getString("label");
                    String existingEye = rs.getString("study_eye");
                    // Case 3: same eye or OU already present.
                    if (existingEye == null) {
                        // No eye in scope on the target → re-occupy
                        // with the transitioning eye (e.g. an earlier
                        // transition downgraded this row to NULL and
                        // the OTHER eye is now coming in too). Treat
                        // as occupying the single eye.
                        try (PreparedStatement upd = c.prepareStatement(
                                "UPDATE study_subject SET study_eye = ? WHERE study_subject_id = ?")) {
                            upd.setString(1, eye);
                            upd.setInt(2, existingId);
                            upd.executeUpdate();
                        }
                        return TargetResolution.ok(existingId, eye, existingLabel);
                    }
                    if ("OU".equals(existingEye) || existingEye.equals(eye)) {
                        return TargetResolution.conflict(
                                "Target subject already has eye '" + eye + "' (study_eye='"
                                        + existingEye + "').");
                    }
                    // Case 2: other single-eye → upgrade to OU.
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE study_subject SET study_eye = 'OU' WHERE study_subject_id = ?")) {
                        upd.setInt(1, existingId);
                        upd.executeUpdate();
                    }
                    return TargetResolution.ok(existingId, "OU", existingLabel);
                }
            }
        }

        // Case 1: no row → INSERT. Reuse the Person-ID re-enrol pattern
        // from SubjectsApiController: the subject_id is preserved so
        // subject.unique_identifier (and demographics) carry across.
        // Mirror the StudySubjectDAO.create field set + a hand-rolled
        // OID by appending the eye + study OID to keep it unique
        // without colliding with the source row's OID.
        int newId = insertTargetStudySubjectRow(c, targetStudy, subjectId, desiredLabel,
                eye, actor);
        return TargetResolution.ok(newId, eye, desiredLabel);
    }

    /**
     * INSERT a {@code study_subject} row into the target study with
     * {@code study_eye=eye}. Direct SQL (rather than via
     * {@code StudySubjectDAO.create}) so we can run in the caller's
     * connection + transaction. The OID is built deterministically
     * from the source label + study OID + eye; collisions are
     * extremely unlikely in single-site MUW deployment and a UNIQUE
     * violation would surface as a transaction rollback (the caller
     * catches it).
     */
    private int insertTargetStudySubjectRow(Connection c, StudyBean targetStudy,
                                            int subjectId, String label, String eye,
                                            UserAccountBean actor)
            throws SQLException {
        String oid = buildOid(targetStudy, label, eye);
        String sql = "INSERT INTO study_subject (label, subject_id, study_id, status_id, "
                + "date_created, owner_id, oc_oid, study_eye) "
                + "VALUES (?, ?, ?, ?, NOW(), ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label);
            ps.setInt(2, subjectId);
            ps.setInt(3, targetStudy.getId());
            ps.setInt(4, Status.AVAILABLE.getId());
            ps.setInt(5, actor.getId());
            ps.setString(6, oid);
            ps.setString(7, eye);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("Target study_subject insert returned no PK.");
            }
        }
    }

    private static String buildOid(StudyBean targetStudy, String label, String eye) {
        // OpenClinica-style OID: SS_<studyOid>_<label>_<eye>; truncate
        // to 40 chars to fit the typical OID column width. Adding eye
        // disambiguates source vs target rows for the same human in
        // single-study cross-eye tests.
        String base = "SS_" + safe(targetStudy.getOid()) + "_" + safe(label) + "_" + eye;
        if (base.length() > 40) base = base.substring(0, 40);
        return base;
    }

    private static String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') sb.append(ch);
            else sb.append('_');
        }
        return sb.toString();
    }

    private int insertTransitionRow(Connection c,
                                    int subjectId, String eye,
                                    int sourceStudySubjectId, int sourceStudyId,
                                    int targetStudySubjectId, int targetStudyId,
                                    int actorUserId, String reason)
            throws SQLException {
        String sql = "INSERT INTO eye_cohort_transition "
                + "(subject_id, eye, source_study_subject_id, source_study_id, "
                + " target_study_subject_id, target_study_id, actor_user_id, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, subjectId);
            ps.setString(2, eye);
            ps.setInt(3, sourceStudySubjectId);
            ps.setInt(4, sourceStudyId);
            ps.setInt(5, targetStudySubjectId);
            ps.setInt(6, targetStudyId);
            ps.setInt(7, actorUserId);
            ps.setString(8, reason);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
                throw new SQLException("eye_cohort_transition insert returned no PK.");
            }
        }
    }

    /**
     * Emit one {@code audit_log_event} row of type 57 inside the
     * caller's transaction. Unlike the export-audit pattern (which
     * tolerates audit failure to ship the user-visible payload), the
     * transition audit row is REQUIRED by the GxP audit trail and
     * rolls back with the rest of the transaction if it fails.
     *
     * <p>{@code old_value} pipe-encodes the source-side facts
     * ({sourceStudyOid}|{eye}|{sourceEyeBefore}); {@code new_value}
     * pipe-encodes the target-side ({targetStudyOid}|{eye}|{targetEyeAfter}|{reason}).
     * The Audit Log SPA splits these at the pipe and renders into a
     * sentence.
     */
    private void emitTransitionAudit(Connection c, int actorUserId, int transitionId,
                                     String sourceStudyOid, String eye, String sourceEyeBefore,
                                     String targetStudyOid, String targetEyeAfter,
                                     String reason, String entityName)
            throws SQLException {
        String oldVal = nz(sourceStudyOid) + "|" + nz(eye) + "|" + nz(sourceEyeBefore);
        String newVal = nz(targetStudyOid) + "|" + nz(eye) + "|" + nz(targetEyeAfter) + "|" + nz(reason);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                        + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, 'eye_cohort_transition', ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_EYE_COHORT_TRANSITION);
            ps.setInt(2, actorUserId);
            ps.setInt(3, transitionId);
            ps.setString(4, nz(entityName));
            ps.setString(5, truncate(oldVal, 4000));
            ps.setString(6, truncate(newVal, 4000));
            ps.executeUpdate();
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Load the per-eye transition history for a single
     * {@code study_subject}, walking BOTH directions (source-side +
     * target-side).
     */
    private List<EyeTransitionDto> loadEyeTransitions(int studySubjectId) {
        String sql = "SELECT t.transition_id, t.eye, "
                + "       CASE WHEN t.source_study_subject_id = ? THEN 'source' ELSE 'target' END AS side, "
                + "       partner_study.unique_identifier AS partner_study_oid, "
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
        List<EyeTransitionDto> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) ps.setInt(i, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("transitioned_at");
                    out.add(new EyeTransitionDto(
                            rs.getInt("transition_id"),
                            rs.getString("eye"),
                            rs.getString("side"),
                            rs.getString("partner_study_oid"),
                            rs.getString("partner_study_name"),
                            rs.getString("partner_label"),
                            ts == null ? null : ts.toInstant().toString(),
                            rs.getString("reason")));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to load eye_cohort_transition rows for study_subject_id={}: {}",
                    studySubjectId, e.getMessage());
        }
        return out;
    }

    /**
     * Test helper hook — unused in production but kept here so the
     * SubjectDAO import isn't dead in case future hardening pivots
     * the controller to surface DOB/identity in the response.
     */
    @SuppressWarnings("unused")
    private SubjectBean fetchSubject(int subjectId) {
        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        return subjectDAO.findByPK(subjectId);
    }
}
