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

import jakarta.servlet.http.HttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.control.form.Validation;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CRF validation legacy data entry runs on an item's value: the
 * {@code func:} or {@code regexp:} expression a CRF author puts in the item's
 * validation column ({@code item_form_metadata.regexp}), failing with the
 * author's message ({@code regexp_error_msg}).
 *
 * <p>Legacy ({@code DataEntryServlet.customValidation}) parses the expression
 * with {@link Validator#processCRFValidationFunction} or
 * {@link Validator#processCRFValidationRegex} and evaluates it with
 * {@link Validator}. So does this, through a validator that reads the value
 * from here instead of from the request, so a range, a comparison, a regular
 * expression and the EAN-13 check behave as they do on the legacy form. As
 * there:
 * <ul>
 *   <li>a blank value is not checked; whether it may be blank is the required
 *       check's business;</li>
 *   <li>an expression that does not parse is ignored (legacy logs it too);</li>
 *   <li>the caller skips a value that already carries an active discrepancy
 *       note: {@code DiscrepancyValidator} does not validate a field with
 *       notes, which is how legacy lets an unexpected value stand with its
 *       explanation.</li>
 * </ul>
 * One difference: where the author gave no message, the check's legacy
 * default message is used rather than an empty one.
 */
final class CrfItemValidation {

    private static final Logger LOG = LoggerFactory.getLogger(CrfItemValidation.class);

    private static final String FIELD = "value";

    private CrfItemValidation() {}

    /**
     * The validation an item's CRF metadata defines, or {@code null} when it
     * defines none or one that does not parse.
     *
     * @param regexp         {@code item_form_metadata.regexp}
     * @param authorMessage  {@code item_form_metadata.regexp_error_msg}
     */
    static Validation of(String regexp, String authorMessage) {
        if (regexp == null || regexp.isBlank()) {
            return null;
        }
        String expression = regexp.trim();
        Validation validation;
        try {
            if (expression.startsWith("func:")) {
                validation = Validator.processCRFValidationFunction(expression);
            } else if (expression.startsWith("regexp:")) {
                validation = Validator.processCRFValidationRegex(expression);
            } else {
                return null;
            }
        } catch (Exception e) {
            LOG.warn("An item's CRF validation does not parse and is ignored: {}", e.getMessage());
            return null;
        }
        if (validation != null && authorMessage != null && !authorMessage.isBlank()) {
            validation.setErrorMessage(authorMessage.trim());
        }
        return validation;
    }

    /**
     * The message {@code value} fails {@code validation} with, or {@code null}
     * when it passes or is blank.
     *
     * @param request the request being served, for the locale of the default
     *                messages
     */
    static String failure(HttpServletRequest request, Validation validation, String value) {
        if (validation == null || value == null || value.trim().isEmpty()) {
            return null;
        }
        ValueValidator validator = new ValueValidator(request, value);
        validator.addValidation(FIELD, validation);
        HashMap<String, ArrayList<String>> errors = validator.validate();
        ArrayList<String> messages = errors.get(FIELD);
        return messages == null || messages.isEmpty() ? null : String.join(" ", messages);
    }

    /** The legacy validator, reading its one field from here. */
    private static final class ValueValidator extends Validator {

        private final String value;

        ValueValidator(HttpServletRequest request, String value) {
            super(request);
            this.value = value;
        }

        @Override
        protected String getFieldValue(String fieldName) {
            return value;
        }
    }
}
