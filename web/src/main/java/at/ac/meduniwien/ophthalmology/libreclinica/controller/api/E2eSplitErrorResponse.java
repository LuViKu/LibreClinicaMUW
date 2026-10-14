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

/** DR-041 - the body of a refused split upload (400, 404, 422, 502, 503): a message fit to show an operator. */
@Schema(name = "E2eSplitErrorResponse")
public record E2eSplitErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message
) {}
