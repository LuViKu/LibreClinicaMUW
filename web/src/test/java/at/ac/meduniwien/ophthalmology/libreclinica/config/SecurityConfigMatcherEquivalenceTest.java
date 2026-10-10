package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import jakarta.servlet.http.MappingMatch;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;

/**
 * The permit list and the deny lists were written against Security 6's Ant
 * request matcher; Security 7 only has the path-pattern matcher, whose rules
 * differ (trailing slash, path parameters, percent-encoding, servlet-path
 * handling). This test keeps the Ant matcher's rules as the reference and
 * asserts that, for every pattern in {@code PUBLIC_PATHS},
 * {@code INTERNET_FACING_DENIED_PATHS} (with and without the SSO addition) and
 * {@code DEIDENTIFICATION_CLOSED_PATHS}, and for a corpus of URLs built from
 * each (exact, below, beside, above, trailing slash, other case, session-id
 * parameter, percent-encoded letter, doubled slash, dot segment), the new
 * matcher answers as the reference does for every request that Spring
 * Security's {@link StrictHttpFirewall} lets through. Requests the firewall
 * refuses never reach a matcher; the test asserts that for the shapes where
 * that is the reason the two could differ.
 *
 * <p>Each URL is presented in three container shapes: the {@code /pages}
 * dispatcher (servlet path {@code /pages}, the rest in path info), an
 * exact-mapped legacy servlet or the default servlet (servlet path is the whole
 * path), and everything in path info. The new matcher reads the request URI, so
 * the shape must not matter.
 */
class SecurityConfigMatcherEquivalenceTest {

    private static final String CONTEXT = "/LibreClinica";

    private static final AntPathMatcher ANT = new AntPathMatcher();

    static {
        ANT.setCaseSensitive(true);
        ANT.setTrimTokens(false);
    }

    /** Security 6's Ant matcher: "/**" prefix patterns by startsWith, the rest by AntPathMatcher. */
    private static boolean reference(String pattern, String decodedPath) {
        if (pattern.equals("/**") || pattern.equals("**")) {
            return true;
        }
        if (pattern.endsWith("/**") && pattern.indexOf('?') == -1 && pattern.indexOf('{') == -1
                && pattern.indexOf('*') == pattern.length() - 2) {
            String sub = pattern.substring(0, pattern.length() - 3);
            return decodedPath.startsWith(sub)
                    && (decodedPath.length() == sub.length() || decodedPath.charAt(sub.length()) == '/');
        }
        return ANT.match(pattern, decodedPath);
    }

    private static List<String> allPatterns() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(List.of(SecurityConfig.PUBLIC_PATHS));
        all.addAll(List.of(SecurityConfig.internetFacingDeniedPaths(false)));
        all.addAll(List.of(SecurityConfig.internetFacingDeniedPaths(true)));
        all.addAll(List.of(SecurityConfig.DEIDENTIFICATION_CLOSED_PATHS));
        all.add("/pages/api/**");
        return new ArrayList<>(all);
    }

    /** Paths derived from a pattern: on it, below it, beside it, above it. */
    private static Set<String> seedPaths(String pattern) {
        Set<String> out = new LinkedHashSet<>();
        String base = pattern;
        if (pattern.endsWith("/**")) {
            base = pattern.substring(0, pattern.length() - 3);
            out.add(base);
            out.add(base + "/");
            out.add(base + "/a");
            out.add(base + "/a/");
            out.add(base + "/a/b");
            out.add(base + "/a.b");
            out.add(base + "x");
        } else if (pattern.contains("*")) {
            out.add(pattern.replace("*", ""));
            out.add(pattern.replace("*", "x"));
            out.add(pattern.replace("*", "-y.z"));
            out.add(pattern.replace("*", "/x"));
            out.add(pattern.replace("*", "") + "/");
            out.add(pattern.replace("*", "x") + "/");
        } else {
            out.add(pattern);
            out.add(pattern + "/");
            out.add(pattern + "x");
            out.add(pattern + "/x");
            out.add(pattern + ".json");
        }
        int slash = base.lastIndexOf('/');
        if (slash > 0) {
            out.add(base.substring(0, slash));
            out.add(base.substring(0, slash) + "/");
        }
        out.add("/");
        return out;
    }

    private record Probe(String label, String rawUri, String decodedPath) {
    }

    /** The URL shapes a client can send for one seed path, as the container would see them. */
    private static List<Probe> variants(String path) {
        List<Probe> v = new ArrayList<>();
        v.add(new Probe("plain", CONTEXT + path, path));
        v.add(new Probe("upper-case", CONTEXT + path.toUpperCase(), path.toUpperCase()));
        v.add(new Probe("session id", CONTEXT + path + ";jsessionid=ABC", path));
        int second = path.indexOf('/', 1);
        if (second > 0) {
            v.add(new Probe("param on first segment",
                    CONTEXT + path.substring(0, second) + ";a=b" + path.substring(second), path));
        }
        // Tomcat decodes a percent-encoded letter before mapping.
        for (int i = 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isLetter(c)) {
                String enc = path.substring(0, i) + "%" + String.format("%02X", (int) c) + path.substring(i + 1);
                v.add(new Probe("encoded letter", CONTEXT + enc, path));
                break;
            }
        }
        if (path.length() > 1) {
            // Tomcat collapses a doubled slash and resolves dot segments before mapping.
            v.add(new Probe("double slash", CONTEXT + "/" + path, path));
            v.add(new Probe("dot segment", CONTEXT + "/." + path, path));
            int last = path.lastIndexOf('/');
            v.add(new Probe("encoded slash", CONTEXT + path.substring(0, last) + "%2F" + path.substring(last + 1), path));
        }
        return v;
    }

    /** The request as Tomcat hands it over, in one of the container shapes. */
    private static MockHttpServletRequest request(Probe p, int shape) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", p.rawUri());
        r.setContextPath(CONTEXT);
        String decoded = p.decodedPath();
        switch (shape) {
            case 0 -> { // the /pages/* dispatcher: a path mapping
                if (decoded.startsWith("/pages/")) {
                    r.setServletPath("/pages");
                    r.setPathInfo(decoded.substring("/pages".length()));
                    r.setHttpServletMapping(new MockHttpServletMapping("*", "/pages/*", "pages", MappingMatch.PATH));
                } else {
                    r.setServletPath(decoded);
                    r.setHttpServletMapping(new MockHttpServletMapping("", "/", "default", MappingMatch.DEFAULT));
                }
            }
            case 1 -> { // exact-mapped legacy servlet
                r.setServletPath(decoded);
                r.setHttpServletMapping(new MockHttpServletMapping(decoded, decoded, "legacy", MappingMatch.EXACT));
            }
            default -> { // the default servlet (Boot's dispatcher at "/")
                r.setServletPath(decoded);
                r.setHttpServletMapping(new MockHttpServletMapping("", "/", "default", MappingMatch.DEFAULT));
            }
        }
        return r;
    }

    private static boolean firewallRefuses(MockHttpServletRequest r) {
        try {
            new StrictHttpFirewall().getFirewalledRequest(r);
            return false;
        } catch (RequestRejectedException e) {
            return true;
        }
    }

    @Test
    void everyPatternDecidesAsTheAntMatcherDidForEveryRequestTheFirewallLetsThrough() {
        List<String> differences = new ArrayList<>();
        int compared = 0;
        int refused = 0;
        int failedClosed = 0;
        for (String pattern : allPatterns()) {
            RequestMatcher actual = SecurityConfig.pathPattern(pattern);
            for (String seed : seedPaths(pattern)) {
                for (Probe probe : variants(seed)) {
                    for (int shape = 0; shape < 3; shape++) {
                        MockHttpServletRequest r = request(probe, shape);
                        if (firewallRefuses(r)) {
                            refused++;
                            continue;
                        }
                        boolean expected = reference(pattern, probe.decodedPath());
                        boolean got;
                        try {
                            got = actual.matches(r);
                        } catch (IllegalArgumentException e) {
                            // Spring cannot split a percent-encoded letter in the
                            // servlet-path prefix ("/%70ages/...") from the context
                            // path; the dispatcher behind the chain fails on the same
                            // request. The chain answers 500: closed, not open.
                            assertTrue(probe.label().equals("encoded letter") && shape == 0
                                    && probe.decodedPath().startsWith("/pages/"), probe.rawUri() + ": " + e);
                            failedClosed++;
                            continue;
                        }
                        compared++;
                        if (expected != got) {
                            differences.add(pattern + "  vs  " + probe.label() + " " + probe.rawUri()
                                    + " (shape " + shape + "): Ant=" + expected + " path-pattern=" + got);
                        }
                    }
                }
            }
        }
        assertTrue(compared > 3000, "the corpus is large enough to mean something: " + compared);
        assertTrue(refused > 0, "some shapes are refused by the firewall");
        assertEquals(List.of(), differences, "matcher decisions that changed (compared " + compared
                + ", firewall refused " + refused + ", failed closed " + failedClosed + ")");
    }

    @Test
    void theFirewallRefusesTheShapesWhereTheMatchersCouldDisagree() {
        // Path parameters, doubled slashes, dot segments and encoded slashes
        // never reach an authorization rule, so the rules cannot be bypassed
        // with them whatever a matcher makes of them.
        for (String raw : List.of(
                "/pages/api/v1/me;jsessionid=ABC",
                "/pages;x=y/api/v1/me",
                "//pages/api/v1/me",
                "/pages//api/v1/me",
                "/pages/./api/v1/me",
                "/pages/api/../api/v1/me",
                "/pages/api%2Fv1/me",
                "/pages/api%5Cv1/me",
                "/pages/api%2e%2e/v1/me",
                "/pages/api/v1/me%00")) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", CONTEXT + raw);
            r.setContextPath(CONTEXT);
            r.setServletPath("/pages");
            r.setPathInfo(raw.substring("/pages".length()));
            assertTrue(firewallRefuses(r), "firewall refuses " + raw);
        }
    }

    @Test
    void aTrailingSlashDoesNotWidenAnExactPattern() {
        // /Contact and /SystemStatus are listed without /**: the slash form is a
        // different (unmapped) URL and must not become anonymous by accident.
        MockHttpServletRequest slash = request(new Probe("slash", CONTEXT + "/Contact/", "/Contact/"), 1);
        assertFalse(SecurityConfig.pathPattern("/Contact").matches(slash));
        MockHttpServletRequest exact = request(new Probe("exact", CONTEXT + "/Contact", "/Contact"), 1);
        assertTrue(SecurityConfig.pathPattern("/Contact").matches(exact));
        // and a /** pattern still covers its base, its slash form and below
        for (String p : List.of("/includes", "/includes/", "/includes/x/y.js")) {
            assertTrue(SecurityConfig.pathPattern("/includes/**")
                    .matches(request(new Probe("sub", CONTEXT + p, p), 1)), p);
        }
    }

    @Test
    void thePercentDecodedFormOfARequestIsWhatIsMatched() {
        // %70 is "p": the deny rule for /pages/api/v1/public/** must still see it.
        String raw = CONTEXT + "/pages/api/v1/%70ublic/oct-upload/x";
        MockHttpServletRequest r = request(new Probe("enc", raw, "/pages/api/v1/public/oct-upload/x"), 0);
        assertFalse(firewallRefuses(r));
        assertTrue(SecurityConfig.pathPattern("/pages/api/v1/public/**").matches(r));
    }
}
