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
 * <p>Milestone B extends the Milestone A vertical slice to cover the
 * full non-formula response-type taxonomy + per-item validation +
 * default values + the cross-CRF response-set catalog. Milestones C/D
 * layer on show-when, calculation expressions, item groups and
 * multi-language labels.
 *
 * <p>The controller synthesises an HSSF workbook from this payload and
 * hands it to {@link CrfSpreadsheetParserService#parseAndPersist} —
 * zero parity drift with the XLS path. {@link CrfJsonToWorkbookAdapter}
 * targets the {@code SpreadSheetTableRepeating} cell layout (27
 * columns) so the full taxonomy is reachable through the same parser
 * the XLS upload uses.
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
     * One item in a section.
     *
     * <p>Milestone B scope:
     * <ul>
     *   <li>{@code dataType} covers {@code ST}, {@code INTEGER}/{@code INT},
     *       {@code REAL}, {@code DATE}, {@code PDATE}, {@code FILE},
     *       {@code BL}.</li>
     *   <li>{@code responseSet} covers every non-formula
     *       {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType}
     *       — TEXT, TEXTAREA, RADIO, SELECT (single-select), SELECTMULTI
     *       (multi-select), CHECKBOX, FILE. CALCULATION /
     *       INSTANT-CALCULATION / GROUP-CALCULATION are deferred to
     *       Milestone C.</li>
     *   <li>{@code validation} carries the optional regexp clause +
     *       a per-item error message.</li>
     *   <li>{@code defaultValue} pre-populates the data-entry field.</li>
     * </ul>
     */
    @Schema(name = "CrfVersionAuthoringRequest.Item")
    public record Item(
            String name,
            String oid,
            String descriptionLabel,
            String leftItemText,
            String rightItemText,
            String units,
            String dataType,
            String defaultValue,
            boolean required,
            ResponseSet responseSet,
            Validation validation
    ) {}

    /**
     * Inline or by-reference response set on an item.
     *
     * <p>The shape models both the inline-author flow (operator types
     * options into the editor) and the catalog-pick flow (operator
     * selects a previously-defined label). When {@code ref} is set, the
     * controller resolves the label against the cross-CRF catalog
     * (every existing {@code response_set} row carrying that label).
     * Otherwise the inline {@code type} / {@code label} / {@code options}
     * fields populate the synthesised workbook directly.
     *
     * <p>Wire shape for inline options:
     * <pre>
     * {
     *   "type": "single-select",
     *   "label": "yes_no",
     *   "options": [
     *     { "text": "Yes", "value": "1" },
     *     { "text": "No",  "value": "0" }
     *   ]
     * }
     * </pre>
     *
     * <p>{@code type} accepts the {@link
     * at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType}
     * canonical names ({@code "text"}, {@code "textarea"}, {@code "radio"},
     * {@code "single-select"}, {@code "multi-select"}, {@code "checkbox"},
     * {@code "file"}). CALCULATION variants are rejected at the
     * shape-validation layer in Milestone B.
     */
    @Schema(name = "CrfVersionAuthoringRequest.ResponseSet")
    public record ResponseSet(
            String type,
            String label,
            List<Option> options,
            ResponseSetRef ref
    ) {}

    @Schema(name = "CrfVersionAuthoringRequest.ResponseSet.Option")
    public record Option(
            String text,
            String value
    ) {}

    /**
     * Reference to a catalog response-set by label. The controller
     * resolves the label against the distinct-tuples view of existing
     * {@code response_set} rows and re-materialises an inline definition
     * before synthesising the workbook (the parser is the persistence
     * authority — each version gets its own {@code response_set} row).
     */
    @Schema(name = "CrfVersionAuthoringRequest.ResponseSetRef")
    public record ResponseSetRef(
            String label
    ) {}

    /**
     * Optional per-item validation. {@code regexp} is the raw regular
     * expression body (no {@code regexp:/.../} wrapper — the adapter
     * adds it when synthesising the workbook). {@code errorMessage} is
     * shown to the operator when validation fails.
     */
    @Schema(name = "CrfVersionAuthoringRequest.Validation")
    public record Validation(
            String regexp,
            String errorMessage
    ) {}
}
