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
 * default values + the cross-CRF response-set catalog. Milestone C
 * layers on show-when conditional display, the three CALCULATION
 * response types (calculation / instant-calculation / group-calculation),
 * flat repeating item groups and layout hints (header / subHeader /
 * pageBreak). Milestone D will fold in multi-language labels.
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
     *   <li>{@code responseSet} covers every
     *       {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType}
     *       — TEXT, TEXTAREA, RADIO, SELECT (single-select), SELECTMULTI
     *       (multi-select), CHECKBOX, FILE plus (M-C) CALCULATION /
     *       INSTANT-CALCULATION / GROUP-CALCULATION.</li>
     *   <li>{@code validation} carries the optional regexp clause +
     *       a per-item error message.</li>
     *   <li>{@code defaultValue} pre-populates the data-entry field.</li>
     * </ul>
     *
     * <p>Milestone C extensions:
     * <ul>
     *   <li>{@code showItem} — OpenClinica expression that drives
     *       conditional display. When non-null the item is hidden until
     *       the expression evaluates to true. Null means always show.
     *       The legacy parser stores this as
     *       {@code SIMPLE_CONDITIONAL_DISPLAY = parentItem,value,message}
     *       (column 26) plus a {@code ITEM_DISPLAY_STATUS = "Hide"}
     *       toggle (column 25).</li>
     *   <li>{@code parentItemOid} — the OID of the item the
     *       {@code showItem} expression scopes against (its value is
     *       evaluated when the parent changes). Null when
     *       {@code showItem} is null. Validated to exist in the draft
     *       and to precede this item in section order.</li>
     *   <li>{@code header} / {@code subHeader} — layout hints (columns
     *       7 and 8 on the Items sheet).</li>
     *   <li>{@code pageBreak} — when true the data-entry UI starts a
     *       new page at this item; rendered via the
     *       {@code RESPONSE_LAYOUT} column (17) as the string
     *       {@code "page-break"}.</li>
     *   <li>{@code groupLabel} — when non-null the item joins a flat
     *       repeating group with that label. All items sharing the
     *       same label form one group; the adapter emits a row on the
     *       Groups sheet per distinct label.</li>
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
            Validation validation,
            String showItem,
            String parentItemOid,
            String header,
            String subHeader,
            boolean pageBreak,
            String groupLabel,
            /**
             * Phase E.6 ophth-field-catalog (2026-06-11): optional
             * reference into {@code ophth_field_catalog.code}. When set,
             * the backend's {@link CrfJsonToWorkbookAdapter} loads the
             * matching catalog entry and back-fills any blank item
             * fields (descriptionLabel, leftItemText, rightItemText,
             * units, dataType, responseSet) from the catalog row before
             * synthesising the workbook. Caller-supplied fields always
             * win over catalog defaults; null means the operator
             * authored the item free-form (legacy path, no catalog).
             */
            String catalogCode
    ) {
        /**
         * Backward-compat constructor — keeps the 17-arg callers (most
         * of the existing test suite + every {@code new
         * CrfVersionAuthoringRequest.Item(…)} site that pre-dates F3)
         * compiling without surgery. {@code catalogCode} defaults to
         * {@code null} which routes the item through the legacy
         * free-form path.
         */
        public Item(
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
                Validation validation,
                String showItem,
                String parentItemOid,
                String header,
                String subHeader,
                boolean pageBreak,
                String groupLabel
        ) {
            this(name, oid, descriptionLabel, leftItemText, rightItemText, units,
                    dataType, defaultValue, required, responseSet, validation,
                    showItem, parentItemOid, header, subHeader, pageBreak, groupLabel,
                    /* catalogCode */ null);
        }
    }

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
     * {@code "file"}). Milestone C extends the accepted vocabulary with
     * the calculation variants: {@code "calculation"} (post-save
     * evaluation against persisted item_data), {@code "instant-calculation"}
     * (form-time evaluation), and {@code "group-calculation"} (across-group
     * aggregate). For all three calculation variants the {@code options}
     * field carries the single formula string (text and value are both
     * set to the formula source — the legacy parser convention).
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
