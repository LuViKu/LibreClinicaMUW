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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DownloadStudyMetadataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyConfigService;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyParameterValueDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

/**
 * {@code GET /api/v1/studies/{oid}/metadata} against the legacy
 * {@code DownloadStudyMetadataServlet}: for the same study both must
 * produce the same ODM document, apart from the two header attributes
 * that carry the generation time. Plus the access gate, which is the
 * servlet's: a system administrator, or anyone whose active role on the
 * study (or, for a site, on its parent) lets them view the study's data.
 */
@SuppressWarnings("resource") // the mock-servlet request and response hold nothing to close
class StudyMetadataApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Both paths get the same collaborators, so any difference is in the generation. */
    private static final RuleSetRuleDao RULES = Mockito.mock(RuleSetRuleDao.class);
    private static final CoreResources CORE = Mockito.mock(CoreResources.class);

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            insertStudy(c, null, "meta-other", "Metadata other", "S_META_OTHER");
            int site = insertStudy(c, 1, "meta-site", "Metadata site", "S_META_SITE");
            // The two data entry roles the SPA does not grant, still held by legacy accounts.
            insertUser(c, "meta_ra");
            insertRole(c, "meta_ra", 1, "ra", 1);
            insertUser(c, "meta_ra2");
            insertRole(c, "meta_ra2", 1, "ra2", 1);
            insertUser(c, "meta_site_only");
            insertRole(c, "meta_site_only", site, "Investigator", 1);
            insertUser(c, "meta_removed_role");
            insertRole(c, "meta_removed_role", 1, "Investigator", 5);
            // An ordinary account holding the study-level Administrator role.
            insertUser(c, "meta_admin_role");
            insertRole(c, "meta_admin_role", 1, "admin", 1);
        }
    }

    @Test
    void theDownloadIsTheDocumentTheLegacyServletProduces() throws Exception {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        String api = mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(sysadmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/xml")))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=\"S_DEFAULTS1_metadata_")))
                .andReturn().getResponse().getContentAsString();

        String legacy = new LegacyServlet().export(defaultStudyAsTheSessionHoldsIt(), DATA_SOURCE);
        assertNotNull(legacy, "the servlet put no document on the request");

        // The metadata and the administrative data are both there.
        assertTrue(api.contains("<Study OID=\"S_DEFAULTS1\""), api);
        assertTrue(api.contains("<MetaDataVersion"), api);
        assertTrue(api.contains("<AdminData"), api);
        assertEquals(withoutGenerationTime(legacy), withoutGenerationTime(api));
    }

    @Test
    void everyRoleThatMayViewTheStudysDataMayDownloadItsDesign() throws Exception {
        for (String name : new String[] {"manual_dm", "manual_crc", "manual_investigator", "manual_monitor",
                "meta_ra", "meta_ra2"}) {
            mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user(name)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void aSiteIsOpenToItsOwnRolesAndToThoseOfItsParent() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_META_SITE/metadata").session(user("meta_site_only")))
                .andExpect(status().isOk());
        mockMvc().perform(get("/api/v1/studies/S_META_SITE/metadata").session(user("manual_monitor")))
                .andExpect(status().isOk());
        // A site role does not reach up to the parent study.
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("meta_site_only")))
                .andExpect(status().isForbidden());
    }

    @Test
    void callersWithoutAnActiveRoleOnTheStudyAreRefused() throws Exception {
        // A role on the Default Study gives nothing on another study.
        mockMvc().perform(get("/api/v1/studies/S_META_OTHER/metadata").session(user("manual_dm")))
                .andExpect(status().isForbidden());
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("meta_removed_role")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStudyLevelAdministratorRoleDoesNotViewTheStudysData() throws Exception {
        // SubmitDataServlet.mayViewData leaves out the "admin" role.
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("meta_admin_role")))
                .andExpect(status().isForbidden());
        // Nor does the parent's "admin" binding open one of its sites.
        mockMvc().perform(get("/api/v1/studies/S_META_SITE/metadata").session(user("meta_admin_role")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownStudiesAndAnonymousCallersAreRefused() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_NO_SUCH_STUDY/metadata").session(sysadmin()))
                .andExpect(status().isNotFound());
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------------------------------ */

    /**
     * Runs the servlet's {@code processRequest} as SecureController would,
     * with the session's current study, and returns the document it hands
     * to {@code downloadStudyMetadata.jsp}, which prints it verbatim.
     */
    private static final class LegacyServlet extends DownloadStudyMetadataServlet {
        private static final long serialVersionUID = 1L;

        String export(StudyBean currentStudy, DataSource ds) throws Exception {
            StaticWebApplicationContext spring = new StaticWebApplicationContext();
            spring.getBeanFactory().registerSingleton("ruleSetRuleDao", RULES);
            spring.getBeanFactory().registerSingleton("coreResources", CORE);
            spring.refresh();
            MockServletContext servletContext = new MockServletContext();
            servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, spring);

            SessionManager sessionManager = Mockito.mock(SessionManager.class);
            Mockito.when(sessionManager.getDataSource()).thenReturn(ds);
            this.sm = sessionManager;
            this.context = servletContext;
            this.currentStudy = currentStudy;
            this.request = new MockHttpServletRequest(servletContext);
            this.response = new MockHttpServletResponse();
            processRequest();
            return (String) request.getAttribute("generate");
        }

        @Override
        protected void forwardPage(Page jspPage) {
            // The JSP only prints the "generate" attribute; read it instead.
        }
    }

    /** The current study as SecureController loads it into the session. */
    private static StudyBean defaultStudyAsTheSessionHoldsIt() {
        StudyBean study = new StudyDAO(DATA_SOURCE).findByOid("S_DEFAULTS1");
        study.setStudyParameters(new StudyParameterValueDAO(DATA_SOURCE).findParamConfigByStudy(study));
        new StudyConfigService(DATA_SOURCE).setParametersForStudy(study);
        return study;
    }

    private static String withoutGenerationTime(String odm) {
        return odm.replaceAll("FileOID=\"[^\"]*\"", "FileOID=\"-\"")
                .replaceAll("CreationDateTime=\"[^\"]*\"", "CreationDateTime=\"-\"");
    }

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new StudyMetadataApiController(DATA_SOURCE, RULES, CORE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession session = user("root");
        ((UserAccountBean) Objects.requireNonNull(session.getAttribute("userBean"))).addUserType(UserType.SYSADMIN);
        return session;
    }

    private static MockHttpSession user(String name) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(9800);
        ub.setName(name);
        session.setAttribute("userBean", ub);
        return session;
    }

    private static int insertStudy(Connection c, Integer parent, String uid, String name, String oid)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, name, summary, date_created, owner_id, "
                        + "type_id, status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid) "
                        + "VALUES (?, ?, ?, '', now(), 1, 1, 1, 1, 'PI', 'observational', 'MUW', ?) "
                        + "RETURNING study_id")) {
            if (parent == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, parent);
            ps.setString(2, uid);
            ps.setString(3, name);
            ps.setString(4, oid);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void insertUser(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_account (user_name, passwd, first_name, last_name, email, active_study, "
                        + "institutional_affiliation, status_id, owner_id, date_created, user_type_id, enabled, "
                        + "account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                        + "VALUES (?, 'x', 'F', 'L', ?, 1, 'MUW', 1, 1, now(), 2, true, true, 0, false, "
                        + "'STANDARD', false)")) {
            ps.setString(1, name);
            ps.setString(2, name + "@example.invalid");
            ps.executeUpdate();
        }
    }

    private static void insertRole(Connection c, String user, int studyId, String role, int statusId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name) "
                        + "VALUES (?, ?, ?, 1, now(), ?)")) {
            ps.setString(1, role);
            ps.setInt(2, studyId);
            ps.setInt(3, statusId);
            ps.setString(4, user);
            ps.executeUpdate();
        }
    }
}
