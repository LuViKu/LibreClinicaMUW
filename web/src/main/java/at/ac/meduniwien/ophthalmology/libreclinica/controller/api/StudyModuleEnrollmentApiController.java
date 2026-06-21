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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.api.StudyAdminAuthorization;

import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin-driven study-module enrollment.
 *
 * <p>The SPA's pluggable study-module SPI dispatches on
 * {@code study.protocol_type}, but that field is free-form text and
 * carries no admin-visible toggle. The
 * {@code study_module_enrollment(study_id, module_id)} table this
 * controller fronts decouples activation from the discriminator: a
 * module activates only when the study's {@code protocol_type} matches
 * the manifest AND the study is enrolled here.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET    /api/v1/studies/{studyOid}/modules} — list module
 *       ids enrolled on the study (open to anyone with read access on
 *       the study).</li>
 *   <li>{@code PUT    /api/v1/studies/{studyOid}/modules/{moduleId}} —
 *       enroll the study in the named module. Idempotent: enrolling
 *       an already-enrolled module returns 200 with no change.
 *       Admin-only (mirrors StudyParametersApiController gating).</li>
 *   <li>{@code DELETE /api/v1/studies/{studyOid}/modules/{moduleId}} —
 *       un-enroll. Idempotent.</li>
 * </ul>
 *
 * <p>The module catalog itself lives entirely in the SPA's
 * {@code STUDY_MODULES} registry. The backend stores opaque module ids
 * as VARCHAR(64); it does NOT enforce that an enrolled id corresponds
 * to a known SPA module. Stale enrollments are harmless — the SPA's
 * {@code findModule()} returns {@code null} for unknown ids and the
 * activation skips silently.
 *
 * <p>Module ids are normalised via {@code .trim().toUpperCase()} on
 * write so the lookup in {@code MeApiController} can compare without
 * re-normalising every row.
 */
@RestController
@RequestMapping("/api/v1/studies/{studyOid}/modules")
@Tag(name = "Study Modules",
     description = "Admin-driven per-study module enrollment.")
public class StudyModuleEnrollmentApiController {

    private static final Logger LOG =
            LoggerFactory.getLogger(StudyModuleEnrollmentApiController.class);

    private final DataSource dataSource;

    @Autowired
    public StudyModuleEnrollmentApiController(
            @Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Response shape for both GET and PUT: the post-write enrollment list. */
    public record EnrollmentList(String studyOid, List<String> moduleIds) {}

    /* ---------------------------------------------------------------- */
    /* GET — list enrolled modules                                      */
    /* ---------------------------------------------------------------- */

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@PathVariable("studyOid") String studyOid,
                                  HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }

        try {
            List<String> ids = loadEnrolledModuleIds(target.getId());
            return ResponseEntity.ok(new EnrollmentList(studyOid, ids));
        } catch (SQLException e) {
            LOG.error("List module enrollments failed for oid={}: {}", studyOid, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load module enrollments"));
        }
    }

    /* ---------------------------------------------------------------- */
    /* PUT — enroll                                                     */
    /* ---------------------------------------------------------------- */

    @PutMapping(value = "/{moduleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> enroll(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("moduleId") String moduleId,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        String normalisedId = normalise(moduleId);
        if (normalisedId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "moduleId is required (non-blank, alphanumeric)"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (!StudyAdminAuthorization.userMayEditStudy(me, target, dataSource)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing this study's modules"));
        }

        try {
            upsertEnrollment(target.getId(), normalisedId, me.getId());
            List<String> ids = loadEnrolledModuleIds(target.getId());
            LOG.info("Module enrolled: study={} module={} by user={}",
                    studyOid, normalisedId, me.getName());
            return ResponseEntity.ok(new EnrollmentList(studyOid, ids));
        } catch (SQLException e) {
            LOG.error("Enroll module failed for oid={} module={}: {}",
                    studyOid, normalisedId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to enroll module"));
        }
    }

    /* ---------------------------------------------------------------- */
    /* DELETE — un-enroll                                               */
    /* ---------------------------------------------------------------- */

    @DeleteMapping(value = "/{moduleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> unenroll(@PathVariable("studyOid") String studyOid,
                                      @PathVariable("moduleId") String moduleId,
                                      HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        String normalisedId = normalise(moduleId);
        if (normalisedId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "moduleId is required (non-blank, alphanumeric)"));
        }

        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean target = studyDao.findByOid(studyOid);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (!StudyAdminAuthorization.userMayEditStudy(me, target, dataSource)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit editing this study's modules"));
        }

        try {
            deleteEnrollment(target.getId(), normalisedId);
            List<String> ids = loadEnrolledModuleIds(target.getId());
            LOG.info("Module un-enrolled: study={} module={} by user={}",
                    studyOid, normalisedId, me.getName());
            return ResponseEntity.ok(new EnrollmentList(studyOid, ids));
        } catch (SQLException e) {
            LOG.error("Un-enroll module failed for oid={} module={}: {}",
                    studyOid, normalisedId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to un-enroll module"));
        }
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private static String normalise(String moduleId) {
        if (moduleId == null) return "";
        String trimmed = moduleId.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.toUpperCase();
    }

    static List<String> loadEnrolledModuleIds(DataSource dataSource, int studyId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT module_id FROM study_module_enrollment WHERE study_id = ? ORDER BY module_id")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> ids = new LinkedHashSet<>();
                while (rs.next()) ids.add(rs.getString(1));
                return new ArrayList<>(ids);
            }
        }
    }

    private List<String> loadEnrolledModuleIds(int studyId) throws SQLException {
        return loadEnrolledModuleIds(dataSource, studyId);
    }

    private void upsertEnrollment(int studyId, String moduleId, int userId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_module_enrollment (study_id, module_id, enrolled_by) "
                             + "VALUES (?, ?, ?) ON CONFLICT (study_id, module_id) DO NOTHING")) {
            ps.setInt(1, studyId);
            ps.setString(2, moduleId);
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    private void deleteEnrollment(int studyId, String moduleId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM study_module_enrollment WHERE study_id = ? AND module_id = ?")) {
            ps.setInt(1, studyId);
            ps.setString(2, moduleId);
            ps.executeUpdate();
        }
    }
}
