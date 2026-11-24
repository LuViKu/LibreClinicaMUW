/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>A filter row names either a CRF item ({@link #render}, SQL on the
 * {@code id} alias of item_data) or a field of the participant itself
 * ({@link #renderParticipant}, SQL on the {@code ss} alias of study_subject).
 * The participant fields exist because a participant ID is not CRF data: a
 * study that has captured images but no CRF values yet could not be filtered
 * by participant at all.
 */
public final class DatasetFilterPredicates {

    /** The operators the wizard offers. Anything else is refused. */
    public static final Set<String> KNOWN_OPERATORS = Set.of(
            "=", "<>", "<", "<=", ">", ">=", "in", "between", "is-null", "not-null", "starts-with");

    /** Operators that read no value at all. */
    public static final Set<String> UNARY_OPERATORS = Set.of("is-null", "not-null");

    /** Operators only meaningful on ordered data. */
    public static final Set<String> ORDERING_OPERATORS = Set.of("<", "<=", ">", ">=", "between");

    /** The participant's ID ({@code study_subject.label}). */
    public static final String FIELD_PARTICIPANT_ID = "participantId";

    /** The participant's enrolment date ({@code study_subject.enrollment_date}). */
    public static final String FIELD_ENROLLMENT_DATE = "enrollmentDate";

    /** The participant fields a filter row may name instead of an item. */
    public static final Set<String> PARTICIPANT_FIELDS = Set.of(FIELD_PARTICIPANT_ID, FIELD_ENROLLMENT_DATE);

    /** The operators each participant field admits. */
    private static final Set<String> PARTICIPANT_ID_OPERATORS = Set.of("=", "<>", "in", "starts-with");
    private static final Set<String> ENROLLMENT_DATE_OPERATORS = Set.of("=", "<", "<=", ">", ">=", "between");

    /**
     * A numeric item value, or NULL when the stored text is not a number. A
     * value someone typed as "n/a" must not fail the whole query, and a
     * decimal comma counts as a decimal point.
     */
    private static final String NUMERIC_VALUE =
            "(CASE WHEN trim(id.value) ~ '^-?[0-9]+([.,][0-9]+)?$' "
                    + "THEN replace(trim(id.value), ',', '.')::numeric END)";

    private DatasetFilterPredicates() {}

    /** A rendered predicate: SQL referencing the {@code id} alias of item_data, plus its parameters. */
    public record Fragment(String sql, List<Object> params) {}

    /**
     * {@code !=} and {@code <>} are the same operator; the wizard sends the
     * first, SQL spells the second. Lower case, trimmed.
     */
    public static String normalise(String operator) {
        String op = operator == null ? "" : operator.trim().toLowerCase(Locale.ROOT);
        return "!=".equals(op) ? "<>" : op;
    }

    /** As {@link #render(String, String, List, boolean)} on a text item. */
    public static Fragment render(String operator, String value, List<String> values) {
        return render(operator, value, values, false);
    }

    /**
     * @param operator one of {@link #KNOWN_OPERATORS} ({@code !=} is read as {@code <>})
     * @param value    scalar operand; ignored for unary and list operators
     * @param values   list operand for {@code in} / the two bounds for {@code between}
     * @param numeric  the item holds numbers: the ordering operators and
     *                 {@code =} compare as numbers, not as text, so that 10 is
     *                 greater than 9
     * @throws IllegalArgumentException when the operator is unknown or its operands are missing
     */
    public static Fragment render(String operator, String value, List<String> values, boolean numeric) {
        String op = normalise(operator);
        if (!KNOWN_OPERATORS.contains(op) || "starts-with".equals(op)) {
            throw new IllegalArgumentException("unsupported filter operator: " + operator);
        }
        String column = numeric ? NUMERIC_VALUE : "id.value";
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
                List<Object> params = new ArrayList<>();
                for (String v : values) params.add(numeric ? number(v) : v);
                return new Fragment(column + " IN (" + placeholders + ")", params);
            }
            case "between": {
                if (values == null || values.size() != 2) {
                    throw new IllegalArgumentException("'between' needs exactly two values");
                }
                return new Fragment(column + " BETWEEN ? AND ?", numeric
                        ? List.of(number(values.get(0)), number(values.get(1)))
                        : List.of(values.get(0), values.get(1)));
            }
            default: {
                if (value == null) {
                    throw new IllegalArgumentException("operator '" + op + "' needs a value");
                }
                // `op` is whitelisted above, so this concatenation cannot inject.
                return new Fragment(column + " " + op + " ?", List.of(numeric ? number(value) : value));
            }
        }
    }

    /**
     * A predicate on the participant ({@code ss} alias of study_subject).
     *
     * <p>The participant ID compares without regard to case or surrounding
     * blanks: "hae-050" finds HAE-050. {@code starts-with} takes a prefix
     * ("HAE-05"); its wildcard characters are matched literally.
     *
     * @param field one of {@link #PARTICIPANT_FIELDS}
     * @throws IllegalArgumentException when the field, the operator for that
     *         field, or the operands are not usable
     */
    public static Fragment renderParticipant(String field, String operator, String value, List<String> values) {
        String op = normalise(operator);
        if (FIELD_PARTICIPANT_ID.equals(field)) {
            if (!PARTICIPANT_ID_OPERATORS.contains(op)) {
                throw new IllegalArgumentException("operator '" + operator + "' does not apply to the participant ID");
            }
            switch (op) {
                case "in": {
                    if (values == null || values.isEmpty()) {
                        throw new IllegalArgumentException("'in' needs at least one value");
                    }
                    String placeholders = String.join(",", Collections.nCopies(values.size(), "upper(trim(?))"));
                    return new Fragment("upper(ss.label) IN (" + placeholders + ")", new ArrayList<>(values));
                }
                case "starts-with":
                    return new Fragment("upper(ss.label) LIKE ? ESCAPE '\\'",
                            List.of(escapeLike(required(value, op).trim().toUpperCase(Locale.ROOT)) + "%"));
                default:
                    return new Fragment("upper(ss.label) " + op + " upper(trim(?))", List.of(required(value, op)));
            }
        }
        if (FIELD_ENROLLMENT_DATE.equals(field)) {
            if (!ENROLLMENT_DATE_OPERATORS.contains(op)) {
                throw new IllegalArgumentException("operator '" + operator + "' does not apply to the enrolment date");
            }
            if ("between".equals(op)) {
                if (values == null || values.size() != 2) {
                    throw new IllegalArgumentException("'between' needs exactly two values");
                }
                return new Fragment("ss.enrollment_date BETWEEN ? AND ?",
                        List.of(date(values.get(0)), date(values.get(1))));
            }
            return new Fragment("ss.enrollment_date " + op + " ?", List.of(date(required(value, op))));
        }
        throw new IllegalArgumentException("unknown participant field: " + field);
    }

    private static String required(String value, String op) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operator '" + op + "' needs a value");
        }
        return value;
    }

    private static BigDecimal number(String value) {
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + value + "' is not a number");
        }
    }

    private static Date date(String value) {
        try {
            return Date.valueOf(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + value + "' is not a date (yyyy-mm-dd)");
        }
    }

    private static String escapeLike(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
