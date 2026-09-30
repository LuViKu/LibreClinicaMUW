/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

/**
 * {@code GET /api/v1/studies/{oid}/metadata} against the legacy
 * {@code DownloadStudyMetadataServlet}: for the same study both must
 * produce the same ODM document, apart from the two header attributes
 * that carry the generation time. Plus the access gate: sysadmin or a
 * director / coordinator of the study.
 */
class StudyMetadataApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Both paths get the same collaborators, so any difference is in the generation. */
    private static final RuleSetRuleDao RULES = Mockito.mock(RuleSetRuleDao.class);
    private static final CoreResources CORE = Mockito.mock(CoreResources.class);

    @BeforeAll
    static void seedOtherStudy() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study (unique_identifier, name, summary, date_created, owner_id, type_id, "
                             + "status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid) "
                             + "VALUES ('meta-other', 'Metadata other', '', now(), 1, 1, 1, 1, 'PI', "
                             + "'observational', 'MUW', 'S_META_OTHER')")) {
            ps.executeUpdate();
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
    void aStudyDirectorOrCoordinatorOfTheStudyMayDownload() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("manual_dm")))
                .andExpect(status().isOk());
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("manual_crc")))
                .andExpect(status().isOk());
    }

    @Test
    void otherRolesAndOtherStudiesAreRefused() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("manual_investigator")))
                .andExpect(status().isForbidden());
        mockMvc().perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(user("manual_monitor")))
                .andExpect(status().isForbidden());
        // A director of the Default Study has no say over another study.
        mockMvc().perform(get("/api/v1/studies/S_META_OTHER/metadata").session(user("manual_dm")))
                .andExpect(status().isForbidden());
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
        return MockMvcBuilders.standaloneSetup(new StudyMetadataApiController(DATA_SOURCE, RULES, CORE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession session = user("root");
        ((UserAccountBean) session.getAttribute("userBean")).addUserType(UserType.SYSADMIN);
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
}
