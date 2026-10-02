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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The study fields the SPA now binds on create and edit: contact e-mail,
 * collaborators and detailed description ({@code protocolDescription}).
 */
class StudiesApiControllerIdentityDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static int editStudy;
    private static int editSite;
    private static int notifyingStudy;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            editStudy = insertStudy(c, null, "ident-it", "S_IDENT_IT", "DISABLED", "old@example.org");
            editSite = insertStudy(c, editStudy, "ident-it-a", "S_IDENT_IT_A", "DISABLED", "old@example.org");
            notifyingStudy = insertStudy(c, null, "ident-it-mail", "S_IDENT_MAIL", "ENABLED", "pm@example.org");
        }
    }

    @Test
    void createReturnsTheOidTheNewStudyIsStoredUnder() throws Exception {
        String where = "unique_identifier = 'ident-create'";
        String body = mockMvc().perform(post("/api/v1/studies")
                        .contentType("application/json")
                        .content("{\"name\":\"Identity create IT\",\"uniqueProtocolId\":\"ident-create\","
                                + "\"briefSummary\":\"s\",\"principalInvestigator\":\"PI\",\"sponsor\":\"MUW\","
                                + "\"contactEmail\":\"pm@example.org\",\"collaborators\":\"AKH Wien\","
                                + "\"protocolDescription\":\"The long description.\"}")
                        .session(sysadmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactEmail").value("pm@example.org"))
                .andExpect(jsonPath("$.collaborators").value("AKH Wien"))
                .andExpect(jsonPath("$.protocolDescription").value("The long description."))
                .andReturn().getResponse().getContentAsString();
        // The SPA opens the new study by the OID it gets back.
        String storedOid = column("oc_oid", where);
        assertTrue(body.contains("\"oid\":\"" + storedOid + "\""),
                "response must carry the stored OID " + storedOid + ": " + body);
        assertEquals("pm@example.org", column("contact_email", where));
        assertEquals("AKH Wien", column("collaborators", where));
        assertEquals("The long description.", column("protocol_description", where));
    }

    @Test
    void createRefusesAMalformedContactEmail() throws Exception {
        mockMvc().perform(post("/api/v1/studies")
                        .contentType("application/json")
                        .content("{\"name\":\"Identity bad mail IT\",\"uniqueProtocolId\":\"ident-badmail\","
                                + "\"briefSummary\":\"s\",\"principalInvestigator\":\"PI\",\"sponsor\":\"MUW\","
                                + "\"contactEmail\":\"not-an-address\"}")
                        .session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contactEmail"));
    }

    @Test
    void editPersistsTheContactEmailAuditsItAndPassesItToTheSites() throws Exception {
        mockMvc().perform(put("/api/v1/studies/S_IDENT_IT")
                        .contentType("application/json")
                        .content("{\"contactEmail\":\"new@example.org\",\"collaborators\":\"\","
                                + "\"protocolDescription\":\"Edited description.\"}")
                        .session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactEmail").value("new@example.org"))
                .andExpect(jsonPath("$.protocolDescription").value("Edited description."));
        assertEquals("new@example.org", column("contact_email", "study_id = " + editStudy));
        assertEquals("Edited description.", column("protocol_description", "study_id = " + editStudy));
        // An optional field can be cleared.
        assertEquals("", column("collaborators", "study_id = " + editStudy));
        // Legacy parity (UpdateStudyServletNew): a site carries its parent's contact e-mail.
        assertEquals("new@example.org", column("contact_email", "study_id = " + editSite));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT old_value, new_value FROM audit_log_event WHERE audit_log_event_type_id = 51 "
                             + "AND entity_id = ? AND entity_name = 'contact_email'")) {
            ps.setInt(1, editStudy);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a contact_email identity audit row");
                assertEquals("old@example.org", rs.getString(1));
                assertEquals("new@example.org", rs.getString(2));
            }
        }
    }

    @Test
    void editRefusesAMalformedContactEmail() throws Exception {
        mockMvc().perform(put("/api/v1/studies/S_IDENT_IT")
                        .contentType("application/json")
                        .content("{\"contactEmail\":\"nobody\"}")
                        .session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contactEmail"));
    }

    @Test
    void theContactEmailCannotBeClearedWhileLoginNotificationIsOn() throws Exception {
        mockMvc().perform(put("/api/v1/studies/S_IDENT_MAIL")
                        .contentType("application/json")
                        .content("{\"contactEmail\":\"\"}")
                        .session(sysadmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contactEmail"));
        assertEquals("pm@example.org", column("contact_email", "study_id = " + notifyingStudy));
    }

    /* ------------------------------------------------------------------ */

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new StudiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        return session;
    }

    private static String column(String column, String where) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + column + " FROM study WHERE " + where)) {
            assertTrue(rs.next(), "no study where " + where);
            return rs.getString(1);
        }
    }

    private static int insertStudy(Connection c, Integer parent, String uid, String oid,
                                   String mailNotification, String contactEmail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, name, summary, date_created, owner_id, "
                        + "type_id, status_id, old_status_id, principal_investigator, protocol_type, sponsor, "
                        + "collaborators, mail_notification, contact_email, oc_oid) "
                        + "VALUES (?, ?, ?, 'summary', now(), 1, 1, 1, 1, 'PI', 'observational', 'MUW', "
                        + "'Somebody', ?, ?, ?) RETURNING study_id")) {
            if (parent == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, parent);
            ps.setString(2, uid);
            ps.setString(3, "Identity " + uid);
            ps.setString(4, mailNotification);
            ps.setString(5, contactEmail);
            ps.setString(6, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
