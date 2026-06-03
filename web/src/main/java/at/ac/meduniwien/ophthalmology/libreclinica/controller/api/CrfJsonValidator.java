/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Service;

/**
 * Phase E.6 Milestone B — JSON-side validation for the CRF authoring
 * wire contract.
 *
 * <p>The shape-level checks here run <em>before</em> the workbook
 * adapter materialises a temp file. They catch the cheap rejects
 * (missing fields, illegal type/response-set combinations,
 * default-value-vs-membership) so the operator gets a fast, structured
 * 400 response instead of the parser's lower-level error messages.
 *
 * <p>Both the persist endpoint (POST {@code /versions}) and the dry-run
 * endpoint (POST {@code /versions:preview}) feed the request through
 * this validator before touching the parser. Same code, same failures
 * — so {@code :preview} cannot diverge from the real submit path.
 *
 * <p>This validator is purely about the JSON shape and per-item
 * sanity. Cross-cutting checks the parser owns (duplicate
 * {@code item_name} across CRF versions, regexp syntax inside the
 * legacy escape conventions, etc.) stay with the parser — running them
 * here would duplicate logic.
 */
@Service
public class CrfJsonValidator {

    /**
     * Accepted item data-type tokens — short and long names both
     * tolerated. Mirrors the canonical map in
     * {@link CrfJsonToWorkbookAdapter}.
     */
    private static final Set<String> ALLOWED_DATA_TYPES = Set.of(
            "ST", "INTEGER", "INT", "REAL", "DATE", "PDATE", "FILE", "BL", "BOOLEAN");

    /** Data types that <em>are</em> numeric (regex sanity allows numerics). */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "INT", "INTEGER", "REAL");

    /**
     * Run the full shape + per-branch validation. Returns a flat
     * list of field-keyed errors — empty if the payload passes.
     */
    public List<SubjectsApiController.ValidationErrorBody.FieldError> validate(
            CrfVersionAuthoringRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        validateVersionName(body, out);
        if (body.sections() == null || body.sections().isEmpty()) {
            out.add(fe("sections", "At least one section is required"));
            return out;
        }
        Set<String> seenSectionLabels = new HashSet<>();
        Set<String> seenItemNames = new HashSet<>();
        int sectionIdx = 0;
        for (CrfVersionAuthoringRequest.Section section : body.sections()) {
            validateSection(section, sectionIdx, seenSectionLabels, seenItemNames, out);
            sectionIdx++;
        }
        return out;
    }

    private void validateVersionName(CrfVersionAuthoringRequest body,
                                     List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String vname = body.versionName() == null ? "" : body.versionName().trim();
        if (vname.isEmpty()) {
            out.add(fe("versionName", "versionName is required"));
        } else if (vname.length() > 255) {
            out.add(fe("versionName", "versionName must be 255 characters or fewer"));
        }
    }

    private void validateSection(CrfVersionAuthoringRequest.Section section,
                                 int sectionIdx,
                                 Set<String> seenSectionLabels,
                                 Set<String> seenItemNames,
                                 List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String prefix = "sections[" + sectionIdx + "]";
        String label = section == null || section.label() == null ? "" : section.label().trim();
        String title = section == null || section.title() == null ? "" : section.title().trim();
        if (label.isEmpty()) {
            out.add(fe(prefix + ".label", "Section label is required"));
        } else if (!seenSectionLabels.add(label)) {
            out.add(fe(prefix + ".label", "Duplicate section label '" + label + "'"));
        }
        if (title.isEmpty()) {
            out.add(fe(prefix + ".title", "Section title is required"));
        }
        if (section == null || section.items() == null || section.items().isEmpty()) {
            out.add(fe(prefix + ".items", "Section must contain at least one item"));
            return;
        }
        int itemIdx = 0;
        for (CrfVersionAuthoringRequest.Item item : section.items()) {
            validateItem(item, sectionIdx, itemIdx, seenItemNames, out);
            itemIdx++;
        }
    }

    private void validateItem(CrfVersionAuthoringRequest.Item item,
                              int sectionIdx,
                              int itemIdx,
                              Set<String> seenItemNames,
                              List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String iPrefix = "sections[" + sectionIdx + "].items[" + itemIdx + "]";
        // ---- item name ----
        String iname = item == null || item.name() == null ? "" : item.name().trim();
        if (iname.isEmpty()) {
            out.add(fe(iPrefix + ".name", "Item name is required"));
        } else if (!iname.matches("\\w+")) {
            out.add(fe(iPrefix + ".name", "Item name must contain only letters, digits and underscores"));
        } else if (!seenItemNames.add(iname)) {
            out.add(fe(iPrefix + ".name", "Duplicate item name '" + iname + "'"));
        }
        // ---- description ----
        String desc = item == null || item.descriptionLabel() == null ? "" : item.descriptionLabel().trim();
        if (desc.isEmpty()) {
            out.add(fe(iPrefix + ".descriptionLabel", "Description label is required"));
        }
        // ---- data type ----
        String dataType = item == null || item.dataType() == null
                ? "" : item.dataType().trim().toUpperCase(Locale.ROOT);
        if (dataType.isEmpty()) {
            out.add(fe(iPrefix + ".dataType", "Data type is required"));
        } else if (!ALLOWED_DATA_TYPES.contains(dataType)) {
            out.add(fe(iPrefix + ".dataType",
                    "Data type must be one of ST, INTEGER/INT, REAL, DATE, PDATE, FILE, BL"));
        }
        if (item == null) return;
        // ---- response set ----
        validateResponseSet(item, iPrefix, dataType, out);
        // ---- validation regexp ----
        validateValidation(item, iPrefix, dataType, out);
        // ---- default value sanity ----
        validateDefaultValue(item, iPrefix, dataType, out);
    }

    private void validateResponseSet(CrfVersionAuthoringRequest.Item item,
                                     String iPrefix,
                                     String dataType,
                                     List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        CrfVersionAuthoringRequest.ResponseSet rs = item.responseSet();
        if (rs == null) {
            // Default response type is TEXT — no constraint to check
            // beyond the data-type/file pairing.
            if ("FILE".equals(dataType)) {
                out.add(fe(iPrefix + ".responseSet",
                        "Items with data type FILE must declare a 'file' response set"));
            }
            return;
        }
        String type = rs.type() == null ? "" : rs.type().trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            out.add(fe(iPrefix + ".responseSet.type", "Response type is required"));
            return;
        }
        if (!CrfJsonToWorkbookAdapter.ALLOWED_RESPONSE_TYPES.contains(type)) {
            out.add(fe(iPrefix + ".responseSet.type",
                    "Response type must be one of text, textarea, radio, single-select, "
                            + "multi-select, checkbox, file (CALCULATION variants land in Milestone C)"));
            return;
        }
        // File / data type pairing (the parser also checks this, but
        // surfacing it here is friendlier).
        if ("file".equals(type) && !"FILE".equals(dataType) && !dataType.isEmpty()) {
            out.add(fe(iPrefix + ".responseSet.type",
                    "Response type 'file' requires data type FILE"));
        }
        if ("FILE".equals(dataType) && !"file".equals(type)) {
            out.add(fe(iPrefix + ".dataType",
                    "Data type FILE requires response type 'file'"));
        }
        // Inline options sanity for choice-bearing types.
        if (isChoiceResponseType(type) && rs.ref() == null) {
            List<CrfVersionAuthoringRequest.Option> opts = rs.options();
            if (opts == null || opts.isEmpty()) {
                out.add(fe(iPrefix + ".responseSet.options",
                        "Response type '" + type + "' requires at least one option"));
            } else {
                Set<String> seenValues = new HashSet<>();
                int oi = 0;
                for (CrfVersionAuthoringRequest.Option opt : opts) {
                    String oPrefix = iPrefix + ".responseSet.options[" + oi + "]";
                    String v = opt == null || opt.value() == null ? "" : opt.value().trim();
                    String t = opt == null || opt.text() == null ? "" : opt.text().trim();
                    if (v.isEmpty()) {
                        out.add(fe(oPrefix + ".value", "Option value is required"));
                    } else if (!seenValues.add(v)) {
                        out.add(fe(oPrefix + ".value", "Duplicate option value '" + v + "'"));
                    }
                    if (t.isEmpty()) {
                        out.add(fe(oPrefix + ".text", "Option text is required"));
                    }
                    if (NUMERIC_TYPES.contains(dataType) && !v.isEmpty()) {
                        if (!isNumeric(v, "REAL".equals(dataType))) {
                            out.add(fe(oPrefix + ".value",
                                    "Option value '" + v + "' is not a valid " + dataType.toLowerCase(Locale.ROOT)));
                        }
                    }
                    oi++;
                }
            }
        }
    }

    private static boolean isChoiceResponseType(String type) {
        return "radio".equals(type) || "single-select".equals(type)
                || "multi-select".equals(type) || "checkbox".equals(type);
    }

    private static boolean isNumeric(String v, boolean real) {
        try {
            if (real) {
                Double.parseDouble(v);
            } else {
                Integer.parseInt(v);
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void validateValidation(CrfVersionAuthoringRequest.Item item,
                                    String iPrefix,
                                    String dataType,
                                    List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        CrfVersionAuthoringRequest.Validation v = item.validation();
        if (v == null) return;
        String regexp = v.regexp();
        String errMsg = v.errorMessage();
        boolean hasRegexp = regexp != null && !regexp.trim().isEmpty();
        boolean hasErrMsg = errMsg != null && !errMsg.trim().isEmpty();
        if (hasRegexp && !hasErrMsg) {
            out.add(fe(iPrefix + ".validation.errorMessage",
                    "validation.errorMessage is required when validation.regexp is set"));
        }
        if (!hasRegexp) return;
        // Strip any operator-typed regexp:/.../ wrapper before compiling.
        String pattern = regexp.trim();
        if (pattern.startsWith("regexp:")) pattern = pattern.substring("regexp:".length()).trim();
        if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() >= 2) {
            pattern = pattern.substring(1, pattern.length() - 1);
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException pse) {
            out.add(fe(iPrefix + ".validation.regexp",
                    "Invalid regular expression: " + pse.getDescription()));
            return;
        }
        if ("FILE".equals(dataType)) {
            out.add(fe(iPrefix + ".validation.regexp",
                    "FILE items cannot carry a regexp — validation is unreachable"));
        }
        if (errMsg != null && errMsg.length() > 255) {
            out.add(fe(iPrefix + ".validation.errorMessage",
                    "validation.errorMessage must be 255 characters or fewer"));
        }
    }

    private void validateDefaultValue(CrfVersionAuthoringRequest.Item item,
                                      String iPrefix,
                                      String dataType,
                                      List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String def = item.defaultValue();
        if (def == null || def.trim().isEmpty()) return;
        String trimmed = def.trim();
        // Numeric data types: default must parse.
        if (NUMERIC_TYPES.contains(dataType)
                && !isNumeric(trimmed, "REAL".equals(dataType))) {
            out.add(fe(iPrefix + ".defaultValue",
                    "Default value '" + trimmed + "' is not a valid "
                            + dataType.toLowerCase(Locale.ROOT)));
            return;
        }
        // Choice response sets: default must be one of the declared values.
        CrfVersionAuthoringRequest.ResponseSet rs = item.responseSet();
        if (rs == null || rs.ref() != null) return;
        String type = rs.type() == null ? "" : rs.type().trim().toLowerCase(Locale.ROOT);
        if (!isChoiceResponseType(type)) return;
        List<CrfVersionAuthoringRequest.Option> opts = rs.options();
        if (opts == null || opts.isEmpty()) return;
        boolean multi = "multi-select".equals(type) || "checkbox".equals(type);
        Set<String> values = new HashSet<>();
        for (CrfVersionAuthoringRequest.Option opt : opts) {
            if (opt != null && opt.value() != null) values.add(opt.value().trim());
        }
        String[] tokens = multi ? trimmed.split(",") : new String[]{trimmed};
        for (String tok : tokens) {
            String t = tok == null ? "" : tok.trim();
            if (t.isEmpty()) continue;
            if (!values.contains(t)) {
                out.add(fe(iPrefix + ".defaultValue",
                        "Default value '" + t + "' is not one of the declared option values"));
            }
        }
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError fe(String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }
}
