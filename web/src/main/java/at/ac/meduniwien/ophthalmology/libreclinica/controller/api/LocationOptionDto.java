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
 * DR-040 phase 4 — a location a study or site may be placed at, as the
 * create and edit forms offer it ({@code GET /api/v1/locations}). The address
 * is what a site's facility address is copied from.
 */
@Schema(name = "LocationOptionDto")
public record LocationOptionDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        String street,
        String city,
        String zip,
        String country
) {}
