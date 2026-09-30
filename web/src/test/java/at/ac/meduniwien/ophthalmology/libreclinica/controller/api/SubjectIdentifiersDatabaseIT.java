/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The subject identifiers a study requires, against a real database: the
 * API takes and refuses the Person ID, the date or year of birth and the sex
 * as the study parameters say, as legacy subject registration does, and the
 * subject edit changes the columns it names.
 *
 * <p>Default Study's seed: {@code subjectPersonIdRequired=required},
 * {@code collectDob=1} (full date), {@code genderRequired=true}. A test that
 * changes a parameter puts it back.
 */
class SubjectIdentifiersDatabaseIT extends AbstractApiControllerDatabaseIT {

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(buildSubjectsController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession investigator() {
        return ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /* ------------------------------------------------------------------ */
    /* Add subject                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void aSubjectWithoutTheIdentifiersTheStudyRequiresIsRefused() throws Exception {
        mvc().perform(json(post("/api/v1/subjects"),
                        "{\"id\":\"ID-1\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\"}")
                        .session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("personId", "dateOfBirth")));

        mvc().perform(json(post("/api/v1/subjects"),
                        "{\"id\":\"ID-1\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\","
                                + "\"personId\":\"P-ID-1\",\"yearOfBirth\":1970}")
                        .session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("dateOfBirth")));
    }

    @Test
    void aSubjectWithThemIsCreated() throws Exception {
        mvc().perform(json(post("/api/v1/subjects"),
                        "{\"id\":\"ID-2\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\","
                                + "\"personId\":\"P-ID-2\",\"dateOfBirth\":\"1970-05-04\"}")
                        .session(investigator()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dateOfBirth").value("1970-05-04"))
                .andExpect(jsonPath("$.yearOfBirth").value(1970));

        assertEquals("P-ID-2|1970-05-04|true|f", subjectRow("ID-2"));
    }

    @Test
    void aYearOnlyStudyTakesTheYearAndNotTheDate() throws Exception {
        setParameter("collectDob", "2");
        try {
            mvc().perform(json(post("/api/v1/subjects"),
                            "{\"id\":\"ID-3\",\"gender\":\"M\",\"enrolledOn\":\"2025-01-01\","
                                    + "\"personId\":\"P-ID-3\",\"dateOfBirth\":\"1980-02-03\"}")
                            .session(investigator()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field",
                            containsInAnyOrder("dateOfBirth", "yearOfBirth")));

            mvc().perform(json(post("/api/v1/subjects"),
                            "{\"id\":\"ID-3\",\"gender\":\"M\",\"enrolledOn\":\"2025-01-01\","
                                    + "\"personId\":\"P-ID-3\",\"yearOfBirth\":1980}")
                            .session(investigator()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.yearOfBirth").value(1980));
            assertEquals("P-ID-3|1980-01-01|false|m", subjectRow("ID-3"));
        } finally {
            setParameter("collectDob", "1");
        }
    }

    @Test
    void aStudyThatCollectsNeitherRefusesThem() throws Exception {
        setParameter("collectDob", "3");
        setParameter("subjectPersonIdRequired", "not_used");
        try {
            mvc().perform(json(post("/api/v1/subjects"),
                            "{\"id\":\"ID-4\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\","
                                    + "\"personId\":\"P-ID-4\",\"yearOfBirth\":1990}")
                            .session(investigator()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("personId", "yearOfBirth")));

            mvc().perform(json(post("/api/v1/subjects"),
                            "{\"id\":\"ID-4\",\"gender\":\"F\",\"enrolledOn\":\"2025-01-01\"}")
                            .session(investigator()))
                    .andExpect(status().isCreated());
            assertEquals("null|null|false|f", subjectRow("ID-4"));
        } finally {
            setParameter("collectDob", "1");
            setParameter("subjectPersonIdRequired", "required");
        }
    }

    @Test
    void theSexMayBeLeftOutWhereTheStudyDoesNotRequireIt() throws Exception {
        String body = "{\"id\":\"ID-5\",\"enrolledOn\":\"2025-01-01\","
                + "\"personId\":\"P-ID-5\",\"dateOfBirth\":\"1975-06-07\"}";
        mvc().perform(json(post("/api/v1/subjects"), body).session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("gender")));

        setParameter("genderRequired", "false");
        try {
            mvc().perform(json(post("/api/v1/subjects"), body).session(investigator()))
                    .andExpect(status().isCreated());
            assertEquals("P-ID-5|1975-06-07|true|null", subjectRow("ID-5"));
        } finally {
            setParameter("genderRequired", "true");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Edit subject                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void theFullDateOfBirthIsCorrected() throws Exception {
        createSubject("ID-6", "f", "P-ID-6", "1966-01-01");

        mvc().perform(json(put("/api/v1/subjects/ID-6"), "{\"gender\":\"F\",\"dateOfBirth\":\"1966-09-21\"}")
                        .session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateOfBirth").value("1966-09-21"));
        assertEquals("P-ID-6|1966-09-21|true|f", subjectRow("ID-6"));

        // Where the full date is collected, a bare year does not replace it.
        mvc().perform(json(put("/api/v1/subjects/ID-6"), "{\"gender\":\"F\",\"yearOfBirth\":1967}")
                        .session(investigator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("yearOfBirth")));
        assertEquals("P-ID-6|1966-09-21|true|f", subjectRow("ID-6"));
    }

    @Test
    void theSecondaryIdIsTheSecondaryLabelNotThePersonId() throws Exception {
        createSubject("ID-7", "m", "P-ID-7", "1971-02-02");

        mvc().perform(json(put("/api/v1/subjects/ID-7"), "{\"gender\":\"M\",\"secondaryId\":\"SEC-7\"}")
                        .session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondaryId").value("SEC-7"));

        assertEquals("SEC-7", secondaryLabel("ID-7"));
        assertEquals("P-ID-7|1971-02-02|true|m", subjectRow("ID-7"), "the Person ID is untouched");
    }

    @Test
    void anEditKeepsTheSexOtherAndUnknown() throws Exception {
        createSubject("ID-8", "o", "P-ID-8", "1972-03-03");

        mvc().perform(json(put("/api/v1/subjects/ID-8"), "{\"gender\":\"O\",\"secondaryId\":\"SEC-8\"}")
                        .session(investigator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").value("O"));
        assertEquals("P-ID-8|1972-03-03|true|o", subjectRow("ID-8"));

        mvc().perform(json(put("/api/v1/subjects/ID-8"), "{\"gender\":\"U\"}").session(investigator()))
                .andExpect(status().isOk());
        assertEquals("P-ID-8|1972-03-03|true|u", subjectRow("ID-8"));
    }

    /* ------------------------------------------------------------------ */

    private void createSubject(String label, String gender, String personId, String dob) throws Exception {
        mvc().perform(json(post("/api/v1/subjects"),
                        "{\"id\":\"" + label + "\",\"gender\":\"" + gender.toUpperCase() + "\","
                                + "\"enrolledOn\":\"2025-01-01\",\"personId\":\"" + personId + "\","
                                + "\"dateOfBirth\":\"" + dob + "\"}")
                        .session(investigator()))
                .andExpect(status().isCreated());
    }

    /** Upsert Default Study's value of a parameter. */
    private static void setParameter(String handle, String value) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE study_parameter_value SET value = ? WHERE study_id = 1 AND parameter = ?")) {
                ps.setString(1, value);
                ps.setString(2, handle);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO study_parameter_value (study_parameter_value_id, study_id, value, parameter) "
                                + "VALUES ((SELECT COALESCE(MAX(study_parameter_value_id), 0) + 1 "
                                + "FROM study_parameter_value), 1, ?, ?)")) {
                    ps.setString(1, value);
                    ps.setString(2, handle);
                    ps.executeUpdate();
                }
            }
        }
    }

    /** {@code unique_identifier|date_of_birth|dob_collected|gender} of the label's subject. */
    private static String subjectRow(String label) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT s.unique_identifier, s.date_of_birth, s.dob_collected, s.gender "
                             + "FROM subject s JOIN study_subject ss ON ss.subject_id = s.subject_id "
                             + "WHERE ss.label = ?")) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String gender = rs.getString(4);
                return rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getBoolean(3) + "|"
                        + (gender == null || gender.isBlank() ? "null" : gender.trim());
            }
        }
    }

    private static String secondaryLabel(String label) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT secondary_label FROM study_subject WHERE label = ?")) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

}
