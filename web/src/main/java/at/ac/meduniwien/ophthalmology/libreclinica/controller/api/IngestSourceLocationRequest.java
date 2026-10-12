/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DR-040 phase 2 — the body of {@code PUT /api/v1/admin/ingest-sources/{id}/location}.
 *
 * <p>{@code locationId} must be present: a number assigns the source to that
 * location, an explicit {@code null} takes it back to "not assigned". An empty
 * object is refused rather than read as "unassign", so a client that forgets
 * the field cannot unassign a source by accident.
 */
@Schema(name = "IngestSourceLocationRequest")
public record IngestSourceLocationRequest(
        @JsonProperty(value = "locationId", required = true)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "An available location, or null for \"not assigned\" (refused for an uploader).")
        Integer locationId
) {}
