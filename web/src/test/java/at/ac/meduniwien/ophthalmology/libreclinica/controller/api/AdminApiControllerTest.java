/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.DatabaseChangeLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.PasswordRequirementsDao;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase E.8 Slice L3 (2026-06-20) — MockMvc IT pinning the sysadmin
 * admin tooling endpoints. Drives every branch reachable without a
 * Spring container:
 *
 * <ul>
 *   <li>Auth gate: anonymous → 401, non-sysadmin → 403, sysadmin →
 *       200 (for all three endpoints).</li>
 *   <li>System status: response carries jvm + database + application
 *       sub-objects with the expected fields.</li>
 *   <li>Password policy GET: returns the persisted shape from the
 *       (mocked) DAO.</li>
 *   <li>Password policy PUT: 400 on min &gt; max + on out-of-range
 *       expirationDays + on null body.</li>
 *   <li>Config: returns read-only system properties + env-derived
 *       fields.</li>
 * </ul>
 */
class AdminApiControllerTest extends AbstractApiControllerTest {

    /**
     * Build a controller wired against mocked collaborators + an
     * in-test {@link PasswordRequirementsDao} double. The DAO double
     * lets PUT requests round-trip through {@link
     * AdminApiController#newPasswordDao()} without touching Hibernate.
     */
    private MockMvc mockMvcWith(DataSource ds, DatabaseChangeLogDao dbLog,
                                ConfigurationDao cfgDao,
                                PasswordRequirementsDao passDao) {
        AdminApiController controller = new AdminApiController(ds, dbLog, cfgDao) {
            @Override
            protected PasswordRequirementsDao newPasswordDao() {
                return passDao;
            }
        };
        return mockMvcFor(controller);
    }

    private MockMvc baseMockMvc() {
        DataSource ds = Mockito.mock(DataSource.class);
        DatabaseChangeLogDao dbLog = Mockito.mock(DatabaseChangeLogDao.class);
        when(dbLog.count()).thenReturn(42L);
        ConfigurationDao cfg = Mockito.mock(ConfigurationDao.class);
        PasswordRequirementsDao passDao = stubPasswordDao();

        return mockMvcWith(ds, dbLog, cfg, passDao);
    }

    private PasswordRequirementsDao stubPasswordDao() {
        ConfigurationDao cfg = Mockito.mock(ConfigurationDao.class);
        PasswordRequirementsDao real = Mockito.mock(PasswordRequirementsDao.class);
        when(real.hasLower()).thenReturn(true);
        when(real.hasUpper()).thenReturn(true);
        when(real.hasDigits()).thenReturn(true);
        when(real.hasSpecials()).thenReturn(false);
        when(real.minLength()).thenReturn(8);
        when(real.maxLength()).thenReturn(64);
        when(real.expirationDays()).thenReturn(90);
        when(real.changeRequired()).thenReturn(true);
        return real;
    }

    /* ====================================================================== */
    /* Auth gate                                                              */
    /* ====================================================================== */

    @Test
    void systemStatusReturns401WhenAnonymous() throws Exception {
        baseMockMvc()
                .perform(get("/api/v1/admin/system-status")
                        .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void systemStatusReturns403WhenNotSysadmin() throws Exception {
        baseMockMvc()
                .perform(get("/api/v1/admin/system-status")
                        .session((MockHttpSession)
                                authenticatedSession(7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());
    }

    /* ====================================================================== */
    /* System status — happy path                                             */
    /* ====================================================================== */

    @Test
    void systemStatusReturnsJvmAndDbAndAppSections() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("14.10");
        when(conn.getMetaData()).thenReturn(md);
        when(conn.isValid(2)).thenReturn(true);
        when(ds.getConnection()).thenReturn(conn);

        DatabaseChangeLogDao dbLog = Mockito.mock(DatabaseChangeLogDao.class);
        when(dbLog.count()).thenReturn(42L);
        ConfigurationDao cfg = Mockito.mock(ConfigurationDao.class);
        PasswordRequirementsDao pass = stubPasswordDao();

        mockMvcWith(ds, dbLog, cfg, pass)
                .perform(get("/api/v1/admin/system-status")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvm.heapMaxMb").isNumber())
                .andExpect(jsonPath("$.jvm.threadCount").isNumber())
                .andExpect(jsonPath("$.database.reachable").value(true))
                .andExpect(jsonPath("$.database.liquibaseChangelogCount").value(42))
                .andExpect(jsonPath("$.database.databaseProductName").value("PostgreSQL"))
                .andExpect(jsonPath("$.application.status").value("OK"));
    }

    /* ====================================================================== */
    /* Password policy                                                        */
    /* ====================================================================== */

    @Test
    void passwordPolicyGetReturnsPersistedShape() throws Exception {
        baseMockMvc()
                .perform(get("/api/v1/admin/password-policy")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireLower").value(true))
                .andExpect(jsonPath("$.requireSpecials").value(false))
                .andExpect(jsonPath("$.minLength").value(8))
                .andExpect(jsonPath("$.maxLength").value(64))
                .andExpect(jsonPath("$.expirationDays").value(90))
                .andExpect(jsonPath("$.changeRequiredOnFirstLogin").value(true))
                .andExpect(jsonPath("$.specialsAlphabet").value(Matchers.notNullValue()));
    }

    @Test
    void passwordPolicyPutReturns400OnMinGreaterThanMax() throws Exception {
        baseMockMvc()
                .perform(put("/api/v1/admin/password-policy")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study"))
                        .contentType("application/json")
                        .content("{\"minLength\":40,\"maxLength\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='minLength')]")
                        .value(Matchers.not(Matchers.empty())));
    }

    @Test
    void passwordPolicyPutReturns400OnExpirationOutOfRange() throws Exception {
        baseMockMvc()
                .perform(put("/api/v1/admin/password-policy")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study"))
                        .contentType("application/json")
                        .content("{\"expirationDays\":99999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='expirationDays')]")
                        .value(Matchers.not(Matchers.empty())));
    }

    @Test
    void passwordPolicyPutReturns400OnNullBody() throws Exception {
        baseMockMvc()
                .perform(put("/api/v1/admin/password-policy")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study"))
                        .contentType("application/json")
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    /* ====================================================================== */
    /* Config                                                                 */
    /* ====================================================================== */

    @Test
    void configReturnsReadOnlyFlagAndJvmDerivedFields() throws Exception {
        baseMockMvc()
                .perform(get("/api/v1/admin/config")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.defaultTimezone").value(Matchers.notNullValue()))
                .andExpect(jsonPath("$.fileEncoding").value(Matchers.notNullValue()));
    }

    @Test
    void configReturns403WhenNotSysadmin() throws Exception {
        baseMockMvc()
                .perform(get("/api/v1/admin/config")
                        .session((MockHttpSession)
                                authenticatedSession(7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());
    }
}
