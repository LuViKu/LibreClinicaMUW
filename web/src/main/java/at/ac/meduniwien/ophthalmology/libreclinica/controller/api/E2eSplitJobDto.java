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

/** DR-041 - a retinal-inference job started for an OCT volume of a split upload. */
@Schema(name = "E2eSplitJobDto")
public record E2eSplitJobDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long jobId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "fluid, ga, onl, pr, layers, ...")
        String task,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "queued or remote_pending at upload")
        String status
) {}
