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
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioDashboardClient;
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
    /** How far behind the last successful pass each pass starts — the late-sync tolerance. */
    static final String KEY_OVERLAP_DAYS = "core.remidio.pull.overlapDays";
    /** Where the very first pass starts (ISO date); default one year back. */
    static final String KEY_SINCE = "core.remidio.pull.since";
    static final int DEFAULT_INTERVAL_SECONDS = 120;
    static final int MIN_INTERVAL_SECONDS = 30;
    /**
     * Two weeks: how late a phone may sync and still be caught. Not the poll
     * interval — the listing filters by capture date, not by upload time.
     */
    static final int DEFAULT_OVERLAP_DAYS = 14;
    static final int MAX_OVERLAP_DAYS = 365;
    static final int DEFAULT_SINCE_DAYS_BACK = 365;

    /** The patient sync (the Remidio side of the worklist) — its own flag, its own client. */
    static final String KEY_SYNC_CREATE_EXAMS = "core.remidio.patientSync.createExams";
    static final String KEY_SYNC_AHEAD_DAYS = "core.remidio.patientSync.aheadDays";
    static final String KEY_SYNC_BEHIND_DAYS = "core.remidio.patientSync.behindDays";
    static final int DEFAULT_SYNC_AHEAD_DAYS = 7;
    static final int DEFAULT_SYNC_BEHIND_DAYS = 1;
    static final int MAX_SYNC_DAYS = 60;

    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Settings activeSettings;
    private volatile RemidioGatewayClient client;
    private volatile RemidioDashboardClient.Settings activeSyncSettings;
    private volatile RemidioDashboardClient syncClient;
    private volatile Instant lastAttempt;
    private volatile Instant lastSuccess;
    private volatile String lastError;
    private volatile Instant lastSyncSuccess;
    private volatile String lastSyncError;
    private volatile boolean warnedUnconfigured;
    private volatile boolean warnedSyncUnconfigured;

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
            // The sync first: a visit scheduled a minute ago should be on the
            // phone before its capture could possibly come back through the pull.
            if (patientSyncEnabled()) syncOnce();
            runOnce();
        } finally {
            running.set(false);
        }
    }

    /**
     * One pass of the patient sync: every subject with a visit scheduled
     * from {@code behindDays} ago to {@code aheadDays} ahead exists in
     * Remidio, with an exam per visit. Never throws.
     */
    public Optional<RemidioPatientSyncService.Summary> syncOnce() {
        RemidioDashboardClient.Settings settings = RemidioDashboardClient.Settings.fromConfig();
        if (!settings.isConfigured()) {
            if (!warnedSyncUnconfigured) {
                LOG.warn("Remidio patient sync is enabled but not configured — {} is empty", settings.missing());
                warnedSyncUnconfigured = true;
            }
            lastSyncError = "not configured: " + settings.missing();
            return Optional.empty();
        }
        warnedSyncUnconfigured = false;
        if (syncClient == null || !settings.equals(activeSyncSettings)) {
            syncClient = new RemidioDashboardClient(settings);
            activeSyncSettings = settings;
        }
        LocalDate today = LocalDate.now(RemidioPullService.CLINIC_ZONE);
        LocalDate from = today.minusDays(syncBehindDays());
        LocalDate to = today.plusDays(syncAheadDays());
        try {
            RemidioPatientSyncService.Summary s = new RemidioPatientSyncService(dataSource, syncClient)
                    .sync(from, to, syncCreateExams());
            lastSyncSuccess = Instant.now();
            lastSyncError = null;
            if (s.patientsCreated() > 0 || s.examsCreated() > 0 || s.failed() > 0) {
                LOG.info("Remidio patient sync {}", s.line());
            } else {
                LOG.debug("Remidio patient sync {}", s.line());
            }
            return Optional.of(s);
        } catch (RemidioException e) {
            lastSyncError = e.reason() + ": " + e.getMessage();
            LOG.warn("Remidio patient sync failed ({}): {}", e.reason(), e.getMessage());
            if (e.reason() == RemidioException.Reason.UNAUTHORIZED) {
                syncClient.reset();
            }
        } catch (RuntimeException e) {
            lastSyncError = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.error("Remidio patient sync failed unexpectedly: {}", e.toString(), e);
        }
        return Optional.empty();
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
        try {
            RemidioPullService.Summary s = new RemidioPullService(dataSource, client)
                    .catchUp(overlapDays(), firstRunSince());
            lastSuccess = Instant.now();
            lastError = null;
            if (s.newExams() > 0 || s.failed() > 0) {
                LOG.info("Remidio pull {}", s.line());
            } else {
                LOG.debug("Remidio pull {}", s.line());
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

    /** When the sync last completed without error, for the status page. */
    public Optional<Instant> lastSyncSuccess() {
        return Optional.ofNullable(lastSyncSuccess);
    }

    public String lastSyncError() {
        return lastSyncError;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(cfg(RemidioGatewayClient.KEY_ENABLED, "false"));
    }

    public static boolean patientSyncEnabled() {
        return "true".equalsIgnoreCase(cfg(RemidioDashboardClient.KEY_ENABLED, "false"));
    }

    static boolean syncCreateExams() {
        return !"false".equalsIgnoreCase(cfg(KEY_SYNC_CREATE_EXAMS, "true"));
    }

    static int syncAheadDays() {
        return Math.max(0, Math.min(MAX_SYNC_DAYS, cfgInt(KEY_SYNC_AHEAD_DAYS, DEFAULT_SYNC_AHEAD_DAYS)));
    }

    static int syncBehindDays() {
        return Math.max(0, Math.min(MAX_SYNC_DAYS, cfgInt(KEY_SYNC_BEHIND_DAYS, DEFAULT_SYNC_BEHIND_DAYS)));
    }

    static int intervalSeconds() {
        return Math.max(MIN_INTERVAL_SECONDS, cfgInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS));
    }

    static int overlapDays() {
        return Math.max(0, Math.min(MAX_OVERLAP_DAYS, cfgInt(KEY_OVERLAP_DAYS, DEFAULT_OVERLAP_DAYS)));
    }

    /** The configured first-pass start, or a year back when unset or unparseable. */
    static LocalDate firstRunSince() {
        String raw = cfg(KEY_SINCE, "");
        if (!raw.isEmpty()) {
            try {
                return LocalDate.parse(raw);
            } catch (RuntimeException notADate) {
                LOG.warn("Remidio pull: {} is not an ISO date ('{}') — starting a year back", KEY_SINCE, raw);
            }
        }
        return LocalDate.now(RemidioPullService.CLINIC_ZONE).minusDays(DEFAULT_SINCE_DAYS_BACK);
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
