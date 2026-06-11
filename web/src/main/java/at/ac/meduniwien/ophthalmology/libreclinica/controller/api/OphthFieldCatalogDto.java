/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 ophth-field-catalog — wire shape for one "pre-built"
 * ophthalmology field type. The CRF Library wizard renders the
 * catalog as a picker; on selection the wizard creates the
 * corresponding {@code Item}(s) with the canonical OID, label,
 * units, validation, and (when applicable) response set already
 * filled in — operators never have to choose an OID or type out
 * a German measurement-name by hand.
 *
 * <p>Seeded by {@code lc-muw-2026-06-11-ophth-field-catalog.xml}.
 *
 * <h2>Field shape</h2>
 *
 * <ul>
 *   <li>{@code code} — catalog identifier, e.g. {@code "BCVA_LETTERS"},
 *       {@code "IOP"}, {@code "REFRACTION"}. Drives the canonical item
 *       OID: bilateral entries produce {@code I_OPHTH_OD_<code>} and
 *       {@code I_OPHTH_OS_<code>}; non-bilateral entries produce
 *       {@code I_OPHTH_OU_<code>}.</li>
 *   <li>{@code labelDe} / {@code labelEn} — primary measurement name.</li>
 *   <li>{@code hintDe} / {@code hintEn} — optional helper text rendered
 *       below the label (typically range or unit hint).</li>
 *   <li>{@code bilateral} — true for paired (OD + OS) entries; false
 *       for single-eye (OU) entries.</li>
 *   <li>{@code dataType} — one of
 *       {@code "integer" | "real" | "string" | "select-one"}; matches
 *       the existing {@link CrfVersionAuthoringRequest.Item#dataType}
 *       vocabulary.</li>
 *   <li>{@code widget} — one of
 *       {@code "number-stepper" | "snellen" | "refraction" | "yesno" |
 *       "text" | "select-one"}; tells the SPA entry view which custom
 *       renderer to use (Phase F2). The generic {@code CrfItemWidget}
 *       remains the fallback for {@code "text"} and {@code "select-one"}.</li>
 *   <li>{@code unit} — short unit token rendered inline (e.g.
 *       {@code "mmHg"}, {@code "Buchst."}, {@code "µm"}). Null for
 *       categorical widgets.</li>
 *   <li>{@code minValue} / {@code maxValue} / {@code stepValue} — numeric
 *       validation + stepper increments. Null for non-numeric widgets.</li>
 *   <li>{@code placeholderText} — input placeholder hint (e.g.
 *       {@code "10–21"} for IOP).</li>
 *   <li>{@code conditionalOnCode} / {@code conditionalShowWhenValue} —
 *       show-when wiring. The catalog supports the
 *       "DONE? → REASON-if-no" pattern from the design's
 *       Spectralis OCT / Fundus rows.</li>
 *   <li>{@code responseOptions} — for {@code "yesno"} and
 *       {@code "select-one"} widgets, the {@code value|label} pairs
 *       (comma-separated) the operator can pick from. Null for free
 *       inputs.</li>
 *   <li>{@code modalityCode} — link to {@code modality.code} (Phase E.6
 *       study-nurse polish). When set, the per-eye baselines panel
 *       can read this field's values directly without an alias table —
 *       the canonical OID matches the modality registry by
 *       construction.</li>
 *   <li>{@code oidPrefix} — namespace token (default {@code "OPHTH"})
 *       used when materialising the item OID.</li>
 *   <li>{@code ordinal} — sort order in the wizard picker.</li>
 * </ul>
 */
@Schema(name = "OphthFieldCatalogDto")
public record OphthFieldCatalogDto(
        String code,
        String labelDe,
        String labelEn,
        String hintDe,
        String hintEn,
        boolean bilateral,
        String dataType,
        String widget,
        String unit,
        BigDecimal minValue,
        BigDecimal maxValue,
        BigDecimal stepValue,
        String placeholderText,
        String conditionalOnCode,
        String conditionalShowWhenValue,
        List<ResponseOption> responseOptions,
        String modalityCode,
        String oidPrefix,
        int ordinal
) {

    /**
     * One option in a {@code yesno} / {@code select-one} catalog entry's
     * response set. Mirrors the storage format
     * ({@code value|label,value|label,…}) decomposed for the wire. The
     * label is the German display string; the SPA picker handles
     * locale-specific overrides on top of the catalog DTO when present
     * via vue-i18n keys.
     */
    @Schema(name = "OphthFieldCatalogResponseOption")
    public record ResponseOption(String value, String label) {}
}
