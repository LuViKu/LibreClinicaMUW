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
 * DR-040 phase 2 — the optional body of
 * {@code PUT /api/v1/admin/locations/{id}/users/{username}}.
 *
 * <p>An absent body, {@code {}} and {@code {"canManageSources":null}} leave the
 * Standort-Admin right as it is: unchanged on an existing assignment, false on
 * a new one. {@code true} grants it and {@code false} takes it away.
 */
@Schema(name = "LocationUserWriteRequest")
public record LocationUserWriteRequest(
        @Schema(nullable = true, description = "The Standort-Admin right at this location: may issue, revoke and replace "
                + "the tokens of its uploaders. Absent or null keeps the current value (false for a new assignment).")
        Boolean canManageSources
) {}
