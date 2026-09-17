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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

/**
 * The one query that answers "which visits are open in this window".
 *
 * <p>Several surfaces need the same list — the Modality Worklist the camera
 * pulls, the "today's visits" picker on the upload page, and (Phase 2) the
 * due-visits view. They must agree: a visit the camera can photograph but the
 * upload page will not offer, or the reverse, is a defect the operator hits
 * with a patient in the chair.
 *
 * <p>Keeping one statement is not tidiness. A sibling pair of extract queries
 * in this codebase drifted by exactly one predicate and silently returned
 * nothing for months.
 *
 * <p>What "open" means here, and why:
 *
 * <ul>
 *   <li>{@code subject_event_status_id IN (1, 3)} — scheduled or data-entry
 *       started. A completed or stopped visit is closed; offering it invites
 *       filing against the wrong encounter.</li>
 *   <li>{@code study_subject.status_id NOT IN (5, 7)} — removed and
 *       auto-removed subjects never appear.</li>
 *   <li>An optional study scope. Both callers serve devices that must not see
 *       other studies' patients; see {@link StudyScopeConfig}.</li>
 * </ul>
 *
 * <p>The row carries identifying fields (sex, date of birth) because the DICOM
 * worklist needs them. <strong>Callers project.</strong> The unauthenticated
 * upload page must expose the label and the visit only — it is a page anyone
 * on the network can open.
 */
final class ScheduledVisitQuery {

    private ScheduledVisitQuery() {}

    /**
     * One open visit.
     *
     * @param dateOfBirth  null unless the subject's DOB was collected
     * @param time         null when the visit carries no meaningful time of day
     * @param eventCrfId   the visit's first live CRF instance, null when data
     *                     entry has not started
     */
    record ScheduledVisit(int studyEventId, int studySubjectId, String subjectLabel,
                          String gender, String dateOfBirth, String date, java.time.LocalTime time,
                          String eventLabel, String studyName, Integer eventCrfId) {}

    /**
     * @param studyIds null for every study; an empty set for none
     * @param limit    hard row cap
     */
    static List<ScheduledVisit> query(DataSource dataSource, LocalDate from, LocalDate to,
                                      Set<Integer> studyIds, int limit) throws SQLException {
        String scope = StudyScopeConfig.inClauseOrNull(studyIds);
        String sql = "SELECT se.study_event_id, ss.study_subject_id, ss.label, "
                + "       sub.gender, sub.date_of_birth, sub.dob_collected, "
                + "       se.date_start, se.start_time_flag, se.sample_ordinal, "
                + "       sed.name AS definition_name, s.name AS study_name, "
                + "       (SELECT ec.event_crf_id FROM event_crf ec "
                + "         WHERE ec.study_event_id = se.study_event_id "
                + "           AND ec.status_id NOT IN (5, 7) "
                + "         ORDER BY ec.event_crf_id LIMIT 1) AS event_crf_id "
                + "  FROM study_event se "
                + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "  JOIN subject sub ON sub.subject_id = ss.subject_id "
                + "  JOIN study s ON s.study_id = ss.study_id "
                + "  JOIN study_event_definition sed "
                + "    ON sed.study_event_definition_id = se.study_event_definition_id "
                + " WHERE date(se.date_start) BETWEEN ? AND ? "
                + "   AND se.subject_event_status_id IN (1, 3) "
                + "   AND ss.status_id NOT IN (5, 7) "
                + (scope == null ? "" : "   AND ss.study_id IN " + scope + " ")
                + " ORDER BY se.date_start, ss.label "
                + " LIMIT " + Math.max(1, limit);

        List<ScheduledVisit> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp start = rs.getTimestamp("date_start");
                    boolean timeMeaningful = rs.getBoolean("start_time_flag");
                    boolean dobCollected = rs.getBoolean("dob_collected");
                    Date dob = rs.getDate("date_of_birth");
                    String defName = rs.getString("definition_name");
                    int ordinal = rs.getInt("sample_ordinal");
                    // Read wasNull() immediately: it reports on the most recent
                    // getter, and the record's argument list has more of them.
                    int ecrf = rs.getInt("event_crf_id");
                    Integer eventCrfId = rs.wasNull() ? null : Integer.valueOf(ecrf);
                    out.add(new ScheduledVisit(
                            rs.getInt("study_event_id"),
                            rs.getInt("study_subject_id"),
                            rs.getString("label"),
                            rs.getString("gender"),
                            (dobCollected && dob != null) ? dob.toLocalDate().toString() : null,
                            start != null ? start.toLocalDateTime().toLocalDate().toString() : null,
                            (timeMeaningful && start != null)
                                    ? start.toLocalDateTime().toLocalTime().withNano(0) : null,
                            ordinal > 1 ? defName + " (#" + ordinal + ")" : defName,
                            rs.getString("study_name"),
                            eventCrfId));
                }
            }
        }
        return out;
    }
}
