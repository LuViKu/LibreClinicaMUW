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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DR-040 phase 4 — the locations the caller may place a study or site at,
 * for the create and edit forms: exactly the ones {@link LocationChoice}
 * accepts from them. A system administrator gets every available location;
 * anybody else the available locations in their own scope (the sites they hold
 * roles at, and their explicit assignments). Anonymous: 401. Never 403: a
 * signed-in user with no scope gets an empty list.
 *
 * <p>The administration of locations is {@code /api/v1/admin/locations}
 * (system administrators); this list is read-only and carries only what a
 * form needs.
 */
@RestController
@RequestMapping("/api/v1/locations")
@Tag(name = "Locations",
     description = "The locations the caller may place a study or site at (DR-040).")
public class LocationsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(LocationsApiController.class);

    private final DataSource dataSource;
    private final LocationScope scope;

    @Autowired
    public LocationsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.scope = new LocationScope(dataSource);
    }

    @Operation(operationId = "listLocationOptions",
               summary = "The available locations the caller may place a study or site at, by name")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(array = @ArraySchema(schema = @Schema(implementation = LocationOptionDto.class))))
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean me = session == null ? null : (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        LocationScope.Visible visible = me.isSysAdmin() ? null : scope.visibleLocations(me);
        try (Connection c = dataSource.getConnection()) {
            List<LocationOptionDto> out = new ArrayList<>();
            for (LocationScope.Place p : LocationScope.places(c).values()) {
                if (!p.available()) continue;
                if (visible != null && !visible.contains(p.id())) continue;
                out.add(new LocationOptionDto(p.id(), p.code(), p.name(), p.street(), p.city(), p.zip(),
                        p.country()));
            }
            out.sort(Comparator.comparing(LocationOptionDto::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(LocationOptionDto::code));
            return ResponseEntity.ok(out);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("could not list the location options: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "Could not read the locations"));
        }
    }
}
