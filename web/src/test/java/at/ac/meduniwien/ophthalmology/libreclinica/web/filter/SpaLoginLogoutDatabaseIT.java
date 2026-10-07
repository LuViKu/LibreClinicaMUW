/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
import java.util.Objects;
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
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.support.GenericWebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SecurityConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AuthApiController;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.MeApiController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.CRFLocker;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.ConfigurationDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.AuditUserLoginBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.ConfigurationBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.PasswordRehashService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.MailNotificationService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.otp.TwoFactorService;
import at.ac.meduniwien.ophthalmology.libreclinica.web.PublicOctUploadRateLimitFilter;
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
 *
 * <p>Every way a session is signed out writes that row once: the SPA's
 * sign-out, the legacy screens' {@code /j_spring_security_logout} through the
 * logout filter {@link SecurityConfig} builds, and the next request of a
 * session a second login displaced. The sessions here tell the session
 * registry when they end, as Tomcat's do, so a row the registry writes for a
 * dying session is counted too.
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
    private static final int NAVBAR = 30111;
    private static final int SSO_NAVBAR = 30112;
    private static final int NAVBAR_FAIL = 30113;
    private static final int DISPLACED = 30114;
    private static final int SSO_SPA = 30115;
    private static final int LOGOUT_FAIL = 30116;
    private static final int LOGOUT_GET = 30117;
    private static final int BROWSER_FIRST = 30118;
    private static final int BROWSER_SECOND = 30119;
    private static final int NO_STUDY = 30120;
    private static final int ON_SITE = 30121;
    private static final int IN_REMOVED = 30122;
    private static final int IN_AUTO_REMOVED = 30123;
    private static final int ROLE_REMOVED = 30124;
    private static final int NO_ROLE = 30125;
    private static final int SYS_ADMIN = 30126;
    private static final int SYS_ADMIN_REMOVED_STUDY = 30127;

    /** S_DEFAULTS1, the study every other account here is bound to. */
    private static final int DEFAULT_STUDY = 1;
    private static final int SITE = 30201;
    private static final int REMOVED_STUDY = 30202;
    private static final int AUTO_REMOVED_STUDY = 30203;

    private static Properties savedSqlInitParams;

    private final List<AuditUserLoginBean> audit = new ArrayList<>();
    /** The audit DAO fails every logout row, as with the database unreachable. */
    private boolean refuseLogoutRows;
    private DefaultListableBeanFactory beans;
    private OpenClinicaUsernamePasswordAuthenticationFilter filter;
    private TwoFactorService twoFactor;
    private MockHttpServletRequest lastRequest;
    private DataSource savedStaticDataSource;
    private GenericWebApplicationContext securityConfig;

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
        seedUser(NAVBAR, "spa-navbar", "Investigator", "current_date");
        seedUser(SSO_NAVBAR, "spa-sso-navbar", "Investigator", "current_date");
        seedUser(NAVBAR_FAIL, "spa-navbar-fail", "Investigator", "current_date");
        seedUser(DISPLACED, "spa-displaced", "Investigator", "current_date");
        seedUser(SSO_SPA, "spa-sso", "Investigator", "current_date");
        seedUser(LOGOUT_FAIL, "spa-logout-fail", "Investigator", "current_date");
        seedUser(LOGOUT_GET, "spa-logout-get", "Investigator", "current_date");
        seedUser(BROWSER_FIRST, "spa-browser-first", "director", "current_date");
        seedUser(BROWSER_SECOND, "spa-browser-second", "Investigator", "current_date");
        seedAccount(NO_STUDY, "spa-no-study", "NULL");
        seedStudy(SITE, DEFAULT_STUDY, Status.AVAILABLE);
        seedAccount(ON_SITE, "spa-on-site", String.valueOf(SITE));
        grant("spa-on-site", "Investigator", SITE);
        grant("spa-on-site", "director", DEFAULT_STUDY);
        seedStudy(REMOVED_STUDY, null, Status.DELETED);
        seedAccount(IN_REMOVED, "spa-in-removed", String.valueOf(REMOVED_STUDY));
        grant("spa-in-removed", "director", REMOVED_STUDY);
        seedStudy(AUTO_REMOVED_STUDY, null, Status.AUTO_DELETED);
        seedAccount(IN_AUTO_REMOVED, "spa-in-auto-removed", String.valueOf(AUTO_REMOVED_STUDY));
        grant("spa-in-auto-removed", "director", AUTO_REMOVED_STUDY);
        seedAccount(ROLE_REMOVED, "spa-role-removed", String.valueOf(DEFAULT_STUDY));
        grant("spa-role-removed", "director", DEFAULT_STUDY);
        sql("UPDATE study_user_role SET status_id = " + Status.DELETED.getId()
                + " WHERE user_name = 'spa-role-removed'");
        seedAccount(NO_ROLE, "spa-no-role", String.valueOf(DEFAULT_STUDY));
        seedAccount(SYS_ADMIN, "spa-sys-admin", String.valueOf(DEFAULT_STUDY));
        sql("UPDATE user_account SET user_type_id = 1 WHERE user_id = " + SYS_ADMIN);
        seedAccount(SYS_ADMIN_REMOVED_STUDY, "spa-sys-admin-removed-study", String.valueOf(REMOVED_STUDY));
        sql("UPDATE user_account SET user_type_id = 1 WHERE user_id = " + SYS_ADMIN_REMOVED_STUDY);
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
        seedAccount(id, name, String.valueOf(DEFAULT_STUDY), passwdTimestamp);
        grant(name, role, DEFAULT_STUDY);
    }

    private static void seedAccount(int id, String name, String activeStudy) throws SQLException {
        seedAccount(id, name, activeStudy, "current_date");
    }

    private static void seedAccount(int id, String name, String activeStudy, String passwdTimestamp)
            throws SQLException {
        sql("INSERT INTO user_account (user_id, user_name, passwd, first_name, last_name, email, active_study, "
                + "institutional_affiliation, status_id, owner_id, date_created, passwd_timestamp, user_type_id, "
                + "enabled, account_non_locked, lock_counter, run_webservices, authtype, enable_api_key) "
                + "VALUES (" + id + ", '" + name + "', '" + HASH + "', 'Spa', 'Login', '" + name
                + "@example.invalid', " + activeStudy + ", 'MUW (test)', 1, 1, current_date, " + passwdTimestamp
                + ", 2, true, true, 0, false, 'STANDARD', false)");
        sql("INSERT INTO authorities (username, authority, version) VALUES ('" + name + "', 'ROLE_USER', 1)");
    }

    private static void grant(String name, String role, int studyId) throws SQLException {
        sql("INSERT INTO study_user_role (role_name, study_id, status_id, owner_id, date_created, user_name) "
                + "VALUES ('" + role + "', " + studyId + ", 1, 1, current_date, '" + name + "')");
    }

    /** A study, or a site of {@code parent}, in {@code status}. */
    private static void seedStudy(int id, Integer parent, Status status) throws SQLException {
        sql("INSERT INTO study (study_id, parent_study_id, unique_identifier, secondary_identifier, "
                + "name, summary, date_planned_start, date_planned_end, date_created, "
                + "owner_id, type_id, status_id, principal_investigator, facility_name, "
                + "facility_city, facility_state, facility_zip, facility_country, "
                + "facility_recruitment_status, facility_contact_name, facility_contact_degree, "
                + "facility_contact_phone, facility_contact_email, protocol_type, "
                + "protocol_description, protocol_date_verification, phase, "
                + "expected_total_enrollment, sponsor, collaborators, medline_identifier, "
                + "url, url_description, conditions, keywords, eligibility, gender, "
                + "age_max, age_min, healthy_volunteer_accepted, purpose, allocation, "
                + "masking, control, assignment, endpoint, interventions, duration, "
                + "selection, timing, official_title, results_reference, oc_oid) "
                + "VALUES (" + id + ", " + parent + ", 'spa-" + id + "', '', 'Study " + id + "', '', "
                + "NOW(), NOW(), NOW(), 1, 1, " + status.getId() + ", 'default', "
                + "'', '', '', '', '', '', '', '', '', '', 'observational', '', NOW(), "
                + "'default', 0, 'default', '', '', '', '', '', '', '', 'both', '', '', "
                + "false, 'Natural History', '', '', '', '', '', '', 'longitudinal', "
                + "'Convenience Sample', 'Retrospective', '', false, 'S_SPA" + id + "')");
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
                if (refuseLogoutRows && row.getLoginStatus() == LoginStatus.SUCCESSFUL_LOGOUT) {
                    throw new DataAccessResourceFailureException("audit_user_login cannot be reached");
                }
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
        if (securityConfig != null) {
            securityConfig.close();
        }
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
        return login(username, password, accept, null);
    }

    /** A login from a browser that still has {@code session}, or none when it is null. */
    private MockHttpServletResponse login(String username, String password, String accept, MockHttpSession session)
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/LibreClinica/j_spring_security_check") {
                    @Override
                    public HttpSession getSession(boolean create) {
                        if (create && super.getSession(false) == null) {
                            setSession(new PublishedSession());
                        }
                        return super.getSession(create);
                    }
                };
        request.setContextPath("/LibreClinica");
        request.setServletPath("/j_spring_security_check");
        if (session != null) {
            request.setSession(session);
        }
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

    private OpenClinicaSessionRegistryImpl registry() {
        return beans.getBean("sessionRegistry", OpenClinicaSessionRegistryImpl.class);
    }

    /**
     * A session that tells the session registry when it ends, as
     * HttpSessionEventPublisher does for Tomcat's sessions: before the session
     * is invalidated, and a listener that fails is logged, not thrown. A plain
     * MockHttpSession tells no one, so a logout row the registry writes for a
     * dying session would go unseen.
     */
    private final class PublishedSession extends MockHttpSession {
        @Override
        public void invalidate() {
            try {
                registry().onApplicationEvent(new HttpSessionDestroyedEvent(this));
            } catch (RuntimeException listenerFailure) {
                // Tomcat logs it and invalidates the session all the same.
            }
            super.invalidate();
        }
    }

    /**
     * A session as the SSO filter (libreclinica.sso.enabled) leaves it: the
     * pre-authenticated login in its security context, and the user bean the
     * screens put beside it. That filter saves the context without the session
     * strategy, so the session registry does not hold the session.
     */
    private MockHttpSession ssoSession(String username) {
        User principal = new User(username, "", AuthorityUtils.createAuthorityList("ROLE_USER"));
        MockHttpSession session = new PublishedSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new PreAuthenticatedAuthenticationToken(principal, "N/A", principal.getAuthorities())));
        session.setAttribute(SecureController.USER_BEAN_NAME, new UserAccountDAO(DATA_SOURCE).findByUserName(username));
        return session;
    }

    /** A request of {@code session}, with the security context the filter chain loads for it. */
    private static MockHttpServletRequest requestOf(MockHttpSession session, String method, String servletPath,
            String pathInfo) {
        MockHttpServletRequest request = new MockHttpServletRequest(method,
                "/LibreClinica" + servletPath + (pathInfo == null ? "" : pathInfo));
        request.setContextPath("/LibreClinica");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);
        request.setSession(session);
        SecurityContextHolder.setContext((SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        return request;
    }

    /**
     * The logout filter of the chain {@link SecurityConfig} builds, over the
     * beans the security XML defines here.
     */
    private LogoutFilter logoutFilter() {
        if (securityConfig == null) {
            securityConfig = new GenericWebApplicationContext(new MockServletContext());
            securityConfig.getDefaultListableBeanFactory().setParentBeanFactory(beans);
            new AnnotatedBeanDefinitionReader(securityConfig).register(SecurityConfig.class);
            securityConfig.registerBean(UserAccountDAO.class, () -> new UserAccountDAO(DATA_SOURCE));
            securityConfig.registerBean(PublicOctUploadRateLimitFilter.class);
            securityConfig.refresh();
        }
        return securityConfig.getBean(SecurityFilterChain.class).getFilters().stream()
                .filter(LogoutFilter.class::isInstance).map(LogoutFilter.class::cast)
                .findFirst().orElseThrow();
    }

    /** The legacy screens' sign-out link, {@code GET /j_spring_security_logout}. */
    private MockHttpServletResponse legacySignOut(MockHttpSession session) throws Exception {
        MockHttpServletRequest request = requestOf(session, "GET", "/j_spring_security_logout", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        logoutFilter().doFilter(request, response, chain);
        assertNull(chain.getRequest(), "the logout filter answers the request itself");
        return response;
    }

    /** The SPA's sign-out of {@code session}, sent with {@code method}. */
    private ResultActions spaSignOut(MockHttpSession session, HttpMethod method) throws Exception {
        // What the security filter chain loads for the request.
        SecurityContextHolder.setContext((SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        AuthApiController api = new AuthApiController(
                beans.getBean("openClinicaLogoutHandler", OpenClinicaSecurityContextLogoutHandler.class),
                beans.getBean("crfLocker", CRFLocker.class));
        return ProductionMvc.standalone(api).build()
                .perform(request(method, "/api/v1/auth/logout").session(session));
    }

    private static StudyUserRoleBean role(HttpSession session) {
        return (StudyUserRoleBean) session.getAttribute("userRole");
    }

    private static StudyBean study(HttpSession session) {
        return (StudyBean) session.getAttribute("study");
    }

    private static ResultActions me(MockHttpSession session) throws Exception {
        MockMvc mvc = ProductionMvc.standalone(new MeApiController(DATA_SOURCE)).build();
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
        assertEquals(Role.STUDYDIRECTOR, ((StudyUserRoleBean) Objects.requireNonNull(session.getAttribute("userRole"))).getRole());
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
        assertTrue(Objects.requireNonNull(response.getContentType()).startsWith(JSON));
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

        spaSignOut(session, HttpMethod.POST).andExpect(status().isNoContent());

        assertTrue(session.isInvalid(), "the session is invalidated");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the security context is cleared");
        assertNull(registry.getSessionInformation(session.getId()));
        assertFalse(locker.isLocked(4711), "the user's CRF locks are released");
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGOUT), audited(), "one logout row");
        assertEquals("spa-logout", audit.get(0).getUserName());
        assertEquals(LOGOUT, audit.get(0).getUserAccountId());
    }

    @Test
    void theSpaLogoutOfAnSsoSessionWritesTheLogoutRowAndReleasesItsLocks() throws Exception {
        MockHttpSession session = ssoSession("spa-sso");
        assertNull(registry().getSessionInformation(session.getId()), "the registry does not hold an SSO session");
        CRFLocker locker = beans.getBean("crfLocker", CRFLocker.class);
        locker.lock(4712, SSO_SPA);

        spaSignOut(session, HttpMethod.POST).andExpect(status().isNoContent());

        assertTrue(session.isInvalid(), "the session is invalidated");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the security context is cleared");
        assertFalse(locker.isLocked(4712), "the user's CRF locks are released");
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGOUT), audited(), "one logout row");
        assertEquals("spa-sso", audit.get(0).getUserName());
        assertEquals(SSO_SPA, audit.get(0).getUserAccountId());
    }

    @Test
    void aFailedLogoutAuditWriteStillEndsTheSpaSession() throws Exception {
        assertEquals(204, login("spa-logout-fail", PASSWORD, JSON).getStatus());
        MockHttpSession session = session();
        refuseLogoutRows = true;

        spaSignOut(session, HttpMethod.POST).andExpect(status().isNoContent());

        assertTrue(session.isInvalid(), "the session is invalidated");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the security context is cleared");
    }

    @Test
    void aGetDoesNotSignTheSpaOut() throws Exception {
        assertEquals(204, login("spa-logout-get", PASSWORD, JSON).getStatus());
        MockHttpSession session = session();
        CRFLocker locker = beans.getBean("crfLocker", CRFLocker.class);
        locker.lock(4713, LOGOUT_GET);
        audit.clear();

        spaSignOut(session, HttpMethod.GET).andExpect(status().isMethodNotAllowed());

        assertFalse(session.isInvalid(), "the session stays");
        assertNotNull(registry().getSessionInformation(session.getId()), "the session stays registered");
        assertTrue(locker.isLocked(4713), "the user's CRF locks stay");
        assertEquals(List.of(), audited(), "no logout row");
    }

    // --- the study binding of the SPA's login ------------------------------

    @Test
    void aSecondSpaLoginInTheSameBrowserSessionReplacesTheStudyAndRole() throws Exception {
        assertEquals(204, login("spa-browser-first", PASSWORD, JSON).getStatus());
        assertEquals(Role.STUDYDIRECTOR, role(session()).getRole());

        assertEquals(204, login("spa-browser-second", PASSWORD, JSON, session()).getStatus());

        MockHttpSession session = session();
        assertEquals("spa-browser-second", ((UserAccountBean) Objects.requireNonNull(session.getAttribute("userBean"))).getName());
        assertEquals(DEFAULT_STUDY, study(session).getId());
        assertEquals(Role.INVESTIGATOR, role(session).getRole(), "the second account's role");
    }

    @Test
    void aSecondSpaLoginWithoutAnActiveStudyClearsThePreviousBinding() throws Exception {
        assertEquals(204, login("spa-browser-first", PASSWORD, JSON).getStatus());
        assertEquals(DEFAULT_STUDY, study(session()).getId());

        assertEquals(204, login("spa-no-study", PASSWORD, JSON, session()).getStatus());

        MockHttpSession session = session();
        assertEquals("spa-no-study", ((UserAccountBean) Objects.requireNonNull(session.getAttribute("userBean"))).getName());
        assertEquals(0, study(session).getId(), "no study is bound");
        assertEquals(0, role(session).getId(), "no role is bound");
    }

    @Test
    void anSpaLoginOnASiteTakesTheHigherRoleOfItsParentStudy() throws Exception {
        assertEquals(204, login("spa-on-site", PASSWORD, JSON).getStatus());

        MockHttpSession session = session();
        assertEquals(SITE, study(session).getId());
        assertEquals(new StudyDAO(DATA_SOURCE).findByPK(DEFAULT_STUDY).getName(),
                study(session).getParentStudyName());
        assertEquals(Role.STUDYDIRECTOR, role(session).getRole(), "the parent study's role, not the site's");
    }

    @Test
    void anSpaLoginGivesNoRoleInARemovedActiveStudy() throws Exception {
        for (String account : List.of("spa-in-removed", "spa-in-auto-removed")) {
            assertEquals(204, login(account, PASSWORD, JSON).getStatus(), account);

            MockHttpSession session = session();
            assertEquals(0, study(session).getId(), account + ": no role in it, so it is not bound");
            assertEquals(0, role(session).getId(), account + ": no role in it");
        }
    }

    @Test
    void aSystemAdministratorInARemovedActiveStudyKeepsTheStudyWithoutARole() throws Exception {
        assertEquals(204, login("spa-sys-admin-removed-study", PASSWORD, JSON).getStatus());

        MockHttpSession session = session();
        assertEquals(REMOVED_STUDY, study(session).getId());
        assertEquals(0, role(session).getId());
    }

    @Test
    void anSpaLoginBindsNoStudyWhereTheUserHasNoRole() throws Exception {
        for (String account : List.of("spa-role-removed", "spa-no-role")) {
            assertEquals(204, login(account, PASSWORD, JSON).getStatus(), account);

            MockHttpSession session = session();
            assertEquals(0, study(session).getId(), account + ": no study is bound");
            assertEquals(0, role(session).getId(), account + ": no role is bound");
        }
    }

    @Test
    void anSpaLoginOfASystemAdministratorBindsTheStoredStudyWithoutARole() throws Exception {
        assertEquals(204, login("spa-sys-admin", PASSWORD, JSON).getStatus());

        MockHttpSession session = session();
        assertTrue(((UserAccountBean) Objects.requireNonNull(session.getAttribute("userBean"))).isSysAdmin());
        assertEquals(DEFAULT_STUDY, study(session).getId());
        assertEquals(0, role(session).getId());
    }

    // --- the legacy sign-outs ------------------------------------------------

    @Test
    void theLegacySignOutWritesOneLogoutRow() throws Exception {
        assertEquals(302, login("spa-navbar", PASSWORD, BROWSER).getStatus());
        MockHttpSession session = session();
        assertNotNull(registry().getSessionInformation(session.getId()), "the login registered the session");
        audit.clear();

        MockHttpServletResponse response = legacySignOut(session);

        assertEquals("/LibreClinica/MainMenu", response.getRedirectedUrl());
        assertTrue(session.isInvalid(), "the session is invalidated");
        assertNull(registry().getSessionInformation(session.getId()));
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGOUT), audited(), "one logout row");
        assertEquals(NAVBAR, audit.get(0).getUserAccountId());
    }

    @Test
    void theLegacySignOutWritesTheLogoutRowOfAnSsoSession() throws Exception {
        MockHttpSession session = ssoSession("spa-sso-navbar");
        assertNull(registry().getSessionInformation(session.getId()), "the registry does not hold an SSO session");

        MockHttpServletResponse response = legacySignOut(session);

        assertEquals("/LibreClinica/MainMenu", response.getRedirectedUrl());
        assertTrue(session.isInvalid(), "the session is invalidated");
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGOUT), audited(), "one logout row");
        assertEquals("spa-sso-navbar", audit.get(0).getUserName());
        assertEquals(SSO_NAVBAR, audit.get(0).getUserAccountId());
    }

    @Test
    void aFailedLogoutAuditWriteStillEndsTheLegacySession() throws Exception {
        assertEquals(302, login("spa-navbar-fail", PASSWORD, BROWSER).getStatus());
        MockHttpSession session = session();
        refuseLogoutRows = true;

        MockHttpServletResponse response = legacySignOut(session);

        assertEquals("/LibreClinica/MainMenu", response.getRedirectedUrl());
        assertTrue(session.isInvalid(), "the session is invalidated");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "the security context is cleared");
    }

    @Test
    void aDisplacedSessionWritesOneLogoutRowOnItsNextRequest() throws Exception {
        assertEquals(204, login("spa-displaced", PASSWORD, JSON).getStatus());
        MockHttpSession displaced = session();
        // The same account signs in again, in another browser.
        assertEquals(204, login("spa-displaced", PASSWORD, JSON).getStatus());
        assertTrue(registry().getSessionInformation(displaced.getId()).isExpired(), "the first session is expired");
        audit.clear();

        MockHttpServletRequest request = requestOf(displaced, "POST", "/pages", "/api/v1/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        beans.getBean("concurrencyFilter", ConcurrentSessionFilter.class).doFilter(request, response, chain);

        assertEquals("/LibreClinica/MainMenu", response.getRedirectedUrl());
        assertNull(chain.getRequest(), "the request goes no further");
        assertTrue(displaced.isInvalid(), "the session is invalidated");
        assertEquals(List.of(LoginStatus.SUCCESSFUL_LOGOUT), audited(), "one logout row");
        assertEquals(DISPLACED, audit.get(0).getUserAccountId());
    }
}
