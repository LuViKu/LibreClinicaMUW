/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /studies/{parent}/sites} — creating a site.
 *
 * <p>Found on the production stack (2026-10-10): the endpoint saved the site,
 * then set its OID through a second {@code StudyDAO.update}, which dereferences
 * the old status a brand-new site does not have. The site existed, the request
 * still answered 500, and every retry got 400 (name and id taken). Nothing
 * covered a successful create before.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SitesApiControllerCreateDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String PARENT = "S_SITECR_IT";
    private static int parent;

    private static final String BODY = "{\"name\":\"Create IT site A\",\"uniqueProtocolId\":\"cr-it-a\","
            + "\"principalInvestigator\":\"Dr. Create\"}";

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            parent = insertStudy(c, null, "sitecr-it", "Site create IT", PARENT, 1);
        }
    }

    @Test
    @Order(1)
    void aNewSiteIsCreatedAndAnswersWithTheOidItIsStoredUnder() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites")
                        .session(sysadminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oid").value("S_CR-IT-A"));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT oc_oid, status_id, parent_study_id FROM study WHERE unique_identifier = 'cr-it-a'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("S_CR-IT-A", rs.getString(1), "the OID is set by the insert itself");
                assertEquals(4, rs.getInt(2), "a new site is pending");
                assertEquals(parent, rs.getInt(3));
            }
        }
    }

    @Test
    @Order(2)
    void creatingItAgainIsAValidationErrorAndLeavesOneSite() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + PARENT + "/sites")
                        .session(sysadminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM study WHERE unique_identifier = 'cr-it-a'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    @Order(3)
    void updatingAStudyThatNeverRecordedAnOldStatusDoesNotThrow() throws Exception {
        StudyDAO dao = new StudyDAO(DATA_SOURCE);
        StudyBean site = dao.findByOid("S_CR-IT-A");
        // What a freshly created bean looks like: no old status.
        site.setOldStatus(null);
        site.setName("Create IT site A renamed");
        UserAccountBean root = new UserAccountBean();
        root.setId(1);
        site.setUpdater(root);
        site.setUpdatedDate(new java.util.Date());
        assertDoesNotThrow(() -> dao.update(site));
        assertEquals("Create IT site A renamed", dao.findByOid("S_CR-IT-A").getName());
    }

    /* ------------------------------------------------------------------ */

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new SitesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean s = new StudyBean();
        s.setId(parent);
        s.setOid(PARENT);
        session.setAttribute("study", s);
        return session;
    }
}
