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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.6 ophth-field-catalog — read-only catalog of pre-built
 * ophthalmology field types. The CRF Library wizard reads this list
 * to populate the "Add from ophthalmology catalog" picker; on
 * selection the wizard materialises a {@code CrfVersionAuthoringRequest.Item}
 * with the canonical OID + label + units + validation already set,
 * removing the need for operators to choose OIDs or type out German
 * measurement names by hand.
 *
 * <p>Seeded by {@code lc-muw-2026-06-11-ophth-field-catalog.xml}.
 * The catalog is institution-wide config; write operations are
 * out of scope for this controller (any catalog evolution rides via
 * a follow-up Liquibase changeset).
 *
 * <h2>Authorization</h2>
 *
 * <p>Any authenticated user — the wizard is used by Data Managers,
 * and Investigators may also browse the catalog for context. No
 * role gate at the controller level.
 */
@RestController
@RequestMapping("/api/v1/ophth-field-catalog")
@Tag(name = "Ophthalmology Field Catalog",
     description = "Read-only catalog of pre-built ophthalmology CRF fields.")
public class OphthFieldCatalogApiController {

    private static final Logger LOG = LoggerFactory.getLogger(OphthFieldCatalogApiController.class);

    /** Active sentinel — matches {@code core.Status.AVAILABLE.getId()}. */
    private static final int STATUS_AVAILABLE = 1;

    private final DataSource dataSource;

    @Autowired
    public OphthFieldCatalogApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* =============================================================== */
    /* GET — list active catalog entries                                */
    /* =============================================================== */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = OphthFieldCatalogDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        List<OphthFieldCatalogDto> out = new ArrayList<>();
        String sql = "SELECT code, label_de, label_en, hint_de, hint_en, "
                + "       bilateral, data_type, widget, unit, "
                + "       min_value, max_value, step_value, "
                + "       placeholder_text, "
                + "       conditional_on_code, conditional_show_when_value, "
                + "       response_options, modality_code, oid_prefix, ordinal "
                + "  FROM ophth_field_catalog "
                + " WHERE status_id = ? "
                + " ORDER BY ordinal ASC, code ASC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, STATUS_AVAILABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to list ophth_field_catalog: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to load ophthalmology field catalog — see server log."));
        }
        return ResponseEntity.ok(out);
    }

    /* =============================================================== */
    /* Helpers                                                          */
    /* =============================================================== */

    private static OphthFieldCatalogDto readRow(ResultSet rs) throws SQLException {
        return new OphthFieldCatalogDto(
                rs.getString("code"),
                rs.getString("label_de"),
                rs.getString("label_en"),
                rs.getString("hint_de"),
                rs.getString("hint_en"),
                rs.getBoolean("bilateral"),
                rs.getString("data_type"),
                rs.getString("widget"),
                rs.getString("unit"),
                rs.getBigDecimal("min_value"),
                rs.getBigDecimal("max_value"),
                rs.getBigDecimal("step_value"),
                rs.getString("placeholder_text"),
                rs.getString("conditional_on_code"),
                rs.getString("conditional_show_when_value"),
                parseResponseOptions(rs.getString("response_options")),
                rs.getString("modality_code"),
                rs.getString("oid_prefix"),
                rs.getInt("ordinal")
        );
    }

    /**
     * Parse the storage format {@code "value|label,value|label,…"} into a
     * list of {@link OphthFieldCatalogDto.ResponseOption}s. Returns an
     * empty list when the storage column is null or blank so the JSON
     * wire shape stays consistent.
     *
     * <p>An entry without a {@code |} separator is treated as both the
     * value and the label (e.g. legacy data); a trailing empty option
     * after a stray comma is silently dropped.
     */
    static List<OphthFieldCatalogDto.ResponseOption> parseResponseOptions(String raw) {
        List<OphthFieldCatalogDto.ResponseOption> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int pipe = t.indexOf('|');
            if (pipe < 0) {
                out.add(new OphthFieldCatalogDto.ResponseOption(t, t));
            } else {
                String value = t.substring(0, pipe).trim();
                String label = t.substring(pipe + 1).trim();
                if (value.isEmpty()) continue;
                out.add(new OphthFieldCatalogDto.ResponseOption(value, label.isEmpty() ? value : label));
            }
        }
        return out;
    }
}
