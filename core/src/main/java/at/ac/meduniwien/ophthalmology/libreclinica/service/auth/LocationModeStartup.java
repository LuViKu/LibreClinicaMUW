/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DR-040 — what the deployment's {@link LocationMode} requires of the database,
 * checked and settled once at startup, after Liquibase.
 *
 * <ul>
 *   <li><strong>Single mode with several active locations refuses to
 *       start.</strong> The application never creates that state (a second
 *       location is refused in single mode); only switching a multi-location
 *       deployment back does. Starting anyway would quietly merge the
 *       locations' inboxes and lookups.</li>
 *   <li><strong>Single mode places what has no place.</strong> Studies,
 *       ingest sources and unfiled files without a location are given the one
 *       location. In single mode they belong to it anyway; written down, a
 *       later switch to multi finds nothing left without a place. Each table
 *       that changed gets one audit row (type {@value #AUDIT_LOCATION_BACKFILLED})
 *       with the count; a run that changes nothing writes nothing.</li>
 *   <li>Multi mode needs nothing: what has no place stays without one, for a
 *       system administrator to assign.</li>
 * </ul>
 */
public final class LocationModeStartup {

    private static final Logger LOG = LoggerFactory.getLogger(LocationModeStartup.class);

    /** {@code audit_log_event_type} 208, seeded by {@code lc-muw-2026-10-13-location-mode.xml}. */
    public static final int AUDIT_LOCATION_BACKFILLED = 208;

    /** The message a refused start logs; it names the way out. */
    static final String SEVERAL_IN_SINGLE_MODE =
            "%d locations are active, but this deployment is in single-location mode "
                    + "(LIBRECLINICA_DEPLOYMENT_LOCATIONS=single). Either set "
                    + "LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi, or disable all locations but one "
                    + "(location.status_id) before starting in single mode.";

    /** One statement of the backfill: the table it changes and the update, whose only parameter is the location. */
    private record Placement(String table, String sql) {}

    private static final List<Placement> PLACEMENTS = List.of(
            new Placement("study", "UPDATE study SET location_id = ? WHERE location_id IS NULL"),
            new Placement("ingest_source", "UPDATE ingest_source SET location_id = ? WHERE location_id IS NULL"),
            new Placement("ingest_item", "UPDATE ingest_item SET origin_location_id = ? "
                    + "WHERE origin_location_id IS NULL AND origin_study_id IS NULL "
                    + "AND status IN ('UNBOUND', 'DISMISSED')"));

    /** What a run did, for the log and the tests. */
    public record Result(LocationMode mode, Integer locationId, List<String> placed) {}

    private LocationModeStartup() {}

    /**
     * Checks the database against the mode and, in single mode, places what has
     * no place, in one transaction.
     *
     * @throws IllegalStateException single mode with several active locations;
     *         the message says what to do. Also when the database cannot be read:
     *         a start that cannot check is not a start.
     */
    public static Result apply(DataSource dataSource, LocationMode mode) {
        if (mode == LocationMode.MULTI) {
            LOG.info("Location mode: multi. Device uploads need an uploader token; "
                    + "what has no location waits for an administrator.");
            return new Result(mode, null, List.of());
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Result r = applySingle(c);
                c.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not check the locations for single-location mode (SQLSTATE "
                    + e.getSQLState() + ")", e);
        }
    }

    private static Result applySingle(Connection c) throws SQLException {
        List<Integer> active = new ArrayList<>();
        String code = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT location_id, code FROM location WHERE status_id = ? ORDER BY location_id")) {
            ps.setInt(1, LocationScope.STATUS_AVAILABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    active.add(rs.getInt(1));
                    if (code == null) code = rs.getString(2);
                }
            }
        }
        if (active.size() > 1) {
            throw new IllegalStateException(String.format(SEVERAL_IN_SINGLE_MODE, active.size()));
        }
        if (active.isEmpty()) {
            // Nothing to place things at; System > Standorte can restore or create the one location.
            LOG.warn("Location mode: single, but no location is active; nothing is placed until one is.");
            return new Result(LocationMode.SINGLE, null, List.of());
        }
        int locationId = active.get(0);
        List<String> placed = new ArrayList<>();
        for (Placement p : PLACEMENTS) {
            int rows;
            try (PreparedStatement ps = c.prepareStatement(p.sql())) {
                ps.setInt(1, locationId);
                rows = ps.executeUpdate();
            }
            if (rows > 0) {
                audit(c, p.table(), locationId, code, rows);
                placed.add(p.table() + "=" + rows);
            }
        }
        if (placed.isEmpty()) {
            LOG.info("Location mode: single, location {} ({}).", code, locationId);
        } else {
            LOG.info("Location mode: single, location {} ({}); placed at it: {}.", code, locationId,
                    String.join(", ", placed));
        }
        return new Result(LocationMode.SINGLE, locationId, List.copyOf(placed));
    }

    /** One row per table: the location's id and code, and how many rows were placed. No user: the system did it. */
    private static void audit(Connection c, String table, int locationId, String code, int rows)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                        + "entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), NULL, 'location', ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_LOCATION_BACKFILLED);
            ps.setInt(2, locationId);
            ps.setString(3, code);
            ps.setString(4, "");
            ps.setString(5, table + "=" + rows);
            ps.executeUpdate();
        }
    }
}
