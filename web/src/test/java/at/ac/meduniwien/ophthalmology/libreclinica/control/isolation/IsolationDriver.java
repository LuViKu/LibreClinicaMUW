/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.isolation;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpSession;

import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.core.CRFLocker;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.DynamicsMetadataService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.InstantOnChangeService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.SimpleConditionalDisplayService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.EventDefinitionCrfTagService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy.StudySubjectServiceImpl;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;

/**
 * Drives one legacy servlet as a logged-in site user and reports what came
 * back: where the request was forwarded, what was written to the response, and
 * every string that a JSP would be handed (request and session attributes).
 *
 * <p>The session is built as a real login builds it:
 * <ol>
 *   <li>{@code OpenClinicaUsernamePasswordAuthenticationFilter} (line 207) stores
 *       {@code UserAccountDAO.findByUserName(name)} - roles included
 *       ({@code UserAccountDAO.getEntityFromHashMap}, line 559) - as
 *       {@code userBean};</li>
 *   <li>no {@code study} or {@code userRole} is in the session yet, so the first
 *       servlet request runs {@code SecureController.process()}, which loads the
 *       user's {@code active_study} and picks {@code ub.getRoleByStudy(study)},
 *       raised to the parent's role on a site (SecureController.java, the
 *       "currentStudy == null" and "currentRole == null" branches).</li>
 * </ol>
 *
 * <p>Like {@code LegacyServletHarness}, this runs the servlet's real
 * {@code service()} with no container. Unlike it, the response records every
 * forward: a container lets only the first one through (the response is
 * committed by then), so the first is what the user sees, and a servlet that
 * forwards to the main menu and then carries on (as {@code
 * SecureController.checkRoleByUserAndStudy} lets one do) must not read as having
 * rendered its page.
 */
final class IsolationDriver {

    /** Tables whose rows a site user's request must not change. */
    private static final List<String> TABLES = List.of("study", "study_subject", "subject", "study_event",
            "event_crf", "item_data", "discrepancy_note", "dn_item_data_map", "dn_study_subject_map",
            "dn_event_crf_map", "dn_study_event_map", "dataset", "study_user_role", "event_definition_crf",
            "study_event_definition", "study_group_class", "study_group", "subject_group_map",
            "archived_dataset_file", "user_account");

    /** The lock registry the servlets share. */
    final CRFLocker crfLocker = new CRFLocker();
    private final DataSource dataSource;
    private final MockServletContext servletContext = new MockServletContext();
    private final GenericWebApplicationContext spring = new GenericWebApplicationContext();

    IsolationDriver(DataSource dataSource) {
        this.dataSource = dataSource;
        spring.setServletContext(servletContext);
        spring.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, spring);
        bean("dataSource", dataSource);
        bean("crfLocker", crfLocker);
        bean("securityManager", mock(SecurityManager.class));
        bean("mailSender", mock(JavaMailSenderImpl.class));
        bean("ruleSetService", mock(RuleSetServiceInterface.class));
        bean("dynamicsMetadataService", mock(DynamicsMetadataService.class));
        bean("simpleConditionalDisplayService", mock(SimpleConditionalDisplayService.class));
        bean("instantOnChangeService", mock(InstantOnChangeService.class));
        bean("eventDefinitionCrfTagService", mock(EventDefinitionCrfTagService.class));
        StudySubjectServiceImpl subjects = new StudySubjectServiceImpl();
        subjects.setDataSource(dataSource);
        bean("studySubjectService", subjects);
    }

    final void bean(String name, Object bean) {
        spring.getBeanFactory().registerSingleton(name, bean);
    }

    /** The user bean a login stores in the session. */
    UserAccountBean login(String userName) {
        UserAccountBean ub = new UserAccountDAO(dataSource).findByUserName(userName);
        if (ub.getId() <= 0) {
            throw new IllegalStateException("no such user " + userName);
        }
        return ub;
    }

    /** A request from a freshly logged-in user whose session holds only the user bean. */
    MockHttpServletRequest request(String method, String servletPath, String userName, String... params) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpServletRequest req = new MockHttpServletRequest(servletContext, method, servletPath);
        req.setServletPath(servletPath);
        HttpSession session = req.getSession();
        session.setAttribute(SecureController.USER_BEAN_NAME, login(userName));
        session.setAttribute("study", new StudyBean());
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return req;
    }

    /** The next request of the same browser session as {@code first}. */
    MockHttpServletRequest continued(MockHttpServletRequest first, String method, String servletPath,
            String... params) {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpServletRequest req = new MockHttpServletRequest(servletContext, method, servletPath);
        req.setServletPath(servletPath);
        req.setSession(first.getSession());
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return req;
    }

    /** A response that remembers every forward. */
    private static final class RecordingResponse extends MockHttpServletResponse {
        final List<String> forwards = new ArrayList<>();

        @Override
        public void setForwardedUrl(String forwardedUrl) {
            forwards.add(forwardedUrl);
            super.setForwardedUrl(forwardedUrl);
        }
    }

    /** What a servlet call did. */
    static final class Outcome {
        /** Every forward, in order; the first one is what the container delivers. */
        List<String> forwards = List.of();
        String redirectedUrl;
        int status;
        String errorMessage;
        String body = "";
        /** Request and session strings the JSP would receive, one per line. */
        String attributeText = "";
        Throwable thrown;
        /** The first exception the servlet logged and swallowed (it then forwards to error.jsp). */
        String swallowed;

        String firstForward() {
            return forwards.isEmpty() ? null : forwards.get(0);
        }

        /** The request ended at the main menu, an HTTP error, or the menu page: access was refused. */
        boolean refused() {
            String f = firstForward();
            return status == 401 || status == 403 || status == 404 || status == 405 || status == 410
                    || "/MainMenu".equals(f) || "/WEB-INF/jsp/menu.jsp".equals(f);
        }

        /** The page the container renders is a JSP, so the attributes reach a reader. */
        boolean rendersJsp() {
            String f = firstForward();
            return f != null && f.endsWith(".jsp") && !"/WEB-INF/jsp/menu.jsp".equals(f)
                    && !"/WEB-INF/jsp/error.jsp".equals(f);
        }

        List<String> found(String text, List<String> markers) {
            List<String> hits = new ArrayList<>();
            for (String m : markers) {
                if (text != null && text.contains(m)) {
                    hits.add(m);
                }
            }
            return hits;
        }

        /** Markers present in the response itself (body, redirect, error). */
        List<String> bodyLeaks(List<String> markers) {
            return found(body + "\n" + redirectedUrl + "\n" + errorMessage, markers);
        }

        /** Markers present in the attributes handed to the page the request was forwarded to. */
        List<String> attributeLeaks(List<String> markers) {
            return found(attributeText, markers);
        }

        /** What a reader receives: the response body, or a JSP that renders the attributes. */
        List<String> readerSees(List<String> markers) {
            List<String> hits = new ArrayList<>(bodyLeaks(markers));
            if (rendersJsp()) {
                for (String m : attributeLeaks(markers)) {
                    if (!hits.contains(m)) {
                        hits.add(m);
                    }
                }
            }
            return hits;
        }

        @Override
        public String toString() {
            return "forwards=" + forwards + " redirect=" + redirectedUrl + " status=" + status
                    + (errorMessage == null ? "" : " error=" + errorMessage)
                    + (swallowed == null ? "" : " logged=" + swallowed)
                    + (thrown == null ? "" : " thrown=" + thrown);
        }
    }

    Outcome run(HttpServlet servlet, MockHttpServletRequest req) {
        Outcome out = new Outcome();
        RecordingResponse resp = new RecordingResponse();
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level saved = root.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        List<ch.qos.logback.classic.Logger> loggers = new ArrayList<>(root.getLoggerContext().getLoggerList());
        for (ch.qos.logback.classic.Logger l : loggers) {
            if (l.getName().startsWith("at.ac.meduniwien") || l == root) {
                l.addAppender(captured);
            }
        }
        root.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            if (servlet.getServletConfig() == null) {
                servlet.init(new MockServletConfig(servletContext));
            }
            servlet.service(req, resp);
        } catch (Throwable t) {
            out.thrown = t;
        } finally {
            for (ch.qos.logback.classic.Logger l : loggers) {
                l.detachAppender(captured);
            }
            root.setLevel(saved);
        }
        for (ch.qos.logback.classic.spi.ILoggingEvent e : captured.list) {
            if (e.getThrowableProxy() != null && out.swallowed == null) {
                out.swallowed = e.getThrowableProxy().getClassName() + ": " + e.getThrowableProxy().getMessage();
            }
        }
        out.forwards = resp.forwards;
        out.redirectedUrl = resp.getRedirectedUrl();
        out.status = resp.getStatus();
        out.errorMessage = resp.getErrorMessage();
        try {
            out.body = resp.getContentAsString();
        } catch (Exception e) {
            out.body = "";
        }
        out.attributeText = attributesOf(req);
        return out;
    }

    // ---- what a JSP would see -----------------------------------------------------------------

    private static String attributesOf(MockHttpServletRequest req) {
        StringBuilder sb = new StringBuilder();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        int[] budget = { 400_000 };
        Enumeration<String> names = req.getAttributeNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            walk(req.getAttribute(n), 0, seen, sb, budget);
        }
        HttpSession session = req.getSession(false);
        if (session != null) {
            Enumeration<String> sn = session.getAttributeNames();
            while (sn.hasMoreElements()) {
                String n = sn.nextElement();
                if ("userBean".equals(n) || "study".equals(n) || "userRole".equals(n)) {
                    continue;
                }
                walk(session.getAttribute(n), 0, seen, sb, budget);
            }
        }
        return sb.toString();
    }

    private static void walk(Object o, int depth, IdentityHashMap<Object, Boolean> seen, StringBuilder sb, int[] budget) {
        if (o == null || depth > 14 || budget[0]-- <= 0) {
            return;
        }
        if (o instanceof CharSequence cs) {
            sb.append(cs).append('\n');
            return;
        }
        if (o instanceof Date d) {
            sb.append(new SimpleDateFormat("yyyy-MM-dd").format(d)).append('\n');
            return;
        }
        Class<?> k = o.getClass();
        if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>
                || k.isPrimitive()) {
            return;
        }
        if (seen.put(o, Boolean.TRUE) != null) {
            return;
        }
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                walk(e.getKey(), depth + 1, seen, sb, budget);
                walk(e.getValue(), depth + 1, seen, sb, budget);
            }
            return;
        }
        if (o instanceof Collection<?> c) {
            for (Object x : c) {
                walk(x, depth + 1, seen, sb, budget);
            }
            return;
        }
        if (k.isArray()) {
            if (!k.getComponentType().isPrimitive()) {
                int n = Array.getLength(o);
                for (int i = 0; i < n; i++) {
                    walk(Array.get(o, i), depth + 1, seen, sb, budget);
                }
            }
            return;
        }
        if (!k.getName().startsWith("at.ac.meduniwien") || k.getSimpleName().endsWith("DAO")
                || k.getSimpleName().endsWith("Dao") || k.getSimpleName().endsWith("Digester")) {
            return;
        }
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    walk(f.get(o), depth + 1, seen, sb, budget);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // an unreadable field cannot hold what we look for
                }
            }
        }
    }

    // ---- database change detection ------------------------------------------------------------

    /** A fingerprint of every table a request could change, by table. */
    Map<String, String> fingerprint() throws SQLException {
        Map<String, String> fp = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            for (String t : TABLES) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT md5(COALESCE(string_agg(x::text, '|' ORDER BY x::text), '')) FROM " + t + " x");
                        ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    fp.put(t, rs.getString(1));
                }
            }
        }
        return fp;
    }

    /** The tables whose rows differ between two fingerprints. */
    static List<String> changed(Map<String, String> before, Map<String, String> after) {
        List<String> diff = new ArrayList<>();
        for (String t : before.keySet()) {
            if (!before.get(t).equals(after.get(t))) {
                diff.add(t);
            }
        }
        return diff;
    }
}
