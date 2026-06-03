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
 * Phase E.6 — wire contract for the JSON-body variant of
 * {@code POST /pages/api/v1/crfs/{crfOid}/versions}.
 *
 * <p>Milestone A vertical slice: a minimal payload that produces a
 * {@code crf_version} row + items + a TEXT response set semantically
 * identical to what the XLS-upload endpoint produces. Milestones B/C/D
 * extend this DTO with the full XLS feature surface (full type
 * taxonomy, show-when, calculations, item groups, multi-language).
 *
 * <p>The controller synthesises an HSSF workbook from this payload and
 * hands it to {@link CrfSpreadsheetParserService#parseAndPersist} —
 * zero parity drift with the XLS path.
 */
@Schema(name = "CrfVersionAuthoringRequest")
public record CrfVersionAuthoringRequest(
        String versionName,
        String versionDescription,
        String revisionNotes,
        List<Section> sections
) {
    /**
     * One section in the authored CRF. {@code label} is the short
     * identifier referenced by items; {@code title} is the
     * human-readable heading shown in the data-entry UI.
     */
    @Schema(name = "CrfVersionAuthoringRequest.Section")
    public record Section(
            String label,
            String title,
            String instructions,
            int ordinal,
            List<Item> items
    ) {}

    /**
     * One item in a section. {@code dataType} is restricted to
     * {@code "ST"} (character string), {@code "INTEGER"} (integer) and
     * {@code "BL"} (boolean) in Milestone A. Response type is always
     * TEXT (Milestones B/C/D introduce the full taxonomy).
     */
    @Schema(name = "CrfVersionAuthoringRequest.Item")
    public record Item(
            String name,
            String oid,
            String descriptionLabel,
            String leftItemText,
            String dataType,
            boolean required
    ) {}
}
