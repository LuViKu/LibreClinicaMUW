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
 * Phase E.6 study-params — GET /api/v1/studies/{studyOid}/parameters
 * response body, also returned by PUT after the upsert.
 *
 * <p>The DTO surfaces the 18 {@code study_parameter_value} handles
 * seeded since the OC 2.5 / amethyst / 3.0 / 3.4 / 3.9 migrations,
 * plus {@code studyOid} for round-trip identity. That gives the SPA
 * exactly 19 wire fields (the playbook's "19" count), matching the
 * field count consistently across this DTO, the
 * {@link UpdateStudyParametersRequest} sibling, and the IT
 * assertions.
 *
 * <p>Each handle is surfaced as a String — the underlying column
 * {@code study_parameter_value.value} is {@code varchar(50)} and the
 * value space across handles mixes booleans ("true"/"false"),
 * enums ("required" / "optional" / "not_used"), and small ordinals
 * ("1" / "2" / "3" for {@code collectDob}). Pushing typing into the
 * controller would force a tagged-union shape and an i18n surface
 * for every conversion edge case — out of scope for this slice.
 *
 * <p>Values fall back to the {@code study_parameter.default_value}
 * column whenever a row is missing in {@code study_parameter_value}
 * — the legacy {@code StudyParameterConfig} bean has hard-coded
 * defaults too, but pulling from the DB-side default keeps the SPA
 * + JSP paths in sync the next time a default is bumped via
 * Liquibase.
 */
@Schema(name = "StudyParametersDto")
public record StudyParametersDto(
        String studyOid,
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
