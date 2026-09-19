/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * SQL for one dataset item-filter predicate.
 *
 * <p>Extracted so the wizard's "how many subjects match?" preview and the
 * extract itself compile predicates the same way. They were separate before —
 * the preview had the only implementation and the extract ignored filters
 * entirely, so an operator could author predicates, watch the preview say
 * "12 of 40 subjects", save, export, and receive all 40.
 *
 * <p>Operators are whitelisted rather than interpolated: {@code operator} comes
 * off the wire, so anything outside {@link #KNOWN_OPERATORS} is rejected before
 * it reaches SQL. Values are always bound as parameters.
 */
public final class DatasetFilterPredicates {

    /** The operators the wizard offers. Anything else is refused. */
    public static final Set<String> KNOWN_OPERATORS = Set.of(
            "=", "<>", "<", "<=", ">", ">=", "in", "between", "is-null", "not-null");

    /** Operators that read no value at all. */
    public static final Set<String> UNARY_OPERATORS = Set.of("is-null", "not-null");

    /** Operators only meaningful on ordered data. */
    public static final Set<String> ORDERING_OPERATORS = Set.of("<", "<=", ">", ">=", "between");

    private DatasetFilterPredicates() {}

    /** A rendered predicate: SQL referencing the {@code id} alias of item_data, plus its parameters. */
    public record Fragment(String sql, List<Object> params) {}

    /**
     * @param operator one of {@link #KNOWN_OPERATORS}
     * @param value    scalar operand; ignored for unary and list operators
     * @param values   list operand for {@code in} / the two bounds for {@code between}
     * @throws IllegalArgumentException when the operator is unknown or its operands are missing
     */
    public static Fragment render(String operator, String value, List<String> values) {
        String op = operator == null ? "" : operator.trim().toLowerCase();
        if (!KNOWN_OPERATORS.contains(op)) {
            throw new IllegalArgumentException("unsupported filter operator: " + operator);
        }
        switch (op) {
            case "is-null":
                return new Fragment("(id.value IS NULL OR id.value = '')", Collections.emptyList());
            case "not-null":
                return new Fragment("(id.value IS NOT NULL AND id.value <> '')", Collections.emptyList());
            case "in": {
                if (values == null || values.isEmpty()) {
                    throw new IllegalArgumentException("'in' needs at least one value");
                }
                String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
                return new Fragment("id.value IN (" + placeholders + ")", new ArrayList<>(values));
            }
            case "between": {
                if (values == null || values.size() != 2) {
                    throw new IllegalArgumentException("'between' needs exactly two values");
                }
                return new Fragment("id.value BETWEEN ? AND ?",
                        List.of(values.get(0), values.get(1)));
            }
            default: {
                if (value == null) {
                    throw new IllegalArgumentException("operator '" + op + "' needs a value");
                }
                // `op` is whitelisted above, so this concatenation cannot inject.
                return new Fragment("id.value " + op + " ?", List.of(value));
            }
        }
    }
}
