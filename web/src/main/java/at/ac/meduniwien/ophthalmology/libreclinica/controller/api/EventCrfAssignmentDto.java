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
 * Phase E A8.3 — wire shape for one {@code event_definition_crf} row.
 *
 * <p>Returned by the assignment endpoints (POST / PUT) and inlined in
 * a future GET /event-definitions/{sedOid}/crfs surface that an A8.3
 * follow-up may add. The fields mirror
 * {@link EventCrfAssignmentRequest} plus identity (oids of the
 * referenced CRF + default version + the assignment's own row).
 */
@Schema(name = "EventCrfAssignmentDto")
public record EventCrfAssignmentDto(
        String crfOid,
        String crfName,
        String defaultVersionOid,
        String defaultVersionName,
        boolean required,
        boolean doubleEntry,
        boolean decisionCondition,
        boolean electronicSignature,
        boolean hideCrf,
        String sourceDataVerification,
        boolean participantForm,
        boolean allowAnonymousSubmission,
        String submissionUrl,
        boolean offline,
        String status
) {}
