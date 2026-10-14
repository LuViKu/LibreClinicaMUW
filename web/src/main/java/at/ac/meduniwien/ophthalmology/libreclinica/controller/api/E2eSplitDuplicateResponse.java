/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** DR-041 - the 409 body when an export with the same SHA-256 was already split. */
@Schema(name = "E2eSplitDuplicateResponse")
public record E2eSplitDuplicateResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean duplicate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The items the caller may see; may be empty.")
        List<Long> existingIngestItemIds
) {}
