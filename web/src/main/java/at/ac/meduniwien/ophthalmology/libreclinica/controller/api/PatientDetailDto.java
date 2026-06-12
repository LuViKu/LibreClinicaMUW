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
 * Phase E.6 study-nurse polish — patient detail wire shape.
 *
 * <p>Single-call shape for the patient overview page: identity +
 * enrolments (same shape as {@link PatientDto.Enrolment}) +
 * cross-study eye-cohort transitions. Used by the SPA's patient
 * detail view in place of N round-trips against the list endpoint.
 *
 * <p>{@code eyeTransitions} carries the full per-eye hand-off
 * history across every protocol the human participated in.
 * Chronological by transition timestamp. The list mirrors the shape
 * of {@link EyeTransitionDto} with the addition of from/to
 * (study OID + name + label) so the SPA can render
 * "iAMD-001 OD → GA-001 OD on 2026-06-07" inline without
 * a second fetch.
 */
@Schema(name = "PatientDetailDto")
public record PatientDetailDto(
        int subjectId,
        String uniqueIdentifier,
        String gender,
        Integer yearOfBirth,
        String firstName,
        String lastName,
        String dateOfBirth,
        List<PatientDto.Enrolment> enrolments,
        List<EyeTransition> eyeTransitions
) {

    /**
     * One row per per-eye transition. Both directions of the same
     * transition (source + target) collapse into one row here — the
     * SPA renders the arrow inline, so it doesn't need two rows.
     */
    @Schema(name = "PatientEyeTransitionDto")
    public record EyeTransition(
            int transitionId,
            String eye,
            String eventAt,
            String fromStudyOid,
            String fromStudyName,
            String fromLabel,
            String toStudyOid,
            String toStudyName,
            String toLabel,
            String reason
    ) {}
}
