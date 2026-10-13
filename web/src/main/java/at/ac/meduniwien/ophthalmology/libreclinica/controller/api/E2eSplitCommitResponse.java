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

/**
 * DR-041 - the answer to {@code POST .../upload/commit} with {@code split=true} (201): the
 * multimodal export was converted to one DICOM per kept series.
 */
@Schema(name = "E2eSplitCommitResponse")
public record E2eSplitCommitResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "SHA-256 of the uploaded .e2e.")
        String sha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "none, empty, matches or differs: the file's label against the folder.")
        String headerPatient,
        @Schema(nullable = true,
                description = "The matched subject's own label; null when the file resolved to no single visible subject.")
        String subjectLabel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True when no item was bound to a visit.")
        boolean parked,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One item per kept series.")
        List<E2eSplitItemDto> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The seriesKeys left out by excludeSeries: not converted, not stored, no item.")
        List<String> excluded,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Always e2e (what the client uploaded).")
        String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Always e2e.")
        String format
) {}
