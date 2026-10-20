/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.i18n.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Locale;

import org.junit.Test;

/**
 * Asking for an explicit locale must work before any request has bound it.
 * The bundles used to be loaded only by {@code updateLocale}, so on an
 * installation where everyone works in German, {@code POST /me/password}
 * (which reads the English exceptions bundle) answered 500 with a
 * NullPointerException for every user.
 */
public class ResourceBundleProviderLocaleTest {

    @Test
    public void anExplicitLocaleIsLoadedOnFirstUse() {
        // a locale no other test binds, so nothing has loaded it yet
        Locale unbound = new Locale("en", "ZZ");
        ResourceBundleProvider.resBundleSetMap.remove(unbound);
        ResourceBundleProvider.clearLocale();

        assertNotNull(ResourceBundleProvider.getExceptionsBundle(unbound));
        assertNotNull(ResourceBundleProvider.getExceptionsBundle(unbound).getString("wrong_old_password"));
        assertNotNull(ResourceBundleProvider.getWordsBundle(unbound));
        // and the calling thread's own locale is left alone
        assertNull(ResourceBundleProvider.getLocale());
    }

    @Test
    public void aThreadWithNoLocaleGetsTheDefault() {
        ResourceBundleProvider.clearLocale();

        assertNotNull(ResourceBundleProvider.getTermsBundle());
        assertNotNull(ResourceBundleProvider.getTermsBundle().getString("Study_Director"));
        assertNull(ResourceBundleProvider.getLocale());
    }
}
