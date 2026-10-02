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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfWriteRules;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfWriteRules.Refusal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every state {@link EventCrfWriteRules#refusal} reads, against a real
 * database: the event CRF's own status, its visit's status and visit status,
 * its subject, its study and its parent study. Every writer of CRF values
 * (the SPA, the retinal populator, the flags panel, the BCVA portal, the
 * performed tick) asks it, so each source is pinned here once.
 *
 * <p>Seed: event CRF 3 is in data entry, on visit 3 of M-001 (study subject
 * 1) in Default Study (study 1). A site of Default Study is added for the
 * parent-study cases. Each case puts back what it changed.
 */
class EventCrfWriteRulesDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int EVENT_CRF = 3;

    private static int site;

    @BeforeAll
    static void addSite() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, name, summary, "
                             + "date_created, owner_id, type_id, status_id, protocol_type, oc_oid) "
                             + "VALUES (1, 'S_WRITE_RULES_SITE', 'S_WRITE_RULES_SITE', 'Write rules site', '', "
                             + "now(), 1, 1, 1, 'observational', 'S_WRITE_RULES_SITE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                site = keys.getInt(1);
            }
        }
    }

    @AfterEach
    void restore() throws SQLException {
        execute("UPDATE event_crf SET status_id = 1 WHERE event_crf_id = " + EVENT_CRF);
        execute("UPDATE study_event SET status_id = 1, subject_event_status_id = 3 WHERE study_event_id = 3");
        execute("UPDATE study_subject SET status_id = 1, study_id = 1 WHERE study_subject_id = 1");
        execute("UPDATE study SET status_id = 1 WHERE study_id IN (1, " + site + ")");
    }

    private static Refusal refusal() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            return EventCrfWriteRules.refusal(c, EVENT_CRF);
        }
    }

    private static void execute(String sql) throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE, sql);
    }

    @Test
    void aCrfInDataEntryMayChange() throws SQLException {
        assertNull(refusal());
    }

    @Test
    void thereIsNoRefusalForACrfThatDoesNotExist() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            assertNull(EventCrfWriteRules.refusal(c, 987654));
        }
    }

    @ParameterizedTest(name = "{0} = {1} -> {2}")
    @CsvSource({
            "event_crf.status_id,                5, REMOVED",
            "event_crf.status_id,                7, REMOVED",
            "event_crf.status_id,                6, LOCKED",
            "event_crf.status_id,                8, SIGNED",
            "study_event.status_id,              5, REMOVED",
            "study_event.status_id,              7, REMOVED",
            "study_event.subject_event_status_id, 7, LOCKED",
            "study_event.subject_event_status_id, 8, SIGNED",
            "study_subject.status_id,            5, REMOVED",
            "study_subject.status_id,            7, REMOVED",
            "study_subject.status_id,            6, LOCKED",
            "study_subject.status_id,            8, SIGNED",
            "study.status_id,                    5, REMOVED",
            "study.status_id,                    7, REMOVED",
            "study.status_id,                    6, LOCKED",
            "study.status_id,                    9, LOCKED",
    })
    void eachStateRefusesAsItShould(String column, int value, Refusal expected) throws SQLException {
        execute(update(column, value));
        assertEquals(expected, refusal());
    }

    @ParameterizedTest(name = "parent study status {0} -> {1}")
    @CsvSource({"5, REMOVED", "7, REMOVED", "6, LOCKED", "9, LOCKED"})
    void aSitesSubjectIsRefusedByItsParentStudy(int parentStatus, Refusal expected) throws SQLException {
        execute("UPDATE study_subject SET study_id = " + site + " WHERE study_subject_id = 1");
        assertNull(refusal(), "the site and its parent are available");

        execute("UPDATE study SET status_id = " + parentStatus + " WHERE study_id = 1");
        assertEquals(expected, refusal());
    }

    @Test
    void aRemovalOutranksALockAndALockASignature() throws SQLException {
        execute("UPDATE event_crf SET status_id = 8 WHERE event_crf_id = " + EVENT_CRF);
        execute("UPDATE study_subject SET status_id = 6 WHERE study_subject_id = 1");
        assertEquals(Refusal.LOCKED, refusal());

        execute("UPDATE study_event SET status_id = 5 WHERE study_event_id = 3");
        assertEquals(Refusal.REMOVED, refusal());
    }

    /** {@code table.column} of event CRF 3's own row, visit, subject or study set to {@code value}. */
    private static String update(String column, int value) {
        String[] parts = column.split("\\.");
        String where = switch (parts[0]) {
            case "event_crf" -> "event_crf_id = " + EVENT_CRF;
            case "study_event" -> "study_event_id = 3";
            case "study_subject" -> "study_subject_id = 1";
            case "study" -> "study_id = 1";
            default -> throw new IllegalArgumentException(column);
        };
        return "UPDATE " + parts[0] + " SET " + parts[1] + " = " + value + " WHERE " + where;
    }
}
