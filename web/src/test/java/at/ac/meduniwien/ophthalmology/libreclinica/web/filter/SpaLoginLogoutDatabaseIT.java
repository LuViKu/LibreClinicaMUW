/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AuthApiController;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.MeApiController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.CRFLocker;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.ConfigurationBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.PasswordRehashService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.MailNotificationService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.TwoFactorService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;

/**
 * The SPA's sign-in and sign-out against a real database, through the login
 * filter and handlers exactly as {@code applicationContext-security.xml}
 * wires them. What the XML takes from elsewhere is supplied here: a
 * local-password provider over the user query of
 * {@code applicationContext-core-security.xml} (without LDAP, which is off by
 * default), the configuration read from the {@code configuration} table, and
 * an audit DAO that records the rows the code asks it to write.
 *
 * <p>The SPA's login answers 204 or 401 with the reason as JSON and sets
 * up what the SPA used to get from following the redirect to
 * {@code /MainMenu}; a browser form login still gets that redirect. The
 * SPA's sign-out writes the logout audit row and ends the session.
 */
class SpaLoginLogoutDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String PASSWORD = "12345678";
    /** bcrypt of {@link #PASSWORD}, as in the demo seeds. */
    private static final String HASH = "{bcrypt}$2a$10$9QHaEdYWWSRQKYOaOECfbuQf8L1I1zWUPevUyMderR4S/ZmIc5/dG";

    private static final String JSON = "application/json";
    private static final String BROWSER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    /** Distinct from SecureController's fallback (3600) and Tomcat's default (1800). */
    private static final int IDLE_TIMEOUT = 2700;

    private static final int DM = 30101;
    private static final int VISIT = 30102;
    private static final int WRONG = 30103;
    private static final int LOCKOUT = 30104;
    private static final int EXPIRED = 30105;
    private static final int FIRST = 30106;
    private static final int TWO_FACTOR = 30107;
    private static final int LEGACY = 30108;
    private static final int LEGACY_LOCKED = 30109;
    private static final int LOGOUT = 30110;

    private static Properties savedSqlInitParams;

    private final List<AuditUserLoginBean> audit = new ArrayList<>();
    private DefaultListableBeanFactory beans;
    private OpenClinicaUsernamePasswordAuthenticationFilter filter;
    private TwoFactorService twoFactor;
    private MockHttpServletRequest lastRequest;
    private DataSource savedStaticDataSource;

    @BeforeAll
    static void seedAccountsAndSettings() throws Exception {
        seedUser(DM, "spa-dm", "director", "current_date");
        seedUser(VISIT, "spa-visit", "Investigator", "current_date");
        seedUser(WRONG, "spa-wrong", "Investigator", "current_date");
        seedUser(LOCKOUT, "spa-lockout", "Investigator", "current_date");
        seedUser(EXPIRED, "spa-expired", "Investigator", "current_date - 400");
        seedUser(FIRST, "spa-first", "Investigator", "NULL");
        seedUser(TWO_FACTOR, "spa-2fa", "Investigator", "current_date");
        seedUser(LEGACY, "spa-legacy", "Investigator", "current_date");
        seedUser(LEGACY_LOCKED, "spa-legacy-locked", "Investigator", "current_date");
        seedUser(LOGOUT, "spa-logout", "Investigator", "current_date");
        sql("UPDATE user_account SET status_id = " + Status.LOCKED.getId()
                + ", account_non_locked = false WHERE user_id = " + LEGACY_LOCKED);
        // Lock after three consecutive failures (off by default).
        sql("UPDATE configuration SET value = 'TRUE' WHERE key = 'user.lock.switch'");
        sql("UPDATE configuration SET value = '3' WHERE key = 'user.lock.allowedFailedConsecutiveLoginAttempts'");

        Field params = SQLInitServlet.class.getDeclaredField("params");
        params.setAccessible(true);
        Properties current = (Properties) params.get(null);
        savedSqlInitParams = new Properties();
        savedSqlInitParams.putAll(current);
        Properties staged = new Properties();
        staged.putAll(current);
        staged.setProperty("change_passwd_required", "1");
        staged.setProperty("passwd_expiration_time", "180");
        staged.setProperty("max_inactive_interval", String.valueOf(IDLE_TIMEOUT));
        params.set(null, staged);
    }

    @AfterAll
    static void restoreSqlInitParams() throws Exception {
        Field params = SQLInitServlet.class.getDeclaredField("params");
        params.setAccessible(true);
        params.set(null, savedSqlInitParams);
    }

    private static void seedUser(int id, String name, String role, String passwdTimestamp) throws SQLException {
        sql("INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, email, active_study, "
                + "institutional_affiliation, status_id, owner_id, date_created, passwd_timestamp, user_type_id, "
                + "enabled, account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                + "VALUES (" + id + ", '" + name + "', '" + HASH + "', 'Spa', 'Login', '" + name
                + "@example.invalid', 1, 'MUW (test)', 1, 1, current_date, " + passwdTimestamp
                + ", 2, true, true, 0, false, 'STANDARD', false)");
        sql("INSERT INTO authorities (username, authority, version) VALUES ('" + name + "', 'ROLE_USER', 1)");
        sql("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name) "
                + "VALUES ('" + role + "', 1, 1, 1, current_date, '" + name + "')");
    }

    private static void sql(String statement) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    @BeforeEach
    void wireTheSecurityBeans() {
        beans = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(beans).loadBeanDefinitions(new ClassPathResource(
                "at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml"));

        OpenClinicaJdbcService users = new OpenClinicaJdbcService();
        users.setDataSource(DATA_SOURCE);
        users.setUsersByUsernameQuery(
                "SELECT user_name,passwd,enabled,account_non_locked FROM user_account WHERE user_name = ?");
        // The provider's default encoder delegates on the {bcrypt} prefix of
        // the seeded hashes, as PasswordEncoderConfig's does.
        DaoAuthenticationProvider local = new DaoAuthenticationProvider(users);

        AuditUserLoginDao auditDao = new AuditUserLoginDao() {
            @Override
            public AuditUserLoginBean saveOrUpdate(AuditUserLoginBean row) {
                audit.add(row);
                return row;
            }
        };
        twoFactor = mock(TwoFactorService.class);
        beans.registerSingleton("dataSource", DATA_SOURCE);
        beans.registerSingleton("authenticationManager", new ProviderManager(local));
        beans.registerSingleton("auditUserLoginDao", auditDao);
        beans.registerSingleton("loginAuditService", new LoginAuditService(auditDao));
        beans.registerSingleton("configurationDao", configurationTable());
        beans.registerSingleton("crfLocker", new CRFLocker());
        beans.registerSingleton("factorService", twoFactor);
        beans.registerSingleton("mailNotificationService", mock(MailNotificationService.class));
        // Rewrites legacy MD5/SHA-1 hashes only; the seeded ones are bcrypt.
        beans.registerSingleton("passwordRehashService", mock(PasswordRehashService.class));
        beans.registerSingleton("ssoProperties", new SsoProperties());
        filter = beans.getBean("myFilter", OpenClinicaUsernamePasswordAuthenticationFilter.class);

        savedStaticDataSource = SessionManager.getStaticDataSource();
        SessionManager.setStaticDataSource(null);
    }

    @AfterEach
    void clearThreadAndStatics() {
        SecurityContextHolder.clearContext();
        SessionManager.setStaticDataSource(savedStaticDataSource);
    }

    /** ConfigurationDao reading the configuration table directly instead of through Hibernate. */
    private static ConfigurationDao configurationTable() {
        return new ConfigurationDao() {
            @Override
            public ConfigurationBean findByKey(String key) {
                try (Connection c = DATA_SOURCE.getConnection();
                     PreparedStatement ps = c.prepareStatement("SELECT value FROM configuration WHERE key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        ConfigurationBean bean = new ConfigurationBean();
                        bean.setKey(key);
                        bean.setValue(rs.getString(1));
                        return bean;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    // --- requests --------------------------------------------------------

    private MockHttpServletResponse login(String username, String password, String accept) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/LibreClinica/j_spring_security_check");
        request.setContextPath("/LibreClinica");
        request.setServletPath("/j_spring_security_check");
        request.addParameter("j_username", username);
        request.addParameter("j_password", password);
        request.addHeader("Accept", accept);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        lastRequest = request;
        return response;
    }

    private MockHttpSession session() {
        return (MockHttpSession) lastRequest.getSession(false);
    }

    private static ResultActions me(MockHttpSession session) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeApiController(DATA_SOURCE)).build();
        return mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk());
    }

    private static String reason(MockHttpServletResponse response) throws Exception {
        return new ObjectMapper().readTree(response.getContentAsString()).get("error").asText();
    }

    private List<LoginStatus> audited() {
        return audit.stream().map(AuditUserLoginBean::getLoginStatus).toList();
    }

    private static Object column(String column, int userId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM user_account WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    // --- the SPA's login ---------------------------------------------------

    @Test
    void anSpaLoginIsAnswered204AndSignsIn() throws Exception {
        MockHttpServletResponse response = login("spa-dm", PASSWORD, JSON);

        assertEquals(204, response.getStatus());
        assertNull(response.getRedirectedUrl());
        HttpSession session = session();
        assertNotNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertEquals("spa-dm", ((UserAccountBean) session.getAttribute("userBean")).getName());
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGIN), audited());
    }

    @Test
    void theSpaLoginBindsTheStoredActiveStudyAndRole() throws Exception {
        assertEquals(204, login("spa-dm", PASSWORD, JSON).getStatus());

        MockHttpSession session = session();
        StudyBean study = (StudyBean) session.getAttribute("study");
        assertNotNull(study, "the session is bound to the active study");
        assertEquals(1, study.getId());
        assertEquals(Role.STUDYDIRECTOR, ((StudyUserRoleBean) session.getAttribute("userRole")).getRole());
        me(session)
                .andExpect(jsonPath("$.activeStudy.id").value(1))
                .andExpect(jsonPath("$.activeStudy.oid").value("S_DEFAULTS1"))
                .andExpect(jsonPath("$.role").value("Data Manager"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void theSpaLoginRecordsTheVisitAndNothingElse() throws Exception {
        assertNull(column("date_lastvisit", VISIT));
        Object updatedBefore = column("date_updated", VISIT);

        assertEquals(204, login("spa-visit", PASSWORD, JSON).getStatus());

        assertTrue(column("date_lastvisit", VISIT) instanceof Timestamp, "the visit is recorded");
        assertEquals(updatedBefore, column("date_updated", VISIT));
        assertNull(column("update_id", VISIT));
    }

    @Test
    void theSpaLoginKeepsTheConfiguredIdleTimeout() throws Exception {
        assertEquals(204, login("spa-dm", PASSWORD, JSON).getStatus());

        assertEquals(IDLE_TIMEOUT, session().getMaxInactiveInterval());
    }

    @Test
    void theSpaLoginGivesLazyLoadingBeansTheirDataSource() throws Exception {
        assertEquals(204, login("spa-dm", PASSWORD, JSON).getStatus());

        assertSame(DATA_SOURCE, SessionManager.getStaticDataSource());
    }

    @Test
    void anExpiredPasswordMustBeChangedAfterAnSpaLogin() throws Exception {
        assertEquals(204, login("spa-expired", PASSWORD, JSON).getStatus());

        me(session())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.passwordChangeReason").value("rotation"));
    }

    @Test
    void aFirstLoginMustChangeThePasswordAfterAnSpaLogin() throws Exception {
        assertEquals(204, login("spa-first", PASSWORD, JSON).getStatus());

        me(session())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.passwordChangeReason").value("first-login"));
    }

    @Test
    void aWrongPasswordIsAnswered401AndCounted() throws Exception {
        MockHttpServletResponse response = login("spa-wrong", "not-the-password", JSON);

        assertEquals(401, response.getStatus());
        assertNull(response.getRedirectedUrl());
        assertTrue(response.getContentType().startsWith(JSON));
        assertEquals("bad_credentials", reason(response));
        assertEquals(1, column("lock_counter", WRONG));
        assertEquals(List.of(LoginStatus.FAILED_LOGIN), audited());
    }

    @Test
    void theAccountLocksAfterTheAllowedFailuresAndTheSpaIsToldSo() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            MockHttpServletResponse response = login("spa-lockout", "not-the-password", JSON);
            assertEquals(401, response.getStatus());
            assertEquals("bad_credentials", reason(response), "attempt " + attempt);
        }
        assertEquals(Status.LOCKED.getId(), column("status_id", LOCKOUT));
        assertEquals(false, column("account_non_locked", LOCKOUT));

        MockHttpServletResponse locked = login("spa-lockout", PASSWORD, JSON);

        assertEquals(401, locked.getStatus());
        assertEquals("locked", reason(locked));
        assertEquals(List.of(LoginStatus.FAILED_LOGIN, LoginStatus.FAILED_LOGIN, LoginStatus.FAILED_LOGIN,
                LoginStatus.FAILED_LOGIN_LOCKED), audited());
    }

    @Test
    void aTwoFactorSetUpToRenewIsReported() throws Exception {
        when(twoFactor.isTwoFactorActivatedLetterAndOutDated()).thenReturn(true);

        MockHttpServletResponse response = login("spa-2fa", PASSWORD, JSON);

        assertEquals(401, response.getStatus());
        assertEquals("2fa_outdated", reason(response));
    }

    // --- the legacy form login, unchanged ----------------------------------

    @Test
    void aFormLoginStillRedirectsToTheMainMenu() throws Exception {
        MockHttpServletResponse response = login("spa-legacy", PASSWORD, BROWSER);

        assertEquals(302, response.getStatus());
        assertEquals("/LibreClinica/MainMenu", response.getRedirectedUrl());
        assertNull(session().getAttribute("study"), "the main menu binds the study, as before");
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGIN), audited());
    }

    @Test
    void aFailedFormLoginStillRedirectsToTheLoginPage() throws Exception {
        MockHttpServletResponse response = login("spa-legacy", "not-the-password", BROWSER);

        assertEquals(302, response.getStatus());
        assertEquals("/LibreClinica/pages/login/login?action=errorLogin", response.getRedirectedUrl());
    }

    @Test
    void aLockedAccountsFormLoginStillRedirectsToTheLockedMessage() throws Exception {
        MockHttpServletResponse response = login("spa-legacy-locked", PASSWORD, BROWSER);

        assertEquals(302, response.getStatus());
        assertEquals("/LibreClinica/pages/login/login?action=errorLocked", response.getRedirectedUrl());
        assertEquals(List.of(LoginStatus.FAILED_LOGIN_LOCKED), audited());
    }

    // --- the SPA's sign-out --------------------------------------------------

    @Test
    void theSpaLogoutWritesTheLogoutRowAndEndsTheSession() throws Exception {
        assertEquals(204, login("spa-logout", PASSWORD, JSON).getStatus());
        MockHttpSession session = session();
        SessionRegistry registry = beans.getBean("sessionRegistry", SessionRegistry.class);
        assertNotNull(registry.getSessionInformation(session.getId()), "the login registered the session");
        CRFLocker locker = beans.getBean("crfLocker", CRFLocker.class);
        locker.lock(4711, LOGOUT);
        audit.clear();
        // What the security filter chain loads for the request.
        SecurityContextHolder.setContext((SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));

        MockMvcBuilders.standaloneSetup(new AuthApiController(registry, locker)).build()
                .perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isNoContent());

        assertTrue(session.isInvalid(), "the session is invalidated");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the security context is cleared");
        assertNull(registry.getSessionInformation(session.getId()));
        assertFalse(locker.isLocked(4711), "the user's CRF locks are released");
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGOUT), audited(), "one logout row");
        assertEquals("spa-logout", audit.get(0).getUserName());
        assertEquals(LOGOUT, audit.get(0).getUserAccountId());
    }
}
