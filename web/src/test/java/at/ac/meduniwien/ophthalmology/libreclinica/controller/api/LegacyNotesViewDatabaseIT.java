/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

/**
 * The legacy View Notes list, its summary and its download read
 * {@code view_discrepancy_note}. A thread nobody has answered is in it.
 *
 * <p>Before, {@code view_dn_stats} kept a parent note only if it had a child
 * note. The heritage UI always wrote one, the SPA never does: every note
 * created in the SPA, and each of the eight notes of the demo seed, was
 * missing from the legacy list until somebody replied.
 */
class LegacyNotesViewDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void aThreadNobodyAnsweredIsInTheLegacyList() throws Exception {
        // Seed note 1: a New query on item data 3 that has no child note.
        Instant created = NoteFixtures.createdAt(DATA_SOURCE, 1);
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT label, total_notes, date_updated, age FROM view_discrepancy_note "
                             + "WHERE discrepancy_note_id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "note 1 is in the legacy list");
                assertEquals(NoteFixtures.subjectLabel(DATA_SOURCE, 1), rs.getString("label"));
                assertEquals(1, rs.getInt("total_notes"), "the thread holds one note");
                assertEquals(created, rs.getTimestamp("date_updated").toInstant(),
                        "last updated when it was created");
                long expectedAge = ChronoUnit.DAYS.between(created, Instant.now());
                assertTrue(Math.abs(expectedAge - rs.getLong("age")) <= 1, "days since creation");
            }
        }
    }

    @Test
    void theLegacySummaryCountsEverySeededQuery() throws Exception {
        // The eight seeded notes are New queries without a child note.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM view_discrepancy_note "
                             + "WHERE discrepancy_note_id BETWEEN 1 AND 8")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(8, rs.getInt(1));
            }
        }
    }

    @Test
    void anAnsweredThreadReadsAsBefore() throws Exception {
        Instant created = Instant.now().minus(Duration.ofDays(9).plusHours(1)).truncatedTo(ChronoUnit.SECONDS);
        Instant answered = created.plus(Duration.ofDays(4));
        int note = NoteFixtures.insertItemNote(DATA_SOURCE, 3, 2, created, 11, "Height recheck");
        NoteFixtures.insertChild(DATA_SOURCE, note, 2, answered, "Measured again");
        try {
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT total_notes, date_updated, days, age FROM view_discrepancy_note "
                                 + "WHERE discrepancy_note_id = ?")) {
                ps.setInt(1, note);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "note " + note + " is in the legacy list");
                    assertEquals(1, rs.getInt("total_notes"), "one child note");
                    assertEquals(answered, rs.getTimestamp("date_updated").toInstant());
                    assertEquals(5, rs.getInt("days"), "days since the answer");
                    assertEquals(9, rs.getInt("age"), "days since creation");
                }
            }
        } finally {
            NoteFixtures.delete(DATA_SOURCE, note);
        }
    }
}
