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

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupClassBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyGroupClassDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @Autowired
    public BuildStudyApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/{oid}/build-status")
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

        int studyId = study.getId();
        int parentId = study.getParentStudyId() > 0 ? study.getParentStudyId() : studyId;

        CRFDAO crfDao = new CRFDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyGroupClassDAO sgcDao = new StudyGroupClassDAO(dataSource);
        StudySubjectDAO ssDao = new StudySubjectDAO(dataSource);
        UserAccountDAO userDao = new UserAccountDAO(dataSource);

        int crfs = crfDao.findAllByStudy(studyId).size();
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
        tasks.add(task("groups", groups, statusForCount(groups), null));
        tasks.add(task("rules", rules, statusForCount(rules), null));
        tasks.add(task("sites", sites, statusForCount(sites), null));
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
