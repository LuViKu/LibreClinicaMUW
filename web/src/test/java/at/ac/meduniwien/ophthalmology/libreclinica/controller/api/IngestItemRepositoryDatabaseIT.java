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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;

/**
 * P3.1 — the one place a row enters {@code ingest_item}.
 *
 * <p>What this replaced was two hand-maintained INSERTs of twenty-one and
 * sixteen positional parameters, listing overlapping subsets of the same
 * columns in different orders. The failure mode of that code is not an
 * exception: a misnumbered parameter writes the accession number into the
 * laterality column and the row looks entirely plausible.
 *
 * <p>So these check that named values land in the columns they were named
 * for, and that a caller who sets nothing extra still produces a valid row.
 */
class IngestItemRepositoryDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String MARKER = "repo-it-";

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ingest_item WHERE stored_path LIKE ?")) {
            ps.setString(1, MARKER + "%");
            ps.executeUpdate();
        }
    }

    private record Row(String kind, String sourceKind, String status, String matchPolicy,
                       String device, String sha256, Long byteSize, Integer scanIndex,
                       String laterality, String accession, LocalDate acquisitionDate,
                       Integer boundSubject, Integer boundEvent, Integer boundEventCrf,
                       Integer boundByUser, boolean hasBoundAt, Integer candidate) {}

    private Row read(long id) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind, source_kind, status, match_policy, device, sha256, byte_size, "
                             + "scan_index, laterality, accession_number, acquisition_date, "
                             + "bound_study_subject_id, bound_study_event_id, bound_event_crf_id, "
                             + "bound_by_user_id, bound_at, candidate_study_subject_id "
                             + "FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the row the repository said it wrote must exist");
                return new Row(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6),
                        nullableLong(rs, 7), nullableInt(rs, 8),
                        rs.getString(9), rs.getString(10),
                        rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(),
                        nullableInt(rs, 12), nullableInt(rs, 13), nullableInt(rs, 14),
                        nullableInt(rs, 15), rs.getTimestamp(16) != null, nullableInt(rs, 17));
            }
        }
    }

    private static Integer nullableInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /** A seeded visit, so the binding columns can carry real foreign keys. */
    private record Visit(int studySubjectId, int studyEventId) {}

    private Visit someVisit() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_subject_id, study_event_id FROM study_event "
                             + "ORDER BY study_event_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the seed must contain at least one visit");
            return new Visit(rs.getInt(1), rs.getInt(2));
        }
    }

    /* ---------------- the minimum ---------------- */

    @Test
    void theThreeRequiredValuesAreEnoughForAValidRow() throws Exception {
        long id;
        try (Connection c = DATA_SOURCE.getConnection()) {
            id = IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.IMAGE, "upload", MARKER + "min.jpg")
                    .insert(c);
        }
        Row row = read(id);
        assertEquals("image", row.kind());
        assertEquals("upload", row.sourceKind());
        assertEquals("UNBOUND", row.status(), "a row nobody has reconciled is unbound");
        assertNull(row.matchPolicy(), "nothing matched it, so no policy claims it did");
        assertNull(row.boundSubject());
        // Columns the caller never mentioned must stay untouched rather than
        // being written as empty strings that later read as real values.
        assertNull(row.device());
        assertNull(row.laterality());
        assertNull(row.sha256());
    }

    @Test
    void kindIsWrittenFromTheStoresOwnVocabulary() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            assertEquals("dicom", read(IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.DICOM, "dicom", MARKER + "a.dcm").insert(c)).kind());
            assertEquals("e2e", read(IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.E2E, "portal-oct", MARKER + "b.e2e").insert(c)).kind());
            assertEquals("other", read(IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.OTHER, "api", MARKER + "c.bin").insert(c)).kind());
        }
    }

    /* ---------------- named values reach their own columns ---------------- */

    @Test
    void eachNamedValueLandsInItsOwnColumn() throws Exception {
        long id;
        try (Connection c = DATA_SOURCE.getConnection()) {
            id = IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.DICOM, "dicom", MARKER + "full.dcm")
                    .device("OPTOMEDLUMO")
                    .laterality("OS")
                    .accessionNumber("LC4242")
                    .acquisitionDate(LocalDate.of(2026, 3, 14))
                    .digest("f".repeat(64), 123456L)
                    .scanIndex(7)
                    .insert(c);
        }
        Row row = read(id);
        // The point of the builder: the accession is in accession_number and
        // not, say, in laterality.
        assertEquals("OPTOMEDLUMO", row.device());
        assertEquals("OS", row.laterality());
        assertEquals("LC4242", row.accession());
        assertEquals(LocalDate.of(2026, 3, 14), row.acquisitionDate());
        assertEquals("f".repeat(64), row.sha256());
        assertEquals(Long.valueOf(123456L), row.byteSize());
        assertEquals(Integer.valueOf(7), row.scanIndex());
    }

    /* ---------------- binding ---------------- */

    @Test
    void boundToSetsTheWholeBindingAtOnce() throws Exception {
        Visit visit = someVisit();
        long id;
        try (Connection c = DATA_SOURCE.getConnection()) {
            id = IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.DICOM, "dicom", MARKER + "bound.dcm")
                    .boundTo(visit.studySubjectId(), visit.studyEventId(), null, "worklist")
                    .insert(c);
        }
        Row row = read(id);
        assertEquals("BOUND", row.status());
        assertEquals("worklist", row.matchPolicy());
        assertEquals(Integer.valueOf(visit.studySubjectId()), row.boundSubject());
        assertEquals(Integer.valueOf(visit.studyEventId()), row.boundEvent());
        assertNull(row.boundEventCrf(), "the visit's CRF may not have been started");
        // Nobody clicked bind — the worklist accession did, and recording a
        // person as having decided it would be a false attribution.
        assertNull(row.boundByUser(), "an automatic bind has no human author");
        assertTrue(row.hasBoundAt(), "when it was bound is part of the trail");
    }

    /**
     * A suggestion is not a decision. The inbox has to be able to show who the
     * platform thinks a file belongs to without the row claiming anybody
     * confirmed it.
     */
    @Test
    void aCandidateIsRecordedWithoutBindingAnything() throws Exception {
        Visit visit = someVisit();
        long id;
        try (Connection c = DATA_SOURCE.getConnection()) {
            id = IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.IMAGE, "upload", MARKER + "cand.jpg")
                    .candidateStudySubjectId(visit.studySubjectId())
                    .insert(c);
        }
        Row row = read(id);
        assertEquals(Integer.valueOf(visit.studySubjectId()), row.candidate());
        assertEquals("UNBOUND", row.status());
        assertNull(row.boundSubject(), "a candidate must not read as a binding");
    }

    /* ---------------- refusals ---------------- */

    @Test
    void aRowWithoutTheThingsEveryRowNeeds_isRefusedBeforeSql() {
        // Named rather than left to the NOT NULL constraint, so the caller gets
        // the parameter name instead of a Postgres error about a column.
        assertThrows(IllegalArgumentException.class, () ->
                IngestItemRepository.newItem(null, "upload", MARKER + "x.jpg"));
        assertThrows(IllegalArgumentException.class, () ->
                IngestItemRepository.newItem(IngestArtifactStore.Kind.IMAGE, " ", MARKER + "x.jpg"));
        assertThrows(IllegalArgumentException.class, () ->
                IngestItemRepository.newItem(IngestArtifactStore.Kind.IMAGE, "upload", null));
    }

    /**
     * The index exists so a device that re-sends, or a sidecar that retries,
     * does not put one acquisition in the inbox twice — which is how an image
     * gets bound to a visit twice.
     */
    @Test
    void theSameDigestAndScanCannotBeQueuedTwice() throws Exception {
        String sha = "a".repeat(64);
        try (Connection c = DATA_SOURCE.getConnection()) {
            IngestItemRepository.newItem(IngestArtifactStore.Kind.E2E, "portal-oct", MARKER + "1.e2e")
                    .digest(sha, 10L).scanIndex(0).insert(c);

            SQLException dup = assertThrows(SQLException.class, () ->
                    IngestItemRepository.newItem(IngestArtifactStore.Kind.E2E, "portal-oct", MARKER + "2.e2e")
                            .digest(sha, 10L).scanIndex(0).insert(c));
            assertEquals("23505", dup.getSQLState(), "a duplicate must surface as a unique violation");

            // A different scan of the same file is a different acquisition.
            assertNotNull(IngestItemRepository
                    .newItem(IngestArtifactStore.Kind.E2E, "portal-oct", MARKER + "3.e2e")
                    .digest(sha, 10L).scanIndex(1).insert(c));
        }
    }

    /** Rows without a digest predate the column; they must not collide. */
    @Test
    void rowsWithoutADigestDoNotCollide() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            IngestItemRepository.newItem(IngestArtifactStore.Kind.IMAGE, "upload", MARKER + "n1.jpg").insert(c);
            IngestItemRepository.newItem(IngestArtifactStore.Kind.IMAGE, "upload", MARKER + "n2.jpg").insert(c);
        }
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM ingest_item WHERE stored_path LIKE ? AND sha256 IS NULL")) {
            ps.setString(1, MARKER + "n%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        }
    }
}
