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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M12 — Build-Study tracker adapter.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /pages/api/v1/studies/{oid}/build-status} —
 *       returns the 7-task setup tracker for the given study OID.
 *       Each task counts the corresponding entity (CRFs / event
 *       defs / subject groups / rules / sites / users); status is
 *       {@code complete} when count &gt; 0 or {@code not-started}
 *       when count == 0. Create-study is always {@code complete}
 *       since the study row itself is the precondition for the
 *       endpoint resolving.</li>
 * </ul>
 *
 * <p><strong>Authorization:</strong> chain-level
 * {@code .anyRequest().hasRole("USER")}. The endpoint additionally
 * 404s on unknown OIDs.
 */
@RestController
@RequestMapping("/api/v1/studies")
@Tag(name = "Build Study", description = "Per-study build-status (7-task setup tracker).")
public class BuildStudyApiController {

    private static final Logger LOG = LoggerFactory.getLogger(BuildStudyApiController.class);

    /** Count distinct users with any role on the study (or its sites). */
    private static final String COUNT_USERS_SQL = """
            SELECT COUNT(DISTINCT sur.user_id)
            FROM study_user_role sur
            WHERE sur.study_id = ?
               OR sur.study_id IN (SELECT study_id FROM study WHERE parent_study_id = ?)
            """;

    /** Count rules attached to the study. */
    private static final String COUNT_RULES_SQL = """
            SELECT COUNT(*) FROM rule WHERE study_id = ?
            """;

    /** Count enrolled (status=AVAILABLE) study_subjects. */
    private static final String COUNT_ENROLLED_SQL = """
            SELECT COUNT(*) FROM study_subject WHERE study_id = ? AND status_id = 1
            """;

    /** Read the set of operator-acknowledged optional task ids for a study. */
    private static final String SELECT_ACKS_SQL = """
            SELECT task_id FROM study_build_task_ack WHERE study_id = ?
            """;

    /** Idempotent insert — clicking the ack button twice is a no-op. */
    private static final String INSERT_ACK_SQL = """
            INSERT INTO study_build_task_ack (study_id, task_id, acknowledged_by)
            VALUES (?, ?, ?)
            ON CONFLICT (study_id, task_id) DO NOTHING
            """;

    /** Audit-log emit for build-study task acknowledgements (event type 63). */
    private static final String INSERT_AUDIT_SQL = """
            INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id,
                                         audit_table, entity_id, entity_name,
                                         old_value, new_value)
            VALUES (63, now(), ?, 'study', ?, ?, '', ?)
            """;

    /**
     * Closed taxonomy of operator-discretion tasks the acknowledge
     * endpoint accepts. Mirrors the {@code statusForOptionalCount}
     * branches in the GET path.
     */
    static final Set<String> ACK_TASK_IDS = Set.of("groups", "rules", "sites");

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public BuildStudyApiController(@Qualifier("dataSource") DataSource dataSource,
                                   SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping("/{oid}/build-status")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyBuildDto.class)))
    public ResponseEntity<?> buildStatus(@PathVariable("oid") String oid, HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(oid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + oid + "'"));
        }

        ResponseEntity<?> guard = visibilityGuard(study, oid, session, me);
        if (guard != null) return guard;

        return ResponseEntity.ok(buildDto(study));
    }

    /**
     * Phase E.6 build-study tracker — manual acknowledgement of an
     * operator-discretion task. Operator clicks "Mark as complete"
     * on a {@code groups / rules / sites} card whose count is zero
     * to record the explicit decision that no further configuration
     * is needed. Idempotent: a repeat POST is a no-op.
     *
     * <p>Validation:
     * <ul>
     *   <li>{@code taskId} required + in {@link #ACK_TASK_IDS} — 400
     *       with {@link ValidationErrorBody} otherwise.</li>
     *   <li>Same auth + visibility guard as the GET — 401 / 404 /
     *       403 are emitted before any write.</li>
     * </ul>
     *
     * <p>Audit: writes one {@code audit_log_event} row of type 63
     * (build_study_task_acknowledged), with {@code entity_id} =
     * study_id and {@code new_value} = "ack:{taskId}".
     */
    @PostMapping(value = "/{oid}/build-status/acknowledge",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyBuildDto.class)))
    public ResponseEntity<?> acknowledgeTask(@PathVariable("oid") String oid,
                                             @RequestBody(required = false) AcknowledgeRequest body,
                                             HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        // Body shape validation — fields-first so the SPA's per-field
        // error display lights up.
        String taskId = body == null ? null : body.taskId();
        String trimmed = taskId == null ? "" : taskId.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "taskId is required.",
                    List.of(new FieldError("taskId", "taskId is required."))));
        }
        if (!ACK_TASK_IDS.contains(trimmed)) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "taskId must be one of " + ACK_TASK_IDS + ".",
                    List.of(new FieldError("taskId",
                            "Unsupported task id '" + taskId + "'."))));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = studyDao.findByOid(oid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + oid + "'"));
        }

        ResponseEntity<?> guard = visibilityGuard(study, oid, session, me);
        if (guard != null) return guard;

        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(INSERT_ACK_SQL)) {
                ps.setInt(1, study.getId());
                ps.setString(2, trimmed);
                ps.setInt(3, me.getId());
                ps.executeUpdate();
            }
            emitAcknowledgeAudit(c, me.getId(), study.getId(), study.getOid(), trimmed);
        } catch (SQLException e) {
            LOG.error("Failed to acknowledge build-study task {} for study {}: {}",
                    trimmed, oid, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to record acknowledgement — see server log."));
        }

        return ResponseEntity.ok(buildDto(study));
    }

    /* ----------------------------------------------------------------- */
    /* Shared GET path                                                   */
    /* ----------------------------------------------------------------- */

    /**
     * A4 — visibility guard. Only return build-status for studies
     * that fall inside the session user's visible tree (based on
     * the SESSION-bound active study, not the requested one — the
     * build-status endpoint is a metadata read on top of the
     * user's role chain, not a free-form study lookup). For a
     * user whose session has no active study bound we fall back
     * to "is the requested study one of the user's role grants?"
     */
    private ResponseEntity<?> visibilityGuard(StudyBean study, String oid,
                                              HttpSession session, UserAccountBean me) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        if (currentStudy != null && currentStudy.getId() > 0) {
            Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                    me, currentStudy, currentRole);
            if (!visibleStudyIds.contains(study.getId())) {
                return ResponseEntity.status(403).body(Map.of("message",
                        "Study '" + oid + "' is not visible to your role."));
            }
        }
        return null;
    }

    private StudyBuildDto buildDto(StudyBean study) {
        int studyId = study.getId();

        CRFDAO crfDao = new CRFDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        UserAccountDAO userDao = new UserAccountDAO(dataSource);

        // findAllByStudy filters by `source_study_id` — but seeded /
        // institution-wide CRFs (Demographics, Ophthalmology Visit, etc.)
        // ship with source_study_id NULL so they're shared across the
        // single-site deployment. Count globally so the Build Study tracker
        // reflects what the operator actually sees in the CRF library.
        int crfs = crfDao.findAll().size();
        int events = sedDao.findAllByStudy(study).size();
        ArrayList<StudyGroupClassBean> groupClasses = sgcDao.findAllActiveByStudy(study);
        int groups = groupClasses == null ? 0 : groupClasses.size();
        int sites = studyDao().findAllByParent(studyId).size();
        int users = userDao.findAllUsersByStudy(studyId).size();
        int rules = countQuery(COUNT_RULES_SQL, studyId);
        int enrolled = countQuery(COUNT_ENROLLED_SQL, studyId);

        Set<String> acknowledged = loadAcknowledgedTaskIds(studyId);

        List<StudyBuildDto.StudyBuildTaskDto> tasks = new ArrayList<>();
        tasks.add(task("create-study", null, "complete", null));
        tasks.add(task("crf", crfs, statusForCount(crfs), null));
        tasks.add(task("events", events, statusForCount(events), null));
        // groups / rules / sites are operator-discretion: a single-site
        // observational study at MUW often has zero of each and that's
        // a valid completion state. Report "optional" instead of
        // "not-started" so the SPA tracker doesn't paint them as
        // outstanding work — and flip to "complete" once the operator
        // explicitly acknowledges the zero-count step.
        tasks.add(task("groups", groups,
                statusForOptionalCount(groups, acknowledged.contains("groups")), null));
        tasks.add(task("rules", rules,
                statusForOptionalCount(rules, acknowledged.contains("rules")), null));
        tasks.add(task("sites", sites,
                statusForOptionalCount(sites, acknowledged.contains("sites")), null));
        tasks.add(task("users", users, statusForCount(users), "/manage-users"));

        return new StudyBuildDto(
                nullToEmpty(study.getOid()),
                nullToEmpty(study.getName()),
                nullToEmpty(study.getProtocolType()),
                sites,
                enrolled,
                tasks);
    }

    private StudyDAO studyDao() {
        return new StudyDAO(dataSource);
    }

    private Set<String> loadAcknowledgedTaskIds(int studyId) {
        Set<String> out = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ACKS_SQL)) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            // Don't block the dashboard on a single missing table
            // (e.g. pre-migration env). Worst case: optional tasks
            // stay "optional" until the changeset runs.
            LOG.warn("Failed to read study_build_task_ack for study {}: {}",
                    studyId, e.getMessage());
        }
        return out;
    }

    private void emitAcknowledgeAudit(Connection c, int userId, int studyId,
                                      String studyOid, String taskId) {
        try (PreparedStatement ps = c.prepareStatement(INSERT_AUDIT_SQL)) {
            ps.setInt(1, userId);
            ps.setInt(2, studyId);
            ps.setString(3, studyOid == null ? "" : studyOid);
            ps.setString(4, "ack:" + taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Same pattern as ModalitiesApiController.emitAudit —
            // do NOT roll back the underlying write on audit failure.
            LOG.warn("Failed to write build-study-task-ack audit (study={}, task={}): {}",
                    studyId, taskId, e.getMessage());
        }
    }

    /**
     * Request body for {@code POST /build-status/acknowledge}.
     * Single-field DTO; the controller normalises whitespace + case
     * before validating against {@link #ACK_TASK_IDS}.
     */
    public record AcknowledgeRequest(String taskId) {}

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private int countQuery(String sql, int... bindings) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < bindings.length; i++) ps.setInt(i + 1, bindings[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.warn("Count query failed: {}", e.getMessage());
        }
        return 0;
    }

    private static StudyBuildDto.StudyBuildTaskDto task(String id, Integer count, String status, String to) {
        return new StudyBuildDto.StudyBuildTaskDto(id, count, status, to);
    }

    private static String statusForCount(int count) {
        return count > 0 ? "complete" : "not-started";
    }

    /**
     * Status helper for operator-discretion tasks (groups / rules /
     * sites). Returns "complete" if either:
     * <ul>
     *   <li>the count is positive — configuring even one row is
     *       evidence the operator has decided what the step needs;</li>
     *   <li>or the operator has explicitly acknowledged the zero-count
     *       step via {@code POST /build-status/acknowledge}.</li>
     * </ul>
     * Otherwise returns "optional" so the SPA tracker renders a
     * non-blocking pill — the study isn't waiting on the operator to
     * define groups if the protocol doesn't need them.
     */
    private static String statusForOptionalCount(int count, boolean acknowledged) {
        if (count > 0 || acknowledged) return "complete";
        return "optional";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
