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
import java.util.List;

/**
 * Phase E.6 {@code crf-library} cluster — references to a CRF version
 * that block a hard-remove.
 *
 * <p>Returned as the 409 body on
 * {@code DELETE /api/v1/crfs/{crfOid}/versions/{versionOid}} when the
 * version is still referenced by:
 * <ul>
 *   <li>One or more {@code event_definition_crf} rows that name it as
 *       {@code default_version_id} (each carries an
 *       {@link EventDefinitionReference} so the SPA can deep-link the
 *       operator to the offending event definition's reassign dialog).</li>
 *   <li>One or more {@code event_crf} rows — i.e. an existing event-CRF
 *       slot has historic data entered against the version. We surface
 *       the count + a sample of subject labels so the operator knows
 *       what they'd lose; we never expose the full subject list because
 *       that is potentially sensitive.</li>
 * </ul>
 *
 * <p>The shape is intentionally read-only — there's no PATCH on this
 * type. Operators resolve the references in the legacy / SPA event-def
 * editor and retry the hard-remove.
 */
@Schema(name = "CrfVersionUsageReport",
        description = "Why a CRF version cannot be hard-removed.")
public record VersionUsageReport(
        String crfOid,
        String versionOid,
        String versionName,
        List<EventDefinitionReference> blockingEventDefinitions,
        int eventCrfCount,
        List<String> sampleSubjectLabels
) {

    /**
     * One {@code event_definition_crf} row that names the doomed version
     * as its default. Includes the parent study's OID so the SPA can
     * route to the correct study scope without an extra round-trip.
     */
    @Schema(name = "CrfVersionUsageEventDefinitionRef")
    public record EventDefinitionReference(
            String studyOid,
            String sedOid,
            String sedName
    ) {}
}
