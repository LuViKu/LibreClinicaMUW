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
 * DR-041 - one ingest item made from a series of a split multimodal {@code .e2e}: an ordinary DICOM
 * item with its own stored file, which {@code seriesKey} / {@code seriesModality} tie back to the
 * series of the export.
 */
@Schema(name = "E2eSplitItemDto")
public record E2eSplitItemDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ingestItemId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The converter's opaque key of the series within the export.")
        String seriesKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "OCT, OCT_LINE, IR, BAF, BR, IRAF, QAF, ICGA, FA or OTHER.")
        String seriesModality,
        @Schema(nullable = true, description = "OD or OS; null when the export names no eye.")
        String laterality,
        @Schema(nullable = true, description = "ISO date the series was acquired.")
        String acquisitionDate,
        @Schema(nullable = true, description = "Number of frames in the series' DICOM.")
        Integer frames,
        @Schema(nullable = true, description = "The study's catalogue entry; null while parked or unmatched.")
        Integer imagingModalityId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "BOUND or UNBOUND (parked in the inbox).")
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Analysis jobs started for an OCT volume.")
        List<E2eSplitJobDto> jobs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "True when the same picture was already here under another label (parked).")
        boolean heldBack,
        @Schema(nullable = true, description = "The earlier item that shows the same picture, when held back.")
        Long sameImageAs
) {}
