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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The token-free CSRF defence: {@link CrossSiteRequestFilter} plus the
 * SameSite attribute on the session cookie.
 */
class CrossSiteRequestFilterTest {

    /** A request as Tomcat sees it behind nginx: https via RemoteIpValve, Host forwarded. */
    private static MockHttpServletRequest proxied(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setScheme("https");
        req.setServerName("ecrf.augen.meduniwien.ac.at");
        req.setServerPort(443);
        return req;
    }

    private static MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new CrossSiteRequestFilter().doFilter(req, resp, chain);
        return resp;
    }

    @Test
    void crossSitePostIsRefusedBeforeReachingTheApplication() throws Exception {
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/pages/handleSDVPost");
        req.addHeader("Sec-Fetch-Site", "cross-site");
        req.addHeader("Origin", "https://evil.example");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse resp = run(req, chain);

        assertEquals(403, resp.getStatus());
        assertNull(chain.getRequest(), "a refused request must not continue down the chain");
    }

    @Test
    void sameSitePostIsRefusedToo() throws Exception {
        // Another host under the institutional domain is "same-site" but not us.
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/pages/api/v1/subjects");
        req.addHeader("Sec-Fetch-Site", "same-site");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse resp = run(req, chain);

        assertEquals(403, resp.getStatus());
        assertTrue(Objects.requireNonNull(resp.getContentType()).startsWith("application/json"));
        assertNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-origin", "none", "SAME-ORIGIN"})
    void sameOriginAndUserInitiatedPostsPass(String site) throws Exception {
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/pages/api/v1/subjects");
        req.addHeader("Sec-Fetch-Site", site);
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse resp = run(req, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void everyUnsafeMethodIsChecked(String method) throws Exception {
        MockHttpServletRequest req = proxied(method, "/LibreClinica/pages/api/v1/subjects/1");
        req.addHeader("Sec-Fetch-Site", "cross-site");
        MockFilterChain chain = new MockFilterChain();

        assertEquals(403, run(req, chain).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void safeMethodsPassEvenCrossSite(String method) throws Exception {
        // A link from e-mail or the intranet is a cross-site GET navigation.
        MockHttpServletRequest req = proxied(method, "/LibreClinica/ViewStudySubject");
        req.addHeader("Sec-Fetch-Site", "cross-site");
        MockFilterChain chain = new MockFilterChain();

        assertEquals(200, run(req, chain).getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void withoutFetchMetadataAMatchingOriginPasses() throws Exception {
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/j_spring_security_check");
        req.addHeader("Origin", "https://ecrf.augen.meduniwien.ac.at");
        MockFilterChain chain = new MockFilterChain();

        assertEquals(200, run(req, chain).getStatus());
    }

    @Test
    void withoutFetchMetadataAForeignOriginIsRefused() throws Exception {
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/j_spring_security_check");
        req.addHeader("Origin", "https://intranet.meduniwien.ac.at");
        MockFilterChain chain = new MockFilterChain();

        assertEquals(403, run(req, chain).getStatus());
        assertNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null",                                        // sandboxed / opaque origin
        "http://ecrf.augen.meduniwien.ac.at",          // scheme differs
        "https://ecrf.augen.meduniwien.ac.at:8443",    // port differs
        "https://ecrf.augen.meduniwien.ac.at.evil.example",
        "not a url"
    })
    void originMustMatchSchemeHostAndPort(String origin) throws Exception {
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/pages/studymodule");
        req.addHeader("Origin", origin);

        assertEquals(403, run(req, new MockFilterChain()).getStatus());
    }

    @Test
    void directDevelopmentAccessMatchesItsOwnOrigin() throws Exception {
        // docker compose up without nginx: http://127.0.0.1:8080
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/LibreClinica/pages/api/v1/subjects");
        req.setScheme("http");
        req.setServerName("127.0.0.1");
        req.setServerPort(8080);
        req.addHeader("Origin", "http://127.0.0.1:8080");

        assertEquals(200, run(req, new MockFilterChain()).getStatus());
    }

    @Test
    void nonBrowserClientsWithoutEitherHeaderPass() throws Exception {
        // Export Watcher / Optomed bridge / dicom-scp sidecar / API-key clients.
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/pages/api/v1/device/uploader/heartbeat");
        MockFilterChain chain = new MockFilterChain();

        assertEquals(200, run(req, chain).getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void fetchMetadataWinsOverAMatchingOrigin() throws Exception {
        MockHttpServletRequest req = proxied("POST", "/LibreClinica/pages/handleSDVPost");
        req.addHeader("Sec-Fetch-Site", "cross-site");
        req.addHeader("Origin", "https://ecrf.augen.meduniwien.ac.at");

        assertEquals(403, run(req, new MockFilterChain()).getStatus());
    }

    @Test
    void sessionCookieIsIssuedSameSiteLax() throws Exception {
        // Surefire runs with the module directory as working directory.
        File contextXml = new File("src/main/webapp/META-INF/context.xml");
        assertTrue(contextXml.isFile(), "META-INF/context.xml must ship in the WAR");

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Files.newInputStream(contextXml.toPath()));
        NodeList processors = doc.getDocumentElement().getElementsByTagName("CookieProcessor");
        assertEquals(1, processors.getLength());
        Element processor = (Element) processors.item(0);
        assertEquals("org.apache.tomcat.util.http.Rfc6265CookieProcessor", processor.getAttribute("className"));
        assertEquals("lax", processor.getAttribute("sameSiteCookies").toLowerCase());
    }
}
