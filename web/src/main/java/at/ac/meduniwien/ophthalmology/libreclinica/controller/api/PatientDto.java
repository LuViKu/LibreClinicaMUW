/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 study-nurse polish — patient list-row wire shape.
 *
 * <p>One row per human in the operator's visible-study set. A single
 * {@code subject_id} (one person) is de-duped across multiple
 * {@code study_subject} rows (multiple participations) — each
 * participation is surfaced as one {@link Enrolment} entry.
 *
 * <p>{@code uniqueIdentifier} is the {@code subject.unique_identifier}
 * column (the institutional patient identifier, sometimes a hospital
 * MRN). May be {@code null} for older subjects pre-dating the field.
 *
 * <p>{@code yearOfBirth} carries the year portion of
 * {@code subject.date_of_birth}; the SPA composes "1962 (F)" in the
 * list view without leaking the full DOB.
 */
@Schema(name = "PatientDto")
public record PatientDto(
        int subjectId,
        String uniqueIdentifier,
        String gender,
        Integer yearOfBirth,
        List<Enrolment> enrolments
) {

    /**
     * Single (patient, study) participation. {@code label} is the
     * {@code study_subject.label} (operator-visible identifier within
     * the study, e.g. {@code "M-001"}). {@code studyEye} is the
     * per-protocol scope — one of {@code "OD" / "OS" / "OU"} or null
     * when the row has been downgraded out of scope (e.g. by a cohort
     * transition).
     *
     * <p>{@code lastVisitAt} is the most recent
     * {@code study_event.date_start} for this enrolment as an ISO
     * date-time; null when the subject hasn't had any visits scheduled.
     */
    @Schema(name = "PatientEnrolmentDto")
    public record Enrolment(
            String studyOid,
            String studyName,
            String label,
            String studyEye,
            String enrolledOn,
            String lastVisitAt
    ) {}
}
