/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.config.LegacyServletRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Bucket;
import at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation.LegacyServletDeprecationCatalog.Entry;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;

/**
 * The legacy-screen catalogue: its keys are the paths the container really
 * maps, it covers every registered legacy servlet, and the SPA routes it names
 * exist.
 */
// 2026-06-28 — heritage null-analysis suppress; per-site
// null-safety review is the deferred follow-up.
@SuppressWarnings("null")
class LegacyServletDeprecationCatalogTest {

    private final LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();

    @Test
    void servletIsFoundByItsServletPath() {
        Entry entry = catalog.lookup("/ListStudySubjects", null).orElseThrow();
        assertEquals("/ListStudySubjects", entry.legacyPath());
        assertEquals("/app/subjects", entry.spaRoute());
        assertEquals(Bucket.SUBJECTS_AND_EVENTS, entry.bucket());
    }

    @Test
    void servletKeyMatchesOnlyItsExactPath() {
        // An exact servlet mapping never has a pathInfo; a request that does
        // went to some other, prefix-mapped servlet.
        assertFalse(catalog.lookup("/ListStudySubjects", "/123").isPresent());
        assertFalse(catalog.lookup("/ListStudySubjects/123").isPresent());
        assertFalse(catalog.lookup("/ListStudySubjectsX", null).isPresent());
    }

    @Test
    void pagesRouteIsFoundWithTheScreensSubPaths() {
        assertEquals("/pages/studymodule",
                catalog.lookup("/pages", "/studymodule").orElseThrow().legacyPath());
        assertEquals("/pages/studymodule",
                catalog.lookup("/pages", "/studymodule/S_DEFAULTS1/deactivate").orElseThrow().legacyPath());
        assertEquals("/pages/studymodule",
                catalog.lookup("/pages/studymodule/S_DEFAULTS1/deactivate").orElseThrow().legacyPath());
        assertEquals("/pages/managestudy/chooseCRFVersion",
                catalog.lookup("/pages", "/managestudy/chooseCRFVersion").orElseThrow().legacyPath());
    }

    @Test
    void pagesRouteMatchesOnSegmentBoundariesOnly() {
        assertFalse(catalog.lookup("/pages", "/studymodulex").isPresent());
        assertFalse(catalog.lookup("/pages", "/managestudy").isPresent());
        assertFalse(catalog.lookup("/pages", "/managestudy/other").isPresent());
        assertFalse(catalog.lookup("/pages", null).isPresent());
        assertFalse(catalog.lookup("/pages", "/").isPresent());
    }

    @Test
    void heritageApiIsOneKeyCoveringEverythingBelowIt() {
        assertEquals("/pages/auth",
                catalog.lookup("/pages", "/auth/api/v1/system/systemstatus").orElseThrow().legacyPath());
        assertEquals("/pages/auth",
                catalog.lookup("/pages", "/auth/api/itemdata").orElseThrow().legacyPath());
        assertFalse(catalog.lookup("/pages", "/authx/api").isPresent());
        // the SPA's own API stays outside it, including anything named auth
        assertFalse(catalog.lookup("/pages", "/api/v1/auth/logout").isPresent());
    }

    @Test
    void spaApiAndOtherRequestsMiss() {
        assertFalse(catalog.lookup("/pages", "/api/v1/subjects").isPresent());
        assertFalse(catalog.lookup("/pages", "/login/login").isPresent());
        assertFalse(catalog.lookup("/app/subjects", null).isPresent());
        assertFalse(catalog.lookup("/images/bt_View.gif", null).isPresent());
        // The keys carry no context path; a request URI never matches.
        assertFalse(catalog.lookup("/LibreClinica/ListUserAccounts").isPresent());
    }

    @Test
    void nullPathMisses() {
        assertFalse(catalog.lookup(null, null).isPresent());
        assertFalse(catalog.lookup(null).isPresent());
        assertFalse(catalog.entry(null).isPresent());
    }

    /**
     * The keys that named no registered servlet on 2026-09-30, and what they
     * were corrected to.
     */
    @Test
    void keysNameTheRegisteredServletPaths() {
        assertTrue(catalog.entry("/ListStudySubject").isPresent());
        assertTrue(catalog.entry("/UpdateStudyNew").isPresent());
        assertTrue(catalog.entry("/ListDiscNotesForCRFServlet").isPresent());
        for (String gone : List.of("/ListStudySubjectsManage", "/ViewStudyEvent", "/UpdateStudyServlet",
                "/ListDiscNotesForCRF", "/pages/ListStudySubjects", "/pages/Contact")) {
            assertFalse(catalog.entry(gone).isPresent(), gone);
        }
    }

    /** Every path {@code LegacyServletRegistry} maps has an entry, so every legacy servlet is logged. */
    @Test
    void everyRegisteredLegacyServletIsCatalogued() throws Exception {
        Set<String> registered = registeredServletPaths();
        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(servletKeys());
        assertTrue(missing.isEmpty(), "registered legacy servlets missing from the catalogue: " + missing);
        assertTrue(registered.size() > 200, "the registry was read: " + registered.size());
    }

    /** Every servlet key is a registered path: a stale key names a screen no request can match. */
    @Test
    void everyCataloguedServletIsRegistered() throws Exception {
        Set<String> stale = new TreeSet<>(servletKeys());
        stale.removeAll(registeredServletPaths());
        assertTrue(stale.isEmpty(), "catalogue keys that no legacy servlet is mapped at: " + stale);
    }

    /** A named replacement is a real SPA route, so the 410 page never links into the SPA's 404. */
    @Test
    void namedSpaRoutesExistInTheSpaRouter() throws Exception {
        String router = Files.readString(Path.of("src/spa/src/router/index.ts"), StandardCharsets.UTF_8);
        Set<String> routes = new TreeSet<>();
        Matcher m = Pattern.compile("path:\\s*'([^']+)'").matcher(router);
        while (m.find()) {
            routes.add(m.group(1));
        }
        assertTrue(routes.contains("/subjects"), "the router was read: " + routes);
        Set<String> unknown = catalog.all().values().stream()
                .filter(Entry::hasSpaRoute)
                .map(Entry::spaRoute)
                .filter(route -> !route.startsWith("/app/") || !routes.contains(route.substring("/app".length())))
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(unknown.isEmpty(), "SPA routes the router does not define: " + unknown);
    }

    /**
     * Screens the administrator catalogue (§16.3) found without an SPA
     * replacement, although this catalogue used to name one, plus the
     * study-event list whose route never existed.
     */
    @Test
    void screensFoundUncoveredNameNoSpaRoute() {
        for (String path : List.of(
                "/ViewStudy",
                "/AuditDatabase", "/ViewLogMessage",
                "/ViewStudyEvents")) {
            Entry entry = catalog.entry(path).orElseThrow(() -> new AssertionError(path + " not catalogued"));
            assertFalse(entry.hasSpaRoute(), path + " still names " + entry.spaRoute());
        }
        // §16.3 found this one covered after all; it keeps its route.
        assertEquals("/app/system/audit-log", catalog.entry("/AuditLogUser").orElseThrow().spaRoute());
    }

    @Test
    void allBucketsHaveAtLeastOneEntry() {
        var byBucket = new java.util.EnumMap<Bucket, Integer>(Bucket.class);
        catalog.all().values().forEach(e -> byBucket.merge(e.bucket(), 1, Integer::sum));
        for (Bucket b : Bucket.values()) {
            assertTrue(byBucket.getOrDefault(b, 0) > 0,
                    "Bucket " + b + " has no entries — was it removed by mistake?");
        }
    }

    private Set<String> servletKeys() {
        return catalog.all().values().stream()
                .filter(e -> !e.isPagesRoute())
                .map(Entry::legacyPath)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Runs the real {@link LegacyServletRegistry} initializer against a
     * {@link ServletContext} that only records the mappings. The servlet
     * classes are referenced, not instantiated.
     */
    static Set<String> registeredServletPaths() throws Exception {
        Set<String> paths = new TreeSet<>();
        ServletRegistration.Dynamic registration = (ServletRegistration.Dynamic) Proxy.newProxyInstance(
                LegacyServletDeprecationCatalogTest.class.getClassLoader(),
                new Class<?>[] {ServletRegistration.Dynamic.class},
                (proxy, method, args) -> {
                    if ("addMapping".equals(method.getName())) {
                        paths.addAll(List.of((String[]) args[0]));
                        return Set.of();
                    }
                    return null;
                });
        ServletContext context = (ServletContext) Proxy.newProxyInstance(
                LegacyServletDeprecationCatalogTest.class.getClassLoader(),
                new Class<?>[] {ServletContext.class},
                (proxy, method, args) -> "addServlet".equals(method.getName()) ? registration : null);
        new LegacyServletRegistry().legacyServletsInitializer().onStartup(context);
        return paths;
    }
}
