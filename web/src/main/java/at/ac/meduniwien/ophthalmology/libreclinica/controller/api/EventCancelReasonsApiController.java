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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Institutional cancel-reason catalog used by the SPA's cancel-visit dialog.
 *
 * <p>The companion DELETE {@code /api/v1/events/{id}} handler requires
 * the operator to pick one of these rows; the {@code isOther} flag tells
 * the SPA when to reveal a free-text field, and the controller server-side
 * validates that the picked code exists + that free text is non-blank when
 * the row is the "Other" entry.
 *
 * <p>Auth: session-bound; 401 anonymous. No additional role gate.
 */
@RestController
@RequestMapping("/api/v1/event-cancel-reasons")
@Tag(name = "Event cancel reasons",
        description = "Institutional cancel-reason catalog used by the SPA's cancel-visit dialog.")
public class EventCancelReasonsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(EventCancelReasonsApiController.class);

    private final DataSource dataSource;

    @Autowired
    public EventCancelReasonsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * List the institutional cancel-reason catalog ordered by
     * {@code sort_order ASC}, then {@code code ASC} for stable ties.
     *
     * <p>Returns shape:
     * <pre>
     *   [
     *     { "code":"PATIENT_NO_SHOW", "labelDe":"...", "labelEn":"...",
     *       "sortOrder":10, "isOther":false },
     *     ...
     *   ]
     * </pre>
     */
    @GetMapping
    @Operation(operationId = "listEventCancelReasons")
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        final String sql = "SELECT code, label_de, label_en, sort_order, is_other "
                + "FROM study_event_cancel_reason ORDER BY sort_order, code";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("code", rs.getString("code"));
                row.put("labelDe", rs.getString("label_de"));
                row.put("labelEn", rs.getString("label_en"));
                row.put("sortOrder", rs.getInt("sort_order"));
                row.put("isOther", rs.getBoolean("is_other"));
                rows.add(row);
            }
        } catch (SQLException sqle) {
            LOG.error("Failed to load study_event_cancel_reason catalog", sqle);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to load cancel reasons — see server log."));
        }
        return ResponseEntity.ok(rows);
    }
}
