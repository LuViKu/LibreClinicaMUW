/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

/**
 * P3.0 — the one answer to "whose visit is this file for?".
 *
 * <p>Three places asked that question and answered it three ways: the OCT
 * portal, the image portal, and the reconciliation inbox. They agreed on the
 * vocabulary and disagreed on the rules — one treated a single subject with no
 * same-day visit as {@code novisit}, another as {@code suggested}; one filtered
 * by the caller's visible studies, two did not. An operator using both portals
 * on the same day got different answers about the same patient.
 *
 * <p>The states, and what each means to the operator:
 *
 * <ul>
 *   <li>{@code nopatient} — nothing matched that label. Deliberately the same
 *       answer as "matched, but in a study you may not see", so an
 *       unauthenticated portal cannot be used to probe the register.</li>
 *   <li>{@code ambiguous} — more than one subject carries that label. Filing
 *       against either would be a guess, so the operator chooses.</li>
 *   <li>{@code suggested} — exactly one subject, with a visit on the stated
 *       date. This is the one-click case.</li>
 *   <li>{@code novisit} — exactly one subject, but no visit on that date. The
 *       subject is known; the file still needs a visit.</li>
 * </ul>
 *
 * <p>Visibility is a parameter rather than a policy baked in here: the staff
 * inbox restricts to what the session can see, while a portal restricts to the
 * studies the institution configured for it. Passing {@code null} means no
 * restriction, which is what a single-study development instance wants.
 */
public class IngestResolutionService {

    /** One subject that matched, with the visit found for the date if any. */
    public record ResolveCandidate(int studyId, String studyName, String studyOid,
                                   int studySubjectId, String subjectLabel, String siteName,
                                   EventCandidate matchingEvent) {}

    /** The answer: a state, and the candidates behind it. */
    public record Resolution(String state, List<ResolveCandidate> candidates) {

        /** The single candidate, when there is exactly one. */
        public Optional<ResolveCandidate> single() {
            return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        }

        /** True when one subject and one visit were found — the one-click case. */
        public boolean isSuggested() {
            return STATE_SUGGESTED.equals(state);
        }
    }

    public static final String STATE_NO_PATIENT = "nopatient";
    public static final String STATE_AMBIGUOUS = "ambiguous";
    public static final String STATE_SUGGESTED = "suggested";
    public static final String STATE_NO_VISIT = "novisit";

    private static final Resolution NOTHING =
            new Resolution(STATE_NO_PATIENT, List.of());

    private final StudySubjectFinder finder;

    public IngestResolutionService(StudySubjectFinder finder) {
        this.finder = finder;
    }

    /**
     * @param label            the subject label an operator typed or a device sent
     * @param date             the acquisition date, or null when it is unknown
     * @param visibleStudyIds  studies the caller may resolve against, or
     *                         {@code null} for every study
     */
    public Resolution resolve(String label, LocalDate date, Set<Integer> visibleStudyIds) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) return NOTHING;

        List<StudySubjectMatch> matches;
        try {
            matches = finder.findByLabelAcrossStudies(trimmed);
        } catch (RuntimeException lookupFailed) {
            // A lookup failure is not "no such patient": answering nopatient
            // would tell the operator something untrue about the register.
            throw lookupFailed;
        }
        if (matches == null || matches.isEmpty()) return NOTHING;

        List<ResolveCandidate> candidates = new ArrayList<>(matches.size());
        int withVisit = 0;
        for (StudySubjectMatch m : matches) {
            if (visibleStudyIds != null && !visibleStudyIds.contains(m.studyId())) continue;
            EventCandidate ev = date == null
                    ? null
                    : finder.findEventOnDate(m.studySubjectId(), date).orElse(null);
            if (ev != null) withVisit++;
            candidates.add(new ResolveCandidate(
                    m.studyId(), m.studyName(), m.studyOid(),
                    m.studySubjectId(), m.subjectLabel(), m.siteName(), ev));
        }

        // Everything the label matched is out of scope. Same answer as no match
        // at all: the caller learns nothing about what it cannot see.
        if (candidates.isEmpty()) return NOTHING;

        if (candidates.size() > 1) {
            return new Resolution(STATE_AMBIGUOUS, List.copyOf(candidates));
        }
        return new Resolution(withVisit == 1 ? STATE_SUGGESTED : STATE_NO_VISIT,
                List.copyOf(candidates));
    }

    /** Convenience for callers with an ISO date string that may be absent or malformed. */
    public Resolution resolve(String label, String isoDate, Set<Integer> visibleStudyIds) {
        return resolve(label, parseIsoOrNull(isoDate), visibleStudyIds);
    }

    /**
     * The one-click target, when there is one: exactly one visible subject with
     * exactly one visit on the date. Returns empty in every other state, so a
     * caller cannot accidentally auto-bind an ambiguous match.
     */
    public Optional<ResolveCandidate> suggest(String label, LocalDate date,
                                              Set<Integer> visibleStudyIds) {
        Resolution r = resolve(label, date, visibleStudyIds);
        return r.isSuggested() ? r.single() : Optional.empty();
    }

    private static LocalDate parseIsoOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception notADate) {
            return null;
        }
    }
}
