/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.time.LocalDate;

/**
 * One scheduled (or in-progress) event_crf row that matches the scan
 * date for a candidate study_subject. Sibling to
 * {@link StudySubjectMatch}.
 *
 * <p>{@code matchPolicy} is reserved for future fuzzy-matching (e.g.
 * "within-3-days" / "next-scheduled"); for v1 the service emits only
 * {@code "same-day"}.
 *
 * @param eventCrfId      event_crf.event_crf_id — what /commit binds the
 *                        new retinal_inference_job row against
 * @param definitionLabel study_event_definition.name + sample_ordinal
 *                        (e.g. "V1 Inclusion")
 * @param dateStart       study_event.date_start (date portion)
 * @param matchPolicy     descriptor for how the date match was scored
 */
public record EventCandidate(
        int eventCrfId,
        String definitionLabel,
        LocalDate dateStart,
        String matchPolicy
) {
}
