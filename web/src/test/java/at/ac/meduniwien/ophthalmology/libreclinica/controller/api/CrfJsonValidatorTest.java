/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Phase E.6 Milestone B — per-branch checks for the JSON authoring
 * validator. Each test pins one validation branch with a minimal
 * fixture; the M-A happy path is covered by the existing controller
 * MockMvc tests.
 */
class CrfJsonValidatorTest {

    private final CrfJsonValidator v = new CrfJsonValidator();

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private static CrfVersionAuthoringRequest.Item item(String name, String dataType) {
        return new CrfVersionAuthoringRequest.Item(
                name, "", name, "", "", "", dataType, "", false, null, null);
    }

    private static CrfVersionAuthoringRequest singleSection(CrfVersionAuthoringRequest.Item item) {
        CrfVersionAuthoringRequest.Section section = new CrfVersionAuthoringRequest.Section(
                "S1", "Section 1", "", 1, List.of(item));
        return new CrfVersionAuthoringRequest("v1.0", "", "", List.of(section));
    }

    private static boolean hasField(List<SubjectsApiController.ValidationErrorBody.FieldError> errors,
                                    String fieldPath) {
        return errors.stream().anyMatch(e -> e.field().equals(fieldPath));
    }

    /* ----------------------------------------------------------------- */
    /* Happy paths                                                       */
    /* ----------------------------------------------------------------- */

    @Test
    void minimalIntegerPassesValidation() {
        var errors = v.validate(singleSection(item("AGE", "INTEGER")));
        assertEquals(0, errors.size(), "expected no errors, got " + errors);
    }

    @Test
    void everyAllowedDataTypePasses() {
        for (String dt : List.of("ST", "INTEGER", "INT", "REAL", "DATE", "PDATE", "BL", "BOOLEAN")) {
            var req = singleSection(item("ITEM_" + dt, dt));
            var errors = v.validate(req);
            assertTrue(errors.isEmpty(), "type " + dt + " unexpectedly failed: " + errors);
        }
    }

    @Test
    void fileTypeRequiresFileResponseSet() {
        var req = singleSection(item("UPLOAD", "FILE"));
        var errors = v.validate(req);
        assertTrue(hasField(errors, "sections[0].items[0].responseSet"));
    }

    /* ----------------------------------------------------------------- */
    /* Sections / items                                                  */
    /* ----------------------------------------------------------------- */

    @Test
    void missingVersionNameYieldsError() {
        var req = new CrfVersionAuthoringRequest("", "", "",
                List.of(new CrfVersionAuthoringRequest.Section("S1", "Title", "", 1, List.of(item("A", "ST")))));
        var errors = v.validate(req);
        assertTrue(hasField(errors, "versionName"));
    }

    @Test
    void emptySectionsYieldsError() {
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of());
        var errors = v.validate(req);
        assertTrue(hasField(errors, "sections"));
    }

    @Test
    void duplicateSectionLabelsYieldError() {
        var s1 = new CrfVersionAuthoringRequest.Section("S1", "Sec 1", "", 1, List.of(item("A", "ST")));
        var s2 = new CrfVersionAuthoringRequest.Section("S1", "Sec 2", "", 2, List.of(item("B", "ST")));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(s1, s2));
        var errors = v.validate(req);
        assertTrue(hasField(errors, "sections[1].label"));
    }

    @Test
    void duplicateItemNamesAcrossSectionsYieldError() {
        var s1 = new CrfVersionAuthoringRequest.Section("S1", "Sec 1", "", 1, List.of(item("DUP", "ST")));
        var s2 = new CrfVersionAuthoringRequest.Section("S2", "Sec 2", "", 2, List.of(item("DUP", "INT")));
        var req = new CrfVersionAuthoringRequest("v1.0", "", "", List.of(s1, s2));
        var errors = v.validate(req);
        assertTrue(hasField(errors, "sections[1].items[0].name"));
    }

    @Test
    void itemNameWithIllegalCharactersYieldsError() {
        var req = singleSection(item("BAD NAME", "ST"));
        var errors = v.validate(req);
        assertTrue(hasField(errors, "sections[0].items[0].name"));
    }

    /* ----------------------------------------------------------------- */
    /* Response sets                                                     */
    /* ----------------------------------------------------------------- */

    @Test
    void radioRequiresOptions() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("radio", "sex", List.of(), null);
        var it = new CrfVersionAuthoringRequest.Item(
                "SEX", "", "Sex", "", "", "", "ST", "", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].responseSet.options"));
    }

    @Test
    void calculationResponseTypeRejectedInMilestoneB() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("calculation", "calc", null, null);
        var it = new CrfVersionAuthoringRequest.Item(
                "X", "", "X", "", "", "", "INT", "", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].responseSet.type"));
    }

    @Test
    void numericOptionValueRejectedOnIntegerItem() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("radio", "band",
                List.of(new CrfVersionAuthoringRequest.Option("Young", "abc")), null);
        var it = new CrfVersionAuthoringRequest.Item(
                "BAND", "", "Band", "", "", "", "INTEGER", "", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].responseSet.options[0].value"));
    }

    @Test
    void duplicateOptionValuesRejected() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("single-select", "yn",
                List.of(new CrfVersionAuthoringRequest.Option("Yes", "1"),
                        new CrfVersionAuthoringRequest.Option("No", "1")),
                null);
        var it = new CrfVersionAuthoringRequest.Item(
                "YN", "", "YN", "", "", "", "ST", "", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].responseSet.options[1].value"));
    }

    @Test
    void fileResponseTypeRequiresFileDataType() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("file", "file", null, null);
        var it = new CrfVersionAuthoringRequest.Item(
                "UP", "", "Upload", "", "", "", "ST", "", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].responseSet.type"));
    }

    /* ----------------------------------------------------------------- */
    /* Validation regexp                                                 */
    /* ----------------------------------------------------------------- */

    @Test
    void regexpWithoutErrorMessageRejected() {
        var val = new CrfVersionAuthoringRequest.Validation("[0-9]+", "");
        var it = new CrfVersionAuthoringRequest.Item(
                "PHONE", "", "Phone", "", "", "", "ST", "", false, null, val);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].validation.errorMessage"));
    }

    @Test
    void invalidRegexpRejected() {
        var val = new CrfVersionAuthoringRequest.Validation("([0-9", "Bad number");
        var it = new CrfVersionAuthoringRequest.Item(
                "PHONE", "", "Phone", "", "", "", "ST", "", false, null, val);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].validation.regexp"));
    }

    @Test
    void regexpWithRegexpPrefixAccepted() {
        var val = new CrfVersionAuthoringRequest.Validation("regexp: /[0-9]+/", "Bad");
        var it = new CrfVersionAuthoringRequest.Item(
                "PHONE", "", "Phone", "", "", "", "ST", "", false, null, val);
        var errors = v.validate(singleSection(it));
        assertTrue(errors.isEmpty(), "expected no errors, got " + errors);
    }

    @Test
    void regexpOnFileItemRejected() {
        var val = new CrfVersionAuthoringRequest.Validation("[0-9]+", "Bad");
        var rs = new CrfVersionAuthoringRequest.ResponseSet("file", "file", null, null);
        var it = new CrfVersionAuthoringRequest.Item(
                "UP", "", "Upload", "", "", "", "FILE", "", false, rs, val);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].validation.regexp"));
    }

    /* ----------------------------------------------------------------- */
    /* Default values                                                    */
    /* ----------------------------------------------------------------- */

    @Test
    void defaultValueOnIntegerMustBeNumeric() {
        var it = new CrfVersionAuthoringRequest.Item(
                "AGE", "", "Age", "", "", "", "INTEGER", "abc", false, null, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].defaultValue"));
    }

    @Test
    void defaultValueMustBeInOptions() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("single-select", "sex",
                List.of(new CrfVersionAuthoringRequest.Option("Male", "M"),
                        new CrfVersionAuthoringRequest.Option("Female", "F")),
                null);
        var it = new CrfVersionAuthoringRequest.Item(
                "SEX", "", "Sex", "", "", "", "ST", "X", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].defaultValue"));
    }

    @Test
    void defaultValueMatchingOptionsAccepted() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("single-select", "sex",
                List.of(new CrfVersionAuthoringRequest.Option("Male", "M"),
                        new CrfVersionAuthoringRequest.Option("Female", "F")),
                null);
        var it = new CrfVersionAuthoringRequest.Item(
                "SEX", "", "Sex", "", "", "", "ST", "M", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(errors.isEmpty(), "expected no errors, got " + errors);
    }

    @Test
    void multiSelectDefaultMembersValidatedIndividually() {
        var rs = new CrfVersionAuthoringRequest.ResponseSet("multi-select", "colors",
                List.of(new CrfVersionAuthoringRequest.Option("Red", "R"),
                        new CrfVersionAuthoringRequest.Option("Green", "G"),
                        new CrfVersionAuthoringRequest.Option("Blue", "B")),
                null);
        var it = new CrfVersionAuthoringRequest.Item(
                "COLORS", "", "Colors", "", "", "", "ST", "R,X", false, rs, null);
        var errors = v.validate(singleSection(it));
        assertTrue(hasField(errors, "sections[0].items[0].defaultValue"));
    }
}
