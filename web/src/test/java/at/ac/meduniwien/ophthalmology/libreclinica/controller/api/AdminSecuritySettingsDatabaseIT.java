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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.JpaConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.DatabaseChangeLogDao;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.MailNotificationService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.TwoFactorService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.OpenClinicaUsernamePasswordAuthenticationFilter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R1.2 — the security settings on the password-policy page: the account
 * lockout the legacy {@code /Configure} page edited, next to the password
 * rules, against a real PostgreSQL with the production changelog applied.
 *
 * <p>The configuration table is read and written through the production
 * {@link ConfigurationDao}, in a Spring context built from the production
 * {@link JpaConfig} (Hibernate, second-level cache on). The same DAO bean
 * feeds the production login filter, so a test can change a setting through
 * the API and then log in and see it applied, with nothing restarted.
 *
 * <p>Pinned: only a system administrator reads or changes the settings; the
 * attempt count keeps the legacy form's 1–25; a change is stored the way the
 * legacy form stored it and leaves one audit row per changed key, as a
 * password-rule change now does too, and the system audit log names the
 * setting; saving the page unchanged writes and audits nothing, and keeps
 * the stored "force a change" and "no maximum"; and the switch and the
 * count take effect at the next failed login.
 */
class AdminSecuritySettingsDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String POLICY = "/api/v1/admin/password-policy";
    /** The keys the login filter reads; spelled out, as a rename would break it. */
    private static final String SWITCH = "user.lock.switch";
    private static final String ATTEMPTS = "user.lock.allowedFailedConsecutiveLoginAttempts";

    /** {@code AuditTypeIds.SYSTEM_SETTING_CHANGED}, as its seed changeset defines it. */
    private static final int SYSTEM_SETTING_CHANGED = 145;

    /** The account whose failed logins the lockout tests make. */
    private static final int PROBE_ID = 10201;
    private static final String PROBE = "lockout-probe";

    private static AnnotationConfigApplicationContext jpa;

    /** The configuration table as the changelog left it; restored before each test. */
    private static final Map<String, String> SEEDED = new LinkedHashMap<>();

    @BeforeAll
    static void startJpaAndSeed() throws Exception {
        jpa = new AnnotationConfigApplicationContext();
        jpa.getEnvironment().getPropertySources().addFirst(new MapPropertySource("it",
                Map.of("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")));
        jpa.registerBean(PropertySourcesPlaceholderConfigurer.class);
        jpa.registerBean("dataSource", DataSource.class, () -> DATA_SOURCE);
        jpa.register(JpaConfig.class);
        jpa.registerBean("configurationDao", ConfigurationDao.class);
        jpa.registerBean("auditUserLoginDao", AuditUserLoginDao.class);
        jpa.refresh();

        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT key, value FROM configuration")) {
                while (rs.next()) SEEDED.put(rs.getString(1), rs.getString(2));
            }
            st.execute("INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, "
                    + "email, active_study, institutional_affiliation, status_id, owner_id, "
                    + "date_created, user_type_id, enabled, account_non_locked, lock_counter, "
                    + "run_webservices, authtype, enable_api_key) "
                    + "VALUES (" + PROBE_ID + ", '" + PROBE + "', "
                    + "'{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG', "
                    + "'Lockout', 'Probe', 'lockout-probe@example.invalid', 1, 'MUW (test)', 1, 1, "
                    + "current_timestamp, 2, true, true, 0, false, 'STANDARD', false)");
        }
    }

    @AfterAll
    static void stopJpa() {
        if (jpa != null) jpa.close();
    }

    @BeforeEach
    void restore() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE configuration SET value = ? WHERE key = ?")) {
                for (Map.Entry<String, String> e : SEEDED.entrySet()) {
                    ps.setString(1, e.getValue());
                    ps.setString(2, e.getKey());
                    ps.executeUpdate();
                }
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE user_account SET status_id = 1, account_non_locked = true, "
                        + "lock_counter = 0 WHERE user_id = " + PROBE_ID);
                st.executeUpdate("DELETE FROM audit_log_event WHERE audit_table = 'configuration'");
                st.executeUpdate("DELETE FROM audit_user_login WHERE user_name = '" + PROBE + "'");
            }
        }
    }

    /* ---- wiring ------------------------------------------------------- */

    private MockMvc adminMvc() {
        AdminApiController controller = new AdminApiController(DATA_SOURCE,
                Mockito.mock(DatabaseChangeLogDao.class), jpa.getBean(ConfigurationDao.class));
        return ProductionMvc.standalone(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadmin() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        return s;
    }

    private static MockHttpSession physician() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(7);
        ub.setName("physician");
        s.setAttribute("userBean", ub);
        return s;
    }

    private JsonNode policy() throws Exception {
        return JSON.readTree(adminMvc().perform(get(POLICY).session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode save(String body) throws Exception {
        return JSON.readTree(adminMvc().perform(put(POLICY).session(sysadmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static String stored(String key) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM configuration WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The settings audit rows, oldest first: key, old value, new value, user, whether the id is the row's. */
    private static List<List<String>> auditRows() throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT a.entity_name, a.old_value, a.new_value, a.user_id, "
                             + "(a.entity_id = cfg.id) AS is_row, a.audit_log_event_type_id "
                             + "FROM audit_log_event a LEFT JOIN configuration cfg ON cfg.key = a.entity_name "
                             + "WHERE a.audit_table = 'configuration' ORDER BY a.audit_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                assertEquals(SYSTEM_SETTING_CHANGED, rs.getInt("audit_log_event_type_id"));
                out.add(List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                        String.valueOf(rs.getInt(4)), String.valueOf(rs.getBoolean(5))));
            }
        }
        return out;
    }

    /* ---- the login ---------------------------------------------------- */

    /**
     * The production login filter, reading the lockout through the same
     * {@link ConfigurationDao} bean the API writes through. Every password is
     * wrong: what is under test is what a failed login does.
     */
    private static OpenClinicaUsernamePasswordAuthenticationFilter loginFilter() {
        OpenClinicaUsernamePasswordAuthenticationFilter filter = new OpenClinicaUsernamePasswordAuthenticationFilter();
        filter.setAuthenticationManager(_ -> {
            throw new BadCredentialsException("Bad credentials");
        });
        filter.setConfigurationDao(jpa.getBean(ConfigurationDao.class));
        filter.setDataSource(DATA_SOURCE);
        filter.setFactorService(Mockito.mock(TwoFactorService.class));
        filter.setMailNotificationService(Mockito.mock(MailNotificationService.class));
        filter.setLoginAuditService(new LoginAuditService(jpa.getBean(AuditUserLoginDao.class)));
        return filter;
    }

    /**
     * One filter for the whole test, as production has one singleton bean:
     * a value the filter kept from an earlier attempt would show here.
     */
    private OpenClinicaUsernamePasswordAuthenticationFilter filter;

    @BeforeEach
    void newFilter() {
        filter = loginFilter();
    }

    /** One failed login as the probe account; the simple name of the refusal. */
    private String failLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/j_spring_security_check");
        request.addParameter("j_username", PROBE);
        request.addParameter("j_password", "not-the-password");
        try {
            filter.attemptAuthentication(request, new MockHttpServletResponse());
        } catch (AuthenticationException refused) {
            return refused.getClass().getSimpleName();
        }
        throw new AssertionError("every password is wrong in this test");
    }

    /** {@code [lock_counter, account_non_locked, status_id]} of the probe account. */
    private static List<Object> probe() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT lock_counter, account_non_locked, status_id FROM user_account WHERE user_id = ?")) {
            ps.setInt(1, PROBE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return List.of(rs.getInt(1), rs.getBoolean(2), rs.getInt(3));
            }
        }
    }

    private static final List<Object> OPEN_NO_FAILURES = List.of(0, true, 1);

    /* ---- reading ------------------------------------------------------ */

    @Test
    void theLockoutIsReadNextToThePasswordRulesAsStored() throws Exception {
        JsonNode p = policy();
        assertTrue(p.path("lockoutEnabled").isBoolean(), "the lockout switch is part of the page");
        assertEquals(Boolean.parseBoolean(SEEDED.get(SWITCH)), p.get("lockoutEnabled").asBoolean());
        assertEquals(Integer.parseInt(SEEDED.get(ATTEMPTS)), p.path("lockoutFailedAttempts").asInt(-1));

        // The 2012 seed: a change is required (1) and there is no maximum (-1).
        assertEquals("1", SEEDED.get("pwd.change.required"));
        assertTrue(p.get("changeRequiredOnFirstLogin").asBoolean());
        assertEquals("-1", SEEDED.get("pwd.chars.max"));
        assertEquals(0, p.get("maxLength").asInt());
    }

    @Test
    void onlyASystemAdministratorReadsOrChangesTheSettings() throws Exception {
        String body = "{\"lockoutEnabled\":true,\"lockoutFailedAttempts\":2,\"minLength\":20}";
        adminMvc().perform(get(POLICY).session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
        adminMvc().perform(get(POLICY).session(physician()))
                .andExpect(status().isForbidden());
        adminMvc().perform(put(POLICY).session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        adminMvc().perform(put(POLICY).session(physician())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        assertEquals(SEEDED.get(SWITCH), stored(SWITCH));
        assertEquals(SEEDED.get(ATTEMPTS), stored(ATTEMPTS));
        assertEquals(SEEDED.get("pwd.chars.min"), stored("pwd.chars.min"));
        assertTrue(auditRows().isEmpty());
    }

    /* ---- writing ------------------------------------------------------ */

    @Test
    void theAttemptCountKeepsTheLegacyRange() throws Exception {
        for (String refused : List.of("0", "26", "-3")) {
            adminMvc().perform(put(POLICY).session(sysadmin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lockoutFailedAttempts\":" + refused + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("lockoutFailedAttempts"));
        }
        adminMvc().perform(put(POLICY).session(sysadmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockoutFailedAttempts\":\"three\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(SEEDED.get(ATTEMPTS), stored(ATTEMPTS), "a refused value is not stored");

        save("{\"lockoutFailedAttempts\":1}");
        assertEquals("1", stored(ATTEMPTS));
        save("{\"lockoutFailedAttempts\":25}");
        assertEquals("25", stored(ATTEMPTS));
    }

    @Test
    void aLockoutChangeIsStoredAsTheLegacyFormStoredItAndAudited() throws Exception {
        // lc-muw-2026-10-06-account-lockout turns the lockout on, at five
        // attempts, wherever the seeded "off, three" was still in place.
        assertEquals("TRUE", SEEDED.get(SWITCH), "the lockout is on by default");
        assertEquals("5", SEEDED.get(ATTEMPTS), "five failed attempts by default");

        JsonNode after = save("{\"lockoutEnabled\":false,\"lockoutFailedAttempts\":7}");
        assertFalse(after.path("lockoutEnabled").asBoolean(), "the answer carries the stored switch");
        assertEquals(7, after.path("lockoutFailedAttempts").asInt(-1));

        assertEquals("FALSE", stored(SWITCH));
        assertEquals("7", stored(ATTEMPTS));
        assertEquals(List.of(
                        List.of(SWITCH, SEEDED.get(SWITCH), "FALSE", "1", "true"),
                        List.of(ATTEMPTS, SEEDED.get(ATTEMPTS), "7", "1", "true")),
                auditRows());

        save("{\"lockoutEnabled\":true}");
        assertEquals("TRUE", stored(SWITCH));
        assertEquals(3, auditRows().size());
    }

    @Test
    void aPasswordRuleChangeIsAuditedToo() throws Exception {
        save("{\"minLength\":12,\"requireDigits\":true}");
        assertEquals(List.of(
                        List.of("pwd.chars.digits", SEEDED.get("pwd.chars.digits"), "true", "1", "true"),
                        List.of("pwd.chars.min", SEEDED.get("pwd.chars.min"), "12", "1", "true")),
                auditRows());
    }

    @Test
    void theSystemAuditLogNamesTheSettingThatChanged() throws Exception {
        save("{\"lockoutEnabled\":false}");

        MockMvc audit = ProductionMvc.standalone(
                        new AuditApiController(DATA_SOURCE, new SiteVisibilityFilter(DATA_SOURCE)))
                .build();
        JsonNode rows = JSON.readTree(audit.perform(get("/api/v1/audit/system").session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode r : rows.path("events")) {
            if (SWITCH.equals(r.path("details").asText())) row = r;
        }
        assertNotNull(row, "the change is listed with the key of the setting");
        assertEquals("System setting changed", row.get("title").asText());
        assertEquals("admin", row.get("variant").asText());
        assertEquals("root", row.get("actor").asText());
        assertEquals("yes", row.get("before").asText());
        assertEquals("no", row.get("after").asText());
    }

    @Test
    void savingThePageUnchangedWritesAndAuditsNothing() throws Exception {
        JsonNode page = policy();
        save(JSON.writeValueAsString(page));

        for (Map.Entry<String, String> e : SEEDED.entrySet()) {
            assertEquals(e.getValue(), stored(e.getKey()), e.getKey() + " must survive a save of the page as shown");
        }
        assertTrue(auditRows().isEmpty(), "nothing changed, so nothing is audited");
    }

    /* ---- the login ---------------------------------------------------- */

    @Test
    void theLockoutTakesEffectAtTheNextFailedLogin() throws Exception {
        save("{\"lockoutEnabled\":false,\"lockoutFailedAttempts\":2}");
        for (int i = 0; i < 4; i++) {
            assertEquals("BadCredentialsException", failLogin());
        }
        assertEquals(OPEN_NO_FAILURES, probe(), "lockout off: failures are neither counted nor locked");

        // Switched on through the API; the filter reads it at the next attempt.
        save("{\"lockoutEnabled\":true}");
        assertEquals("BadCredentialsException", failLogin());
        assertEquals(List.of(1, true, 1), probe());
        assertEquals("BadCredentialsException", failLogin());
        assertEquals(List.of(2, false, 6), probe(), "the second failure locks the account");
        assertEquals("LockedException", failLogin(), "a locked account is refused as locked");

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT login_status_code, COUNT(*) FROM audit_user_login WHERE user_name = ? "
                             + "GROUP BY login_status_code ORDER BY login_status_code")) {
            ps.setString(1, PROBE);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) rows.add(rs.getInt(1) + ":" + rs.getInt(2));
                assertEquals(List.of("2:6", "3:1"), rows, "six failed logins and one refused as locked");
            }
        }
    }

    @Test
    void aChangedCountAppliesWithoutARestart() throws Exception {
        save("{\"lockoutEnabled\":true,\"lockoutFailedAttempts\":5}");
        failLogin();
        failLogin();
        assertEquals(List.of(2, true, 1), probe());

        // Lowered to 3: the third failure locks, where 5 would have allowed two more.
        save("{\"lockoutFailedAttempts\":3}");
        assertEquals("BadCredentialsException", failLogin());
        assertEquals(List.of(3, false, 6), probe());

        // Raising it again does not unlock the account; an administrator does that.
        save("{\"lockoutFailedAttempts\":10}");
        assertEquals("LockedException", failLogin());
        assertFalse((Boolean) probe().get(1));
    }
}
