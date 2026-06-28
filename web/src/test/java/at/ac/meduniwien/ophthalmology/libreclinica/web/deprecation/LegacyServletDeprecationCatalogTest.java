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

import org.junit.jupiter.api.Test;

/**
 * Phase E.8 legacy-retirement (2026-06-20) — unit tests for the
 * deprecation catalog lookup semantics.
 */
// 2026-06-28 — heritage null-analysis suppress; per-site
// null-safety review is the deferred follow-up.
@SuppressWarnings("null")
class LegacyServletDeprecationCatalogTest {

    private final LegacyServletDeprecationCatalog catalog = new LegacyServletDeprecationCatalog();

    @Test
    void exactPathHitsCatalog() {
        var entry = catalog.lookup("/pages/ListStudySubjects");
        assertTrue(entry.isPresent());
        assertEquals("/app/subjects", entry.get().spaRoute());
        assertEquals(LegacyServletDeprecationCatalog.Bucket.SUBJECTS_AND_EVENTS,
                entry.get().bucket());
    }

    @Test
    void pathWithExtraSegmentHitsCatalog() {
        // Legacy servlets accept extra path info; the SPA-replacement
        // banner should still fire.
        var entry = catalog.lookup("/pages/ListStudySubjects/123");
        assertTrue(entry.isPresent());
        assertEquals(LegacyServletDeprecationCatalog.Bucket.SUBJECTS_AND_EVENTS,
                entry.get().bucket());
    }

    @Test
    void unmappedPathMisses() {
        assertFalse(catalog.lookup("/pages/api/v1/subjects").isPresent());
        assertFalse(catalog.lookup("/LibreClinica/app/subjects").isPresent());
        assertFalse(catalog.lookup("/pages/login/login").isPresent());
    }

    @Test
    void nullPathMisses() {
        assertFalse(catalog.lookup(null).isPresent());
    }

    @Test
    void allBucketsHaveAtLeastOneEntry() {
        var byBucket = new java.util.EnumMap<LegacyServletDeprecationCatalog.Bucket, Integer>(
                LegacyServletDeprecationCatalog.Bucket.class);
        catalog.all().values().forEach(e ->
                byBucket.merge(e.bucket(), 1, Integer::sum));
        for (var b : LegacyServletDeprecationCatalog.Bucket.values()) {
            assertTrue(byBucket.getOrDefault(b, 0) > 0,
                    "Bucket " + b + " has no entries — was it removed by mistake?");
        }
    }
}
