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
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * DR-040 phase 2 — ingest sources: where ingested files come from, and the
 * tokens that let an uploader program say so.
 *
 * <p>A source is a row of {@code ingest_source}: an <em>uploader</em> (a
 * program on an acquisition PC that authenticates with a token), a
 * <em>DICOM calling AE title</em>, the <em>Remidio account</em> (or, reserved,
 * the public upload page). Each has a location, or none yet. What a source
 * with no location sends waits for a system administrator
 * ({@code IngestItemVisibility}).
 *
 * <h2>Who calls what</h2>
 * <ul>
 *   <li>The administration endpoints use {@link #issueUploader},
 *       {@link #replaceToken}, {@link #revoke}, {@link #rename} and
 *       {@link #assignLocation}. Each is one transaction that changes the row
 *       and writes its audit row (types 201-205); nothing is half done.</li>
 *   <li>The device endpoints (the visit lookup, the upload, the heartbeat and
 *       the Optomed worklist) use {@link #findActiveByToken}: the token of the
 *       request decides which location the request works for.
 *       {@link #noteInstance} records which heartbeat instance a token
 *       belongs to.</li>
 *   <li>The DICOM receiver and the Remidio pull use {@link #upsertAutoSource}
 *       or {@link #resolveAutoSource} for a sender they have already
 *       accepted: the first time it is seen it becomes an unassigned source.
 *       The call creates a row, so it belongs behind whatever allow-list the
 *       caller has; it is not a way for the network to make rows.</li>
 * </ul>
 *
 * <h2>Tokens</h2>
 * Shape and hashing are in {@link UploaderTokens}. A token is returned exactly
 * once, inside the {@link IssuedToken} of {@link #issueUploader} or
 * {@link #replaceToken}; only its SHA-256 and an eight-character hint are
 * stored. <strong>Nothing in this class logs a token or a hash</strong>, and
 * the audit rows carry the hint at most. A failed statement is reported by its
 * SQLSTATE and never by the database's message, because a unique violation's
 * message quotes the value.
 *
 * <h2>Errors</h2>
 * A request that cannot be carried out throws {@link Refusal} (not found, a
 * location that is gone, a name already used, an invalid value); a database
 * that cannot be reached throws {@link Failure}. {@link #findActiveByToken}
 * never throws: it fails closed and answers "no such source".
 */
@Component
public class IngestSourceService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestSourceService.class);

    /** {@code ingest_source.kind}. */
    public static final String KIND_UPLOADER = "uploader";
    public static final String KIND_DICOM_AE = "dicom_ae";
    public static final String KIND_REMIDIO = "remidio";
    /** Reserved: the public upload page. Nothing creates one yet. */
    public static final String KIND_PORTAL = "portal";

    /** {@link Source#status()}. */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    /*
     * The audit types (lc-muw-2026-10-12-audit-types-ingest-sources.xml). The same ids are in
     * AuditTypeIds of the web module, which cannot be seen from here; a test keeps the two equal.
     * 206 and 207 (the Standort-Admin right) are written by the location administration.
     */
    /** An uploader issued, or a DICOM AE / Remidio account seen for the first time. */
    public static final int AUDIT_INGEST_SOURCE_CREATED = 201;
    public static final int AUDIT_UPLOADER_TOKEN_REPLACED = 202;
    public static final int AUDIT_UPLOADER_TOKEN_REVOKED = 203;
    public static final int AUDIT_UPLOADER_RENAMED = 204;
    public static final int AUDIT_INGEST_SOURCE_LOCATION_ASSIGNED = 205;

    /** {@code ingest_source.display_name} is VARCHAR(64). */
    public static final int NAME_MAX = 64;

    /** The longest DICOM AE title the platform accepts as a source key. */
    public static final int AE_TITLE_MAX = 16;

    /** A DICOM AE title as the platform accepts it: 1 to 16 of letters, digits, space, dot, underscore, hyphen. */
    private static final Pattern AE_TITLE = Pattern.compile("[A-Za-z0-9 ._-]{1," + AE_TITLE_MAX + "}");

    /** {@code last_used_at} is written at most this often per source. */
    static final int TOUCH_INTERVAL_SECONDS = 60;

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String UPLOADER_NAME_INDEX = "ux_ingest_source_uploader_name";
    private static final int AVAILABLE = LocationScope.STATUS_AVAILABLE;

    private final DataSource dataSource;

    @Autowired
    public IngestSourceService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ====================================================================== */
    /* The answers                                                             */
    /* ====================================================================== */

    /**
     * A source as the rest of the platform sees it. {@code locationId} is null
     * while the source is not assigned; {@code sourceKey} is null for an
     * uploader; {@code tokenHint} is null for a source without a token;
     * {@code status} is {@link #STATUS_ACTIVE} or {@link #STATUS_REVOKED}.
     */
    public record Source(long id, String kind, String sourceKey, String displayName, Integer locationId,
                         String tokenHint, String status) {
        public boolean active() {
            return STATUS_ACTIVE.equals(status);
        }
    }

    /**
     * A source and the token that was just made for it: the one and only time
     * the token is available. {@link #toString} leaves the token out, so a
     * log line that prints the object cannot leak it.
     */
    public record IssuedToken(Source source, String token) {
        @Override
        public String toString() {
            return "IssuedToken[source=" + source + ", token=<not shown>]";
        }
    }

    /** Why a request was refused. */
    public enum Reason {
        /** No such source (or not of the kind the call is about). */
        NOT_FOUND,
        /** The location named does not exist. */
        LOCATION_NOT_FOUND,
        /** The location exists but is disabled, or the source has none. */
        LOCATION_UNAVAILABLE,
        /** An uploader of that name already exists at the location. */
        NAME_TAKEN,
        /** A value is not acceptable; {@link Refusal#field()} names it. */
        INVALID
    }

    /** A request the service will not carry out. Nothing was changed. */
    public static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reason reason;
        private final String field;

        Refusal(Reason reason, String field, String message) {
            super(message);
            this.reason = reason;
            this.field = field;
        }

        public Reason reason() {
            return reason;
        }

        /** The request field at fault ({@code displayName}, {@code sourceKey}), or null. */
        public String field() {
            return field;
        }
    }

    /** The database could not do it. Carries no value from a statement that handled a token. */
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String what;
        private final String sqlState;

        Failure(String what, SQLException cause) {
            // The cause of an integrity violation quotes the offending values; only that class is withheld.
            super("could not " + what + " (SQLSTATE " + cause.getSQLState() + ")",
                    isIntegrityViolation(cause) ? null : cause);
            this.what = what;
            this.sqlState = cause.getSQLState();
        }

        /** What was being done, e.g. {@code issue the uploader}; fit for a message to the client. */
        public String what() {
            return what;
        }

        public String sqlState() {
            return sqlState;
        }
    }

    /* ====================================================================== */
    /* Administration: issue, replace, revoke, rename                          */
    /* ====================================================================== */

    /**
     * Issues an uploader at a location: a new source of kind {@code uploader}
     * and the only copy of its token.
     *
     * @param displayName a name that tells the uploader apart from the others
     *                    at the location (1 to 64 characters, unique there
     *                    without regard to case; revoked uploaders keep theirs)
     * @param actorUserId the signed-in user, recorded as the creator and in the audit row
     * @throws Refusal {@link Reason#LOCATION_NOT_FOUND}, {@link Reason#LOCATION_UNAVAILABLE},
     *                 {@link Reason#NAME_TAKEN} or {@link Reason#INVALID} (field {@code displayName})
     */
    public IssuedToken issueUploader(int locationId, String displayName, int actorUserId) {
        String name = cleanName(displayName, "displayName");
        String token = UploaderTokens.generate();
        String hash = UploaderTokens.hash(token);
        String hint = UploaderTokens.hint(token);
        return inTransaction("issue the uploader", c -> {
            Loc location = lockLocation(c, locationId);
            if (location == null) {
                throw new Refusal(Reason.LOCATION_NOT_FOUND, null, "No location with id " + locationId);
            }
            if (location.statusId() != AVAILABLE) {
                throw new Refusal(Reason.LOCATION_UNAVAILABLE, null,
                        "Location " + location.code() + " is disabled; restore it before issuing uploaders for it.");
            }
            if (nameTaken(c, locationId, name, 0)) {
                throw nameTakenRefusal(name);
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ingest_source (kind, display_name, location_id, token_hash, token_hint, created_by, "
                            + "created_at) VALUES ('uploader', ?, ?, ?, ?, ?, now()) RETURNING ingest_source_id")) {
                ps.setString(1, name);
                ps.setInt(2, locationId);
                ps.setString(3, hash);
                ps.setString(4, hint);
                setUser(ps, 5, actorUserId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            audit(c, AUDIT_INGEST_SOURCE_CREATED, actorUserId, id, name, null,
                    "kind=uploader; location=" + location.code() + "; hint=" + hint);
            LOG.info("ingest sources: uploader id {} issued at location id {} by user id={}", id, locationId, actorUserId);
            return new IssuedToken(readSource(c, id), token);
        });
    }

    /**
     * Replaces an uploader's token: a new one is made, the old one stops working
     * at once, and a revoked uploader becomes active again. The uploader's use
     * history ({@code last_used_at}, the heartbeat instance) starts over, so the
     * first request with the new token is visible as such.
     *
     * @throws Refusal {@link Reason#NOT_FOUND} (no such uploader) or
     *                 {@link Reason#LOCATION_UNAVAILABLE} (its location is disabled, or it has none)
     */
    public IssuedToken replaceToken(long sourceId, int actorUserId) {
        String token = UploaderTokens.generate();
        String hash = UploaderTokens.hash(token);
        String hint = UploaderTokens.hint(token);
        return inTransaction("replace the token", c -> {
            Row row = lockUploader(c, sourceId);
            Loc location = row.locationId() == null ? null : lockLocation(c, row.locationId());
            if (location == null || location.statusId() != AVAILABLE) {
                throw new Refusal(Reason.LOCATION_UNAVAILABLE, null, location == null
                        ? "The uploader is not assigned to a location."
                        : "Location " + location.code() + " is disabled; restore it before replacing tokens.");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_source SET token_hash = ?, token_hint = ?, revoked_at = NULL, revoked_by = NULL, "
                            + "last_used_at = NULL, instance_uid = NULL WHERE ingest_source_id = ?")) {
                ps.setString(1, hash);
                ps.setString(2, hint);
                ps.setLong(3, sourceId);
                ps.executeUpdate();
            }
            audit(c, AUDIT_UPLOADER_TOKEN_REPLACED, actorUserId, sourceId, row.displayName(),
                    "hint=" + row.tokenHint() + (row.revoked() ? "; status=revoked" : ""),
                    "hint=" + hint + "; location=" + location.code() + (row.revoked() ? "; status=active" : ""));
            LOG.info("ingest sources: token of uploader id {} replaced by user id={}", sourceId, actorUserId);
            return new IssuedToken(readSource(c, sourceId), token);
        });
    }

    /**
     * Revokes an uploader: its token is refused from now on. Revoking a revoked
     * uploader changes nothing and writes nothing. The uploader keeps its name
     * and its row; {@link #replaceToken} brings it back.
     *
     * @throws Refusal {@link Reason#NOT_FOUND}
     */
    public void revoke(long sourceId, int actorUserId) {
        inTransaction("revoke the token", c -> {
            Row row = lockUploader(c, sourceId);
            if (row.revoked()) return null;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_source SET revoked_at = now(), revoked_by = ? WHERE ingest_source_id = ?")) {
                setUser(ps, 1, actorUserId);
                ps.setLong(2, sourceId);
                ps.executeUpdate();
            }
            audit(c, AUDIT_UPLOADER_TOKEN_REVOKED, actorUserId, sourceId, row.displayName(),
                    "status=active; hint=" + row.tokenHint(),
                    "status=revoked; location=" + locationCode(c, row.locationId()));
            LOG.info("ingest sources: uploader id {} revoked by user id={}", sourceId, actorUserId);
            return null;
        });
    }

    /**
     * Renames an uploader. The same name (exactly) changes and audits nothing; a
     * change of case only is a rename.
     *
     * @throws Refusal {@link Reason#NOT_FOUND}, {@link Reason#NAME_TAKEN} or
     *                 {@link Reason#INVALID} (field {@code displayName})
     */
    public void rename(long sourceId, String displayName, int actorUserId) {
        String name = cleanName(displayName, "displayName");
        inTransaction("rename the uploader", c -> {
            Row row = lockUploader(c, sourceId);
            if (name.equals(row.displayName())) return null;
            if (row.locationId() != null && nameTaken(c, row.locationId(), name, sourceId)) {
                throw nameTakenRefusal(name);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_source SET display_name = ? WHERE ingest_source_id = ?")) {
                ps.setString(1, name);
                ps.setLong(2, sourceId);
                ps.executeUpdate();
            }
            audit(c, AUDIT_UPLOADER_RENAMED, actorUserId, sourceId, name, "name=" + row.displayName(),
                    "name=" + name + "; location=" + locationCode(c, row.locationId()));
            LOG.info("ingest sources: uploader id {} renamed by user id={}", sourceId, actorUserId);
            return null;
        });
    }

    /* ====================================================================== */
    /* Location                                                                */
    /* ====================================================================== */

    /**
     * Assigns a source to a location, or, with {@code null}, takes it back to
     * "not assigned". Any kind of source can be assigned.
     *
     * <p>Assigning also places what is waiting: the source's files that are
     * still unbound or dismissed and have neither an origin location nor an
     * origin study get the location as their origin, so they appear in the
     * right inboxes at once. Files that already have an origin keep it, and
     * taking a source back from a location does not move its files back. The
     * change, the files and the audit row are one transaction. Assigning the
     * location a source already has still places its stragglers, and writes
     * an audit row only if something changed.
     *
     * <p>An uploader always has a location (it was issued for one): taking it
     * back to none is refused, and so is moving it to a location where another
     * uploader already has its name.
     *
     * @throws Refusal {@link Reason#NOT_FOUND}, {@link Reason#LOCATION_UNAVAILABLE} (the
     *                 location does not exist or is disabled), {@link Reason#NAME_TAKEN}
     *                 (field {@code locationId}) or {@link Reason#INVALID} (field
     *                 {@code locationId}: {@code null} for an uploader)
     */
    public void assignLocation(long sourceId, Integer locationId, int actorUserId) {
        inTransaction("assign the location", c -> {
            Row row = lock(c, sourceId);
            if (locationId == null && KIND_UPLOADER.equals(row.kind())) {
                throw new Refusal(Reason.INVALID, "locationId",
                        "An uploader always belongs to a location; revoke it to stop it.");
            }
            Loc target = null;
            if (locationId != null) {
                target = lockLocation(c, locationId);
                if (target == null || target.statusId() != AVAILABLE) {
                    throw new Refusal(Reason.LOCATION_UNAVAILABLE, null, target == null
                            ? "Location " + locationId + " does not exist."
                            : "Location " + target.code() + " is disabled; restore it before assigning sources to it.");
                }
            }
            boolean moved = !Objects.equals(row.locationId(), locationId);
            if (moved) {
                if (locationId != null && KIND_UPLOADER.equals(row.kind())
                        && nameTaken(c, locationId, row.displayName(), sourceId)) {
                    throw new Refusal(Reason.NAME_TAKEN, "locationId",
                            "An uploader named '" + row.displayName() + "' already exists at that location.");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ingest_source SET location_id = ? WHERE ingest_source_id = ?")) {
                    if (locationId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, locationId);
                    ps.setLong(2, sourceId);
                    ps.executeUpdate();
                }
            }
            int placed = 0;
            if (locationId != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ingest_item SET origin_location_id = ? WHERE ingest_source_id = ? "
                                + "AND status IN ('UNBOUND', 'DISMISSED') "
                                + "AND origin_location_id IS NULL AND origin_study_id IS NULL")) {
                    ps.setInt(1, locationId);
                    ps.setLong(2, sourceId);
                    placed = ps.executeUpdate();
                }
            }
            if (moved || placed > 0) {
                audit(c, AUDIT_INGEST_SOURCE_LOCATION_ASSIGNED, actorUserId, sourceId, row.displayName(),
                        "location=" + (row.locationId() == null ? "unassigned" : locationCode(c, row.locationId())),
                        "kind=" + row.kind() + "; location=" + (target == null ? "unassigned" : target.code())
                                + "; items=" + placed);
                LOG.info("ingest sources: source id {} assigned to location {} by user id={} ({} waiting items placed)",
                        sourceId, locationId, actorUserId, placed);
            }
            return null;
        });
    }

    /* ====================================================================== */
    /* The device endpoints                                                    */
    /* ====================================================================== */

    /**
     * The source a token belongs to, for a request that carries it; empty for
     * anything that is not an acceptable token:
     * <ul>
     *   <li>a string that is not shaped like a token (no lookup is made);</li>
     *   <li>an unknown token, and one that was replaced;</li>
     *   <li>a revoked uploader;</li>
     *   <li>an uploader with no location, or whose location is disabled.</li>
     * </ul>
     * The caller cannot tell these apart and must not try to tell the client:
     * every one of them is "token rejected". A database that cannot be read also
     * answers empty (fail closed), and is reported by its SQLSTATE only.
     *
     * <p>The lookup is by the token's hash through a unique index, with no
     * cache, so a revoked or replaced token is refused on the very next
     * request. A valid token also renews {@code last_used_at}, but at most once
     * a minute per source, and only if the row is that old: an ordinary request
     * reads one row and writes none. A failed renewal never fails the request.
     *
     * @return the active source, whose {@link Source#locationId()} is never null
     */
    public Optional<Source> findActiveByToken(String plainToken) {
        if (!UploaderTokens.wellFormed(plainToken)) return Optional.empty();
        String hash = UploaderTokens.hash(plainToken);
        try (Connection c = dataSource.getConnection()) {
            Seen seen = seenBy(c, "s.token_hash = ?", hash);
            if (seen == null || !usable(seen.row().revoked(), seen.row().locationId(), seen.locationStatus())) {
                return Optional.empty();
            }
            touchIfDue(c, seen);
            return Optional.of(seen.row().toSource());
        } catch (SQLException | RuntimeException e) {
            LOG.warn("uploader token lookup failed ({})", e instanceof SQLException s ? s.getSQLState() : e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Whether a source found by its token may be used: not revoked and at a
     * location that exists and is available. Pure, so the rule is tested
     * without a database.
     */
    static boolean usable(boolean revoked, Integer locationId, Integer locationStatus) {
        return !revoked && locationId != null && locationStatus != null && locationStatus == AVAILABLE;
    }

    /**
     * Whether {@code last_used_at} is due for renewal: never written, or older
     * than {@value #TOUCH_INTERVAL_SECONDS} seconds by the database's own clock
     * (so the JVM's clock and time zone play no part).
     */
    static boolean touchDue(Double ageSeconds) {
        return ageSeconds == null || ageSeconds >= TOUCH_INTERVAL_SECONDS;
    }

    /** One source by id; empty when there is none. */
    public Optional<Source> find(long sourceId) {
        try (Connection c = dataSource.getConnection()) {
            Seen seen = seenBy(c, "s.ingest_source_id = ?", sourceId);
            return seen == null ? Optional.empty() : Optional.of(seen.row().toSource());
        } catch (SQLException e) {
            throw new Failure("read the source", e);
        }
    }

    /**
     * Records which heartbeat instance ({@code uploader_instance.instance_uid})
     * an uploader's token is being used by, so the uploaders page can show the
     * health of the program behind it. Writes only when the value changes. A
     * blank or longer than 36 characters value is ignored.
     */
    public void noteInstance(long sourceId, String instanceUid) {
        if (instanceUid == null || instanceUid.isBlank() || instanceUid.length() > 36) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ingest_source SET instance_uid = ? WHERE ingest_source_id = ? "
                             + "AND instance_uid IS DISTINCT FROM ?")) {
            ps.setString(1, instanceUid);
            ps.setLong(2, sourceId);
            ps.setString(3, instanceUid);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not note the instance of source {} ({})", sourceId, e.getSQLState());
        }
    }

    /* ====================================================================== */
    /* Senders that have no credential                                         */
    /* ====================================================================== */

    /**
     * The id of the source for a DICOM calling AE title ({@link #KIND_DICOM_AE})
     * or the Remidio account ({@link #KIND_REMIDIO}), created without a
     * location the first time it is seen. Idempotent and race-safe: any number
     * of callers get the same id. The call also renews {@code last_used_at}
     * ("last seen"), at most once a minute.
     *
     * <p>A first sight is audited (type 201, no user: the system did it).
     * Call this only for a sender the caller already accepts; the key comes
     * from the network, so the caller's own allow-list is what keeps the table
     * from growing with whatever knocks.
     *
     * @param displayName what the administrator sees; defaults to the key when
     *                    blank or longer than 64 characters. An existing source
     *                    keeps its name.
     * @throws IllegalArgumentException for a kind other than the two above
     * @throws Refusal {@link Reason#INVALID} (field {@code sourceKey}) for a key that is
     *                 not acceptable: an AE title is 1 to 16 of {@code A-Z a-z 0-9 space . _ -}
     */
    public long upsertAutoSource(String kind, String sourceKey, String displayName) {
        return resolveAutoSource(kind, sourceKey, displayName).id();
    }

    /** As {@link #upsertAutoSource}, returning the whole source (so the caller sees its location in one call). */
    public Source resolveAutoSource(String kind, String sourceKey, String displayName) {
        if (!KIND_DICOM_AE.equals(kind) && !KIND_REMIDIO.equals(kind)) {
            throw new IllegalArgumentException("an automatic source is a dicom_ae or a remidio, not " + kind);
        }
        String key = cleanKey(kind, sourceKey);
        String name = nameOrKey(displayName, key);
        try (Connection c = dataSource.getConnection()) {
            Seen seen = seenBy(c, "s.kind = ? AND s.source_key = ?", kind, key);
            if (seen != null) {
                touchIfDue(c, seen);
                return seen.row().toSource();
            }
        } catch (SQLException e) {
            throw new Failure("read the source", e);
        }
        return inTransaction("create the source", c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ingest_source (kind, source_key, display_name, created_at, last_used_at) "
                            + "VALUES (?, ?, ?, now(), now()) "
                            + "ON CONFLICT (kind, source_key) WHERE source_key IS NOT NULL DO NOTHING "
                            + "RETURNING ingest_source_id")) {
                ps.setString(1, kind);
                ps.setString(2, key);
                ps.setString(3, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // Another caller made it between our look and our insert.
                        Seen won = seenBy(c, "s.kind = ? AND s.source_key = ?", kind, key);
                        return won.row().toSource();
                    }
                    id = rs.getLong(1);
                }
            }
            audit(c, AUDIT_INGEST_SOURCE_CREATED, 0, id, name, null,
                    "kind=" + kind + "; key=" + key + "; location=unassigned");
            LOG.info("ingest sources: {} source id {} seen for the first time", kind, id);
            return readSource(c, id);
        });
    }

    /* ====================================================================== */
    /* Validation                                                              */
    /* ====================================================================== */

    /**
     * A display name as stored: trimmed, 1 to 64 characters, nothing a person
     * cannot see (control and invisible formatting characters, which can make
     * two names look alike or one name look like two lines).
     */
    static String cleanName(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new Refusal(Reason.INVALID, field, "Name is required");
        }
        if (name.codePointCount(0, name.length()) > NAME_MAX) {
            throw new Refusal(Reason.INVALID, field, "Name must not exceed " + NAME_MAX + " characters");
        }
        if (hasInvisible(name)) {
            throw new Refusal(Reason.INVALID, field, "Name must not contain control or invisible formatting characters");
        }
        return name;
    }

    /** A source key: an AE title by its pattern, an account name like a display name. */
    static String cleanKey(String kind, String raw) {
        String key = raw == null ? "" : raw.strip();
        if (KIND_DICOM_AE.equals(kind)) {
            if (!AE_TITLE.matcher(key).matches()) {
                throw new Refusal(Reason.INVALID, "sourceKey", "A DICOM AE title is 1 to " + AE_TITLE_MAX
                        + " characters: letters, digits, space, dot, underscore or hyphen");
            }
            return key;
        }
        if (key.isEmpty() || key.codePointCount(0, key.length()) > NAME_MAX || hasInvisible(key)) {
            throw new Refusal(Reason.INVALID, "sourceKey", "A source key is 1 to " + NAME_MAX
                    + " visible characters");
        }
        return key;
    }

    /** The name an automatic source gets: the one the caller offers if it is acceptable, else its key. */
    private static String nameOrKey(String offered, String key) {
        String name = offered == null ? "" : offered.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > NAME_MAX || hasInvisible(name)) return key;
        return name;
    }

    private static boolean hasInvisible(String s) {
        return s.codePoints().anyMatch(cp -> switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
            default -> false;
        });
    }

    private static Refusal nameTakenRefusal(String name) {
        return new Refusal(Reason.NAME_TAKEN, "displayName", "An uploader named '" + name
                + "' already exists at this location (it may be revoked: replace its token instead).");
    }

    /* ====================================================================== */
    /* Rows                                                                    */
    /* ====================================================================== */

    private record Row(long id, String kind, String sourceKey, String displayName, Integer locationId,
                       String tokenHint, boolean revoked) {
        Source toSource() {
            return new Source(id, kind, sourceKey, displayName, locationId, tokenHint,
                    revoked ? STATUS_REVOKED : STATUS_ACTIVE);
        }
    }

    private record Loc(int id, String code, int statusId) {}

    /** A row with the status of its location and the age of {@code last_used_at} by the database's clock. */
    private record Seen(Row row, Integer locationStatus, Double ageSeconds) {}

    private static final String ROW_COLUMNS = "s.ingest_source_id, s.kind, s.source_key, s.display_name, "
            + "s.location_id, s.token_hint, (s.revoked_at IS NOT NULL) AS revoked";

    private static Row readRow(ResultSet rs) throws SQLException {
        int location = rs.getInt("location_id");
        Integer locationId = rs.wasNull() ? null : location;
        return new Row(rs.getLong("ingest_source_id"), rs.getString("kind"), rs.getString("source_key"),
                rs.getString("display_name"), locationId, rs.getString("token_hint"), rs.getBoolean("revoked"));
    }

    /** The one source matching the condition (which binds {@code params}), or null. */
    private static Seen seenBy(Connection c, String where, Object... params) throws SQLException {
        String sql = "SELECT " + ROW_COLUMNS + ", l.status_id AS location_status, "
                + "CASE WHEN s.last_used_at IS NULL THEN NULL "
                + "     ELSE EXTRACT(EPOCH FROM (now() - s.last_used_at))::float8 END AS used_age "
                + "FROM ingest_source s LEFT JOIN location l ON l.location_id = s.location_id WHERE " + where;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Row row = readRow(rs);
                int status = rs.getInt("location_status");
                Integer locationStatus = rs.wasNull() ? null : status;
                double age = rs.getDouble("used_age");
                Double ageSeconds = rs.wasNull() ? null : age;
                return new Seen(row, locationStatus, ageSeconds);
            }
        }
    }

    private static Source readSource(Connection c, long id) throws SQLException {
        Seen seen = seenBy(c, "s.ingest_source_id = ?", id);
        if (seen == null) throw new Refusal(Reason.NOT_FOUND, null, "No source with id " + id);
        return seen.row().toSource();
    }

    /** The source row, locked for the rest of the transaction; {@link Reason#NOT_FOUND} when there is none. */
    private static Row lock(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + ROW_COLUMNS + " FROM ingest_source s WHERE s.ingest_source_id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new Refusal(Reason.NOT_FOUND, null, "No source with id " + id);
                return readRow(rs);
            }
        }
    }

    /** As {@link #lock}, for the calls that are about uploaders only. */
    private static Row lockUploader(Connection c, long id) throws SQLException {
        Row row = lock(c, id);
        if (!KIND_UPLOADER.equals(row.kind())) {
            throw new Refusal(Reason.NOT_FOUND, null, "No uploader with id " + id);
        }
        return row;
    }

    /** The location row, locked against a concurrent disable; null when there is none. */
    private static Loc lockLocation(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT location_id, code, status_id FROM location WHERE location_id = ? FOR SHARE")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Loc(rs.getInt(1), rs.getString(2), rs.getInt(3)) : null;
            }
        }
    }

    /** The code of a location for an audit text; {@code unassigned} for none. */
    private static String locationCode(Connection c, Integer locationId) throws SQLException {
        if (locationId == null) return "unassigned";
        try (PreparedStatement ps = c.prepareStatement("SELECT code FROM location WHERE location_id = ?")) {
            ps.setInt(1, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "unknown";
            }
        }
    }

    /** True when another uploader at the location already has the name, without regard to case. */
    private static boolean nameTaken(Connection c, int locationId, String name, long exceptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ingest_source WHERE kind = 'uploader' AND location_id = ? "
                        + "AND lower(display_name) = lower(?) AND ingest_source_id <> ? LIMIT 1")) {
            ps.setInt(1, locationId);
            ps.setString(2, name);
            ps.setLong(3, exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void setUser(PreparedStatement ps, int index, int userId) throws SQLException {
        if (userId > 0) ps.setInt(index, userId); else ps.setNull(index, Types.INTEGER);
    }

    /**
     * Renews {@code last_used_at} when it is due. The condition is repeated in
     * the statement, so two requests that both saw a stale row write once. A
     * failure is a debug line and nothing else: authentication does not depend
     * on bookkeeping.
     */
    private static void touchIfDue(Connection c, Seen seen) {
        if (!touchDue(seen.ageSeconds())) return;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ingest_source SET last_used_at = now() WHERE ingest_source_id = ? "
                        + "AND (last_used_at IS NULL OR last_used_at < now() - interval '"
                        + TOUCH_INTERVAL_SECONDS + " seconds')")) {
            ps.setLong(1, seen.row().id());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.debug("could not renew last_used_at of source {} ({})", seen.row().id(), e.getSQLState());
        }
    }

    /**
     * One {@code audit_log_event} row against table {@code ingest_source}: the
     * source's id, its display name, and the values. Never a token; the hint at
     * most. A user id of 0 or less is the system (the row has no user). The
     * insert is part of the caller's transaction.
     */
    private static void audit(Connection c, int typeId, int userId, long sourceId, String entityName,
                              String oldValue, String newValue) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                        + "entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, 'ingest_source', ?, ?, ?, ?)")) {
            ps.setInt(1, typeId);
            setUser(ps, 2, userId);
            ps.setInt(3, Math.toIntExact(sourceId));
            ps.setString(4, entityName);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.executeUpdate();
        }
    }

    /* ====================================================================== */
    /* Transaction                                                             */
    /* ====================================================================== */

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /**
     * Runs the work in one transaction: it commits when the work returns and
     * rolls back when it throws, so a change is never made without its audit
     * row and a refusal leaves nothing behind.
     */
    private <T> T inTransaction(String what, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The connection is closed next; a pool resets it on return.
                }
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState()) && e.getMessage() != null
                    && e.getMessage().contains(UPLOADER_NAME_INDEX)) {
                // Two requests for one name both got past the check; the index is the final word.
                throw new Refusal(Reason.NAME_TAKEN, "displayName",
                        "An uploader with this name already exists at the location.");
            }
            throw new Failure(what, e);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            LOG.warn("ingest sources: rollback failed ({})", e.getSQLState());
        }
    }

    /** SQLSTATE class 23: the message of such an error quotes the values involved. */
    private static boolean isIntegrityViolation(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }
}
