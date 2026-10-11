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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DR-040 phase 1 — System &gt; Standorte: the administration of global
 * locations.
 *
 * <p>A location is a physical place (a clinic, a department, a partner
 * hospital), global and managed here by system administrators. Studies and
 * sites point at one ({@code study.location_id}); devices and ingested files
 * will follow in the next phase. This controller is the SPA's whole backend
 * for the page; the contract is the list below, and every state change is
 * audited (types 195-200, and 206-207 for the Standort-Admin right,
 * {@link AuditTypeIds}).
 *
 * <ul>
 *   <li>{@code GET    /admin/locations} — every location, removed ones
 *       included, ordered by name, with the four counts.</li>
 *   <li>{@code POST   /admin/locations} — create; 201.</li>
 *   <li>{@code GET    /admin/locations/{id}} — the location with its studies
 *       and sites and its explicitly assigned users.</li>
 *   <li>{@code PUT    /admin/locations/{id}} — edit the name and address; the
 *       code is immutable.</li>
 *   <li>{@code POST   /admin/locations/{id}/disable} — 409 while a study or
 *       site that is not removed is still at the location, and for the only
 *       available location (so a study can always be created somewhere).</li>
 *   <li>{@code POST   /admin/locations/{id}/restore}</li>
 *   <li>{@code PUT    /admin/locations/{id}/users/{username}} — assign a user
 *       (idempotent). An optional body {@code {"canManageSources": true|false}}
 *       grants or takes away the user's Standort-Admin right at the location
 *       (phase 2: they may issue, revoke and replace the tokens of its
 *       uploaders); an absent body, {@code {}} and {@code null} keep the right
 *       as it is, and a new assignment starts without it.
 *       {@code DELETE} unassigns (idempotent) and takes the right with it.</li>
 * </ul>
 *
 * <p><strong>Authorization.</strong> System administrators only: anonymous
 * 401, anyone else 403, on every endpoint.
 *
 * <p><strong>Writes.</strong> A state change and its audit row are one
 * transaction: if the audit row cannot be written the change is not made. The
 * transaction first takes an advisory lock that every location write shares,
 * so the checks that read other rows (a name is unique, a location is not the
 * last available one) cannot be overtaken by a concurrent write. A call that
 * changes nothing (an edit to the same values, disabling a removed location,
 * assigning an assigned user) answers as if it had worked and writes neither
 * the row nor an audit row.
 */
@RestController
@RequestMapping("/api/v1/admin/locations")
@Tag(name = "Locations",
     description = "Sysadmin-only administration of global locations (DR-040): create, edit, disable and restore, "
             + "and the users assigned to a location explicitly.")
public class AdminLocationsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminLocationsApiController.class);

    /** {@code [A-Za-z0-9_-]{2,30}}: what a code may be. */
    static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,30}");
    static final int NAME_MAX = 100;
    static final int STREET_MAX = 255;
    static final int CITY_MAX = 255;
    static final int ZIP_MAX = 64;
    static final int COUNTRY_MAX = 64;

    private static final int AVAILABLE = LocationScope.STATUS_AVAILABLE;
    private static final int REMOVED = LocationScope.STATUS_REMOVED;
    private static final int AUTO_REMOVED = LocationScope.STATUS_AUTO_REMOVED;

    /**
     * Key of the transaction-scoped advisory lock every location write takes
     * first. Arbitrary but fixed; no other code uses it.
     */
    private static final long WRITE_LOCK_KEY = 40_040_001L;

    /** The SQLSTATE of a unique violation: the one way two creates of one code can both get past the check. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    @Autowired
    public AdminLocationsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ====================================================================== */
    /* GET — list and detail                                                   */
    /* ====================================================================== */

    @Operation(operationId = "listLocations", summary = "Every location, removed ones included, with the numbers that decide whether one can be disabled")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(array = @ArraySchema(schema = @Schema(implementation = LocationDto.class))))
    public ResponseEntity<?> listLocations(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SUMMARY_SQL + " ORDER BY lower(l.name), l.location_id");
             ResultSet rs = ps.executeQuery()) {
            List<LocationDto> out = new ArrayList<>();
            while (rs.next()) out.add(summaryOf(rs));
            return ResponseEntity.ok(out);
        } catch (SQLException e) {
            return failed("list the locations", e);
        }
    }

    @Operation(operationId = "getLocation", summary = "One location with its studies and sites and its explicitly assigned users")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = LocationDetailDto.class)))
    public ResponseEntity<?> getLocation(@PathVariable("id") int id, HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        try (Connection c = dataSource.getConnection()) {
            LocationDetailDto detail = detailOf(c, id);
            return detail == null ? notFound(id) : ResponseEntity.ok(detail);
        } catch (SQLException e) {
            return failed("read the location", e);
        }
    }

    /* ====================================================================== */
    /* POST — create                                                           */
    /* ====================================================================== */

    @Operation(operationId = "createLocation", summary = "Create a location")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = LocationDto.class)))
    public ResponseEntity<?> createLocation(@RequestBody(required = false) LocationWriteRequest body,
                                            HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        if (body == null) return missingBody();
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        String code = blankToNull(body.code());
        String name = blankToNull(body.name());
        String street = blankToNull(body.street());
        String city = blankToNull(body.city());
        String zip = blankToNull(body.zip());
        String country = blankToNull(body.country());
        List<FieldError> errors = new ArrayList<>(shapeErrors(code, name, street, city, zip, country, true));

        return inTransaction("create the location", c -> {
            if (code != null && CODE_PATTERN.matcher(code).matches()) {
                addIfPresent(errors, taken(c, "code", code, 0));
            }
            if (name != null && name.length() <= NAME_MAX) {
                addIfPresent(errors, taken(c, "name", name, 0));
            }
            if (!errors.isEmpty()) return validationFailed(errors);

            int id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO location (code, name, status_id, street, city, zip, country, owner_id, date_created) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now()) RETURNING location_id")) {
                int i = 1;
                ps.setString(i++, code);
                ps.setString(i++, name);
                ps.setInt(i++, AVAILABLE);
                ps.setString(i++, street);
                ps.setString(i++, city);
                ps.setString(i++, zip);
                ps.setString(i++, country);
                ps.setInt(i, me.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getInt(1);
                }
            }
            audit(c, AuditTypeIds.LOCATION_CREATED, me.getId(), id, code, null,
                    describe(name, street, city, zip, country));
            LOG.info("locations: location id {} created by sysadmin id={}", id, me.getId());
            return ResponseEntity.status(201).body(summaryOf(c, id));
        });
    }

    /* ====================================================================== */
    /* PUT — edit                                                              */
    /* ====================================================================== */

    @Operation(operationId = "updateLocation", summary = "Edit a location's name and address; the code never changes")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = LocationDetailDto.class)))
    public ResponseEntity<?> updateLocation(@PathVariable("id") int id,
                                            @RequestBody(required = false) LocationWriteRequest body,
                                            HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        if (body == null) return missingBody();
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        // The name is required. An address field that is absent or null stays
        // as it is; an empty string clears it.
        String name = blankToNull(body.name());
        String street = body.street() == null ? null : body.street().strip();
        String city = body.city() == null ? null : body.city().strip();
        String zip = body.zip() == null ? null : body.zip().strip();
        String country = body.country() == null ? null : body.country().strip();

        return inTransaction("edit the location", c -> {
            Stored before = lockRow(c, id);
            if (before == null) return notFound(id);

            List<FieldError> errors = new ArrayList<>(shapeErrors(null, name, street, city, zip, country, false));
            if (name != null && name.length() <= NAME_MAX) {
                addIfPresent(errors, taken(c, "name", name, id));
            }
            if (!errors.isEmpty()) return validationFailed(errors);

            String newStreet = street == null ? before.street() : blankToNull(street);
            String newCity = city == null ? before.city() : blankToNull(city);
            String newZip = zip == null ? before.zip() : blankToNull(zip);
            String newCountry = country == null ? before.country() : blankToNull(country);

            List<String> was = new ArrayList<>();
            List<String> now = new ArrayList<>();
            noteChange(was, now, "name", before.name(), name);
            noteChange(was, now, "street", before.street(), newStreet);
            noteChange(was, now, "city", before.city(), newCity);
            noteChange(was, now, "zip", before.zip(), newZip);
            noteChange(was, now, "country", before.country(), newCountry);
            if (was.isEmpty()) return ResponseEntity.ok(detailOf(c, id));

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE location SET name = ?, street = ?, city = ?, zip = ?, country = ?, "
                            + "date_updated = now(), update_id = ? WHERE location_id = ?")) {
                int i = 1;
                ps.setString(i++, name);
                ps.setString(i++, newStreet);
                ps.setString(i++, newCity);
                ps.setString(i++, newZip);
                ps.setString(i++, newCountry);
                ps.setInt(i++, me.getId());
                ps.setInt(i, id);
                ps.executeUpdate();
            }
            audit(c, AuditTypeIds.LOCATION_UPDATED, me.getId(), id, before.code(),
                    String.join("; ", was), String.join("; ", now));
            LOG.info("locations: {} (id {}) edited by sysadmin id={}", before.code(), id, me.getId());
            return ResponseEntity.ok(detailOf(c, id));
        });
    }

    /* ====================================================================== */
    /* POST — disable and restore                                              */
    /* ====================================================================== */

    @Operation(operationId = "disableLocation", summary = "Disable a location; refused while a study or site is at it, and for the only available one")
    @PostMapping(value = "/{id}/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = LocationDto.class)))
    public ResponseEntity<?> disableLocation(@PathVariable("id") int id, HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        return inTransaction("disable the location", c -> {
            Stored before = lockRow(c, id);
            if (before == null) return notFound(id);
            if (before.statusId() != AVAILABLE) return ResponseEntity.ok(summaryOf(c, id));

            int atIt = studiesAt(c, id);
            if (atIt > 0) {
                return conflict("Location " + before.code() + " cannot be disabled: " + atIt
                        + (atIt == 1 ? " study or site is" : " studies or sites are")
                        + " still at it. Move " + (atIt == 1 ? "it" : "them") + " to another location first.");
            }
            if (otherAvailable(c, id) == 0) {
                return conflict("Location " + before.code() + " cannot be disabled: it is the only available "
                        + "location. Create another one first.");
            }

            setStatus(c, id, REMOVED, me.getId());
            audit(c, AuditTypeIds.LOCATION_DISABLED, me.getId(), id, before.code(), "available", "removed");
            LOG.info("locations: {} (id {}) disabled by sysadmin id={}", before.code(), id, me.getId());
            return ResponseEntity.ok(summaryOf(c, id));
        });
    }

    @Operation(operationId = "restoreLocation", summary = "Restore a disabled location")
    @PostMapping(value = "/{id}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = LocationDto.class)))
    public ResponseEntity<?> restoreLocation(@PathVariable("id") int id, HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        return inTransaction("restore the location", c -> {
            Stored before = lockRow(c, id);
            if (before == null) return notFound(id);
            if (before.statusId() == AVAILABLE) return ResponseEntity.ok(summaryOf(c, id));

            setStatus(c, id, AVAILABLE, me.getId());
            audit(c, AuditTypeIds.LOCATION_RESTORED, me.getId(), id, before.code(), "removed", "available");
            LOG.info("locations: {} (id {}) restored by sysadmin id={}", before.code(), id, me.getId());
            return ResponseEntity.ok(summaryOf(c, id));
        });
    }
    /* ====================================================================== */
    /* PUT / DELETE — a user's explicit assignment                             */
    /* ====================================================================== */

    @Operation(operationId = "assignLocationUser",
               summary = "Assign a user to a location explicitly, and grant or revoke their Standort-Admin right; "
                       + "assigning an assigned user with no change to the right changes nothing")
    @PutMapping(value = "/{id}/users/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = LocationDetailDto.class)))
    public ResponseEntity<?> assignLocationUser(@PathVariable("id") int id,
                                                @PathVariable("username") String username,
                                                @RequestBody(required = false) LocationUserWriteRequest body,
                                                HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        return inTransaction("assign the user", c -> {
            Stored location = lockRow(c, id);
            if (location == null) return notFound(id);
            if (location.statusId() != AVAILABLE) {
                return conflict("Location " + location.code() + " is disabled; restore it before assigning users to it.");
            }
            Account user = accountNamed(c, username);
            if (user == null || user.statusId() == REMOVED || user.statusId() == AUTO_REMOVED) {
                return ResponseEntity.status(404).body(Map.of("message", "No active user '" + username + "'"));
            }

            // The Standort-Admin right (DR-040 phase 2): a body that names it sets it, anything else
            // keeps it as it is (false for a new assignment).
            Boolean wanted = body == null ? null : body.canManageSources();
            boolean grantOnInsert = Boolean.TRUE.equals(wanted);
            int inserted;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO user_location (user_account_id, location_id, can_manage_sources, granted_by, date_granted) "
                            + "VALUES (?, ?, ?, ?, now()) ON CONFLICT (user_account_id, location_id) DO NOTHING")) {
                ps.setInt(1, user.id());
                ps.setInt(2, id);
                ps.setBoolean(3, grantOnInsert);
                ps.setInt(4, me.getId());
                inserted = ps.executeUpdate();
            }
            if (inserted > 0) {
                audit(c, AuditTypeIds.LOCATION_USER_ASSIGNED, me.getId(), id, location.code(), null,
                        "user=" + user.name());
                if (grantOnInsert) {
                    audit(c, AuditTypeIds.LOCATION_ADMIN_GRANTED, me.getId(), id, location.code(), null,
                            "user=" + user.name());
                }
                LOG.info("locations: user id={} assigned to {} (id {}) by sysadmin id={}{}",
                        user.id(), location.code(), id, me.getId(), grantOnInsert ? ", with the Standort-Admin right" : "");
            } else if (wanted != null && wanted != holdsRight(c, user.id(), id)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE user_location SET can_manage_sources = ? WHERE user_account_id = ? AND location_id = ?")) {
                    ps.setBoolean(1, wanted);
                    ps.setInt(2, user.id());
                    ps.setInt(3, id);
                    ps.executeUpdate();
                }
                audit(c, wanted ? AuditTypeIds.LOCATION_ADMIN_GRANTED : AuditTypeIds.LOCATION_ADMIN_REVOKED,
                        me.getId(), id, location.code(),
                        wanted ? null : "user=" + user.name(), wanted ? "user=" + user.name() : null);
                LOG.info("locations: Standort-Admin right of user id={} at {} (id {}) {} by sysadmin id={}",
                        user.id(), location.code(), id, wanted ? "granted" : "revoked", me.getId());
            }
            return ResponseEntity.ok(detailOf(c, id));
        });
    }

    @Operation(operationId = "unassignLocationUser", summary = "Remove a user's explicit assignment to a location; removing a missing one changes nothing")
    @DeleteMapping(value = "/{id}/users/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = LocationDetailDto.class)))
    public ResponseEntity<?> unassignLocationUser(@PathVariable("id") int id,
                                                  @PathVariable("username") String username,
                                                  HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");

        return inTransaction("unassign the user", c -> {
            // A disabled location can be cleaned up, and so can the assignment of a removed user.
            Stored location = lockRow(c, id);
            if (location == null) return notFound(id);
            Account user = accountNamed(c, username);
            if (user == null) {
                return ResponseEntity.status(404).body(Map.of("message", "No user '" + username + "'"));
            }

            boolean heldRight = holdsRight(c, user.id(), id);
            int deleted;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM user_location WHERE user_account_id = ? AND location_id = ?")) {
                ps.setInt(1, user.id());
                ps.setInt(2, id);
                deleted = ps.executeUpdate();
            }
            if (deleted > 0) {
                audit(c, AuditTypeIds.LOCATION_USER_UNASSIGNED, me.getId(), id, location.code(),
                        "user=" + user.name(), null);
                if (heldRight) {
                    // The right goes with the assignment, so taking the assignment away takes it away too.
                    audit(c, AuditTypeIds.LOCATION_ADMIN_REVOKED, me.getId(), id, location.code(),
                            "user=" + user.name(), null);
                }
                LOG.info("locations: user id={} unassigned from {} (id {}) by sysadmin id={}",
                        user.id(), location.code(), id, me.getId());
            }
            return ResponseEntity.ok(detailOf(c, id));
        });
    }

    /* ====================================================================== */
    /* Authorization                                                           */
    /* ====================================================================== */

    /** Null when permitted; 401 for no session user, 403 for anybody but a system administrator. */
    private static ResponseEntity<?> requireSysadmin(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message", "Sysadmin privilege required."));
        }
        return null;
    }

    /* ====================================================================== */
    /* Validation                                                              */
    /* ====================================================================== */

    /**
     * The errors of the fields' shapes: required, pattern and length. The
     * values are trimmed, null when blank (on edit, an address field being
     * cleared is blank-but-present and passes). Uniqueness is checked
     * separately, against the database.
     *
     * @param create true on create, where the code is required and checked;
     *               the code of an edit is ignored
     */
    static List<FieldError> shapeErrors(String code, String name, String street, String city, String zip,
                                        String country, boolean create) {
        List<FieldError> errors = new ArrayList<>();
        if (create) {
            if (code == null) {
                errors.add(new FieldError("code", "Code is required"));
            } else if (!CODE_PATTERN.matcher(code).matches()) {
                errors.add(new FieldError("code",
                        "Code must be 2 to 30 characters: letters, digits, underscore or hyphen"));
            }
        }
        if (name == null) {
            errors.add(new FieldError("name", "Name is required"));
        } else if (name.length() > NAME_MAX) {
            errors.add(new FieldError("name", "Name must not exceed " + NAME_MAX + " characters"));
        }
        tooLong(errors, "street", street, STREET_MAX);
        tooLong(errors, "city", city, CITY_MAX);
        tooLong(errors, "zip", zip, ZIP_MAX);
        tooLong(errors, "country", country, COUNTRY_MAX);
        return errors;
    }

    private static void tooLong(List<FieldError> errors, String field, String value, int max) {
        if (value != null && value.length() > max) {
            errors.add(new FieldError(field, Character.toUpperCase(field.charAt(0)) + field.substring(1)
                    + " must not exceed " + max + " characters"));
        }
    }

    private static void addIfPresent(List<FieldError> errors, FieldError error) {
        if (error != null) errors.add(error);
    }

    /** The value stripped, null when nothing is left. */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static ResponseEntity<?> validationFailed(List<FieldError> errors) {
        return ResponseEntity.badRequest().body(new ValidationErrorBody("Validation failed", errors));
    }

    private static ResponseEntity<?> missingBody() {
        return ResponseEntity.badRequest().body(new ValidationErrorBody(
                "Request body is required", List.of(new FieldError("body", "missing"))));
    }

    private static ResponseEntity<?> notFound(int id) {
        return ResponseEntity.status(404).body(Map.of("message", "No location with id " + id));
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("message", message));
    }

    private static ResponseEntity<?> failed(String what, SQLException e) {
        LOG.error("locations: could not {} ({})", what, e.getSQLState(), e);
        return ResponseEntity.status(500).body(Map.of("message", "Could not " + what));
    }

    /* ====================================================================== */
    /* Transaction                                                             */
    /* ====================================================================== */

    @FunctionalInterface
    private interface Work {
        ResponseEntity<?> run(Connection c) throws SQLException;
    }

    /**
     * Runs a write in one transaction behind the advisory lock. A 2xx answer
     * commits; any other answer and any exception rolls back, so a refused
     * call leaves nothing behind and a change is never made without its
     * audit row.
     */
    private ResponseEntity<?> inTransaction(String what, Work work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT pg_advisory_xact_lock(" + WRITE_LOCK_KEY + ")");
                }
                ResponseEntity<?> response = work.run(c);
                if (response.getStatusCode().is2xxSuccessful()) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return response;
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
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                // Only the code has a unique index; the advisory lock makes this unreachable
                // through this controller, but another writer may still win the race.
                return validationFailed(List.of(new FieldError("code", "A location with this code already exists")));
            }
            return failed(what, e);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            LOG.warn("locations: rollback failed ({})", e.getSQLState());
        }
    }

    /* ====================================================================== */
    /* Reading                                                                 */
    /* ====================================================================== */

    /** A location as stored, locked for the rest of the transaction. */
    private record Stored(int id, String code, String name, int statusId,
                          String street, String city, String zip, String country) {}

    private record Account(int id, String name, int statusId) {}

    private static final String SUMMARY_SQL =
            "SELECT l.location_id, l.code, l.name, l.status_id, l.street, l.city, l.zip, l.country, "
                    + "(SELECT count(*) FROM study s WHERE s.location_id = l.location_id"
                    + "   AND COALESCE(s.parent_study_id, 0) > 0"
                    + "   AND COALESCE(s.status_id, " + AVAILABLE + ") NOT IN (" + REMOVED + ", " + AUTO_REMOVED + ")) AS site_count, "
                    + "(SELECT count(*) FROM study s WHERE s.location_id = l.location_id"
                    + "   AND COALESCE(s.parent_study_id, 0) = 0"
                    + "   AND COALESCE(s.status_id, " + AVAILABLE + ") NOT IN (" + REMOVED + ", " + AUTO_REMOVED + ")) AS study_count, "
                    + "(SELECT count(*) FROM ingest_item i WHERE i.origin_location_id = l.location_id) AS ingest_item_count, "
                    + "(SELECT count(*) FROM user_location ul WHERE ul.location_id = l.location_id) AS user_count "
                    + "FROM location l";

    private static LocationDto summaryOf(ResultSet rs) throws SQLException {
        return new LocationDto(
                rs.getInt("location_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("status_id") == AVAILABLE ? "available" : "removed",
                rs.getString("street"),
                rs.getString("city"),
                rs.getString("zip"),
                rs.getString("country"),
                rs.getInt("site_count"),
                rs.getInt("study_count"),
                rs.getInt("ingest_item_count"),
                rs.getInt("user_count"));
    }

    private static LocationDto summaryOf(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SUMMARY_SQL + " WHERE l.location_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? summaryOf(rs) : null;
            }
        }
    }

    /** The summary with the studies and sites at the location and its assigned users; null when there is no such location. */
    private static LocationDetailDto detailOf(Connection c, int id) throws SQLException {
        LocationDto base = summaryOf(c, id);
        if (base == null) return null;
        return LocationDetailDto.of(base, studiesOf(c, id), usersOf(c, id));
    }

    /** The studies and sites at the location that are not removed; a site names its parent. */
    private static List<LocationStudyDto> studiesOf(Connection c, int id) throws SQLException {
        List<LocationStudyDto> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.oc_oid, s.name, s.status_id, COALESCE(s.parent_study_id, 0) AS parent_id, p.oc_oid AS parent_oid "
                        + "FROM study s LEFT JOIN study p ON p.study_id = s.parent_study_id "
                        + "WHERE s.location_id = ? AND COALESCE(s.status_id, " + AVAILABLE + ") NOT IN ("
                        + REMOVED + ", " + AUTO_REMOVED + ") "
                        + "ORDER BY lower(s.name), s.study_id")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean site = rs.getInt("parent_id") > 0;
                    out.add(new LocationStudyDto(rs.getString("oc_oid"), rs.getString("name"), site,
                            site ? rs.getString("parent_oid") : null, studyStatusKey(rs.getInt("status_id"))));
                }
            }
        }
        return out;
    }

    /** The explicit assignments, by user name. */
    private static List<LocationUserDto> usersOf(Connection c, int id) throws SQLException {
        List<LocationUserDto> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ua.user_name, ua.first_name, ua.last_name, ul.can_manage_sources "
                        + "FROM user_location ul JOIN user_account ua ON ua.user_id = ul.user_account_id "
                        + "WHERE ul.location_id = ? ORDER BY ua.user_name")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String first = rs.getString("first_name");
                    String last = rs.getString("last_name");
                    String display = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
                    out.add(new LocationUserDto(rs.getString("user_name"),
                            display.isEmpty() ? rs.getString("user_name") : display,
                            rs.getBoolean("can_manage_sources")));
                }
            }
        }
        return out;
    }

    /** A locale-independent, lower-case key of a study status id. */
    static String studyStatusKey(int statusId) {
        return switch (statusId) {
            case 1 -> "available";
            case 2 -> "unavailable";
            case 3 -> "private";
            case 4 -> "pending";
            case 5 -> "removed";
            case 6 -> "locked";
            case 7 -> "auto-removed";
            case 8 -> "signed";
            case 9 -> "frozen";
            default -> "unknown";
        };
    }

    /** The row, locked for update; null when there is none. */
    private static Stored lockRow(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT location_id, code, name, status_id, street, city, zip, country "
                        + "FROM location WHERE location_id = ? FOR UPDATE")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Stored(rs.getInt("location_id"), rs.getString("code"), rs.getString("name"),
                        rs.getInt("status_id"), rs.getString("street"), rs.getString("city"),
                        rs.getString("zip"), rs.getString("country"));
            }
        }
    }

    /** True when the user is assigned to the location and holds the Standort-Admin right there. */
    private static boolean holdsRight(Connection c, int userId, int locationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT can_manage_sources FROM user_location WHERE user_account_id = ? AND location_id = ?")) {
            ps.setInt(1, userId);
            ps.setInt(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** The account with exactly this user name, any status; null when there is none. */
    private static Account accountNamed(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id, user_name, status_id FROM user_account WHERE user_name = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Account(rs.getInt(1), rs.getString(2), rs.getInt(3)) : null;
            }
        }
    }

    /** Studies and sites at the location that are not removed. */
    private static int studiesAt(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM study WHERE location_id = ? AND COALESCE(status_id, " + AVAILABLE
                        + ") NOT IN (" + REMOVED + ", " + AUTO_REMOVED + ")")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The available locations other than this one. */
    private static int otherAvailable(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM location WHERE status_id = ? AND location_id <> ?")) {
            ps.setInt(1, AVAILABLE);
            ps.setInt(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * A field error when another location already has this code or name,
     * without regard to case (removed locations count: the code index covers
     * them, and a restored location must not collide). Null when it is free.
     *
     * @param field  {@code code} or {@code name}
     * @param except the location being edited, 0 on create
     */
    private static FieldError taken(Connection c, String field, String value, int except) throws SQLException {
        String sql = switch (field) {
            case "code" -> "SELECT status_id FROM location WHERE lower(code) = lower(?) AND location_id <> ? "
                    + "ORDER BY location_id LIMIT 1";
            case "name" -> "SELECT status_id FROM location WHERE lower(name) = lower(?) AND location_id <> ? "
                    + "ORDER BY location_id LIMIT 1";
            default -> throw new IllegalArgumentException(field);
        };
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setInt(2, except);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String message = "A location with this " + field + " already exists";
                return new FieldError(field, rs.getInt(1) == AVAILABLE
                        ? message : message + " (it is disabled; restore that one instead)");
            }
        }
    }

    /* ====================================================================== */
    /* Writing                                                                 */
    /* ====================================================================== */

    private static void setStatus(Connection c, int id, int statusId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE location SET status_id = ?, date_updated = now(), update_id = ? WHERE location_id = ?")) {
            ps.setInt(1, statusId);
            ps.setInt(2, userId);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    /**
     * One {@code audit_log_event} row, against table {@code location}: the
     * location's id, its code as the entity name, and the values. Nothing
     * personal goes in: the location's own fields and, for an assignment, the
     * user name. The insert is part of the caller's transaction.
     */
    private static void audit(Connection c, int typeId, int userId, int locationId, String code,
                              String oldValue, String newValue) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table, "
                        + "entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, 'location', ?, ?, ?, ?)")) {
            ps.setInt(1, typeId);
            ps.setInt(2, userId);
            ps.setInt(3, locationId);
            ps.setString(4, code);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.executeUpdate();
        }
    }

    /** {@code name=…; street=…}: the fields a new location has, in the order the page shows them. */
    private static String describe(String name, String street, String city, String zip, String country) {
        List<String> parts = new ArrayList<>();
        parts.add("name=" + name);
        if (street != null) parts.add("street=" + street);
        if (city != null) parts.add("city=" + city);
        if (zip != null) parts.add("zip=" + zip);
        if (country != null) parts.add("country=" + country);
        return String.join("; ", parts);
    }

    /** Records {@code field=value} before and after when the value changed. A cleared field reads {@code field=}. */
    private static void noteChange(List<String> was, List<String> now, String field, String before, String after) {
        if (Objects.equals(before, after)) return;
        was.add(field + "=" + (before == null ? "" : before));
        now.add(field + "=" + (after == null ? "" : after));
    }
}
