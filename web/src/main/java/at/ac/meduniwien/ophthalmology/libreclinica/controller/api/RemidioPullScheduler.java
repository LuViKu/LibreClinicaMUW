/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.RemidioException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Settings;

/**
 * DR-031 — runs {@link RemidioPullService} on a timer.
 *
 * <p>Same shape as {@link TerminologyIngestScheduler}: a component-scanned
 * bean with a fixed-delay tick that reads its switches from
 * {@code datainfo.properties} on every tick, so enabling the pull, changing
 * the interval or rotating the credentials needs no restart. Off unless
 * {@code core.remidio.pull.enabled=true}; dev compose and CI never reach out.
 *
 * <p>The tick fires every 30 s and does nothing until the configured
 * interval has passed since the last attempt, which keeps the scheduler
 * itself trivial and the interval a property. One pass at a time; a slow
 * listing does not stack.
 *
 * <p>The client — and with it the cached tokens — lives as long as the
 * settings it was built from. A settings change (rotated password, new site)
 * builds a fresh client; a 401 the client could not recover from clears its
 * tokens so the next pass logs in again rather than repeating the failure.
 */
@Component
public class RemidioPullScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RemidioPullScheduler.class);

    static final String KEY_INTERVAL_SECONDS = "core.remidio.pull.intervalSeconds";
    static final String KEY_LOOKBACK_DAYS = "core.remidio.pull.lookbackDays";
    static final int DEFAULT_INTERVAL_SECONDS = 120;
    static final int MIN_INTERVAL_SECONDS = 30;
    static final int DEFAULT_LOOKBACK_DAYS = 3;
    static final int MAX_LOOKBACK_DAYS = 60;

    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Settings activeSettings;
    private volatile RemidioGatewayClient client;
    private volatile Instant lastAttempt;
    private volatile Instant lastSuccess;
    private volatile String lastError;
    private volatile boolean warnedUnconfigured;

    public RemidioPullScheduler(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 90_000)
    public void tick() {
        if (!enabled()) return;
        Instant now = Instant.now();
        Instant last = lastAttempt;
        if (last != null && Duration.between(last, now).getSeconds() < intervalSeconds()) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            runOnce();
        } finally {
            running.set(false);
        }
    }

    /**
     * One pass over the sliding window. Public so an operator endpoint can
     * trigger it; never throws — a scheduled job's failure is a log line and
     * a status, not an exception.
     *
     * @return the pass's summary, or empty when nothing ran
     */
    public Optional<RemidioPullService.Summary> runOnce() {
        lastAttempt = Instant.now();
        Settings settings = Settings.fromConfig();
        if (!settings.isConfigured()) {
            if (!warnedUnconfigured) {
                LOG.warn("Remidio pull is enabled but not configured — {} is empty", settings.missing());
                warnedUnconfigured = true;
            }
            lastError = "not configured: " + settings.missing();
            return Optional.empty();
        }
        warnedUnconfigured = false;
        if (client == null || !settings.equals(activeSettings)) {
            client = new RemidioGatewayClient(settings);
            activeSettings = settings;
            LOG.info("Remidio pull: client built for site {} on {}", settings.siteCustomId(),
                    settings.baseUrl().replaceFirst("^https?://", ""));
        }
        LocalDate to = LocalDate.now(RemidioPullService.CLINIC_ZONE);
        LocalDate from = to.minusDays(lookbackDays());
        try {
            RemidioPullService.Summary s = new RemidioPullService(dataSource, client).pull(from, to);
            lastSuccess = Instant.now();
            lastError = null;
            if (s.newExams() > 0 || s.failed() > 0) {
                LOG.info("Remidio pull {}..{}: {}", from, to, s.line());
            } else {
                LOG.debug("Remidio pull {}..{}: {}", from, to, s.line());
            }
            return Optional.of(s);
        } catch (RemidioException e) {
            lastError = e.reason() + ": " + e.getMessage();
            LOG.warn("Remidio pull failed ({}): {}", e.reason(), e.getMessage());
            if (e.reason() == RemidioException.Reason.UNAUTHORIZED) {
                client.reset();
            }
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.error("Remidio pull failed unexpectedly: {}", e.toString(), e);
        }
        return Optional.empty();
    }

    /** When a pass last completed without error, for the status page. */
    public Optional<Instant> lastSuccess() {
        return Optional.ofNullable(lastSuccess);
    }

    /** Why the last pass did not complete, or null when it did. */
    public String lastError() {
        return lastError;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(cfg(RemidioGatewayClient.KEY_ENABLED, "false"));
    }

    static int intervalSeconds() {
        return Math.max(MIN_INTERVAL_SECONDS, cfgInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS));
    }

    static int lookbackDays() {
        return Math.max(0, Math.min(MAX_LOOKBACK_DAYS, cfgInt(KEY_LOOKBACK_DAYS, DEFAULT_LOOKBACK_DAYS)));
    }

    private static int cfgInt(String key, int def) {
        try {
            return Integer.parseInt(cfg(key, Integer.toString(def)));
        } catch (NumberFormatException notANumber) {
            return def;
        }
    }

    private static String cfg(String key, String def) {
        try {
            String v = CoreResources.getField(key);
            return (v == null || v.isBlank()) ? def : v.trim();
        } catch (Exception e) {
            return def;
        }
    }
}
