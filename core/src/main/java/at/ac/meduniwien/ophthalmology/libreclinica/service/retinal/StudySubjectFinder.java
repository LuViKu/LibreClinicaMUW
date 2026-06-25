/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Cross-study study_subject lookup that the existing
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO}
 * does not provide. Used by the public OCT-upload portal's
 * {@code /resolve} endpoint to translate a Heidelberg-Spectralis
 * PatientId (the {@code label} on the per-study study_subject row) plus
 * an acquisition date into a (study_subject, event_crf) candidate that
 * the staff can confirm before {@code /commit} persists the .e2e file.
 *
 * <p>Pure JDBC; no Hibernate. The two queries are read-only and
 * fall outside the {@code SQLFactory} digester catalog because they
 * cross study boundaries (the digester catalog is per-study aware).
 *
 * <p>Status filtering mirrors the existing convention in
 * {@code itemdata_dao.xml} et al.: exclude
 * {@code status_id IN (5, 7)} — removed + auto-removed.
 */
@Component
public class StudySubjectFinder {

    private static final Logger LOG = LoggerFactory.getLogger(StudySubjectFinder.class);

    private final DataSource dataSource;

    @Autowired
    public StudySubjectFinder(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Find every study_subject row across all studies whose
     * {@code label} exactly matches the supplied PatientId. Removed
     * (status 5) and auto-removed (status 7) rows are filtered out.
     *
     * <p>Site siblings: when the matched study has a non-null
     * {@code parent_study_id} the parent study's {@code name} is
     * surfaced as {@code siteName} so the SPA can render a site chip
     * ("MUW Vienna · Site-1") next to the study chip; top-level studies
     * leave it null.
     */
    public List<StudySubjectMatch> findByLabelAcrossStudies(String label) {
        if (label == null || label.isBlank()) {
            return List.of();
        }
        // The join with `study site ON site.study_id = ss.parent_study_id`
        // is intentionally LEFT — top-level studies have no parent so
        // site_name remains NULL on the row.
        final String sql =
                "SELECT ss.study_subject_id, ss.label, ss.status_id, "
                        + "       s.study_id, s.name AS study_name, "
                        + "       s.unique_identifier AS study_oid, "
                        + "       site.name AS site_name "
                        + "  FROM study_subject ss "
                        + "  JOIN study s ON s.study_id = ss.study_id "
                        + "  LEFT JOIN study site ON site.study_id = s.parent_study_id "
                        + " WHERE ss.label = ? "
                        + "   AND ss.status_id NOT IN (5, 7) "
                        + " ORDER BY s.study_id";
        List<StudySubjectMatch> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StudySubjectMatch(
                            rs.getInt("study_id"),
                            rs.getString("study_name"),
                            rs.getString("study_oid"),
                            rs.getInt("study_subject_id"),
                            rs.getString("label"),
                            rs.getString("site_name"),
                            rs.getInt("status_id")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("findByLabelAcrossStudies failed for label '{}': {}", label, e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * Prefix search across all studies, used by the staff portal's
     * "Patient suchen" modal (Wave 1B + 2B). Returns rows whose
     * {@code label} starts with the supplied prefix (case-insensitive
     * via {@code ILIKE}); the caller is responsible for filtering the
     * result to studies the session user can see.
     *
     * <p>The query mirrors {@link #findByLabelAcrossStudies(String)}'s
     * shape (same column set, same NOT IN (5,7) status filter), so the
     * portal can render a uniform candidate row regardless of which
     * lookup produced it.
     *
     * @param prefix label prefix; blank → empty list. No SQL wildcards
     *               are added by the caller — they're appended here
     *               so the prefix value is treated as a literal.
     * @param limit  hard ceiling on result rows; caller should clamp
     *               to a sensible range (1-50) before invoking.
     */
    public List<StudySubjectMatch> findByLabelPrefix(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        final String sql =
                "SELECT ss.study_subject_id, ss.label, ss.status_id, "
                        + "       s.study_id, s.name AS study_name, "
                        + "       s.unique_identifier AS study_oid, "
                        + "       site.name AS site_name "
                        + "  FROM study_subject ss "
                        + "  JOIN study s ON s.study_id = ss.study_id "
                        + "  LEFT JOIN study site ON site.study_id = s.parent_study_id "
                        + " WHERE ss.label ILIKE ? "
                        + "   AND ss.status_id NOT IN (5, 7) "
                        + " ORDER BY ss.label "
                        + " LIMIT ?";
        List<StudySubjectMatch> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StudySubjectMatch(
                            rs.getInt("study_id"),
                            rs.getString("study_name"),
                            rs.getString("study_oid"),
                            rs.getInt("study_subject_id"),
                            rs.getString("label"),
                            rs.getString("site_name"),
                            rs.getInt("status_id")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("findByLabelPrefix failed for prefix '{}': {}", prefix, e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * For one resolved study_subject, find the study_event row whose
     * {@code date_start} falls on the supplied scan date.
     *
     * <p>2026-06-23 — the query now anchors on {@code study_event}
     * (LEFT JOIN {@code event_crf}). A planned-but-not-started visit
     * has no {@code event_crf} row yet — the operator hasn't opened
     * the form — so the previous INNER-JOIN missed them entirely.
     * That left subjects with planned visits looking like they had no
     * visits at all on the scan date.
     *
     * <p>The returned {@link EventCandidate} carries a non-null
     * {@code studyEventId} (always) plus a nullable {@code eventCrfId}
     * (only when the CRF is already in flight). The commit endpoint
     * uses {@code studyEventId} to bind the new
     * {@code retinal_inference_job} row; {@code eventCrfId} gets
     * populated later when the operator opens the CRF and commits the
     * derived metrics into item_data.
     *
     * <p>Removed-event filter retained: {@code subject_event_status}
     * 5 (STOPPED) and 7 (LOCKED) are excluded — those visits are
     * terminal and shouldn't accept new scans. The status filter now
     * lives on {@code study_event} rather than {@code event_crf}.
     *
     * <p>When more than one study_event matches on the same day the
     * lowest {@code study_event_id} wins (deterministic, defensible
     * — events are typically created in chronological order).
     */
    public Optional<EventCandidate> findEventOnDate(int studySubjectId, LocalDate scanDate) {
        if (scanDate == null) {
            return Optional.empty();
        }
        final String sql =
                "SELECT se.study_event_id, "
                        + "       ec.event_crf_id, "
                        + "       sed.name AS definition_name, "
                        + "       se.sample_ordinal, "
                        + "       date(se.date_start) AS event_date "
                        + "  FROM study_event se "
                        + "  JOIN study_event_definition sed "
                        + "    ON sed.study_event_definition_id = se.study_event_definition_id "
                        + "  LEFT JOIN event_crf ec "
                        + "    ON ec.study_event_id = se.study_event_id "
                        + "   AND ec.status_id NOT IN (5, 7) "
                        + " WHERE se.study_subject_id = ? "
                        + "   AND date(se.date_start) = ? "
                        + "   AND se.subject_event_status_id NOT IN (5, 7) "
                        + " ORDER BY se.study_event_id ASC, ec.event_crf_id ASC NULLS LAST "
                        + " LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studySubjectId);
            ps.setDate(2, Date.valueOf(scanDate));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String defName = rs.getString("definition_name");
                int ordinal = rs.getInt("sample_ordinal");
                String label = (ordinal > 1) ? defName + " (#" + ordinal + ")" : defName;
                Date dStart = rs.getDate("event_date");
                int ecf = rs.getInt("event_crf_id");
                Integer eventCrfId = rs.wasNull() ? null : ecf;
                return Optional.of(new EventCandidate(
                        rs.getInt("study_event_id"),
                        eventCrfId,
                        label,
                        // 2026-06-24 — pre-format as ISO yyyy-MM-dd so
                        // the SPA renders "2025-11-19" verbatim, not
                        // Jackson's "[ 2025, 11, 19 ]" LocalDate array.
                        dStart == null ? null : dStart.toLocalDate().toString(),
                        "same-day"
                ));
            }
        } catch (SQLException e) {
            LOG.error("findEventOnDate failed for studySubjectId={} date={}: {}",
                    studySubjectId, scanDate, e.getMessage());
            return Optional.empty();
        }
    }
}
