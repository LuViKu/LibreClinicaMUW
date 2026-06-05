/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import javax.sql.DataSource;

import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Phase E.4 M13 — base class for `/pages/api/v1/**` controller
 * MockMvc tests.
 *
 * <p>This is the first piece of MockMvc IT infrastructure for the
 * Phase E.4 adapter family. Each adapter PR carries one subclass
 * pinning its JSON response shape so silent drift between the SPA's
 * TypeScript types and the controller's wire shape gets caught at
 * `mvn test` time (per the plan's adapter PR template).
 *
 * <h2>Scope of this first-cut infrastructure</h2>
 *
 * <p>The controllers under test (subclasses of this) instantiate
 * their DAOs <em>inside</em> their request methods (e.g.
 * {@code new StudySubjectDAO(dataSource)}). That makes it
 * impractical to mock individual DAO method calls without
 * refactoring 10+ controllers to accept DAO collaborators via
 * constructor injection. For the first-cut MockMvc surface we focus
 * on:
 *
 * <ul>
 *   <li><strong>Session-state guards</strong> — the entry-point
 *       short-circuits that never reach a DAO (no active study →
 *       400; no authenticated user in session → 401; bad request
 *       body → 400; etc.). These are the most fragile contract
 *       points because the SPA's error-handling code matches on
 *       the status code + message keys.</li>
 *   <li><strong>URL routing</strong> — verifies the
 *       {@code @RequestMapping} / {@code @GetMapping} patterns
 *       resolve as expected under the {@code /pages} DispatcherServlet
 *       context.</li>
 * </ul>
 *
 * <p>Happy-path tests (which need real DB rows) ride on the
 * existing Selenium smoke ITs + the manual curl-probe runbook in
 * each milestone commit. A future slice can add Testcontainers
 * Postgres + Liquibase boot to upgrade this to full end-to-end
 * MockMvc coverage; see the plan's "MockMvc IT" follow-up note.
 *
 * <h2>Known runner alignment gap — to be unblocked before adopting widely</h2>
 *
 * <p>Surefire 2.22.2 (pinned in the parent pom for JDK 8 era + the
 * existing JUnit 3/4 mixed discovery semantics) auto-loads the
 * junit-platform-surefire-provider; `spring-boot-starter-test`
 * (test scope) drags in junit-jupiter + junit-platform-engine, but
 * its junit-platform-launcher version is not aligned with the
 * engine version surefire ships. At test-discovery time this
 * surfaces as:
 *
 * <pre>
 *   TestEngine with ID 'junit-jupiter' failed to discover tests
 *   OutputDirectoryProvider not available; probably due to
 *   unaligned versions of the junit-platform-engine and
 *   junit-platform-launcher jars on the classpath.
 * </pre>
 *
 * <p>Net result: subclasses compile + the .class is in
 * `target/test-classes/`, but `mvn test` discovers 0 tests in the
 * web module and silently passes. Two clean fixes:
 *
 * <ol>
 *   <li>Pin a junit-platform-launcher version in the parent pom's
 *       dependencyManagement to match the junit-platform-engine
 *       version Spring Boot 3.5.x's BOM declares (typically
 *       1.11.x), OR</li>
 *   <li>Bump surefire to 3.x in the parent pom; the JDK 21
 *       baseline (Phase B.1) makes the JDK 8 surefire-2.x lock-in
 *       irrelevant now.</li>
 * </ol>
 *
 * <p>Until that lands, the M13 MockMvc IT scaffold + this first
 * SubjectsApiControllerTest are inert as far as CI is concerned —
 * they document the test pattern subsequent adapter PRs should
 * follow, and are ready to switch on the moment the surefire/junit-
 * platform alignment is fixed in a one-line pom change. See the
 * Phase E.4 plan's "MockMvc IT" follow-up note for context.
 *
 * <h2>Usage</h2>
 *
 * Subclasses receive a fresh {@link MockMvc} per test method via
 * {@link #mockMvcFor(Object)}, a {@link MockHttpSession} via
 * {@link #emptySession()} / {@link #authenticatedSession(int, int)},
 * and a Mockito mock {@link DataSource} they can hand to their
 * controller's constructor. The base class does not extend any
 * Spring test runner — controllers under {@code controller/api/}
 * are pure {@code @RestController}s with no other Spring
 * collaborators, so the lightweight {@code standaloneSetup} works.
 */
abstract class AbstractApiControllerTest {

    /**
     * Bind a locale on the current test thread so any
     * {@link StudyUserRoleBean#setRole(Role)} call — even outside the
     * authenticated-session helpers — can resolve {@code Term.getName()}
     * via {@link ResourceBundleProvider}. Without this, role-predicate
     * unit tests run in isolation NPE on a ThreadLocal-bundle miss.
     */
    @BeforeEach
    void bindTestLocale() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    /**
     * Build a MockMvc that routes only the supplied controller(s),
     * with the production {@link ApiExceptionHandler} registered so
     * uncaught exceptions wrap to 5xx JSON the way they do in
     * production (rather than the bare ServletException re-throw that
     * standaloneSetup would otherwise emit).
     */
    protected final MockMvc mockMvcFor(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Empty (unauthenticated, no study) HttpSession. */
    protected final HttpSession emptySession() {
        return new MockHttpSession();
    }

    /** Session with a `userBean` (id = userId) but no `study` bound. */
    protected final HttpSession authenticatedSessionWithoutStudy(int userId, String userName) {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(userId);
        ub.setName(userName);
        session.setAttribute("userBean", ub);
        return session;
    }

    /** Session with both `userBean` and `study` bound. */
    protected final HttpSession authenticatedSession(int userId, String userName,
                                                     int studyId, String studyOid, String studyName) {
        MockHttpSession session = (MockHttpSession) authenticatedSessionWithoutStudy(userId, userName);
        StudyBean study = new StudyBean();
        study.setId(studyId);
        study.setOid(studyOid);
        study.setName(studyName);
        session.setAttribute("study", study);
        return session;
    }

    /**
     * Phase E A7 — session whose {@code userBean} carries
     * {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType#SYSADMIN},
     * making {@code ub.isSysAdmin()} return true. Use for tests of
     * sysadmin-gated endpoints (users CRUD, study config) where the
     * permission check would otherwise return 403 before the
     * validation path under test runs.
     */
    protected final HttpSession authenticatedSysadminSession(int userId, String userName,
                                                             int studyId, String studyOid, String studyName) {
        MockHttpSession session = (MockHttpSession)
                authenticatedSession(userId, userName, studyId, studyOid, studyName);
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        ub.addUserType(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType.SYSADMIN);
        return session;
    }

    /** Convenience — a Mockito mock DataSource that DAO constructors will accept. */
    protected final DataSource mockDataSource() {
        return Mockito.mock(DataSource.class);
    }

    /**
     * Session with userBean + study + a synthetic role bound. Use for
     * A4 site-visibility tests that need a {@link StudyUserRoleBean}
     * in session to drive {@code SiteVisibilityFilter} decisions.
     *
     * @param role Role enum constant (e.g. {@link Role#MONITOR}).
     * @param roleStudyId study_id the role is bound to (site or parent).
     */
    protected final HttpSession authenticatedSessionWithRole(int userId, String userName,
                                                             int studyId, String studyOid, String studyName,
                                                             Role role, int roleStudyId) {
        // StudyUserRoleBean's constructor calls setRole(INVALID) which
        // triggers Term.getName → ResourceBundle lookup; bind a locale
        // for the current test thread so the lookup succeeds.
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = (MockHttpSession)
                authenticatedSession(userId, userName, studyId, studyOid, studyName);
        StudyUserRoleBean sur = new StudyUserRoleBean();
        sur.setRole(role);
        sur.setStudyId(roleStudyId);
        sur.setUserName(userName);
        sur.setUserAccountId(userId);
        session.setAttribute("userRole", sur);
        return session;
    }
}
