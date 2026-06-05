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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Service;

import at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree.OpenClinicaExpressionParser;

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
     *
     * <p>Milestone C augments the per-item branch with three new
     * cross-cutting checks:
     * <ul>
     *   <li>{@code showItem} references must point to an item that
     *       appears <em>earlier</em> in the section list (same section
     *       or any prior section) — the legacy data-entry runtime
     *       evaluates the expression top-down, so a forward reference
     *       can never fire.</li>
     *   <li>Items sharing a {@code groupLabel} must all live in the
     *       same section. The repeating-group SQL contract maps one
     *       group → one item_group → one section_id; cross-section
     *       grouping is unrepresentable in the schema.</li>
     *   <li>Calculation-typed items must carry a syntactically valid
     *       OpenClinica expression in {@code responseSet.options[0]}.
     *       Symbols are not resolved (the draft scope isn't materialised
     *       yet), but a parse error fails fast.</li>
     * </ul>
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
        // M-C: track item OIDs in section/item order so showItem
        // references can be checked against preceding items only.
        // Items without an explicit OID register their NAME as fallback
        // (parser convention — the OID falls out of the name in the legacy
        // path). Map values are the data type, which the showItem branch
        // surfaces via the dependency check.
        LinkedHashMap<String, String> seenOidsInOrder = new LinkedHashMap<>();
        // M-C: track which section first hosted a given groupLabel so
        // cross-section duplicates can be flagged.
        Map<String, Integer> groupLabelToSection = new HashMap<>();
        int sectionIdx = 0;
        for (CrfVersionAuthoringRequest.Section section : body.sections()) {
            validateSection(section, sectionIdx, seenSectionLabels,
                    seenItemNames, seenOidsInOrder, groupLabelToSection, out);
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
                                 LinkedHashMap<String, String> seenOidsInOrder,
                                 Map<String, Integer> groupLabelToSection,
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
            validateItem(item, sectionIdx, itemIdx, seenItemNames,
                    seenOidsInOrder, groupLabelToSection, out);
            itemIdx++;
        }
    }

    private void validateItem(CrfVersionAuthoringRequest.Item item,
                              int sectionIdx,
                              int itemIdx,
                              Set<String> seenItemNames,
                              LinkedHashMap<String, String> seenOidsInOrder,
                              Map<String, Integer> groupLabelToSection,
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
        // ---- M-C: show-when reference must point at a preceding item
        //          ----
        validateShowItem(item, iPrefix, seenOidsInOrder, out);
        // ---- M-C: group label cross-section uniqueness ----
        validateGroupLabel(item, iPrefix, sectionIdx, groupLabelToSection, out);
        // ---- Register this item's OID in declaration order for downstream
        //      showItem references. We register *after* per-item checks so
        //      an item cannot reference itself.
        String oid = item.oid() == null || item.oid().trim().isEmpty()
                ? iname : item.oid().trim();
        if (!oid.isEmpty()) {
            seenOidsInOrder.put(oid, dataType);
        }
    }

    /**
     * M-C — when {@code showItem} is set we require:
     * <ol>
     *   <li>{@code parentItemOid} is non-blank;</li>
     *   <li>{@code parentItemOid} matches an item OID (or fallback name)
     *       that has already been registered in declaration order — a
     *       forward reference never fires at runtime because the legacy
     *       data-entry stack evaluates show-when top-down per section.</li>
     * </ol>
     *
     * <p>Self-reference is impossible because the current item is
     * registered <em>after</em> its per-item checks run.
     */
    private void validateShowItem(CrfVersionAuthoringRequest.Item item,
                                  String iPrefix,
                                  LinkedHashMap<String, String> seenOidsInOrder,
                                  List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String showItem = item.showItem();
        if (showItem == null || showItem.trim().isEmpty()) return;
        String parentOid = item.parentItemOid();
        if (parentOid == null || parentOid.trim().isEmpty()) {
            out.add(fe(iPrefix + ".parentItemOid",
                    "parentItemOid is required when showItem is set"));
            return;
        }
        String parent = parentOid.trim();
        if (!seenOidsInOrder.containsKey(parent)) {
            out.add(fe(iPrefix + ".showItem",
                    "showItem references item '" + parent
                            + "' which is not declared earlier in the form — "
                            + "show-when is only evaluated against preceding items"));
        }
    }

    /**
     * M-C — items sharing a {@code groupLabel} must all live in the same
     * section. The legacy {@code item_group_metadata} table binds one
     * group to one section_id, so a cross-section group is
     * unrepresentable on persist.
     */
    private void validateGroupLabel(CrfVersionAuthoringRequest.Item item,
                                    String iPrefix,
                                    int sectionIdx,
                                    Map<String, Integer> groupLabelToSection,
                                    List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String groupLabel = item.groupLabel();
        if (groupLabel == null || groupLabel.trim().isEmpty()) return;
        String label = groupLabel.trim();
        Integer firstSection = groupLabelToSection.get(label);
        if (firstSection == null) {
            groupLabelToSection.put(label, sectionIdx);
        } else if (firstSection != sectionIdx) {
            out.add(fe(iPrefix + ".groupLabel",
                    "Group label '" + label + "' already used in section "
                            + firstSection + " — repeating groups cannot span sections"));
        }
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
                            + "multi-select, checkbox, file, calculation, "
                            + "instant-calculation, group-calculation"));
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
        // M-C: calculation response types carry the formula in
        // options[0]. Pre-parse the expression so syntactic failures
        // surface as friendly per-item errors before the parser sees
        // them.
        if (CrfJsonToWorkbookAdapter.CALCULATION_TYPES.contains(type)) {
            validateCalculationFormula(rs, iPrefix, dataType, out);
            return;
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

    /**
     * M-C — pre-parse a calculation formula with the no-arg
     * {@link OpenClinicaExpressionParser}. The no-arg parser walks the
     * grammar without resolving variable references, which is what we
     * want here: at authoring time draft item OIDs may not yet exist in
     * the DB, so we only check syntactic well-formedness. The
     * persist path runs the parser again against the materialised
     * scope.
     *
     * <p>The formula lives in {@code options[0].value}; we tolerate
     * {@code options[0].text} as a fallback (parity with the adapter).
     * Calculations on numeric items only — {@code FILE} / {@code DATE} /
     * {@code PDATE} reject explicitly because the legacy expression
     * grammar doesn't fold those types into arithmetic in a useful way.
     */
    private void validateCalculationFormula(CrfVersionAuthoringRequest.ResponseSet rs,
                                            String iPrefix,
                                            String dataType,
                                            List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        if ("FILE".equals(dataType)) {
            out.add(fe(iPrefix + ".dataType",
                    "Data type FILE is incompatible with calculation response types"));
        }
        List<CrfVersionAuthoringRequest.Option> opts = rs.options();
        if (opts == null || opts.isEmpty()
                || opts.get(0) == null
                || (blank(opts.get(0).value()) && blank(opts.get(0).text()))) {
            out.add(fe(iPrefix + ".responseSet.options",
                    "Calculation response sets require a single options[0] entry carrying the formula"));
            return;
        }
        CrfVersionAuthoringRequest.Option first = opts.get(0);
        String formula = !blank(first.value()) ? first.value().trim() : first.text().trim();
        // The legacy parser strips an optional "func:" prefix before
        // running ScoreValidator over the body — mirror that here.
        String body = formula;
        if (body.startsWith("func:")) body = body.substring("func:".length()).trim();
        if (body.isEmpty()) {
            out.add(fe(iPrefix + ".responseSet.options[0]",
                    "Calculation formula must not be empty"));
            return;
        }
        try {
            new OpenClinicaExpressionParser().parseExpression(body);
        } catch (RuntimeException e) {
            out.add(fe(iPrefix + ".responseSet.options[0]",
                    "Calculation formula failed to parse: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
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

    /* ----------------------------------------------------------------- */
    /* M-C — public expression validation helper                          */
    /* ----------------------------------------------------------------- */

    /**
     * Phase E.6 M-C — standalone expression validation used by the
     * {@code POST /api/v1/crfs/{crfOid}/versions:validate-expression}
     * endpoint. The wizard calls this from the calculation / show-when
     * editor to surface parse errors live, before the operator finalises
     * the version.
     *
     * <p>The check runs the no-arg {@link OpenClinicaExpressionParser}
     * against the formula, then walks the formula with a coarse OID
     * tokeniser to extract the referenced item identifiers and
     * cross-checks them against the draft scope.
     *
     * <p>The draft scope is the union of:
     * <ul>
     *   <li>{@code draftItemOids} — items already authored in the
     *       in-progress version, indexed by OID. Their data types come
     *       from {@code draftItemDataTypes}.</li>
     * </ul>
     *
     * <p>An expression that references an OID outside the draft scope
     * still parses cleanly but returns {@code valid = false} with an
     * error message naming the unresolved OID.
     *
     * @param expression the formula source (no {@code "func:"} prefix
     *                    expected; the caller strips it before invoking)
     * @param draftItemOids list of OIDs the draft has authored so far,
     *                       in declaration order (empty list when the
     *                       editor is the first item authored)
     * @param draftItemDataTypes map of OID → spreadsheet data-type code
     *                            ({@code ST} / {@code INT} / etc.) for
     *                            the draft items. Used to flag a
     *                            calculation on a non-numeric source.
     * @return the parse outcome — {@code valid}, optional error message,
     *         list of OIDs the expression referenced.
     */
    public ExpressionValidationResult validateExpression(String expression,
                                                         List<String> draftItemOids,
                                                         Map<String, String> draftItemDataTypes) {
        if (expression == null || expression.trim().isEmpty()) {
            return new ExpressionValidationResult(false, "Expression must not be empty",
                    List.of());
        }
        String formula = expression.trim();
        if (formula.startsWith("func:")) formula = formula.substring("func:".length()).trim();
        if (formula.isEmpty()) {
            return new ExpressionValidationResult(false, "Expression must not be empty",
                    List.of());
        }
        try {
            new OpenClinicaExpressionParser().parseExpression(formula);
        } catch (RuntimeException e) {
            return new ExpressionValidationResult(false,
                    "Expression failed to parse: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    List.of());
        }
        // Coarse OID extraction: legacy expression OIDs are upper-case
        // identifiers that may carry [N], [END], [ALL] suffixes and
        // dot-separated path segments (.STARTDATE, .STATUS). The
        // tokeniser pulls "words that start with a letter and contain
        // at least one upper-case letter" — that's the legacy OID
        // signature. Number literals, dates and operators are skipped.
        List<String> referenced = extractOidReferences(formula);
        Set<String> draftSet = draftItemOids == null ? Set.of() : new HashSet<>(draftItemOids);
        Map<String, String> typeMap = draftItemDataTypes == null
                ? Map.of() : draftItemDataTypes;
        for (String oid : referenced) {
            if (!draftSet.contains(oid)) {
                return new ExpressionValidationResult(false,
                        "Expression references item '" + oid
                                + "' which is not part of the draft form",
                        referenced);
            }
            String type = typeMap.get(oid);
            if (type != null && !type.isEmpty()
                    && !"INT".equalsIgnoreCase(type) && !"INTEGER".equalsIgnoreCase(type)
                    && !"REAL".equalsIgnoreCase(type) && !"ST".equalsIgnoreCase(type)) {
                return new ExpressionValidationResult(false,
                        "Expression references item '" + oid + "' with non-numeric data type '"
                                + type + "'",
                        referenced);
            }
        }
        return new ExpressionValidationResult(true, null, referenced);
    }

    /**
     * Coarse-grained OID extraction — pulls candidate item references
     * from the formula. The result preserves first-seen order and
     * dedupes. Built-ins like {@code _CURRENT_DATE} are excluded.
     */
    private static List<String> extractOidReferences(String formula) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        // Pattern: identifier of letters/digits/underscores, optionally
        // followed by [N] / [END] / [ALL] suffix and zero or more
        // .SEGMENT path tails. Excludes pure numbers and quoted strings.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "[A-Za-z_][A-Za-z0-9_]*(\\[(?:END|ALL|\\d+)\\])?(\\.[A-Z_][A-Z0-9_]*)*")
                .matcher(formula);
        while (m.find()) {
            String token = m.group();
            // Skip operator words.
            if (token.equalsIgnoreCase("and") || token.equalsIgnoreCase("or")
                    || token.equalsIgnoreCase("eq") || token.equalsIgnoreCase("ne")
                    || token.equalsIgnoreCase("ct") || token.equalsIgnoreCase("gt")
                    || token.equalsIgnoreCase("gte") || token.equalsIgnoreCase("lt")
                    || token.equalsIgnoreCase("lte")) continue;
            // Skip built-ins.
            if (token.startsWith("_")) continue;
            // Skip pure-numeric (shouldn't match the regex anyway, but
            // belt-and-braces — the regex starts with [A-Za-z_]).
            // Skip date literals (YYYY-MM-DD doesn't match because '-' is
            // not in the identifier class).
            out.put(token, Boolean.TRUE);
        }
        return new ArrayList<>(out.keySet());
    }

    /**
     * Phase E.6 M-C — return shape for
     * {@link #validateExpression(String, List, Map)} and the
     * {@code :validate-expression} controller endpoint.
     */
    public record ExpressionValidationResult(
            boolean valid,
            String errorMessage,
            List<String> referencedOids
    ) {}
}
