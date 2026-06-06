/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 study-params — PUT /api/v1/studies/{studyOid}/parameters
 * request body. Mirror of {@link StudyParametersDto} minus
 * {@code studyOid} (that lives in the path), with every field
 * nullable.
 *
 * <p>Same null-means-leave-unchanged contract as
 * {@link UpdateStudyRequest}. The controller diffs each supplied
 * handle independently against the current persisted value and
 * writes one audit row per actually-changed handle (matches the
 * audit fan-out pattern in {@link StudiesApiController#update}).
 *
 * <p>Validation is enum-allow-list per handle (see controller
 * {@code validateUpdateShape}). Empty strings ("") are rejected;
 * pass {@code null} to leave a field untouched.
 */
@Schema(name = "UpdateStudyParametersRequest")
public record UpdateStudyParametersRequest(
        String subjectIdGeneration,
        String subjectIdPrefixSuffix,
        String subjectPersonIdRequired,
        String personIdShownOnCRF,
        String collectDob,
        String genderRequired,
        String eventLocationRequired,
        String discrepancyManagement,
        String interviewerNameRequired,
        String interviewerNameDefault,
        String interviewerNameEditable,
        String interviewDateRequired,
        String interviewDateDefault,
        String interviewDateEditable,
        String secondaryLabelViewable,
        String adminForcedReasonForChange,
        String participantPortal,
        String randomization
) {}
