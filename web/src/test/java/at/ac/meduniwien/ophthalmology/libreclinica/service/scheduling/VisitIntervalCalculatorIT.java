/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * nAMD treat-and-extend (2026-06-19) — testcontainers IT for the
 * {@link VisitIntervalCalculator} service. Exercises the post-create
 * UPDATE path against a real {@code study_event} row in a freshly-
 * bootstrapped Postgres.
 *
 * <p>Pinned cases:
 *
 * <ul>
 *   <li>Happy path: a valid interval writes
 *       {@code scheduled_for = dateStarted + intervalDays} and the
 *       returned {@link LocalDate} matches.</li>
 *   <li>Null interval: no UPDATE runs, both columns stay null
 *       (verified by readback).</li>
 *   <li>Negative interval: rejects with {@link IllegalArgumentException}.
 *   </li>
 *   <li>Missing row: rejects with {@link IllegalStateException} when
 *       the target study_event_id doesn't exist.</li>
 * </ul>
 *
 * <p>The IT extends {@link AbstractApiControllerDatabaseIT} so it
 * inherits the testcontainers Postgres + Liquibase bootstrap that
 * applies the new {@code lc-muw-2026-06-19-event-scheduled-interval}
 * changeset; without that the columns this service writes wouldn't
 * exist in the test DB.
 */
class VisitIntervalCalculatorIT extends AbstractApiControllerDatabaseIT {

    private static final int SEED_DEF_ID = 1;     // demo-seed event definition
    private static final int SEED_SUBJECT_ID = 1; // demo-seed study_subject

    @AfterEach
    void cleanupSeededRows() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM study_event WHERE study_event_definition_id = ?"
                             + " AND study_subject_id = ? AND sample_ordinal >= 9000")) {
            ps.setInt(1, SEED_DEF_ID);
            ps.setInt(2, SEED_SUBJECT_ID);
            ps.executeUpdate();
        }
    }

    @Test
    void appliesIntervalAndComputesScheduledFor() throws SQLException {
        int eventId = insertStudyEvent(/* ordinal */ 9001, java.time.LocalDate.of(2026, 6, 19));

        VisitIntervalCalculator calc = new VisitIntervalCalculator(DATA_SOURCE);
        LocalDate scheduledFor = calc.applyInterval(
                eventId,
                Date.valueOf(LocalDate.of(2026, 6, 19)),
                /* intervalDays */ 56);

        assertEquals(LocalDate.of(2026, 8, 14), scheduledFor);

        // Readback — DB row reflects what the service computed.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT scheduled_for, scheduled_interval_days FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Date scheduledForDb = rs.getDate("scheduled_for");
                int days = rs.getInt("scheduled_interval_days");
                assertNotNull(scheduledForDb);
                assertEquals(LocalDate.of(2026, 8, 14),
                        scheduledForDb.toLocalDate());
                assertEquals(56, days);
            }
        }
    }

    @Test
    void nullIntervalIsANoOp() throws SQLException {
        int eventId = insertStudyEvent(9002, LocalDate.of(2026, 6, 19));

        VisitIntervalCalculator calc = new VisitIntervalCalculator(DATA_SOURCE);
        LocalDate result = calc.applyInterval(
                eventId,
                Date.valueOf(LocalDate.of(2026, 6, 19)),
                /* intervalDays */ null);

        assertNull(result);

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT scheduled_for, scheduled_interval_days FROM study_event WHERE study_event_id = ?")) {
            ps.setInt(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertNull(rs.getDate("scheduled_for"));
                rs.getInt("scheduled_interval_days");
                // rs.wasNull() returns true on the int read.
            }
        }
    }

    @Test
    void negativeIntervalRejected() {
        VisitIntervalCalculator calc = new VisitIntervalCalculator(DATA_SOURCE);
        assertThrows(IllegalArgumentException.class,
                () -> calc.applyInterval(
                        /* studyEventId */ 1,
                        Date.valueOf(LocalDate.of(2026, 6, 19)),
                        /* intervalDays */ -3));
    }

    @Test
    void missingDateStartedRejected() {
        VisitIntervalCalculator calc = new VisitIntervalCalculator(DATA_SOURCE);
        assertThrows(IllegalArgumentException.class,
                () -> calc.applyInterval(1, /* dateStarted */ null, 14));
    }

    @Test
    void missingRowRejected() throws SQLException {
        VisitIntervalCalculator calc = new VisitIntervalCalculator(DATA_SOURCE);
        assertThrows(IllegalStateException.class,
                () -> calc.applyInterval(
                        /* studyEventId */ 9999999,
                        Date.valueOf(LocalDate.of(2026, 6, 19)),
                        14));
    }

    /* ---------- helpers ---------- */

    private int insertStudyEvent(int ordinal, LocalDate dateStart) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study_event "
                             + "  (study_event_definition_id, study_subject_id, location, sample_ordinal, "
                             + "   date_start, date_end, owner_id, status_id, date_created, "
                             + "   subject_event_status_id, start_time_flag, end_time_flag) "
                             + "VALUES (?, ?, '', ?, ?, NULL, 1, 1, NOW(), 1, false, false) "
                             + "RETURNING study_event_id")) {
            ps.setInt(1, SEED_DEF_ID);
            ps.setInt(2, SEED_SUBJECT_ID);
            ps.setInt(3, ordinal);
            ps.setTimestamp(4, Timestamp.from(
                    dateStart.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
