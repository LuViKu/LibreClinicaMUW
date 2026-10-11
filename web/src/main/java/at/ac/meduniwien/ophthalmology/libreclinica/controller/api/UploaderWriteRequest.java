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
 * DR-040 phase 2 — the body of {@code POST /api/v1/locations/{locationId}/uploaders}
 * (issue) and {@code PUT /api/v1/uploaders/{id}} (rename): the name that tells
 * the uploader apart from the others at its location.
 */
@Schema(name = "UploaderWriteRequest")
public record UploaderWriteRequest(
        @Schema(description = "Required, 1 to 64 characters, unique at the location without regard to case "
                + "(a revoked uploader keeps its name). No control or invisible formatting characters.",
                example = "Clarus, room 12")
        String displayName
) {}
