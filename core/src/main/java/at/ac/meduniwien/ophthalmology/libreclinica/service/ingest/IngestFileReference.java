/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names an ingested file in the audit trail without naming the patient.
 *
 * <p>An audit row about a file used to carry only the file's internal id and
 * a status change. The file itself, and the inbox row describing it, are
 * deleted by the retention sweep once a dismissal is old enough, and from then
 * on the trail could not say which file had been dismissed. So every audit row
 * about a file now records this reference itself, in its entity name.
 *
 * <p>The reference is built only from properties that never change and do not
 * identify a person: the id, the device or route it came through, its kind,
 * the eye, when it arrived, and the start of its SHA-256. The original file
 * name and the patient fields are left out on purpose. A device export's name
 * can carry the patient's name, and a dismissed file is meant to disappear
 * with the sweep, not to live on in the audit trail. The checksum is enough to
 * match a copy of the file that still exists somewhere, which is what "which
 * file was it" needs.
 *
 * <p>Example: {@code file #482 · remidio · image · OD · received 2026-09-24 11:58 UTC · sha256 3f2a9c1b7e4d}
 */
public final class IngestFileReference {

    private static final Logger LOG = LoggerFactory.getLogger(IngestFileReference.class);

    /** Enough of the checksum to tell files apart; the full one is 64 characters. */
    static final int SHA256_PREFIX = 12;

    private static final DateTimeFormatter RECEIVED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final String SEPARATOR = " · ";

    private IngestFileReference() {
    }

    /**
     * The reference for an inbox row, or null when there is no such row or it
     * cannot be read. Never throws: an audit row must not fail because its
     * description could not be built.
     */
    public static String describe(Connection c, long ingestItemId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT device, source_ae_title, source_kind, kind, laterality, received_at, sha256 "
                        + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp received = rs.getTimestamp("received_at");
                return format(ingestItemId,
                        rs.getString("device"), rs.getString("source_ae_title"), rs.getString("source_kind"),
                        rs.getString("kind"), rs.getString("laterality"),
                        received == null ? null : received.toLocalDateTime(),
                        rs.getString("sha256"));
            }
        } catch (SQLException e) {
            LOG.warn("could not describe ingest_item {} for the audit trail: {}", ingestItemId, e.getMessage());
            return null;
        }
    }

    /** As {@link #describe(Connection, long)}, on a connection of its own. */
    public static String describe(DataSource dataSource, long ingestItemId) {
        try (Connection c = dataSource.getConnection()) {
            return describe(c, ingestItemId);
        } catch (SQLException e) {
            LOG.warn("could not describe ingest_item {} for the audit trail: {}", ingestItemId, e.getMessage());
            return null;
        }
    }

    /**
     * References for several rows in one query, keyed by id; ids without a
     * row are absent. For the audit view, which describes rows written before
     * the reference was recorded, as long as their file still exists.
     */
    public static Map<Long, String> describeAll(Connection c, Collection<Long> ingestItemIds)
            throws SQLException {
        Map<Long, String> out = new HashMap<>();
        if (ingestItemIds == null || ingestItemIds.isEmpty()) return out;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ingest_item_id, device, source_ae_title, source_kind, kind, laterality, "
                        + "       received_at, sha256 "
                        + "  FROM ingest_item WHERE ingest_item_id = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("bigint", ingestItemIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("ingest_item_id");
                    Timestamp received = rs.getTimestamp("received_at");
                    out.put(id, format(id,
                            rs.getString("device"), rs.getString("source_ae_title"), rs.getString("source_kind"),
                            rs.getString("kind"), rs.getString("laterality"),
                            received == null ? null : received.toLocalDateTime(),
                            rs.getString("sha256")));
                }
            }
        }
        return out;
    }

    /**
     * The reference from its parts. Blank parts are left out rather than shown
     * empty, so an older row without a checksum still reads cleanly.
     *
     * @param receivedAt the stored arrival time; the server stores UTC
     */
    static String format(long ingestItemId, String device, String aeTitle, String sourceKind,
                         String kind, String laterality, LocalDateTime receivedAt, String sha256) {
        List<String> parts = new ArrayList<>();
        parts.add("file #" + ingestItemId);
        String source = firstNonBlank(device, aeTitle, sourceKind);
        if (source != null) parts.add(source);
        if (notBlank(kind)) parts.add(kind.trim());
        if (notBlank(laterality)) parts.add(laterality.trim());
        if (receivedAt != null) parts.add("received " + RECEIVED.format(receivedAt) + " UTC");
        if (notBlank(sha256)) {
            String hex = sha256.trim();
            parts.add("sha256 " + (hex.length() > SHA256_PREFIX ? hex.substring(0, SHA256_PREFIX) : hex));
        }
        return String.join(SEPARATOR, parts);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) return v.trim();
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
