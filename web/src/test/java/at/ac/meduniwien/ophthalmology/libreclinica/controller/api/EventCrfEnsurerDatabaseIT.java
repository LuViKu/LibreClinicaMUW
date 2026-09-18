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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfEnsurer;

/**
 * P3.0 — one implementation of "get the CRF instance for this visit".
 *
 * <p>The BCVA portal and the retinal flags endpoint each carried the same
 * fourteen-column insert. Collapsing them is mechanical; one difference was
 * not. The portal skipped removed instances, the retinal copy did not.
 *
 * <p>The unique constraint on {@code (study_event_id, crf_version_id,
 * study_subject_id)} settles it, and shows both were wrong: skipping cannot
 * produce a replacement, only a failed insert. So the removed instance is
 * reported with its status and the caller refuses. {@link
 * #aRemovedForm_isReportedRatherThanReplaced} is the test that caught this —
 * it was originally written to assert a second instance, and the database
 * refused.
 *
 * <p>The fixture is built here rather than taken from the seed, because the
 * interesting cases need a visit that has no instance of a given form yet.
 */
class EventCrfEnsurerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int OWNER = 1;

    private int studyEventId;
    private int crfVersionId;
    private int expectedStudySubjectId;

    @BeforeEach
    void pickAVisitWithNoInstanceOfSomeForm() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT se.study_event_id, se.study_subject_id, cv.crf_version_id "
                             + "  FROM study_event se "
                             + " CROSS JOIN crf_version cv "
                             + " WHERE NOT EXISTS (SELECT 1 FROM event_crf ec "
                             + "                    WHERE ec.study_event_id = se.study_event_id "
                             + "                      AND ec.crf_version_id = cv.crf_version_id) "
                             + " ORDER BY se.study_event_id, cv.crf_version_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the seed must offer a visit missing at least one form");
            studyEventId = rs.getInt(1);
            expectedStudySubjectId = rs.getInt(2);
            crfVersionId = rs.getInt(3);
        }
    }

    @AfterEach
    void removeWhatWeCreated() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM event_crf WHERE study_event_id = ? AND crf_version_id = ?")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, crfVersionId);
            ps.executeUpdate();
        }
    }

    private record Row(int id, int statusId, int completionStatusId, int ownerId, int studySubjectId) {}

    /** Every instance for the fixture pair, oldest first. */
    private java.util.List<Row> rows() throws Exception {
        java.util.List<Row> out = new java.util.ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_crf_id, status_id, completion_status_id, owner_id, study_subject_id "
                             + "  FROM event_crf WHERE study_event_id = ? AND crf_version_id = ? "
                             + " ORDER BY event_crf_id")) {
            ps.setInt(1, studyEventId);
            ps.setInt(2, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)));
                }
            }
        }
        return out;
    }

    private void setStatus(int eventCrfId, int statusId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE event_crf SET status_id = ? WHERE event_crf_id = ?")) {
            ps.setInt(1, statusId);
            ps.setInt(2, eventCrfId);
            ps.executeUpdate();
        }
    }

    /* ---------------- creating ---------------- */

    @Test
    void aVisitWithoutTheForm_getsOneInstance() throws Exception {
        EventCrfEnsurer.Instance created;
        try (Connection c = DATA_SOURCE.getConnection()) {
            created = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER);
        }
        assertTrue(created.eventCrfId() > 0);
        assertTrue(created.created(), "this call inserted it, and the caller may want to know");
        assertFalse(created.removed());

        var all = rows();
        assertEquals(1, all.size());
        Row row = all.get(0);
        assertEquals(created.eventCrfId(), row.id());
        assertEquals(1, row.statusId(), "a new instance is available, not removed");
        assertEquals(1, row.completionStatusId(), "initial data entry");
        assertEquals(OWNER, row.ownerId(), "the caller names the owner");
        assertEquals(expectedStudySubjectId, row.studySubjectId(),
                "event_crf denormalises the subject; heritage DAOs read it from here");
    }

    @Test
    void askingTwice_doesNotSplitTheFormInTwo() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            EventCrfEnsurer.Instance first = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER);
            EventCrfEnsurer.Instance second = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER);
            assertEquals(first.eventCrfId(), second.eventCrfId());
            assertFalse(second.created(), "the second call found it rather than inserting");
        }
        assertEquals(1, rows().size(), "a second instance would show the operator half their data");
    }

    /* ---------------- the removal neither copy handled ---------------- */

    /**
     * The schema permits exactly one instance per (visit, version, subject),
     * so a removed form cannot be sidestepped by creating another. Reporting
     * it is the only option that neither crashes nor revives it.
     */
    @Test
    void aRemovedForm_isReportedRatherThanReplaced() throws Exception {
        int id;
        try (Connection c = DATA_SOURCE.getConnection()) {
            id = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER).eventCrfId();
        }
        setStatus(id, 5);

        try (Connection c = DATA_SOURCE.getConnection()) {
            EventCrfEnsurer.Instance again = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER);
            assertEquals(id, again.eventCrfId());
            assertFalse(again.created(), "inserting a second instance violates the unique constraint");
            assertTrue(again.removed(), "the caller has to be able to refuse the write");
        }

        var all = rows();
        assertEquals(1, all.size(), "the constraint allows only one");
        assertEquals(5, all.get(0).statusId(), "and the removal itself must stand untouched");
    }

    @Test
    void anAutoRemovedForm_readsAsRemovedToo() throws Exception {
        int id;
        try (Connection c = DATA_SOURCE.getConnection()) {
            id = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER).eventCrfId();
        }
        // 7 is the cascade of removing the visit above it, and means the same
        // thing to an operator as an explicit delete.
        setStatus(id, 7);

        try (Connection c = DATA_SOURCE.getConnection()) {
            assertTrue(EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER).removed());
        }
    }

    /* ---------------- looking up ---------------- */

    @Test
    void findReportsNothingRatherThanCreating() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            assertNull(EventCrfEnsurer.find(c, studyEventId, crfVersionId));
        }
        assertEquals(0, rows().size(), "a lookup must not have side effects");
    }

    @Test
    void findReturnsTheInstanceWithItsStatus() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int id = EventCrfEnsurer.ensure(c, studyEventId, crfVersionId, OWNER).eventCrfId();
            EventCrfEnsurer.Instance found = EventCrfEnsurer.find(c, studyEventId, crfVersionId);
            assertEquals(id, found.eventCrfId());
            assertEquals(1, found.statusId());
            assertFalse(found.created(), "a lookup never reports having created anything");
        }
    }

    /* ---------------- refusals ---------------- */

    @Test
    void aVisitThatDoesNotExist_failsLoudly() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            // Silently inventing a row here would attach clinical data to nothing.
            assertThrows(SQLException.class,
                    () -> EventCrfEnsurer.ensure(c, -1, crfVersionId, OWNER));
        }
    }

    @Test
    void theFallbackOwner_isAnAccountThatExists() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int owner = EventCrfEnsurer.fallbackOwnerId(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status_id FROM user_account WHERE user_id = ?")) {
                ps.setInt(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "the owner FK would fail against a sentinel");
                    assertEquals(1, rs.getInt(1), "and the account has to be active");
                }
            }
        }
    }
}
