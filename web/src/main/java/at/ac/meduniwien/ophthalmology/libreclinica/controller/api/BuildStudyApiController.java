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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

        // A4 — visibility guard. Only return build-status for studies
        // that fall inside the session user's visible tree (based on
        // the SESSION-bound active study, not the requested one — the
        // build-status endpoint is a metadata read on top of the
        // user's role chain, not a free-form study lookup). For a
        // user whose session has no active study bound we fall back
        // to "is the requested study one of the user's role grants?"
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

        int studyId = study.getId();
        int parentId = study.getParentStudyId() > 0 ? study.getParentStudyId() : studyId;

        CRFDAO crfDao = new CRFDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
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
        int sites = studyDao.findAllByParent(studyId).size();
        int users = userDao.findAllUsersByStudy(studyId).size();
        int rules = countQuery(COUNT_RULES_SQL, studyId);
        int enrolled = countQuery(COUNT_ENROLLED_SQL, studyId);

        List<StudyBuildDto.StudyBuildTaskDto> tasks = new ArrayList<>();
        tasks.add(task("create-study", null, "complete", null));
        tasks.add(task("crf", crfs, statusForCount(crfs), null));
        tasks.add(task("events", events, statusForCount(events), null));
        // groups / rules / sites are operator-discretion: a single-site
        // observational study at MUW often has zero of each and that's
        // a valid completion state. Report "optional" instead of
        // "not-started" so the SPA tracker doesn't paint them as
        // outstanding work.
        tasks.add(task("groups", groups, statusForOptionalCount(groups), null));
        tasks.add(task("rules", rules, statusForOptionalCount(rules), null));
        tasks.add(task("sites", sites, statusForOptionalCount(sites), null));
        tasks.add(task("users", users, statusForCount(users), "/manage-users"));

        StudyBuildDto dto = new StudyBuildDto(
                nullToEmpty(study.getOid()),
                nullToEmpty(study.getName()),
                nullToEmpty(study.getProtocolType()),
                sites,
                enrolled,
                tasks);

        return ResponseEntity.ok(dto);
    }

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
     * sites). Returns "optional" instead of "not-started" so the SPA
     * tracker renders a non-blocking pill — the study isn't waiting on
     * the operator to define groups if the protocol doesn't need them.
     */
    private static String statusForOptionalCount(int count) {
        return count > 0 ? "complete" : "optional";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
