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

/** DR-041 - {@code GET .../upload/preflight?sha256=..&split=true}: is this export already split? */
@Schema(name = "E2eSplitPreflightResponse")
public record E2eSplitPreflightResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "True when items from an export with this SHA-256 exist (also after its source file was deleted).")
        boolean exists,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Always true: this is the split preflight.")
        boolean split,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The items the caller may see; may be empty although exists is true.")
        List<Long> existingIngestItemIds
) {}
