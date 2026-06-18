/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

/**
 * Wave 1B coverage for {@link StudySubjectFinder#findByLabelPrefix(String, int)}.
 *
 * <p>Lives in the web/src/test tree (alongside the controller ITs) so it
 * can reuse {@link AbstractApiControllerDatabaseIT}'s Testcontainers
 * Postgres + Liquibase bootstrap. The seed file fixes M-001 .. M-007 on
 * study_id=1; the prefix tests pin (a) all-matches for "M-", (b) the
 * limit clamp, and (c) the no-match path.
 */
class StudySubjectFinderDatabaseIT extends AbstractApiControllerDatabaseIT {

    private StudySubjectFinder finder() {
        return new StudySubjectFinder(DATA_SOURCE);
    }

    @Test
    void findByLabelPrefix_returnsAllMatches() {
        List<StudySubjectMatch> rows = finder().findByLabelPrefix("M-", 50);
        assertEquals(7, rows.size(), "Expected M-001 .. M-007 to all match prefix 'M-'");
        // Rows ordered by label — first should be M-001.
        assertEquals("M-001", rows.get(0).subjectLabel());
        assertEquals("M-007", rows.get(rows.size() - 1).subjectLabel());
    }

    @Test
    void findByLabelPrefix_honorsLimit() {
        List<StudySubjectMatch> rows = finder().findByLabelPrefix("M-", 3);
        assertEquals(3, rows.size());
        // Limit doesn't change the ordering — still alphabetical.
        assertEquals("M-001", rows.get(0).subjectLabel());
        assertEquals("M-003", rows.get(2).subjectLabel());
    }

    @Test
    void findByLabelPrefix_caseInsensitive() {
        // ILIKE so a lowercase prefix matches the uppercase labels.
        List<StudySubjectMatch> rows = finder().findByLabelPrefix("m-0", 10);
        assertTrue(rows.size() >= 7, "case-insensitive match should still find all 7 rows");
    }

    @Test
    void findByLabelPrefix_emptyOnBlank() {
        assertTrue(finder().findByLabelPrefix("", 10).isEmpty());
        assertTrue(finder().findByLabelPrefix("   ", 10).isEmpty());
        assertTrue(finder().findByLabelPrefix(null, 10).isEmpty());
    }

    @Test
    void findByLabelPrefix_emptyOnNonexistentPrefix() {
        assertTrue(finder().findByLabelPrefix("ZZZ-NOTHING", 10).isEmpty());
    }

    @Test
    void findByLabelPrefix_emptyOnZeroLimit() {
        assertTrue(finder().findByLabelPrefix("M-", 0).isEmpty());
        assertTrue(finder().findByLabelPrefix("M-", -1).isEmpty());
    }
}
