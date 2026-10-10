package at.ac.meduniwien.ophthalmology.libreclinica.i18n.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;
import java.util.Collections;
import org.mockito.Mockito;
import jakarta.servlet.http.HttpServletRequest;

/**
 * "Accept-Language: *" reaches the application as Locale.ROOT on Tomcat 11.
 */
public class LocaleResolverTest {

    private static HttpServletRequest requestWith(Locale... locales) {
        HttpServletRequest r = Mockito.mock(HttpServletRequest.class);
        Mockito.when(r.getLocales()).thenAnswer(i -> Collections.enumeration(List.of(locales)));
        return r;
    }

    @Test
    public void wildcardOnlyFallsBackToDefault() {
        HttpServletRequest r = requestWith(Locale.ROOT);
        assertEquals(Locale.ENGLISH.getLanguage(), LocaleResolver.resolveLocale(r).getLanguage());
        assertEquals(List.of(LocaleResolver.getDefaultLocale()), LocaleResolver.usableLocales(r));
    }

    @Test
    public void wildcardIsSkippedForTheNextLocale() {
        // only English bundles ship, so the wildcard is skipped, German is not qualified, English wins
        HttpServletRequest r = requestWith(Locale.ROOT, Locale.GERMAN, Locale.ENGLISH);
        assertEquals("en", LocaleResolver.resolveLocale(r).getLanguage());
        assertEquals(List.of(Locale.GERMAN, Locale.ENGLISH), LocaleResolver.usableLocales(r));
    }

    @Test
    public void realHeaderWithoutWildcard() {
        assertEquals("en", LocaleResolver.resolveLocale(requestWith(Locale.forLanguageTag("de-AT"), Locale.GERMAN)).getLanguage());
    }

    @Test
    public void emptyOrMissingHeaderFallsBackToDefault() {
        assertEquals("en", LocaleResolver.resolveLocale(requestWith()).getLanguage());
        assertEquals("en", LocaleResolver.resolveLocale(null).getLanguage());
    }

    @Test
    public void wildcardPredicate() {
        assertTrue(LocaleResolver.isWildcard(Locale.ROOT));
        assertTrue(LocaleResolver.isWildcard(null));
        assertFalse(LocaleResolver.isWildcard(Locale.GERMAN));
    }

    /** A basename without a root bundle makes ResourceBundle throw for Locale.ROOT (the 500). */
    @Test
    public void everyMessageSourceBasenameResolvesForRootLocale() throws Exception {
        Path xml = Path.of("src/main/resources/at/ac/meduniwien/ophthalmology/libreclinica/"
                + "applicationContext-core-spring.xml");
        String text = Files.readString(xml);
        Matcher m = Pattern.compile("<value>([^<]*[.]i18n[.][a-z_]+)</value>").matcher(text);
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        assertFalse(names.isEmpty());
        for (String n : names) {
            ResourceBundle.getBundle(n, Locale.ROOT); // throws MissingResourceException
        }
    }
}
