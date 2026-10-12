/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import java.util.Locale;
import java.util.Objects;

/**
 * DR-040 — whether this deployment serves one location or several, chosen when
 * it is deployed ({@code LIBRECLINICA_DEPLOYMENT_LOCATIONS}, Spring property
 * {@value #PROPERTY}) and never by how many locations happen to exist.
 *
 * <ul>
 *   <li>{@link #SINGLE} (the default): exactly one location is active and a
 *       second cannot be created. Everything without a place of its own
 *       belongs to it, and a device may upload without an uploader token.</li>
 *   <li>{@link #MULTI}: any number of locations. A device needs its token from
 *       the first location on, and what has no place belongs to none (system
 *       administrators only, until assigned).</li>
 * </ul>
 *
 * The mode is read once at startup by {@code LocationModeConfig}, which also
 * refuses an unknown value and a single-mode database with several active
 * locations. It is held here so that code without Spring wiring (the DAOs, the
 * static helpers of {@link LocationScope}) reads the same answer. Until the
 * startup sets it, and in unit tests that do not, it is {@link #SINGLE}.
 */
public enum LocationMode {

    SINGLE,
    MULTI;

    /** The Spring property; the environment variable is {@code LIBRECLINICA_DEPLOYMENT_LOCATIONS}. */
    public static final String PROPERTY = "libreclinica.deployment.locations";

    private static volatile LocationMode current = SINGLE;

    /** The mode of this deployment. */
    public static LocationMode current() {
        return current;
    }

    /** Set by the startup; tests set and restore it. */
    public static void set(LocationMode mode) {
        current = Objects.requireNonNull(mode, "mode");
    }

    /**
     * {@code single} or {@code multi}, case and surrounding blanks ignored;
     * unset or blank is {@link #SINGLE}.
     *
     * @throws IllegalArgumentException for any other value
     */
    public static LocationMode parse(String raw) {
        if (raw == null || raw.isBlank()) return SINGLE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "single" -> SINGLE;
            case "multi" -> MULTI;
            default -> throw new IllegalArgumentException(
                    "LIBRECLINICA_DEPLOYMENT_LOCATIONS (" + PROPERTY + ") must be 'single' or 'multi'");
        };
    }

    /** The value as the API and the environment spell it: {@code single} or {@code multi}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
