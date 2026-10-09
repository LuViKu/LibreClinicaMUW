/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.i18n.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * The per-thread locale used to live in a plain HashMap keyed by Thread that
 * every request thread wrote to. These tests hammer the binding from many
 * threads at once, including first-time registration of many distinct locales.
 */
public class ResourceBundleProviderConcurrencyTest {

    @Test
    public void eachThreadSeesOnlyItsOwnLocaleUnderConcurrentBinding() throws Exception {
        final int threads = 48;
        final int iterations = 3000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            // many distinct locales so the bundle-set registry is also written concurrently
            final Locale mine = new Locale("en", "C" + (char) ('A' + t % 26) + (char) ('A' + t / 26));
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        ResourceBundleProvider.updateLocale(mine);
                        assertEquals(mine, ResourceBundleProvider.getLocale());
                        assertTrue(ResourceBundleProvider.getWordsBundle().keySet().size() > 0);
                    }
                    ResourceBundleProvider.clearLocale();
                    assertNull(ResourceBundleProvider.getLocale());
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            all.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : all) {
            th.join(60_000);
            assertFalse("a thread hung (resize loop?)", th.isAlive());
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    public void bindingOnOneThreadIsInvisibleToAnotherAndNullClears() throws Exception {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        AtomicReference<Locale> seen = new AtomicReference<>(Locale.GERMAN);
        Thread other = new Thread(() -> seen.set(ResourceBundleProvider.getLocale()));
        other.start();
        other.join();
        assertNull(seen.get());
        ResourceBundleProvider.updateLocale(null);
        assertNull(ResourceBundleProvider.getLocale());
    }
}
