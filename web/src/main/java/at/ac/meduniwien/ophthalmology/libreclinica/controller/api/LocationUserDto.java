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
 * DR-040 — a user explicitly assigned to a location, in
 * {@link LocationDetailDto#users()}: technicians and photographers, who work
 * at a place without a role on a site there. A user who reaches the location
 * through a role on a site is not listed.
 *
 * <p>{@code displayName} is "First Last" (the user name when the account has
 * neither). {@code canManageSources} is the Standort-Admin right of a later
 * phase; every assignment made through this API leaves it false.
 */
@Schema(name = "LocationUserDto")
public record LocationUserDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canManageSources
) {}
