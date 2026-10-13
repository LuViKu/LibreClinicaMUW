/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DR-040 phase 2 — the reads behind the uploaders page and the ingest-source
 * list, shared by {@link UploadersApiController} and
 * {@link AdminIngestSourcesApiController}. Reads only; every change goes
 * through {@code IngestSourceService}, which also writes its audit row.
 *
 * <p>No token and no hash is selected, so no answer built from here can carry
 * one: the hint is the most a caller ever sees.
 */
final class IngestSourceQueries {

    private IngestSourceQueries() {}

    private static final String UPLOADER_SELECT =
            "SELECT s.ingest_source_id, s.location_id, l.code AS location_code, l.name AS location_name, "
                    + "s.display_name, s.token_hint, (s.revoked_at IS NOT NULL) AS revoked, "
                    + "cu.user_name AS created_by, s.created_at, s.last_used_at, s.revoked_at, s.instance_uid, "
                    + "ui.last_seen_at AS last_heartbeat_at "
                    + "FROM ingest_source s "
                    + "LEFT JOIN location l ON l.location_id = s.location_id "
                    + "LEFT JOIN user_account cu ON cu.user_id = s.created_by "
                    + "LEFT JOIN uploader_instance ui ON ui.instance_uid = s.instance_uid "
                    + "WHERE s.kind = 'uploader'";

    private static final String UPLOADER_ORDER =
            " ORDER BY lower(l.name), l.location_id, lower(s.display_name), s.ingest_source_id";

    /**
     * The uploaders at the given locations, by location name and then name;
     * every uploader when {@code locationIds} is null (a system administrator).
     */
    static List<UploaderDto> uploaders(Connection c, Set<Integer> locationIds) throws SQLException {
        if (locationIds != null && locationIds.isEmpty()) return List.of();
        List<UploaderDto> out = new ArrayList<>();
        // Two constant statements; the locations go in as one array parameter.
        try (PreparedStatement ps = c.prepareStatement(locationIds == null
                ? UPLOADER_SELECT + UPLOADER_ORDER
                : UPLOADER_SELECT + " AND s.location_id = ANY(?)" + UPLOADER_ORDER)) {
            if (locationIds != null) {
                ps.setArray(1, c.createArrayOf("integer", locationIds.toArray(new Integer[0])));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(uploaderOf(rs));
            }
        }
        return out;
    }

    /** One uploader; null when there is no uploader with the id (a source of another kind is not one). */
    static UploaderDto uploader(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPLOADER_SELECT + " AND s.ingest_source_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? uploaderOf(rs) : null;
            }
        }
    }

    private static UploaderDto uploaderOf(ResultSet rs) throws SQLException {
        int location = rs.getInt("location_id");
        boolean noLocation = rs.wasNull();
        return new UploaderDto(
                rs.getLong("ingest_source_id"),
                noLocation ? 0 : location,
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getString("display_name"),
                rs.getString("token_hint"),
                rs.getBoolean("revoked") ? "revoked" : "active",
                rs.getString("created_by"),
                iso(rs.getTimestamp("created_at")),
                iso(rs.getTimestamp("last_used_at")),
                iso(rs.getTimestamp("revoked_at")),
                rs.getString("instance_uid"),
                iso(rs.getTimestamp("last_heartbeat_at")));
    }

    private static final String SOURCE_SELECT =
            "SELECT s.ingest_source_id, s.kind, s.source_key, s.display_name, s.location_id, l.name AS location_name, "
                    + "(SELECT count(*) FROM ingest_item i WHERE i.ingest_source_id = s.ingest_source_id "
                    + "   AND i.status = 'UNBOUND') AS pending, "
                    + "s.last_used_at, (s.revoked_at IS NOT NULL) AS revoked "
                    + "FROM ingest_source s LEFT JOIN location l ON l.location_id = s.location_id";

    /**
     * Every source of every kind, in the order the SPA shows them: those
     * without a location first (they are the ones waiting for an
     * administrator), then by kind and by name without regard to case.
     */
    static List<IngestSourceDto> sources(Connection c) throws SQLException {
        List<IngestSourceDto> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SOURCE_SELECT
                + " ORDER BY (s.location_id IS NOT NULL), s.kind, lower(s.display_name), s.ingest_source_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(sourceOf(rs));
        }
        return out;
    }

    /** One source of any kind; null when there is none. */
    static IngestSourceDto source(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SOURCE_SELECT + " WHERE s.ingest_source_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? sourceOf(rs) : null;
            }
        }
    }

    private static IngestSourceDto sourceOf(ResultSet rs) throws SQLException {
        int location = rs.getInt("location_id");
        boolean noLocation = rs.wasNull();
        return new IngestSourceDto(
                rs.getLong("ingest_source_id"),
                rs.getString("kind"),
                rs.getString("source_key"),
                rs.getString("display_name"),
                noLocation ? null : location,
                rs.getString("location_name"),
                rs.getInt("pending"),
                iso(rs.getTimestamp("last_used_at")),
                rs.getBoolean("revoked") ? "revoked" : "active");
    }

    /** The most instances {@link #anonymousUploaders} lists; its count is the whole number. */
    static final int ANONYMOUS_LISTED = 50;

    /** An uploader program counts as in use while its last heartbeat is no older than this. */
    static final int ANONYMOUS_WINDOW_DAYS = 7;

    /**
     * The uploader programs seen in the last {@value #ANONYMOUS_WINDOW_DAYS}
     * days that no ingest source names: they have never reported with a token
     * (a revoked uploader still names its instance, so it is not one of them).
     * Newest first, at most {@value #ANONYMOUS_LISTED} listed, and the count is
     * of all of them (the window function counts before the limit).
     */
    static AnonymousUploadersDto anonymousUploaders(Connection c) throws SQLException {
        List<AnonymousUploaderDto> listed = new ArrayList<>();
        int total = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) OVER () AS total, ui.display_name, ui.kind, ui.last_seen_at "
                        + "FROM uploader_instance ui "
                        + "WHERE ui.last_seen_at >= now() - interval '" + ANONYMOUS_WINDOW_DAYS + " days' "
                        + "AND NOT EXISTS (SELECT 1 FROM ingest_source s WHERE s.instance_uid = ui.instance_uid) "
                        + "ORDER BY ui.last_seen_at DESC, ui.uploader_instance_id DESC LIMIT " + ANONYMOUS_LISTED);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total = rs.getInt("total");
                listed.add(new AnonymousUploaderDto(rs.getString("display_name"), rs.getString("kind"),
                        iso(rs.getTimestamp("last_seen_at"))));
            }
        }
        return new AnonymousUploadersDto(total, listed);
    }

    /** The ISO-8601 instant of a timestamp, as the other health endpoints write them; null for null. */
    static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
