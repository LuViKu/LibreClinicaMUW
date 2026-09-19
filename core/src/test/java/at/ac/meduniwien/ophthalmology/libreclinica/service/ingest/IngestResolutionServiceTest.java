/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

/**
 * P3.0 — one answer to "whose visit is this file for?".
 *
 * <p>Three call sites asked that question and answered it differently. These
 * pin the single answer, and in particular the two rules the old copies got
 * wrong in opposite directions: a lone subject with no same-day visit is
 * {@code novisit} rather than {@code suggested}, and a subject matched only
 * outside the caller's scope is indistinguishable from no match at all.
 */
public class IngestResolutionServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    private static StudySubjectMatch match(int studyId, int subjectId, String label) {
        return new StudySubjectMatch(studyId, "Study " + studyId, "S_" + studyId,
                subjectId, label, null, 1);
    }

    private static EventCandidate visit(int eventId) {
        return new EventCandidate(eventId, null, "V1", DAY.toString(), "exact");
    }

    private static IngestResolutionService with(List<StudySubjectMatch> matches,
                                                Optional<EventCandidate> event) {
        StudySubjectFinder finder = mock(StudySubjectFinder.class);
        when(finder.findByLabelAcrossStudies(any())).thenReturn(matches);
        when(finder.findEventOnDate(anyInt(), any())).thenReturn(event);
        return new IngestResolutionService(finder);
    }

    /* ---------------- the four states ---------------- */

    @Test
    public void anUnknownLabelIsNoPatient() {
        var r = with(List.of(), Optional.empty()).resolve("ZZZ-999", DAY, null);
        assertEquals(IngestResolutionService.STATE_NO_PATIENT, r.state());
        assertTrue(r.candidates().isEmpty());
    }

    @Test
    public void aBlankLabelIsNoPatientWithoutAskingTheDatabase() {
        StudySubjectFinder finder = mock(StudySubjectFinder.class);
        var r = new IngestResolutionService(finder).resolve("   ", DAY, null);
        assertEquals(IngestResolutionService.STATE_NO_PATIENT, r.state());
    }

    @Test
    public void twoSubjectsSharingALabelAreAmbiguous() {
        var r = with(List.of(match(1, 10, "M-001"), match(2, 20, "M-001")),
                Optional.of(visit(5))).resolve("M-001", DAY, null);
        assertEquals(IngestResolutionService.STATE_AMBIGUOUS, r.state());
        assertEquals(2, r.candidates().size());
        assertTrue("an ambiguous match must not be treated as a suggestion",
                r.single().isEmpty());
    }

    @Test
    public void oneSubjectWithAVisitThatDayIsSuggested() {
        var r = with(List.of(match(1, 10, "M-001")), Optional.of(visit(5)))
                .resolve("M-001", DAY, null);
        assertEquals(IngestResolutionService.STATE_SUGGESTED, r.state());
        assertTrue(r.isSuggested());
        assertEquals(10, r.single().orElseThrow().studySubjectId());
    }

    /**
     * The rule the old copies disagreed on. The subject is known but the visit
     * is not, so the operator still has to choose one — calling that a
     * suggestion would file the image against nothing.
     */
    @Test
    public void oneSubjectWithNoVisitThatDayIsNoVisit() {
        var r = with(List.of(match(1, 10, "M-001")), Optional.empty())
                .resolve("M-001", DAY, null);
        assertEquals(IngestResolutionService.STATE_NO_VISIT, r.state());
        assertFalse(r.isSuggested());
        assertEquals(1, r.candidates().size());
    }

    /** Without a date there is no visit to find, so a lone subject is novisit. */
    @Test
    public void noDateMeansNoVisit() {
        var r = with(List.of(match(1, 10, "M-001")), Optional.of(visit(5)))
                .resolve("M-001", (LocalDate) null, null);
        assertEquals(IngestResolutionService.STATE_NO_VISIT, r.state());
    }

    @Test
    public void anUnparseableDateIsTreatedAsNoDate() {
        var r = with(List.of(match(1, 10, "M-001")), Optional.of(visit(5)))
                .resolve("M-001", "not-a-date", null);
        assertEquals(IngestResolutionService.STATE_NO_VISIT, r.state());
    }

    /* ---------------- scope ---------------- */

    /**
     * A subject that exists only outside the caller's scope must be
     * indistinguishable from one that does not exist. Anything else lets an
     * unauthenticated portal probe the register for labels.
     */
    @Test
    public void aSubjectOutsideTheScopeLooksLikeNoSubject() {
        var r = with(List.of(match(2, 20, "M-001")), Optional.of(visit(5)))
                .resolve("M-001", DAY, Set.of(1));
        assertEquals(IngestResolutionService.STATE_NO_PATIENT, r.state());
        assertTrue("no candidate may leak from an invisible study", r.candidates().isEmpty());
    }

    /** Scoping can turn an ambiguous label into a usable suggestion. */
    @Test
    public void scopeNarrowsAmbiguityToASuggestion() {
        var r = with(List.of(match(1, 10, "M-001"), match(2, 20, "M-001")),
                Optional.of(visit(5))).resolve("M-001", DAY, Set.of(1));
        assertEquals(IngestResolutionService.STATE_SUGGESTED, r.state());
        assertEquals(10, r.single().orElseThrow().studySubjectId());
    }

    @Test
    public void aNullScopeMeansEveryStudy() {
        var r = with(List.of(match(7, 70, "M-001")), Optional.of(visit(5)))
                .resolve("M-001", DAY, null);
        assertEquals(IngestResolutionService.STATE_SUGGESTED, r.state());
    }

    /* ---------------- suggest() ---------------- */

    @Test
    public void suggestAnswersOnlyForTheOneClickCase() {
        assertTrue(with(List.of(match(1, 10, "M-001")), Optional.of(visit(5)))
                .suggest("M-001", DAY, null).isPresent());

        assertTrue("an ambiguous label must not auto-bind",
                with(List.of(match(1, 10, "M-001"), match(2, 20, "M-001")), Optional.of(visit(5)))
                        .suggest("M-001", DAY, null).isEmpty());

        assertTrue("a subject with no visit must not auto-bind",
                with(List.of(match(1, 10, "M-001")), Optional.empty())
                        .suggest("M-001", DAY, null).isEmpty());
    }

    /**
     * A lookup failure is not an answer. Reporting nopatient would tell the
     * operator something untrue about the register, so it propagates.
     */
    @Test(expected = IllegalStateException.class)
    public void aLookupFailurePropagatesRatherThanReadingAsNoPatient() {
        StudySubjectFinder finder = mock(StudySubjectFinder.class);
        when(finder.findByLabelAcrossStudies(eq("M-001")))
                .thenThrow(new IllegalStateException("database down"));
        new IngestResolutionService(finder).resolve("M-001", DAY, null);
    }
}
