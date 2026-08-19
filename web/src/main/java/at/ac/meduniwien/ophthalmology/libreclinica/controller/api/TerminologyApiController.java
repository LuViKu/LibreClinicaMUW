package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #26 — Terminology autocomplete + ingest.
 *
 * <ul>
 *   <li>{@code GET /api/v1/terminology/search?system=&q=&limit=} — typeahead
 *       for the repeating-table item's autocomplete columns. Any authenticated
 *       user (physicians use it during data entry). Prefix-matches the
 *       normalised display and the code.</li>
 *   <li>{@code POST /api/v1/terminology/ingest} — sysadmin-only manual load of
 *       a FHIR CodeSystem file from a server path (local / ops use). The
 *       scheduled worker (slice 2) drives the same {@link TerminologyIngestService}
 *       from a pinned upstream URL.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/terminology")
@Tag(name = "Terminology", description = "ICD-10-GM / drug-catalogue autocomplete for the repeating-table item.")
public class TerminologyApiController {

    private static final Logger LOG = LoggerFactory.getLogger(TerminologyApiController.class);
    private static final int MAX_LIMIT = 50;

    private final DataSource dataSource;
    private final TerminologyIngestService ingestService;

    public TerminologyApiController(@Qualifier("dataSource") DataSource dataSource,
                                    TerminologyIngestService ingestService) {
        this.dataSource = dataSource;
        this.ingestService = ingestService;
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestParam("system") String system,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "limit", required = false) Integer limit,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return ResponseEntity.ok(List.of()); // require ≥2 chars — a 1-char prefix returns the whole catalogue
        }
        int lim = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIMIT));
        String prefix = TerminologyIngestService.normalise(query) + "%";
        String codePrefix = query.toUpperCase() + "%";

        // Prefix on the normalised display (index range scan via text_pattern_ops)
        // OR a code prefix; exact-code matches sort first.
        final String sql = "SELECT code, display, properties "
                + "FROM terminology_concept "
                + "WHERE code_system = ? AND (match_text LIKE ? OR code LIKE ?) "
                + "ORDER BY (CASE WHEN upper(code) = ? THEN 0 ELSE 1 END), length(code), code "
                + "LIMIT ?";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, system);
            ps.setString(2, prefix);
            ps.setString(3, codePrefix);
            ps.setString(4, query.toUpperCase());
            ps.setInt(5, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", rs.getString("code"));
                    row.put("display", rs.getString("display"));
                    row.put("properties", rs.getString("properties"));
                    out.add(row);
                }
            }
        } catch (Exception e) {
            LOG.warn("Terminology search failed (system={} q={}): {}", sanitizeForLog(system), sanitizeForLog(query), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "Terminology search failed"));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Strip line breaks from user-supplied values before they reach the log,
     * so a crafted {@code q} / {@code system} can't forge extra log lines
     * (CWE-117 log injection).
     */
    private static String sanitizeForLog(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n]", "_");
    }

    public record IngestRequest(String system, String path, String sourceUrl, String sourceSha) {}

    @PostMapping(value = "/ingest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(@RequestBody IngestRequest req, HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!me.isSysAdmin() && !me.isTechAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message", "Administrator only"));
        }
        if (req == null || req.system() == null || req.path() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "system + path are required"));
        }
        Path file = Path.of(req.path());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(404).body(Map.of("message", "No file at '" + req.path() + "'"));
        }
        try (InputStream in = Files.newInputStream(file)) {
            TerminologyIngestService.IngestResult r =
                    ingestService.ingestFhirCodeSystem(req.system(), req.sourceUrl(), req.sourceSha(), in);
            return ResponseEntity.ok(Map.of(
                    "codeSystem", r.codeSystem(),
                    "declaredCount", r.declaredCount(),
                    "loaded", r.loaded(),
                    "sourceVersion", r.sourceVersion() == null ? "" : r.sourceVersion()));
        } catch (Exception e) {
            LOG.error("Terminology ingest failed for system={} path={}", req.system(), req.path(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Ingest failed: " + e.getMessage()));
        }
    }
}
