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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

/**
 * The site label on the users API: a binding on a site carries the site's
 * name, a binding on a parent study carries none, and a user with no binding
 * at all (study id 0) carries none.
 *
 * <p>Seeds one site under study 1 and three users: one bound to the site,
 * one bound to study 1, and one bound to both.
 */
class UsersApiControllerSiteLabelDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String SITE_NAME = "Site label IT site";

    private static int siteId;

    @BeforeAll
    static void seedSiteAndUsers() throws Exception {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study (parent_study_id, unique_identifier, name, status_id, "
                            + "date_created, owner_id, oc_oid) "
                            + "VALUES (1, 'site-label-it', ?, 1, NOW(), 1, 'S_SITELABEL_IT') "
                            + "RETURNING study_id")) {
                ps.setString(1, SITE_NAME);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    siteId = rs.getInt(1);
                }
            }
            try (Statement stmt = c.createStatement()) {
                user(stmt, 20101, "sitelabel-site");
                user(stmt, 20102, "sitelabel-parent");
                user(stmt, 20103, "sitelabel-both");
                binding(stmt, "sitelabel-site", siteId);
                binding(stmt, "sitelabel-parent", 1);
                binding(stmt, "sitelabel-both", 1);
                binding(stmt, "sitelabel-both", siteId);
            }
        }
    }

    private static void user(Statement stmt, int id, String name) throws Exception {
        stmt.execute(
                "INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                + "email, active_study, institutional_affiliation, status_id, owner_id, "
                + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                + "run_webservices, authtype, enable_api_key) "
                + "VALUES (" + id + ", '" + name + "', "
                + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                + "'Site', 'Label', '" + name + "@example.invalid', 1, 'MUW (test)', 1, 1, "
                + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false)");
    }

    private static void binding(Statement stmt, String userName, int studyId) throws Exception {
        stmt.execute(
                "INSERT INTO study_user_role "
                + "(role_name, study_id, status_id, owner_id, date_created, user_name) "
                + "VALUES ('investigator', " + studyId + ", 1, 1, current_timestamp, '" + userName + "')");
    }

    private MockMvc mockMvc() {
        UsersApiController controller = new UsersApiController(
                DATA_SOURCE,
                new SiteVisibilityFilter(DATA_SOURCE),
                Mockito.mock(SecurityManager.class),
                Mockito.mock(AuthoritiesDao.class),
                new SsoProperties());
        return ProductionMvc.standalone(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        session.setAttribute("study", study);
        return session;
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mockMvc().perform(get(url).session(sysadminSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode row : array) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        return null;
    }

    @Test
    void theUserListLabelsSiteBindingsOnly() throws Exception {
        JsonNode users = getJson("/api/v1/users");

        JsonNode site = find(users, "username", "sitelabel-site");
        JsonNode parent = find(users, "username", "sitelabel-parent");
        // manual_admin has no study binding: the list gives it a study id 0 row.
        JsonNode unbound = find(users, "username", "manual_admin");
        assertNotNull(site, "users: " + users);
        assertNotNull(parent, "users: " + users);
        assertNotNull(unbound, "users: " + users);

        assertEquals(SITE_NAME, site.path("siteLabel").asText(null));
        assertNull(parent.path("siteLabel").stringValue(null), "parent: " + parent);
        assertNull(unbound.path("siteLabel").stringValue(null), "unbound: " + unbound);
    }

    @Test
    void theRoleBindingsLabelSiteBindingsOnly() throws Exception {
        JsonNode roles = getJson("/api/v1/users/sitelabel-both/roles");

        assertEquals(2, roles.size(), "roles: " + roles);
        JsonNode parent = find(roles, "studyId", "1");
        JsonNode site = find(roles, "studyId", String.valueOf(siteId));
        assertNotNull(parent, "roles: " + roles);
        assertNotNull(site, "roles: " + roles);

        assertNull(parent.path("siteLabel").stringValue(null), "parent: " + parent);
        assertEquals(SITE_NAME, site.path("siteLabel").asText(null));
    }
}
