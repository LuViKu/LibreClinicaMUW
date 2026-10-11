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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Failure;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DR-040 phase 2 — System &gt; Standorte, sources: every place files come from
 * and where each is assigned.
 *
 * <ul>
 *   <li>{@code GET /admin/ingest-sources} — every source of every kind
 *       (uploaders, DICOM AE titles, the Remidio account, and the reserved
 *       public page), those without a location first, with the number of
 *       their files still waiting to be filed.</li>
 *   <li>{@code PUT /admin/ingest-sources/{id}/location} — assign a source to
 *       a location, or with an explicit {@code null} back to none. Assigning
 *       also places the source's waiting files (unbound or dismissed, no
 *       origin yet) at the location in the same transaction, so they appear in
 *       the right inboxes at once.</li>
 * </ul>
 *
 * <p><strong>Authorization.</strong> System administrators only: anonymous 401,
 * anyone else 403, a Standort-Admin included. A Standort-Admin manages the
 * tokens of their own location's uploaders ({@link UploadersApiController}),
 * not where sources are assigned.
 *
 * <p><strong>Writes</strong> are {@link IngestSourceService#assignLocation}'s:
 * the change, the files placed and the audit row (type 205) are one
 * transaction. A location that is disabled or unknown is 409; an uploader
 * cannot be taken back to no location, nor moved to a place where another
 * uploader has its name (400, field {@code locationId}).
 */
@RestController
@RequestMapping("/api/v1/admin/ingest-sources")
@Tag(name = "Ingest sources",
     description = "Sysadmin-only list of every ingest source (uploaders, DICOM AE titles, the Remidio account) "
             + "and the assignment of a source to a location (DR-040).")
public class AdminIngestSourcesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminIngestSourcesApiController.class);

    private final DataSource dataSource;
    private final IngestSourceService sources;

    @Autowired
    public AdminIngestSourcesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.sources = new IngestSourceService(dataSource);
    }

    @Operation(operationId = "listIngestSources",
               summary = "Every ingest source of every kind, unassigned ones first, with the files still waiting")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(array = @ArraySchema(schema = @Schema(implementation = IngestSourceDto.class))))
    public ResponseEntity<?> listIngestSources(HttpSession session) {
        ResponseEntity<?> guard = LocationAdminAuthorization.requireSysadmin(session);
        if (guard != null) return guard;
        try (Connection c = dataSource.getConnection()) {
            return ResponseEntity.ok(IngestSourceQueries.sources(c));
        } catch (SQLException e) {
            return unreadable("list the ingest sources", e);
        }
    }

    @Operation(operationId = "assignIngestSourceLocation",
               summary = "Assign a source to a location (or null: back to none); its waiting files are placed at the location in the same transaction")
    @PutMapping(value = "/{id}/location", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IngestSourceDto.class)))
    public ResponseEntity<?> assignIngestSourceLocation(@PathVariable("id") long id,
                                                        @RequestBody(required = false) IngestSourceLocationRequest body,
                                                        HttpSession session) {
        ResponseEntity<?> guard = LocationAdminAuthorization.requireSysadmin(session);
        if (guard != null) return guard;
        if (body == null) return IngestSourceResponses.missingBody();
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        try {
            sources.assignLocation(id, body.locationId(), me.getId());
            try (Connection c = dataSource.getConnection()) {
                IngestSourceDto source = IngestSourceQueries.source(c, id);
                return source == null ? IngestSourceResponses.notFound("No source with id " + id)
                        : ResponseEntity.ok(source);
            }
        } catch (Refusal r) {
            return IngestSourceResponses.refusal(r);
        } catch (Failure f) {
            return IngestSourceResponses.failure(f);
        } catch (SQLException e) {
            return unreadable("read the ingest source", e);
        }
    }

    private static ResponseEntity<?> unreadable(String what, SQLException e) {
        LOG.error("ingest sources: could not {} ({})", what, e.getSQLState(), e);
        return ResponseEntity.status(500).body(Map.of("message", "Could not " + what));
    }
}
