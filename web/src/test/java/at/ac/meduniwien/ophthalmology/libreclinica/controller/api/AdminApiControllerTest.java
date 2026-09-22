/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.DatabaseChangeLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.PasswordRequirementsDao;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth.NodeSpec;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth.NodeStatus;

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
 *   <li>Retinal cluster (2026-09-22): auth gate; "not configured" when
 *       no URL is set; one row per node from a canned prober, with
 *       the degraded node naming its missing tasks; the monitor log
 *       tail, and the unreadable-log case reported rather than
 *       swallowed.</li>
 * </ul>
 */
@SuppressWarnings("null")
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
        Mockito.mock(ConfigurationDao.class);
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

    /* ====================================================================== */
    /* Retinal inference cluster                                              */
    /* ====================================================================== */

    /** Controller whose config + network are both under test control. */
    private MockMvc clusterMockMvc(Map<String, String> config, List<NodeStatus> canned) {
        AdminApiController controller = new AdminApiController(
                Mockito.mock(DataSource.class),
                Mockito.mock(DatabaseChangeLogDao.class),
                Mockito.mock(ConfigurationDao.class)) {
            @Override
            protected String configField(String key, String fallback) {
                return config.getOrDefault(key, fallback);
            }

            @Override
            protected List<NodeStatus> probeCluster(List<NodeSpec> specs) {
                return canned; // never opens a socket
            }
        };
        return mockMvcFor(controller);
    }

    @Test
    void retinalClusterReturns403WhenNotSysadmin() throws Exception {
        clusterMockMvc(Map.of(), List.of())
                .perform(get("/api/v1/admin/retinal-cluster")
                        .session((MockHttpSession)
                                authenticatedSession(7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retinalClusterReportsNotConfiguredWhenNoUrlAtAll() throws Exception {
        clusterMockMvc(Map.of(), List.of())
                .perform(get("/api/v1/admin/retinal-cluster")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.nodes").isEmpty())
                .andExpect(jsonPath("$.monitor").doesNotExist());
    }

    @Test
    void retinalClusterReturnsOneRowPerNodeAndTheMonitorTail() throws Exception {
        Path log = Files.createTempFile("retinal-monitor", ".log");
        try {
            Files.writeString(log, "old line\nRETINAL CLUSTER PROBLEM (http://on3:8000/health), failed check #1\n");

            NodeStatus healthy = new NodeStatus("on3", "http://on3:8000", "healthy",
                    RetinalClusterHealth.EXPECTED_TASKS, List.of(), 14L,
                    "on3", "2", "NVIDIA GeForce RTX 2080 Ti", null);
            NodeStatus degraded = new NodeStatus("cn6", "http://cn6:8000", "degraded",
                    List.of("fluid", "ga", "onl", "pr"), List.of("bm", "layers"), 9L,
                    "cn6", "3", "NVIDIA GeForce RTX 2080 Ti", null);

            clusterMockMvc(Map.of(
                            "core.retinalInference.remotePushUrl", "http://nginx:8088",
                            "core.retinalInference.clusterNodes", "on3=http://on3:8000,cn6=http://cn6:8000",
                            "core.retinalInference.clusterMonitorLog", log.toString()),
                    List.of(healthy, degraded))
                    .perform(get("/api/v1/admin/retinal-cluster")
                            .session((MockHttpSession)
                                    authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(true))
                    .andExpect(jsonPath("$.remotePushUrl").value("http://nginx:8088"))
                    .andExpect(jsonPath("$.expectedTasks", Matchers.hasSize(6)))
                    .andExpect(jsonPath("$.nodes", Matchers.hasSize(2)))
                    .andExpect(jsonPath("$.nodes[0].name").value("on3"))
                    .andExpect(jsonPath("$.nodes[0].state").value("healthy"))
                    .andExpect(jsonPath("$.nodes[0].gpuName").value("NVIDIA GeForce RTX 2080 Ti"))
                    .andExpect(jsonPath("$.nodes[1].state").value("degraded"))
                    .andExpect(jsonPath("$.nodes[1].missingTasks", Matchers.contains("bm", "layers")))
                    .andExpect(jsonPath("$.monitor.readable").value(true))
                    .andExpect(jsonPath("$.monitor.lines", Matchers.hasSize(2)))
                    .andExpect(jsonPath("$.monitor.lines[1]", Matchers.containsString("failed check #1")));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    @Test
    void retinalClusterReportsAnUnreadableMonitorLogInsteadOfHidingIt() throws Exception {
        clusterMockMvc(Map.of(
                        "core.retinalInference.remotePushUrl", "http://on3:8000",
                        "core.retinalInference.clusterMonitorLog", "/definitely/not/here.log"),
                List.of())
                .perform(get("/api/v1/admin/retinal-cluster")
                        .session((MockHttpSession)
                                authenticatedSysadminSession(1, "root", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isOk())
                // No node list -> the push URL becomes the single "remote" row's spec.
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.monitor.path").value("/definitely/not/here.log"))
                .andExpect(jsonPath("$.monitor.readable").value(false))
                .andExpect(jsonPath("$.monitor.lines").isEmpty());
    }
}
