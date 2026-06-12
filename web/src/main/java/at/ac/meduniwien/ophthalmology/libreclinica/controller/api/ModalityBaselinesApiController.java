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
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.6 study-nurse polish — per-eye modality baselines.
 *
 * <p>One row per active modality on a (subject, eye) combination. Each
 * row carries TWO aggregates side-by-side:
 *
 * <ul>
 *   <li><strong>global</strong> — earliest observation across every
 *       participation the human ever had where this eye was in scope,
 *       INCLUDING participations on prior studies (iAMD before cohort
 *       transition to GA). The SPA renders this as the "first ever"
 *       baseline next to the per-study one.</li>
 *   <li><strong>perStudy</strong> — earliest observation restricted to
 *       the operator's currently-active study only.</li>
 * </ul>
 *
 * <h2>Eye scope CTE</h2>
 *
 * <p>The "global" half walks every {@code study_subject} row for this
 * person where the eye has been in scope at any point. The predicate:
 *
 * <pre>
 *   study_subject.study_eye IN ('OU', :eye)
 *   OR study_subject.study_subject_id appears in eye_cohort_transition
 *      as source or target for (subject_id, :eye)
 * </pre>
 *
 * <p>The second branch covers the transition-history edge case: after
 * the iAMD source row downgrades to NULL (single-eye case), the row's
 * {@code study_eye} no longer mentions the transitioned eye, but the
 * data already recorded during the iAMD era is still part of the
 * patient's clinical history. The CTE includes those rows so global
 * baselines stay anchored at the true first observation.
 */
@RestController
@RequestMapping("/api/v1/subjects")
@Tag(name = "Modality Baselines",
     description = "Per-eye baseline observations (global + per-study).")
public class ModalityBaselinesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ModalityBaselinesApiController.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public ModalityBaselinesApiController(@Qualifier("dataSource") DataSource dataSource,
                                          SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping("/{label}/eyes/{eye}/modality-baselines")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = ModalityBaselineDto.class)))
    public ResponseEntity<?> list(@PathVariable("label") String label,
                                  @PathVariable("eye") String eyeRaw,
                                  HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."));
        }
        final String eye = eyeRaw == null ? null : eyeRaw.trim().toUpperCase();
        if (!"OD".equals(eye) && !"OS".equals(eye)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "eye must be 'OD' or 'OS' (got '" + eyeRaw + "')."));
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

        int subjectId = ss.getSubjectId();
        int activeStudyId = currentStudy.getId();

        try (Connection c = dataSource.getConnection()) {
            // Collect every study_subject the human has that includes the
            // requested eye at any point (study_eye OU/eye OR transition
            // edge). One round-trip; the result feeds every modality row's
            // global aggregate.
            Set<Integer> globalStudySubjectIds = collectEyeStudySubjectIds(c, subjectId, eye);
            Set<Integer> perStudyStudySubjectIds = collectPerStudyStudySubjectIds(
                    c, subjectId, activeStudyId);

            // Walk every active modality; per modality, pick the
            // eye-side OID and aggregate.
            List<ModalityRow> modalities = loadActiveModalities(c);
            List<ModalityBaselineDto> out = new ArrayList<>(modalities.size());
            for (ModalityRow m : modalities) {
                String itemOid = "OD".equals(eye) ? m.itemOidOd : m.itemOidOs;
                String itemOidAlias = "OD".equals(eye) ? m.itemOidOdAlias : m.itemOidOsAlias;
                if ((itemOid == null || itemOid.isEmpty())
                        && (itemOidAlias == null || itemOidAlias.isEmpty())) {
                    // This modality doesn't track the requested eye —
                    // surface an empty row so the SPA can render "no
                    // data" rather than silently omit the modality.
                    out.add(emptyRow(m, ""));
                    continue;
                }
                // Phase E.6 modality OID aliases (2026-06-12): query the
                // union of primary + alias OIDs so the baselines panel
                // resolves observations entered against either CRF
                // version (v1.0 seed OID OR v2.0 runtime OID).
                List<String> oidsForEye = resolveOidsForEye(itemOid, itemOidAlias);
                ModalityBaselineDto.Aggregate global =
                        aggregate(c, oidsForEye, globalStudySubjectIds);
                ModalityBaselineDto.Aggregate perStudy =
                        aggregate(c, oidsForEye, perStudyStudySubjectIds);
                // Surface the primary OID in the response when present;
                // otherwise fall back to the alias so the SPA still has
                // a non-null OID to display in tooling.
                String reportedOid = itemOid != null && !itemOid.isEmpty() ? itemOid : itemOidAlias;
                out.add(new ModalityBaselineDto(
                        m.code, m.labelEn, m.labelDe, reportedOid,
                        m.dataType, m.unit, global, perStudy));
            }
            return ResponseEntity.ok(out);
        } catch (SQLException e) {
            LOG.error("Failed to load modality baselines for label={} eye={}: {}",
                    label, eye, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to load modality baselines — see server log."));
        }
    }

    /* =============================================================== */
    /* Helpers                                                          */
    /* =============================================================== */

    private static ModalityBaselineDto emptyRow(ModalityRow m, String itemOid) {
        ModalityBaselineDto.Aggregate empty = new ModalityBaselineDto.Aggregate(null, null, 0);
        return new ModalityBaselineDto(
                m.code, m.labelEn, m.labelDe, itemOid,
                m.dataType, m.unit, empty, empty);
    }

    /**
     * CTE-equivalent — collect every {@code study_subject_id} for this
     * human where the requested eye has ever been in scope. Two
     * unioned sources:
     *
     * <ol>
     *   <li>Direct: {@code study_eye IN ('OU', :eye)}.</li>
     *   <li>Transition edge: any {@code study_subject_id} that appears
     *       as source or target in an {@code eye_cohort_transition}
     *       row for this (subject, eye). Covers the post-transition
     *       NULL-stub case where the data exists but {@code study_eye}
     *       no longer carries the eye.</li>
     * </ol>
     */
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

    /**
     * Per-study scope — every {@code study_subject_id} where the human
     * is enrolled in the operator's currently-active study. No eye
     * filter here: the per-study aggregate uses the same modality
     * row's eye-side OID and the item_data rows themselves are
     * per-eye by virtue of the OID.
     */
    private Set<Integer> collectPerStudyStudySubjectIds(Connection c, int subjectId, int studyId)
            throws SQLException {
        Set<Integer> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_subject_id FROM study_subject "
                        + "WHERE subject_id = ? AND study_id = ?")) {
            ps.setInt(1, subjectId);
            ps.setInt(2, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    /**
     * Compute the earliest-observation aggregate for the given
     * {@code itemOid} restricted to events that belong to one of the
     * supplied {@code study_subject_id}s. Returns null-fields when no
     * observations are in scope.
     */
    private ModalityBaselineDto.Aggregate aggregate(Connection c, List<String> itemOids,
                                                    Set<Integer> studySubjectIds)
            throws SQLException {
        // Phase E.6 modality OID aliases (2026-06-12): the registry's
        // primary {@code item_oid_od/_os} column carries the v1.0 OPHTH
        // OID; the new alias columns carry the v2.0 OID convention. The
        // caller passes both (de-duplicated, non-null) so this method
        // resolves an observation regardless of which CRF version the
        // operator entered the value on. Each non-null OID is an
        // additional placeholder in the IN clause.
        if (studySubjectIds.isEmpty() || itemOids.isEmpty()) {
            return new ModalityBaselineDto.Aggregate(null, null, 0);
        }
        // Use a single query to find earliest date + count, then a
        // tiebreak on min(item_data_id) to pick a deterministic value
        // when two rows share the earliest date.
        String inList = inPlaceholders(studySubjectIds.size());
        String oidList = inPlaceholders(itemOids.size());
        String sql = "WITH eligible AS ( "
                + "  SELECT id_.value AS value, id_.item_data_id AS item_data_id, "
                + "         ec.date_completed AS date_completed "
                + "    FROM item_data id_ "
                + "    JOIN item it ON it.item_id = id_.item_id "
                + "    JOIN event_crf ec ON ec.event_crf_id = id_.event_crf_id "
                + "   WHERE it.oc_oid IN " + oidList + " "
                + "     AND ec.study_subject_id IN " + inList + " "
                + "     AND id_.value IS NOT NULL AND id_.value <> '' "
                + "     AND id_.status_id NOT IN (5, 7) "
                + ") "
                + "SELECT (SELECT MIN(date_completed) FROM eligible) AS earliest, "
                + "       (SELECT COUNT(*) FROM eligible) AS cnt, "
                + "       (SELECT value FROM eligible "
                + "         WHERE date_completed = (SELECT MIN(date_completed) FROM eligible) "
                + "         ORDER BY item_data_id ASC LIMIT 1) AS earliest_value";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String oid : itemOids) {
                ps.setString(i++, oid);
            }
            for (Integer ssId : studySubjectIds) {
                ps.setInt(i++, ssId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("earliest");
                    int cnt = rs.getInt("cnt");
                    String value = rs.getString("earliest_value");
                    String date = ts == null ? null : toIsoDate(ts);
                    return new ModalityBaselineDto.Aggregate(date, value, cnt);
                }
            }
        }
        return new ModalityBaselineDto.Aggregate(null, null, 0);
    }

    /**
     * Build the list of OIDs to query for a given eye. De-dupes nulls
     * and same-as-primary aliases so the prepared statement's IN clause
     * stays minimal. The order is primary-first so the row's reported
     * {@code itemOid} (when needed elsewhere) still surfaces the
     * primary registry value.
     */
    private static List<String> resolveOidsForEye(String primary, String alias) {
        List<String> out = new ArrayList<>(2);
        if (primary != null && !primary.isBlank()) out.add(primary);
        if (alias != null && !alias.isBlank() && !alias.equals(primary)) out.add(alias);
        return out;
    }

    private List<ModalityRow> loadActiveModalities(Connection c) throws SQLException {
        List<ModalityRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT code, label_en, label_de, "
                        + "       item_oid_od, item_oid_os, "
                        + "       item_oid_od_alias, item_oid_os_alias, "
                        + "       data_type, unit "
                        + "  FROM modality WHERE status_id = 1 "
                        + "  ORDER BY ordinal ASC, code ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ModalityRow(
                            rs.getString("code"),
                            rs.getString("label_en"),
                            rs.getString("label_de"),
                            rs.getString("item_oid_od"),
                            rs.getString("item_oid_os"),
                            rs.getString("item_oid_od_alias"),
                            rs.getString("item_oid_os_alias"),
                            rs.getString("data_type"),
                            rs.getString("unit")));
                }
            }
        }
        return out;
    }

    static String inPlaceholders(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        sb.append(")");
        return sb.toString();
    }

    private static String toIsoDate(java.sql.Timestamp ts) {
        // Date-only — event_crf.date_completed is a timestamp but the
        // SPA renders the date portion only; mirror that here so the
        // wire shape stays stable.
        return ts.toLocalDateTime().toLocalDate().toString();
    }

    private record ModalityRow(
            String code, String labelEn, String labelDe,
            String itemOidOd, String itemOidOs,
            String itemOidOdAlias, String itemOidOsAlias,
            String dataType, String unit
    ) {

        /** Accessor names the call sites expect (camelCase, no parens). */
        @Override public String itemOidOd() { return itemOidOd; }
        @Override public String itemOidOs() { return itemOidOs; }
        @Override public String itemOidOdAlias() { return itemOidOdAlias; }
        @Override public String itemOidOsAlias() { return itemOidOsAlias; }
    }
}
