/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

/**
 * One scheduled (or in-progress) study_event row that matches the
 * scan date for a candidate study_subject. Sibling to
 * {@link StudySubjectMatch}.
 *
 * <p>{@code matchPolicy} is reserved for future fuzzy-matching (e.g.
 * "within-3-days" / "next-scheduled"); for v1 the service emits only
 * {@code "same-day"}.
 *
 * <p>2026-06-23 — the candidate now anchors on {@code study_event}
 * (not {@code event_crf}). A planned-but-not-started visit has a
 * {@code studyEventId} but no {@code eventCrfId}; the commit endpoint
 * binds the new {@code retinal_inference_job} row against
 * {@code studyEventId} alone and leaves {@code event_crf_id} NULL
 * until the operator opens the CRF.
 *
 * <p>2026-06-24 user-feedback round — {@code dateStart} was
 * previously typed as {@link java.time.LocalDate}. Jackson's default
 * JSR-310 serializer emits LocalDate as a {@code [year, month, day]}
 * array unless WRITE_DATES_AS_TIMESTAMPS is disabled at the
 * ObjectMapper level; the SPA's uploader was rendering "[ 2025, 11,
 * 19 ]" verbatim because of that. Switching the record component to
 * a pre-formatted ISO {@code yyyy-MM-dd} string sidesteps the
 * ObjectMapper config dependency.
 *
 * @param studyEventId    study_event.study_event_id — always present
 *                        for a valid candidate; the visit the scan is
 *                        for
 * @param eventCrfId      event_crf.event_crf_id — present only when
 *                        the CRF has already been opened; may be
 *                        {@code null} for a scheduled visit
 * @param definitionLabel study_event_definition.name + sample_ordinal
 *                        (e.g. "V1 Inclusion")
 * @param dateStart       study_event.date_start as ISO {@code yyyy-MM-dd}
 * @param matchPolicy     descriptor for how the date match was scored
 */
public record EventCandidate(
        int studyEventId,
        Integer eventCrfId,
        String definitionLabel,
        String dateStart,
        String matchPolicy
) {
}
