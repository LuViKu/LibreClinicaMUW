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
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LocationAdminAuthorization.Caller;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LocationAdminAuthorization.Outcome;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Failure;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.IssuedToken;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Refusal;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DR-040 phase 2 — the uploaders of a location and their tokens.
 *
 * <p>An <em>uploader</em> is a program on an acquisition PC (the Export
 * Watcher, the Optomed Bridge). It authenticates its requests to the device
 * endpoints with a token, and the token decides which location its files and
 * its visit lookup belong to. This controller is the whole backend of the
 * uploaders page:
 *
 * <ul>
 *   <li>{@code GET  /uploaders} — the uploaders the caller may manage, by
 *       location and name, revoked ones included.</li>
 *   <li>{@code POST /locations/{locationId}/uploaders} — issue one; 201 with
 *       the token, which is in that answer <strong>and nowhere else</strong>
 *       (the platform keeps a hash and an eight-character hint).</li>
 *   <li>{@code PUT  /uploaders/{id}} — rename.</li>
 *   <li>{@code POST /uploaders/{id}/replace} — a new token; the old one stops
 *       working at once, and a revoked uploader is active again.</li>
 *   <li>{@code POST /uploaders/{id}/revoke} — the token is refused from now
 *       on; idempotent.</li>
 * </ul>
 *
 * <p><strong>Authorization</strong> ({@link LocationAdminAuthorization}): a
 * system administrator, or a Standort-Admin for the locations they hold the
 * right at. Anonymous 401; a user who manages nothing 403; a Standort-Admin
 * naming another location's uploader (or a location or uploader that is not
 * there) 404, so the answer reveals nothing about other places. These
 * endpoints are authorised by location, not by study: no site-owned clinical
 * record is reachable through them.
 *
 * <p><strong>Writes</strong> are {@link IngestSourceService}'s: a change and
 * its audit row (types 201-204) are one transaction, and no audit text holds a
 * token. A location that is disabled takes no new uploader and no new token
 * (409).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Uploaders",
     description = "Uploaders (programs on acquisition PCs) and their per-location tokens (DR-040): issue, rename, "
             + "replace, revoke. System administrators for every location, Standort-Admins for their own.")
public class UploadersApiController {

    private static final Logger LOG = LoggerFactory.getLogger(UploadersApiController.class);

    private final DataSource dataSource;
    private final IngestSourceService sources;
    private final LocationAdminAuthorization authorization;

    @Autowired
    public UploadersApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.sources = new IngestSourceService(dataSource);
        this.authorization = new LocationAdminAuthorization(dataSource);
    }

    /* ====================================================================== */
    /* GET                                                                     */
    /* ====================================================================== */

    @Operation(operationId = "listManagedUploaders",
               summary = "The uploaders the caller may manage (a system administrator: all; a Standort-Admin: their locations)")
    @GetMapping(value = "/uploaders", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(array = @ArraySchema(schema = @Schema(implementation = UploaderDto.class))))
    public ResponseEntity<?> listUploaders(HttpSession session) {
        Outcome outcome = authorization.authorize(session);
        if (outcome.refusal() != null) return outcome.refusal();
        Caller caller = outcome.caller();
        try (Connection c = dataSource.getConnection()) {
            return ResponseEntity.ok(IngestSourceQueries.uploaders(c, caller.sysAdmin() ? null : caller.locationIds()));
        } catch (SQLException e) {
            return unreadable("list the uploaders", e);
        }
    }

    /* ====================================================================== */
    /* POST — issue                                                            */
    /* ====================================================================== */

    @Operation(operationId = "issueUploader",
               summary = "Issue an uploader for a location; the token is in this answer only and is never shown again")
    @PostMapping(value = "/locations/{locationId}/uploaders", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = IssuedUploaderDto.class)))
    public ResponseEntity<?> issueUploader(@PathVariable("locationId") int locationId,
                                           @RequestBody(required = false) UploaderWriteRequest body,
                                           HttpSession session) {
        Outcome outcome = authorization.authorize(session);
        if (outcome.refusal() != null) return outcome.refusal();
        Caller caller = outcome.caller();
        if (!caller.mayManage(locationId)) {
            return IngestSourceResponses.notFound("No location with id " + locationId);
        }
        if (body == null) return IngestSourceResponses.missingBody();
        try {
            IssuedToken issued = sources.issueUploader(locationId, body.displayName(), caller.userId());
            return issuedAnswer(201, issued);
        } catch (Refusal r) {
            return IngestSourceResponses.refusal(r);
        } catch (Failure f) {
            return IngestSourceResponses.failure(f);
        }
    }

    /* ====================================================================== */
    /* PUT — rename                                                            */
    /* ====================================================================== */

    @Operation(operationId = "renameUploader", summary = "Rename an uploader; the name stays unique at its location")
    @PutMapping(value = "/uploaders/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UploaderDto.class)))
    public ResponseEntity<?> renameUploader(@PathVariable("id") long id,
                                            @RequestBody(required = false) UploaderWriteRequest body,
                                            HttpSession session) {
        Outcome outcome = authorization.authorize(session);
        if (outcome.refusal() != null) return outcome.refusal();
        Caller caller = outcome.caller();
        try {
            ResponseEntity<?> missing = unlessManaged(caller, id);
            if (missing != null) return missing;
            if (body == null) return IngestSourceResponses.missingBody();
            sources.rename(id, body.displayName(), caller.userId());
            return current(id);
        } catch (Refusal r) {
            return IngestSourceResponses.refusal(r);
        } catch (Failure f) {
            return IngestSourceResponses.failure(f);
        } catch (SQLException e) {
            return unreadable("rename the uploader", e);
        }
    }

    /* ====================================================================== */
    /* POST — replace and revoke                                               */
    /* ====================================================================== */

    @Operation(operationId = "replaceUploaderToken",
               summary = "A new token for the uploader: the old one stops working at once, and a revoked uploader is active again")
    @PostMapping(value = "/uploaders/{id}/replace", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IssuedUploaderDto.class)))
    public ResponseEntity<?> replaceUploaderToken(@PathVariable("id") long id, HttpSession session) {
        Outcome outcome = authorization.authorize(session);
        if (outcome.refusal() != null) return outcome.refusal();
        Caller caller = outcome.caller();
        try {
            ResponseEntity<?> missing = unlessManaged(caller, id);
            if (missing != null) return missing;
            return issuedAnswer(200, sources.replaceToken(id, caller.userId()));
        } catch (Refusal r) {
            return IngestSourceResponses.refusal(r);
        } catch (Failure f) {
            return IngestSourceResponses.failure(f);
        } catch (SQLException e) {
            return unreadable("replace the token", e);
        }
    }

    @Operation(operationId = "revokeUploader",
               summary = "Revoke the uploader's token: it is refused from now on. Revoking a revoked uploader changes nothing")
    @PostMapping(value = "/uploaders/{id}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UploaderDto.class)))
    public ResponseEntity<?> revokeUploader(@PathVariable("id") long id, HttpSession session) {
        Outcome outcome = authorization.authorize(session);
        if (outcome.refusal() != null) return outcome.refusal();
        Caller caller = outcome.caller();
        try {
            ResponseEntity<?> missing = unlessManaged(caller, id);
            if (missing != null) return missing;
            sources.revoke(id, caller.userId());
            return current(id);
        } catch (Refusal r) {
            return IngestSourceResponses.refusal(r);
        } catch (Failure f) {
            return IngestSourceResponses.failure(f);
        } catch (SQLException e) {
            return unreadable("revoke the token", e);
        }
    }

    /* ====================================================================== */
    /* Helpers                                                                 */
    /* ====================================================================== */

    /**
     * Null when the uploader exists and the caller may manage its location;
     * else 404 — the same answer for an uploader that is not there, one of
     * another kind, and one of another place.
     */
    private ResponseEntity<?> unlessManaged(Caller caller, long id) throws SQLException {
        UploaderDto uploader;
        try (Connection c = dataSource.getConnection()) {
            uploader = IngestSourceQueries.uploader(c, id);
        }
        if (uploader == null || !caller.mayManageSourceAt(uploader.locationId())) {
            return IngestSourceResponses.notFound("No uploader with id " + id);
        }
        return null;
    }

    /** The uploader as it is now, 200. */
    private ResponseEntity<?> current(long id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UploaderDto uploader = IngestSourceQueries.uploader(c, id);
            return uploader == null ? IngestSourceResponses.notFound("No uploader with id " + id)
                    : ResponseEntity.ok(uploader);
        }
    }

    /** The uploader with its new token. Never cached: the token is in the body. */
    private ResponseEntity<?> issuedAnswer(int status, IssuedToken issued) {
        UploaderDto uploader;
        try (Connection c = dataSource.getConnection()) {
            uploader = IngestSourceQueries.uploader(c, issued.source().id());
        } catch (SQLException e) {
            // The change is made and the token exists; losing the answer must not lose the token.
            LOG.error("uploaders: could not read the uploader back after a change ({})", e.getSQLState());
            uploader = fallback(issued.source());
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new IssuedUploaderDto(uploader, issued.token()));
    }

    /** What the service knows of the uploader, for the rare answer that cannot be read back. */
    private static UploaderDto fallback(IngestSourceService.Source s) {
        return new UploaderDto(s.id(), s.locationId() == null ? 0 : s.locationId(), "", "", s.displayName(),
                s.tokenHint(), s.status(), null, "", null, null, null, null);
    }

    private static ResponseEntity<?> unreadable(String what, SQLException e) {
        LOG.error("uploaders: could not {} ({})", what, e.getSQLState(), e);
        return ResponseEntity.status(500).body(Map.of("message", "Could not " + what));
    }
}
