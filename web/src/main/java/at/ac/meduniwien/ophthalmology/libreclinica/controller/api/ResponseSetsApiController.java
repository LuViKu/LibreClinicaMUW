/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * Phase E.6 Milestone B — cross-CRF response-set catalog.
 *
 * <p>Surfaces the distinct {@code (label, response_type, options_text,
 * options_values)} tuples currently materialised across
 * {@code response_set} rows so operators authoring a new CRF can pick
 * a previously-defined set rather than re-typing options.
 *
 * <p><b>Why a virtual catalog and not a new table</b> (DR-020): the
 * existing schema scopes {@code response_set} rows to a CRF
 * {@code version_id}. A standalone catalog table would either (a)
 * require dual-writing the same definition on every CRF-version create
 * — adding a write path the existing parser doesn't know about — or
 * (b) decouple catalog labels from how the parser persists them,
 * inviting drift. The distinct-tuples view materialises the catalog
 * read-side without touching the write side; each "pick from catalog"
 * in the SPA inlines the chosen tuple back into the authoring payload,
 * and the parser persists a fresh {@code response_set} row tied to the
 * new version. Catalog entries are therefore a UX affordance, not a
 * separate persistence concept.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/v1/response-sets} — list catalog entries,
 *       optionally filtered by {@code q} (label substring) and
 *       {@code responseType} (canonical name)</li>
 *   <li>{@code POST   /api/v1/response-sets} — accept-only virtual
 *       create. Returns 201 with the echoed body but does NOT touch
 *       the database; the SPA persists the entry into its
 *       authoring-store cache. Final persistence happens when the
 *       operator submits the CRF version</li>
 * </ul>
 *
 * <p>Auth: read open to any authenticated user; write requires the
 * same role triad as CRF authoring (sysadmin or director/coordinator
 * on the active study).
 */
@RestController
@RequestMapping("/api/v1/response-sets")
@Tag(name = "Response Sets",
     description = "Cross-CRF response-set catalog — virtual, derived from existing response_set rows.")
public class ResponseSetsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseSetsApiController.class);

    private final DataSource dataSource;

    @Autowired
    public ResponseSetsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ----------------------------------------------------------------- */
    /* GET /api/v1/response-sets                                         */
    /* ----------------------------------------------------------------- */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = ResponseSetDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "responseType", required = false) String responseTypeFilter,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit,
            HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        int effectiveLimit = Math.max(1, Math.min(limit, 500));
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        int activeStudyId = currentStudy == null ? 0 : currentStudy.getId();

        try {
            List<ResponseSetDto> catalog = loadDistinctCatalog(
                    normalise(query), normalise(responseTypeFilter),
                    effectiveLimit, activeStudyId);
            return ResponseEntity.ok(catalog);
        } catch (SQLException e) {
            LOG.warn("Failed to load response-set catalog: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to load response-set catalog: " + e.getMessage()));
        }
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/response-sets                                        */
    /* ----------------------------------------------------------------- */

    /**
     * Virtual create — accepts the catalog entry, validates its shape
     * and echoes it back. Catalog rows are not persisted as standalone
     * rows (DR-020); persistence happens at the next CRF version
     * create. Returning 201 lets the SPA optimistically add the entry
     * to its picker without a synchronous DB write.
     */
    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = ResponseSetDto.class)))
    public ResponseEntity<?> create(@RequestBody(required = false) CreateRequest body,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!StudyAdminAuthorization.userMayManageCrfLibrary(me, dataSource)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit managing response sets — sysadmin or Director/Coordinator only"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new ValidationErrorBody(
                    "Request body is required",
                    List.of(new ValidationErrorBody.FieldError("body", "missing"))));
        }
        List<ValidationErrorBody.FieldError> errors = validateCreate(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new ValidationErrorBody("Validation failed", errors));
        }

        ResponseSetDto out = new ResponseSetDto(
                body.label().trim(),
                body.responseType().trim().toLowerCase(Locale.ROOT),
                body.options(),
                0L,
                false);
        return ResponseEntity.status(201).body(out);
    }

    /* ----------------------------------------------------------------- */
    /* Catalog loader                                                    */
    /* ----------------------------------------------------------------- */

    /**
     * Materialise the distinct-tuples view directly from SQL — keeps
     * us off Hibernate for a read-only catalog query and avoids
     * loading every {@code ResponseSet} entity. {@code response_set}
     * is small enough (low thousands of rows at MUW) that a single
     * DISTINCT scan is cheap.
     */
    private List<ResponseSetDto> loadDistinctCatalog(String labelFilter,
                                                     String responseTypeFilter,
                                                     int limit,
                                                     int activeStudyId) throws SQLException {
        StringBuilder sql = new StringBuilder(512);
        // `crf_version` has no `study_id`; scope through
        // event_definition_crf → study_event_definition.study_id so the
        // "in active study" flag reflects whether any of the response
        // set's parent CRFs is wired into a SED in this study.
        sql.append("SELECT rs.label, rt.name AS response_type, ")
           .append("       rs.options_text, rs.options_values, ")
           .append("       COUNT(DISTINCT rs.version_id) AS usage_count, ")
           .append("       BOOL_OR(EXISTS (")
           .append("           SELECT 1 FROM event_definition_crf edc ")
           .append("           JOIN study_event_definition sed ")
           .append("             ON sed.study_event_definition_id = edc.study_event_definition_id ")
           .append("           WHERE edc.crf_id = cv.crf_id AND sed.study_id = ? ")
           .append("       )) AS in_active_study ")
           .append("FROM response_set rs ")
           .append("JOIN response_type rt ON rt.response_type_id = rs.response_type_id ")
           .append("LEFT JOIN crf_version cv ON cv.crf_version_id = rs.version_id ")
           .append("WHERE rs.label IS NOT NULL AND rs.label <> '' ");
        if (labelFilter != null) {
            sql.append("AND LOWER(rs.label) LIKE ? ");
        }
        if (responseTypeFilter != null) {
            sql.append("AND LOWER(rt.name) = ? ");
        }
        sql.append("GROUP BY rs.label, rt.name, rs.options_text, rs.options_values ")
           .append("ORDER BY usage_count DESC, rs.label ASC ")
           .append("LIMIT ?");

        List<ResponseSetDto> out = new ArrayList<>(limit);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setInt(idx++, activeStudyId);
            if (labelFilter != null) {
                ps.setString(idx++, "%" + labelFilter.toLowerCase(Locale.ROOT) + "%");
            }
            if (responseTypeFilter != null) {
                ps.setString(idx++, responseTypeFilter.toLowerCase(Locale.ROOT));
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String label = rs.getString(1);
                    String responseType = rs.getString(2);
                    String optionsText = rs.getString(3);
                    String optionsValues = rs.getString(4);
                    long usage = rs.getLong(5);
                    boolean inActive = rs.getBoolean(6);
                    out.add(new ResponseSetDto(
                            label == null ? "" : label,
                            responseType == null ? "" : responseType,
                            zipOptions(optionsText, optionsValues),
                            usage,
                            inActive));
                }
            }
        }
        return out;
    }

    /**
     * Reverse the parser's comma-join: split on unescaped commas,
     * un-escape {@code "\\,"} back to literal {@code ","}. Mirrors
     * {@link CrfJsonToWorkbookAdapter}'s {@code joinOptions} (inverse).
     */
    static List<CrfVersionAuthoringRequest.Option> zipOptions(String optionsText, String optionsValues) {
        if ((optionsText == null || optionsText.isBlank())
                && (optionsValues == null || optionsValues.isBlank())) {
            return List.of();
        }
        String[] texts = splitEscaped(optionsText);
        String[] values = splitEscaped(optionsValues);
        int n = Math.max(texts.length, values.length);
        List<CrfVersionAuthoringRequest.Option> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String t = i < texts.length ? texts[i] : "";
            String v = i < values.length ? values[i] : "";
            out.add(new CrfVersionAuthoringRequest.Option(t, v));
        }
        return out;
    }

    /**
     * Split a comma-joined options column where literal commas were
     * escaped as {@code "\\,"} (the legacy convention also used by
     * {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseSetBean#setOptions}).
     */
    private static String[] splitEscaped(String value) {
        if (value == null || value.isBlank()) return new String[0];
        String hex = value.replace("\\,", "");
        String[] parts = hex.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("", ",").trim();
        }
        return parts;
    }

    /* ----------------------------------------------------------------- */
    /* POST validation                                                   */
    /* ----------------------------------------------------------------- */

    private static List<ValidationErrorBody.FieldError> validateCreate(CreateRequest body) {
        List<ValidationErrorBody.FieldError> out = new ArrayList<>();
        String label = body.label() == null ? "" : body.label().trim();
        if (label.isEmpty()) out.add(fe("label", "Label is required"));
        else if (label.length() > 80) out.add(fe("label", "Label must be 80 characters or fewer"));
        String type = body.responseType() == null ? "" : body.responseType().trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            out.add(fe("responseType", "Response type is required"));
        } else if (!CrfJsonToWorkbookAdapter.ALLOWED_RESPONSE_TYPES.contains(type)) {
            out.add(fe("responseType",
                    "Response type must be one of text, textarea, radio, single-select, "
                            + "multi-select, checkbox, file"));
        }
        if (("text".equals(type) || "textarea".equals(type) || "file".equals(type))) {
            // No options expected — drop silently if supplied.
        } else if (body.options() == null || body.options().isEmpty()) {
            out.add(fe("options", "Response type '" + type + "' requires at least one option"));
        } else {
            int oi = 0;
            java.util.Set<String> seenValues = new java.util.HashSet<>();
            for (CrfVersionAuthoringRequest.Option opt : body.options()) {
                String oPrefix = "options[" + oi + "]";
                String v = opt == null || opt.value() == null ? "" : opt.value().trim();
                String t = opt == null || opt.text() == null ? "" : opt.text().trim();
                if (v.isEmpty()) out.add(fe(oPrefix + ".value", "Option value is required"));
                else if (!seenValues.add(v)) out.add(fe(oPrefix + ".value", "Duplicate option value '" + v + "'"));
                if (t.isEmpty()) out.add(fe(oPrefix + ".text", "Option text is required"));
                oi++;
            }
        }
        return out;
    }

    private static ValidationErrorBody.FieldError fe(String field, String msg) {
        return new ValidationErrorBody.FieldError(field, msg);
    }

    private static String normalise(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** Body shape for {@code POST /api/v1/response-sets}. */
    @Schema(name = "CreateResponseSetRequest")
    public record CreateRequest(
            String label,
            String responseType,
            List<CrfVersionAuthoringRequest.Option> options
    ) {}
}
