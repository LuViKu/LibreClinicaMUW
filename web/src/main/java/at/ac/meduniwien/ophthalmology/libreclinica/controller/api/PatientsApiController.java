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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.6 study-nurse polish — patients-overview surface.
 *
 * <p>"Patient" here means {@code subject.subject_id} (one human),
 * distinct from {@code study_subject.study_subject_id} (one per-study
 * participation). The SPA's patient overview rolls multiple
 * participations of the same person into one row + surfaces every
 * enrolment underneath.
 *
 * <h2>Three endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/patients?page=…&pageSize=…&search=…} —
 *       paginated list across the operator's full visible-study set.
 *       De-duped by {@code subject_id}.</li>
 *   <li>{@code GET /api/v1/patients/{subjectId}} — full detail for a
 *       single human, including the cross-study eye-cohort transition
 *       history.</li>
 *   <li>{@code GET /api/v1/patients/{subjectId}/measurements?modalityCode=…&eye=…}
 *       — full measurement series for one (subject, modality, eye)
 *       tuple, ordered by date with numeric coercion.</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 *
 * <p>Any authenticated user with at least one active
 * {@code study_user_role} binding can read the list. The visible set
 * is everything {@link StudiesApiController#list} would return —
 * mirroring the SPA's study picker so the operator sees the same
 * studies in both surfaces.
 *
 * <p>Patients out-of-scope of every active grant are filtered out at
 * the SQL layer; the controller doesn't surface them at all (404 on
 * the detail / measurements endpoint, missing from the list endpoint).
 */
@RestController
@RequestMapping("/api/v1/patients")
@Tag(name = "Patients",
     description = "Patient overview (de-duped across study participations).")
public class PatientsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(PatientsApiController.class);

    /** Soft default page size matching the SPA's table page size. */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Cap to avoid runaway queries; the SPA never asks for more. */
    private static final int MAX_PAGE_SIZE = 500;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public PatientsApiController(@Qualifier("dataSource") DataSource dataSource,
                                 SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * Pagination response wrapper. {@code totalCount} is the full
     * result size (across all pages); {@code patients} carries the
     * current page slice.
     */
    @Schema(name = "PatientsListResponse")
    public record PatientsListResponse(
            long totalCount,
            int page,
            int pageSize,
            List<PatientDto> patients
    ) {}

    /* =============================================================== */
    /* GET — paged list                                                 */
    /* =============================================================== */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = PatientsListResponse.class)))
    public ResponseEntity<?> list(@RequestParam(value = "page", required = false) Integer page,
                                  @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                  @RequestParam(value = "search", required = false) String search,
                                  HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        int p = (page == null || page < 0) ? 0 : page;
        int ps = (pageSize == null || pageSize <= 0)
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String searchTrim = (search == null) ? "" : search.trim();

        Set<Integer> visibleStudyIds = visibleStudyIdsForUser(currentUser, session);
        if (visibleStudyIds.isEmpty()) {
            return ResponseEntity.ok(new PatientsListResponse(0, p, ps, List.of()));
        }

        try (Connection c = dataSource.getConnection()) {
            // Step 1: total distinct subjects matching search across the
            // visible study set. Used for pagination metadata.
            long total = countMatchingSubjects(c, visibleStudyIds, searchTrim);
            // Step 2: page slice — list subject_ids ordered by subject_id
            // ASC (stable + deterministic), bounded by LIMIT/OFFSET.
            List<Integer> subjectIds = pageSubjectIds(c, visibleStudyIds, searchTrim, p, ps);
            // Step 3: hydrate identity + every visible enrolment.
            List<PatientDto> patients = hydratePatients(c, subjectIds, visibleStudyIds);
            return ResponseEntity.ok(new PatientsListResponse(total, p, ps, patients));
        } catch (SQLException e) {
            LOG.error("Failed to list patients: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to list patients — see server log."));
        }
    }

    /* =============================================================== */
    /* GET — patient detail                                             */
    /* =============================================================== */

    @GetMapping("/{subjectId}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = PatientDetailDto.class)))
    public ResponseEntity<?> detail(@PathVariable("subjectId") int subjectId, HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        Set<Integer> visibleStudyIds = visibleStudyIdsForUser(currentUser, session);
        if (visibleStudyIds.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Patient with subjectId=" + subjectId + " not found."));
        }
        try (Connection c = dataSource.getConnection()) {
            PatientIdentity identity = loadIdentity(c, subjectId);
            if (identity == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Patient with subjectId=" + subjectId + " not found."));
            }
            List<PatientDto.Enrolment> enrolments =
                    loadEnrolments(c, List.of(subjectId), visibleStudyIds).getOrDefault(
                            subjectId, List.of());
            if (enrolments.isEmpty()) {
                // The subject exists but has zero participations the
                // operator can see — treat as 404 so we don't leak
                // identity info outside the scope.
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Patient with subjectId=" + subjectId + " not found."));
            }
            List<PatientDetailDto.EyeTransition> transitions =
                    loadEyeTransitions(c, subjectId);
            PatientDetailDto out = new PatientDetailDto(
                    identity.subjectId,
                    identity.uniqueIdentifier,
                    identity.gender,
                    identity.yearOfBirth,
                    identity.firstName,
                    identity.lastName,
                    identity.dateOfBirth,
                    enrolments,
                    transitions);
            return ResponseEntity.ok(out);
        } catch (SQLException e) {
            LOG.error("Failed to load patient detail for subjectId={}: {}",
                    subjectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to load patient detail — see server log."));
        }
    }

    /* =============================================================== */
    /* GET — measurement series                                         */
    /* =============================================================== */

    @GetMapping("/{subjectId}/measurements")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = MeasurementSeriesDto.class)))
    public ResponseEntity<?> measurements(@PathVariable("subjectId") int subjectId,
                                          @RequestParam("modalityCode") String modalityCode,
                                          @RequestParam("eye") String eyeRaw,
                                          HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        Set<Integer> visibleStudyIds = visibleStudyIdsForUser(currentUser, session);
        if (visibleStudyIds.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Patient with subjectId=" + subjectId + " not found."));
        }
        if (modalityCode == null || modalityCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "modalityCode is required."));
        }
        final String eye = eyeRaw == null ? null : eyeRaw.trim().toUpperCase();
        if (!"OD".equals(eye) && !"OS".equals(eye)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eye must be 'OD' or 'OS' (got '" + eyeRaw + "')."));
        }

        try (Connection c = dataSource.getConnection()) {
            // Patient existence + visibility check.
            PatientIdentity identity = loadIdentity(c, subjectId);
            if (identity == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Patient with subjectId=" + subjectId + " not found."));
            }
            Map<Integer, List<PatientDto.Enrolment>> enrolmentMap =
                    loadEnrolments(c, List.of(subjectId), visibleStudyIds);
            if (enrolmentMap.getOrDefault(subjectId, List.of()).isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Patient with subjectId=" + subjectId + " not found."));
            }

            // Resolve modality + eye-side OID.
            ModalityResolution mod = resolveModality(c, modalityCode.trim());
            if (mod == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Unknown modalityCode '" + modalityCode + "'."));
            }
            String itemOid = "OD".equals(eye) ? mod.itemOidOd : mod.itemOidOs;
            if (itemOid == null || itemOid.isEmpty()) {
                // Modality doesn't track the requested eye — empty series.
                return ResponseEntity.ok(new MeasurementSeriesDto(
                        mod.code, mod.dataType, mod.unit, List.of()));
            }

            // Same CTE-equivalent as ModalityBaselinesApiController.
            Set<Integer> studySubjectIds = collectEyeStudySubjectIds(c, subjectId, eye);
            // Filter to operator's visible studies — we don't surface
            // observations that come from a study they can't see.
            Set<Integer> filtered = filterStudySubjectsByVisibleStudies(
                    c, studySubjectIds, visibleStudyIds);

            List<MeasurementSeriesDto.Observation> series = loadSeries(c, itemOid, filtered, mod);
            return ResponseEntity.ok(new MeasurementSeriesDto(
                    mod.code, mod.dataType, mod.unit, series));
        } catch (SQLException e) {
            LOG.error("Failed to load measurement series for subjectId={} modality={} eye={}: {}",
                    subjectId, modalityCode, eye, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to load measurement series — see server log."));
        }
    }

    /* =============================================================== */
    /* Helpers — visibility                                             */
    /* =============================================================== */

    /**
     * Operator's full visible-study set across every active grant.
     * Reuses the per-study {@link SiteVisibilityFilter} machinery once
     * per top-level study the user has a grant on, then unions the
     * results — same logic {@code StudiesApiController} effectively
     * applies to populate the SPA's study picker.
     *
     * <p>For a single-study deployment (MUW Vienna) this collapses to
     * one filter call; for multi-study sponsors the union is bounded
     * by the user's grant count, which is small.
     */
    private Set<Integer> visibleStudyIdsForUser(UserAccountBean user, HttpSession session) {
        Set<Integer> out = new LinkedHashSet<>();
        StudyDAO studyDAO = new StudyDAO(dataSource);
        UserAccountDAO userDAO = new UserAccountDAO(dataSource);
        ArrayList<StudyUserRoleBean> grants = userDAO.findAllRolesByUserName(user.getName());
        if (grants == null || grants.isEmpty()) return out;

        // Walk each active grant; for each, materialise the
        // SiteVisibilityFilter result of "currentStudy bound to this
        // grant's study". The set-union absorbs sites visible across
        // multiple grants without duplication.
        for (StudyUserRoleBean grant : grants) {
            if (grant == null || grant.getStatus() == null) continue;
            if (grant.getStatus().getId() != Status.AVAILABLE.getId()) continue;
            int sid = grant.getStudyId();
            if (sid <= 0) continue;
            StudyBean grantStudy = (StudyBean) studyDAO.findByPK(sid);
            if (grantStudy == null || grantStudy.getId() == 0) continue;
            Set<Integer> chunk = siteVisibilityFilter.visibleStudyIds(user, grantStudy, grant);
            out.addAll(chunk);
        }
        return out;
    }

    /* =============================================================== */
    /* Helpers — list                                                   */
    /* =============================================================== */

    private long countMatchingSubjects(Connection c, Set<Integer> visibleStudyIds, String search)
            throws SQLException {
        String inList = ModalityBaselinesApiController.inPlaceholders(visibleStudyIds.size());
        String sql;
        if (search.isEmpty()) {
            sql = "SELECT COUNT(DISTINCT ss.subject_id) FROM study_subject ss "
                    + "WHERE ss.study_id IN " + inList;
        } else {
            sql = "SELECT COUNT(DISTINCT ss.subject_id) FROM study_subject ss "
                    + "JOIN subject su ON su.subject_id = ss.subject_id "
                    + "WHERE ss.study_id IN " + inList + " "
                    + "AND (LOWER(ss.label) LIKE ? OR LOWER(COALESCE(su.unique_identifier, '')) LIKE ?)";
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Integer sid : visibleStudyIds) ps.setInt(i++, sid);
            if (!search.isEmpty()) {
                String pattern = "%" + search.toLowerCase() + "%";
                ps.setString(i++, pattern);
                ps.setString(i++, pattern);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return 0L;
            }
        }
    }

    private List<Integer> pageSubjectIds(Connection c, Set<Integer> visibleStudyIds,
                                         String search, int page, int pageSize) throws SQLException {
        String inList = ModalityBaselinesApiController.inPlaceholders(visibleStudyIds.size());
        String sql;
        if (search.isEmpty()) {
            sql = "SELECT DISTINCT ss.subject_id FROM study_subject ss "
                    + "WHERE ss.study_id IN " + inList + " "
                    + "ORDER BY ss.subject_id ASC LIMIT ? OFFSET ?";
        } else {
            sql = "SELECT DISTINCT ss.subject_id FROM study_subject ss "
                    + "JOIN subject su ON su.subject_id = ss.subject_id "
                    + "WHERE ss.study_id IN " + inList + " "
                    + "AND (LOWER(ss.label) LIKE ? OR LOWER(COALESCE(su.unique_identifier, '')) LIKE ?) "
                    + "ORDER BY ss.subject_id ASC LIMIT ? OFFSET ?";
        }
        List<Integer> out = new ArrayList<>(pageSize);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Integer sid : visibleStudyIds) ps.setInt(i++, sid);
            if (!search.isEmpty()) {
                String pattern = "%" + search.toLowerCase() + "%";
                ps.setString(i++, pattern);
                ps.setString(i++, pattern);
            }
            ps.setInt(i++, pageSize);
            ps.setInt(i++, page * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    /**
     * Hydrate identity (subject row) + enrolments (study_subject rows
     * across visible studies) for the supplied subject_id slice. Order
     * preserved.
     */
    private List<PatientDto> hydratePatients(Connection c, List<Integer> subjectIds,
                                             Set<Integer> visibleStudyIds) throws SQLException {
        if (subjectIds.isEmpty()) return List.of();
        Map<Integer, PatientIdentity> identities = loadIdentities(c, subjectIds);
        Map<Integer, List<PatientDto.Enrolment>> enrolments =
                loadEnrolments(c, subjectIds, visibleStudyIds);
        List<PatientDto> out = new ArrayList<>(subjectIds.size());
        for (Integer sid : subjectIds) {
            PatientIdentity ident = identities.get(sid);
            if (ident == null) continue;
            List<PatientDto.Enrolment> enrs = enrolments.getOrDefault(sid, List.of());
            out.add(new PatientDto(
                    ident.subjectId,
                    ident.uniqueIdentifier,
                    ident.gender,
                    ident.yearOfBirth,
                    ident.firstName,
                    ident.lastName,
                    ident.dateOfBirth,
                    enrs));
        }
        return out;
    }

    private Map<Integer, PatientIdentity> loadIdentities(Connection c, List<Integer> subjectIds)
            throws SQLException {
        String inList = ModalityBaselinesApiController.inPlaceholders(subjectIds.size());
        String sql = "SELECT subject_id, unique_identifier, gender, date_of_birth, "
                + "       first_name, last_name "
                + "  FROM subject WHERE subject_id IN " + inList;
        Map<Integer, PatientIdentity> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Integer sid : subjectIds) ps.setInt(i++, sid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PatientIdentity id = readIdentity(rs);
                    out.put(id.subjectId, id);
                }
            }
        }
        return out;
    }

    private PatientIdentity loadIdentity(Connection c, int subjectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT subject_id, unique_identifier, gender, date_of_birth, "
                        + "       first_name, last_name "
                        + "FROM subject WHERE subject_id = ?")) {
            ps.setInt(1, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readIdentity(rs);
                return null;
            }
        }
    }

    private static PatientIdentity readIdentity(ResultSet rs) throws SQLException {
        String gender = rs.getString("gender");
        java.sql.Date dob = rs.getDate("date_of_birth");
        Integer yob = null;
        String dobIso = null;
        if (dob != null) {
            yob = dob.toLocalDate().getYear();
            dobIso = dob.toLocalDate().toString();
        }
        return new PatientIdentity(
                rs.getInt("subject_id"),
                rs.getString("unique_identifier"),
                gender == null ? "" : gender,
                yob,
                rs.getString("first_name"),
                rs.getString("last_name"),
                dobIso);
    }

    private Map<Integer, List<PatientDto.Enrolment>> loadEnrolments(Connection c,
                                                                    List<Integer> subjectIds,
                                                                    Set<Integer> visibleStudyIds)
            throws SQLException {
        Map<Integer, List<PatientDto.Enrolment>> out = new LinkedHashMap<>();
        if (subjectIds.isEmpty() || visibleStudyIds.isEmpty()) return out;
        String subIn = ModalityBaselinesApiController.inPlaceholders(subjectIds.size());
        String studyIn = ModalityBaselinesApiController.inPlaceholders(visibleStudyIds.size());
        // Single query — join study_subject → study + a correlated
        // MAX(date_start) sub-select for the last-visit indicator.
        String sql = "SELECT ss.subject_id AS sid, "
                + "       ss.study_subject_id AS ssid, "
                + "       s.oc_oid AS study_oid, "
                + "       s.name AS study_name, "
                + "       ss.label AS label, "
                + "       ss.study_eye AS study_eye, "
                + "       ss.enrollment_date AS enrolled_on, "
                + "       (SELECT MAX(se.date_start) FROM study_event se "
                + "         WHERE se.study_subject_id = ss.study_subject_id) AS last_visit_at "
                + "  FROM study_subject ss "
                + "  JOIN study s ON s.study_id = ss.study_id "
                + " WHERE ss.subject_id IN " + subIn
                + "   AND ss.study_id IN " + studyIn
                + " ORDER BY ss.subject_id ASC, ss.enrollment_date ASC NULLS LAST, ss.study_subject_id ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Integer sid : subjectIds) ps.setInt(i++, sid);
            for (Integer sid : visibleStudyIds) ps.setInt(i++, sid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sid = rs.getInt("sid");
                    java.sql.Date enrDate = rs.getDate("enrolled_on");
                    Timestamp lastVisit = rs.getTimestamp("last_visit_at");
                    PatientDto.Enrolment e = new PatientDto.Enrolment(
                            rs.getString("study_oid"),
                            rs.getString("study_name"),
                            rs.getString("label"),
                            rs.getString("study_eye"),
                            enrDate == null ? null : enrDate.toLocalDate().toString(),
                            lastVisit == null ? null : lastVisit.toInstant().toString());
                    out.computeIfAbsent(sid, k -> new ArrayList<>()).add(e);
                }
            }
        }
        return out;
    }

    /* =============================================================== */
    /* Helpers — detail (eye transitions)                               */
    /* =============================================================== */

    /**
     * Flatten the per-eye transition history into one row per
     * transition (NOT two as on {@link EyeCohortTransitionsApiController}).
     * The SPA's patient-detail view renders the arrow inline, so a
     * single row carrying both ends is the cleaner shape.
     */
    private List<PatientDetailDto.EyeTransition> loadEyeTransitions(Connection c, int subjectId)
            throws SQLException {
        String sql = "SELECT t.transition_id, t.eye, t.transitioned_at, t.reason, "
                + "       src_study.oc_oid AS from_oid, "
                + "       src_study.name AS from_name, "
                + "       src_ss.label AS from_label, "
                + "       tgt_study.oc_oid AS to_oid, "
                + "       tgt_study.name AS to_name, "
                + "       tgt_ss.label AS to_label "
                + "  FROM eye_cohort_transition t "
                + "  JOIN study_subject src_ss ON src_ss.study_subject_id = t.source_study_subject_id "
                + "  JOIN study src_study ON src_study.study_id = t.source_study_id "
                + "  JOIN study_subject tgt_ss ON tgt_ss.study_subject_id = t.target_study_subject_id "
                + "  JOIN study tgt_study ON tgt_study.study_id = t.target_study_id "
                + " WHERE t.subject_id = ? "
                + " ORDER BY t.transitioned_at ASC, t.transition_id ASC";
        List<PatientDetailDto.EyeTransition> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp at = rs.getTimestamp("transitioned_at");
                    out.add(new PatientDetailDto.EyeTransition(
                            rs.getInt("transition_id"),
                            rs.getString("eye"),
                            at == null ? null : at.toInstant().toString(),
                            rs.getString("from_oid"),
                            rs.getString("from_name"),
                            rs.getString("from_label"),
                            rs.getString("to_oid"),
                            rs.getString("to_name"),
                            rs.getString("to_label"),
                            rs.getString("reason")));
                }
            }
        }
        return out;
    }

    /* =============================================================== */
    /* Helpers — measurements                                           */
    /* =============================================================== */

    private ModalityResolution resolveModality(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT code, item_oid_od, item_oid_os, data_type, unit FROM modality "
                        + "WHERE code = ? AND status_id = 1")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ModalityResolution(
                            rs.getString("code"),
                            rs.getString("item_oid_od"),
                            rs.getString("item_oid_os"),
                            rs.getString("data_type"),
                            rs.getString("unit"));
                }
                return null;
            }
        }
    }

    /** Same CTE-equivalent as the baselines controller; kept here to avoid cross-controller coupling. */
    private Set<Integer> collectEyeStudySubjectIds(Connection c, int subjectId, String eye)
            throws SQLException {
        Set<Integer> out = new LinkedHashSet<>();
        String sql = "SELECT DISTINCT ss.study_subject_id "
                + "  FROM study_subject ss "
                + " WHERE ss.subject_id = ? "
                + "   AND (ss.study_eye = 'OU' OR ss.study_eye = ?) "
                + "UNION "
                + "SELECT ss.study_subject_id "
                + "  FROM study_subject ss "
                + "  JOIN eye_cohort_transition t "
                + "    ON (t.source_study_subject_id = ss.study_subject_id "
                + "        OR t.target_study_subject_id = ss.study_subject_id) "
                + " WHERE ss.subject_id = ? AND t.eye = ? AND t.subject_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, subjectId);
            ps.setString(2, eye);
            ps.setInt(3, subjectId);
            ps.setString(4, eye);
            ps.setInt(5, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    private Set<Integer> filterStudySubjectsByVisibleStudies(Connection c,
                                                              Set<Integer> studySubjectIds,
                                                              Set<Integer> visibleStudyIds)
            throws SQLException {
        if (studySubjectIds.isEmpty() || visibleStudyIds.isEmpty()) return new LinkedHashSet<>();
        Set<Integer> out = new LinkedHashSet<>();
        String ssIn = ModalityBaselinesApiController.inPlaceholders(studySubjectIds.size());
        String stdIn = ModalityBaselinesApiController.inPlaceholders(visibleStudyIds.size());
        String sql = "SELECT study_subject_id FROM study_subject "
                + "WHERE study_subject_id IN " + ssIn
                + " AND study_id IN " + stdIn;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Integer ssId : studySubjectIds) ps.setInt(i++, ssId);
            for (Integer stdId : visibleStudyIds) ps.setInt(i++, stdId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    private List<MeasurementSeriesDto.Observation> loadSeries(Connection c, String itemOid,
                                                              Set<Integer> studySubjectIds,
                                                              ModalityResolution mod)
            throws SQLException {
        if (studySubjectIds.isEmpty()) return List.of();
        String inList = ModalityBaselinesApiController.inPlaceholders(studySubjectIds.size());
        String sql = "SELECT id_.value AS value, "
                + "       ec.date_completed AS date_completed, "
                + "       ec.event_crf_id AS event_crf_id, "
                + "       s.oc_oid AS study_oid, "
                + "       s.name AS study_name, "
                + "       sed.name AS event_name "
                + "  FROM item_data id_ "
                + "  JOIN item it ON it.item_id = id_.item_id "
                + "  JOIN event_crf ec ON ec.event_crf_id = id_.event_crf_id "
                + "  JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id "
                + "  JOIN study s ON s.study_id = ss.study_id "
                + "  JOIN study_event se ON se.study_event_id = ec.study_event_id "
                + "  JOIN study_event_definition sed ON sed.study_event_definition_id = se.study_event_definition_id "
                + " WHERE it.oc_oid = ? "
                + "   AND ec.study_subject_id IN " + inList
                + "   AND id_.value IS NOT NULL AND id_.value <> '' "
                + "   AND id_.status_id NOT IN (5, 7) "
                + " ORDER BY ec.date_completed ASC, id_.item_data_id ASC";
        List<MeasurementSeriesDto.Observation> out = new ArrayList<>();
        boolean numeric = "numeric".equals(mod.dataType);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, itemOid);
            for (Integer ssId : studySubjectIds) ps.setInt(i++, ssId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("date_completed");
                    String value = rs.getString("value");
                    Double num = (numeric && value != null) ? coerceNumeric(value) : null;
                    out.add(new MeasurementSeriesDto.Observation(
                            ts == null ? null : ts.toLocalDateTime().toLocalDate().toString(),
                            value,
                            num,
                            rs.getString("study_oid"),
                            rs.getString("study_name"),
                            rs.getInt("event_crf_id"),
                            rs.getString("event_name")));
                }
            }
        }
        return out;
    }

    /**
     * Locale-tolerant numeric coercion. The ItemDataDAO surface deals
     * in date parsing rather than numeric, so we keep this here:
     * {@code item_data.value} is persisted as the operator-entered
     * string and may carry a "," or "." decimal separator. Try
     * {@link Double#parseDouble} on the trimmed value with "," → "."
     * substitution; null on failure so the SPA's sparkline can skip
     * the row without breaking the chart.
     */
    private static Double coerceNumeric(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Normalise European decimal separator. Don't strip a thousands
        // separator — the legacy DDE / casebook UIs don't insert them
        // and that would silently corrupt "1,234.5" if someone did.
        if (s.indexOf(',') >= 0 && s.indexOf('.') < 0) {
            s = s.replace(',', '.');
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* =============================================================== */
    /* Inner types                                                       */
    /* =============================================================== */

    private record PatientIdentity(
            int subjectId,
            String uniqueIdentifier,
            String gender,
            Integer yearOfBirth,
            String firstName,
            String lastName,
            String dateOfBirth
    ) {}

    private record ModalityResolution(
            String code,
            String itemOidOd,
            String itemOidOs,
            String dataType,
            String unit
    ) {}
}
