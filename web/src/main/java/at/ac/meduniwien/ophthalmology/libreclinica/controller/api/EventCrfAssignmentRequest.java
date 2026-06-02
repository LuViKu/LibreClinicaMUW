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
 * Phase E A8.3 — assign a CRF (with a default version) to an event
 * definition, or update the assignment options.
 *
 * <p>Used by both POST (attach new) and PUT (update existing) flows.
 * Maps to the {@code event_definition_crf} table.
 *
 * <p>{@code crfOid} is required on POST (the CRF being attached) but
 * ignored on PUT (the path variable carries it). {@code defaultVersionOid}
 * is required on both — the legacy model requires every assignment to
 * pin a default version that data entry starts on.
 *
 * <p>Boolean fields default to {@code false} when omitted.
 * {@code sourceDataVerification} must be one of {@code AllREQUIRED} /
 * {@code PARTIALREQUIRED} / {@code NOTREQUIRED} / {@code NOTAPPLICABLE}
 * (per legacy {@code SourceDataVerification} enum).
 */
@Schema(name = "EventCrfAssignmentRequest")
public record EventCrfAssignmentRequest(
        String crfOid,
        String defaultVersionOid,
        Boolean required,
        Boolean doubleEntry,
        Boolean decisionCondition,
        Boolean electronicSignature,
        Boolean hideCrf,
        String sourceDataVerification,
        Boolean participantForm,
        Boolean allowAnonymousSubmission,
        String submissionUrl,
        Boolean offline
) {}
