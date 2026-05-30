/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.cache;

import javax.cache.Cache;
import javax.cache.CacheManager;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSR-107 (JCache) adapter for the DAO-side query cache.
 *
 * <p>Phase B.5: ported from the net.sf.ehcache 2.x {@code CacheManager} /
 * {@code Ehcache} / {@code Element} types (deleted as part of dropping
 * ehcache 2 — javax-only) to the standard {@code javax.cache} API. The
 * concrete provider on the classpath is ehcache 3 (jakarta classifier)
 * configured via {@code ehcache.xml}.
 *
 * <p>The {@code get} method still gates on {@code dbType == "postgres"} to
 * preserve the original 2.x behaviour where this cache was only consulted on
 * Postgres deployments. The historic comment noted that other DB types
 * skipped the cache; not re-evaluating that policy here.
 */
public class EhCacheWrapper<K, V> implements CacheWrapper<K, V> {

    private final String cacheName;
    private final CacheManager cacheManager;
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    public EhCacheWrapper(final String cacheName, final CacheManager cacheManager) {
        this.cacheName = cacheName;
        this.cacheManager = cacheManager;
    }

    @Override
    public void put(final K key, final V value) {
        Cache<K, V> cache = getCache();
        if (cache != null) {
            cache.put(key, value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(final K key) {
        String dbType = CoreResources.getField("dbType");
        if (!"postgres".equalsIgnoreCase(dbType)) {
            return null;
        }
        Cache<K, V> cache = getCache();
        if (cache == null) {
            return null;
        }
        V value = cache.get(key);
        if (logger.isDebugEnabled()) {
            logger.debug("cache {} key {} → {}", cacheName, key, value == null ? "miss" : "hit");
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Cache<K, V> getCache() {
        if (cacheManager == null) {
            return null;
        }
        return (Cache<K, V>) (Cache) cacheManager.getCache(cacheName);
    }
}
