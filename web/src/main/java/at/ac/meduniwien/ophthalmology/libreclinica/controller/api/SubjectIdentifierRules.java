/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.service.StudyParameterConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;

/**
 * Which subject identifiers a study collects and which it requires, read
 * from the study parameters the way legacy subject registration
 * ({@code AddNewSubjectServlet}) reads them.
 *
 * <ul>
 *   <li>{@code subjectPersonIdRequired}: {@code required} requires the
 *       Person ID, {@code optional} accepts one, {@code not_used} collects
 *       none (legacy does not show the field).</li>
 *   <li>{@code collectDob}: {@code 1} requires the full date of birth,
 *       {@code 2} the year of birth only; anything else collects
 *       neither.</li>
 *   <li>{@code genderRequired}: requires the sex unless it is
 *       {@code false}.</li>
 * </ul>
 *
 * <p>Where legacy has no field to put a value in, the API refuses the
 * value instead of storing what the study says it does not collect.
 *
 * @param personId    the {@code subjectPersonIdRequired} value
 * @param dateOfBirth the {@code collectDob} value
 */
record SubjectIdentifierRules(String personId, String dateOfBirth, boolean genderRequired) {

    static final String PERSON_ID_REQUIRED = "required";
    static final String PERSON_ID_NOT_USED = "not_used";
    static final String FULL_DATE_OF_BIRTH = "1";
    static final String YEAR_OF_BIRTH_ONLY = "2";

    /** Legacy's maximum for {@code subject.unique_identifier}. */
    private static final int PERSON_ID_MAX_LENGTH = 255;

    /** The rules of the session's study, as {@link StudyParameters} resolves them. */
    static SubjectIdentifierRules forStudy(DataSource dataSource, StudyBean study) {
        StudyParameterConfig defaults = new StudyParameterConfig();
        return new SubjectIdentifierRules(
                StudyParameters.value(dataSource, study, StudyParameters.SUBJECT_PERSON_ID_REQUIRED,
                        defaults.getSubjectPersonIdRequired()),
                StudyParameters.value(dataSource, study, StudyParameters.COLLECT_DOB,
                        defaults.getCollectDob()),
                !"false".equals(StudyParameters.value(dataSource, study, StudyParameters.GENDER_REQUIRED,
                        defaults.getGenderRequired())));
    }

    boolean personIdRequired() {
        return PERSON_ID_REQUIRED.equals(personId);
    }

    boolean personIdCollected() {
        return !PERSON_ID_NOT_USED.equals(personId);
    }

    boolean fullDateOfBirth() {
        return FULL_DATE_OF_BIRTH.equals(dateOfBirth);
    }

    boolean yearOfBirthOnly() {
        return YEAR_OF_BIRTH_ONLY.equals(dateOfBirth);
    }

    /**
     * The errors a new subject's Person ID and date or year of birth give
     * under these rules. The range of a year of birth is checked by the
     * caller.
     */
    List<ValidationErrorBody.FieldError> validateNewSubject(String personIdValue, String dateOfBirthValue,
                                                            Integer yearOfBirthValue, LocalDate today) {
        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        errors.addAll(validatePersonId(personIdValue));

        String dob = dateOfBirthValue == null ? "" : dateOfBirthValue.trim();
        if (fullDateOfBirth()) {
            if (dob.isEmpty()) {
                errors.add(new ValidationErrorBody.FieldError("dateOfBirth",
                        "Date of birth is required in this study."));
            } else {
                errors.addAll(validateDateOfBirth(dob, today));
            }
        } else if (yearOfBirthOnly()) {
            if (!dob.isEmpty()) {
                errors.add(new ValidationErrorBody.FieldError("dateOfBirth",
                        "This study records the year of birth only."));
            }
            if (yearOfBirthValue == null) {
                errors.add(new ValidationErrorBody.FieldError("yearOfBirth",
                        "Year of birth is required in this study."));
            }
        } else {
            if (!dob.isEmpty()) {
                errors.add(new ValidationErrorBody.FieldError("dateOfBirth",
                        "This study does not collect the date of birth."));
            }
            if (yearOfBirthValue != null) {
                errors.add(new ValidationErrorBody.FieldError("yearOfBirth",
                        "This study does not collect the year of birth."));
            }
        }
        return errors;
    }

    /** The Person ID's errors: required, not collected, too long, or markup. */
    List<ValidationErrorBody.FieldError> validatePersonId(String personIdValue) {
        String pid = personIdValue == null ? "" : personIdValue.trim();
        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        if (!personIdCollected()) {
            if (!pid.isEmpty()) {
                errors.add(new ValidationErrorBody.FieldError("personId",
                        "This study does not collect a Person ID."));
            }
        } else if (pid.isEmpty()) {
            if (personIdRequired()) {
                errors.add(new ValidationErrorBody.FieldError("personId",
                        "Person ID is required in this study."));
            }
        } else if (pid.length() > PERSON_ID_MAX_LENGTH) {
            errors.add(new ValidationErrorBody.FieldError("personId",
                    "Person ID is too long (max " + PERSON_ID_MAX_LENGTH + " characters)."));
        } else if (pid.contains("<") || pid.contains(">")) {
            errors.add(new ValidationErrorBody.FieldError("personId",
                    "Person ID must not contain '<' or '>'."));
        }
        return errors;
    }

    /** A full date of birth: an ISO date, not in the future. */
    static List<ValidationErrorBody.FieldError> validateDateOfBirth(String dob, LocalDate today) {
        try {
            if (LocalDate.parse(dob).isAfter(today)) {
                return List.of(new ValidationErrorBody.FieldError("dateOfBirth",
                        "Date of birth must not be in the future."));
            }
            return List.of();
        } catch (DateTimeParseException e) {
            return List.of(new ValidationErrorBody.FieldError("dateOfBirth",
                    "Date of birth must be a valid ISO date (YYYY-MM-DD)."));
        }
    }
}
