/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The time zone in which the clinic's calendar runs: what "today" is.
 *
 * <p>The server itself runs in UTC, and stays there: timestamps are stored
 * without a zone in the JVM's zone, everything stored so far is UTC, and a
 * UTC clock never repeats an hour. But a calendar day is a clinic's day. A
 * worklist for "today", the visits due, a date that must not lie in the
 * future, the date on an export - taken in UTC, each of them was still the
 * previous day between midnight and 01:00 (winter) or 02:00 (summer) in
 * Vienna. Every such decision asks this class instead of
 * {@code LocalDate.now()}.
 *
 * <p>{@value #CONFIG_KEY} in {@code datainfo.properties}; blank or invalid
 * means {@link #DEFAULT} (an invalid value is logged once).
 */
public final class ClinicZone {

    private static final Logger LOG = LoggerFactory.getLogger(ClinicZone.class);

    public static final String CONFIG_KEY = "core.clinicZone";
    public static final ZoneId DEFAULT = ZoneId.of("Europe/Vienna");

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private ClinicZone() {
    }

    /** The configured clinic zone, {@link #DEFAULT} when unset or invalid. */
    public static ZoneId zone() {
        String raw;
        try {
            raw = CoreResources.getField(CONFIG_KEY);
        } catch (RuntimeException notInitialised) {
            // CoreResources is not loaded (unit tests, early start-up).
            raw = null;
        }
        return parse(raw);
    }

    /** Today's date in the clinic. */
    public static LocalDate today() {
        return LocalDate.now(zone());
    }

    /** Today's date in the clinic by the given clock - the testable form. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(zone()));
    }

    /** A zone id, {@link #DEFAULT} when blank or not a zone. */
    static ZoneId parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException invalid) {
            if (WARNED.compareAndSet(false, true)) {
                LOG.warn("{} is not a time zone id; using {}", CONFIG_KEY, DEFAULT.getId());
            }
            return DEFAULT;
        }
    }
}
