package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequest;

class LocaleFilterTest {

    private static Locale downstreamLocale(MockHttpServletRequest req, AtomicReference<java.util.List<Locale>> all)
            throws Exception {
        AtomicReference<Locale> seen = new AtomicReference<>();
        new LocaleFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            HttpServletRequest h = (HttpServletRequest) rq;
            seen.set(h.getLocale());
            all.set(Collections.list(h.getLocales()));
        });
        return seen.get();
    }

    @Test
    void wildcardNeverReachesDownstreamConsumers() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addPreferredLocale(Locale.ROOT);
        AtomicReference<java.util.List<Locale>> all = new AtomicReference<>();
        assertEquals(Locale.ENGLISH, downstreamLocale(req, all));
        assertFalse(all.get().contains(Locale.ROOT));
    }

    @Test
    void wildcardIsDroppedAndRealLocaleKept() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addPreferredLocale(Locale.ROOT);
        req.addPreferredLocale(Locale.GERMAN);
        AtomicReference<java.util.List<Locale>> all = new AtomicReference<>();
        assertEquals(Locale.GERMAN, downstreamLocale(req, all));
        assertEquals(Locale.GERMAN, all.get().get(0));
        assertFalse(all.get().contains(Locale.ROOT));
    }
}
