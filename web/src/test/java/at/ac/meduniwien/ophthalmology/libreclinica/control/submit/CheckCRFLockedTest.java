/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.CRFLocker;

/**
 * The lock-release branch of {@link CheckCRFLocked}: it releases the session
 * user's locks only, and follows {@code exitTo} only inside the application.
 */
class CheckCRFLockedTest {

    private static final int SESSION_USER = 3;
    private static final int OTHER_USER = 8;

    /** Wires the servlet's request state by hand; no container needed. */
    private static final class Probe extends CheckCRFLocked {
        private static final long serialVersionUID = 1L;
        private final CRFLocker locker = new CRFLocker();

        MockHttpServletResponse run(MockHttpServletRequest req, UserAccountBean user) throws Exception {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            this.request = req;
            this.response = resp;
            this.ub = user;
            processRequest();
            return resp;
        }

        @Override
        public CRFLocker getCrfLocker() {
            return locker;
        }
    }

    private static UserAccountBean user(int id) {
        UserAccountBean ub = new UserAccountBean();
        ub.setId(id);
        ub.setName("user" + id);
        return ub;
    }

    private static MockHttpServletRequest release(String userIdParam, String exitTo) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/LibreClinica/CheckCRFLocked");
        req.setContextPath("/LibreClinica");
        req.addParameter("userId", userIdParam);
        if (exitTo != null) {
            req.addParameter("exitTo", exitTo);
        }
        return req;
    }

    @Test
    void theUserIdParameterCannotReleaseSomeoneElsesLocks() throws Exception {
        Probe probe = new Probe();
        probe.getCrfLocker().lock(501, OTHER_USER);
        probe.getCrfLocker().lock(502, SESSION_USER);

        probe.run(release(String.valueOf(OTHER_USER), null), user(SESSION_USER));

        assertTrue(probe.getCrfLocker().isLocked(501), "another user's lock was released");
        assertFalse(probe.getCrfLocker().isLocked(502), "the session user's own lock stays");
    }

    @Test
    void anExternalExitToIsReplacedByTheSubjectList() throws Exception {
        MockHttpServletResponse resp = new Probe().run(release("3", "https://evil.example/login"), user(SESSION_USER));

        assertEquals(CheckCRFLocked.DEFAULT_EXIT, resp.getRedirectedUrl());
    }

    @Test
    void anInAppExitToIsFollowed() throws Exception {
        MockHttpServletResponse resp = new Probe().run(release("3", "ViewStudySubject?id=5"), user(SESSION_USER));

        assertEquals("ViewStudySubject?id=5", resp.getRedirectedUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ViewStudySubject?id=5",
        "EnterDataForStudyEvent?eventId=12",
        "/LibreClinica/ViewStudySubject?id=5",
        "ListStudySubjects",
        "ViewStudySubject?next=http://x"   // a colon after '?' is query text, not a scheme
    })
    void inAppPathsAreAccepted(String exitTo) {
        assertEquals(exitTo, CheckCRFLocked.safeExitTo(exitTo, "/LibreClinica"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://evil.example/",
        "http:evil.example",
        "javascript:alert(1)",
        "JavaScript:alert(1)",
        "//evil.example/path",
        "/\\evil.example",
        "\\\\evil.example",
        "/OtherApp/page",
        "/LibreClinicaEvil/page",
        " https://evil.example",
        "View\r\nSet-Cookie:x=1",
        ""
    })
    void everythingElseIsRefused(String exitTo) {
        assertNull(CheckCRFLocked.safeExitTo(exitTo, "/LibreClinica"));
    }
}
