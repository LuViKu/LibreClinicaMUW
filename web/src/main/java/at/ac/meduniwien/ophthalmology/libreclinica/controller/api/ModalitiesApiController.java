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
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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
 * Phase E.6 study-nurse polish — institution-wide modality CRUD.
 *
 * <p>A "modality" is a per-eye measurement family (BCVA-LogMAR, IOP,
 * refraction sphere, etc.) — see {@code lc-muw-2026-06-08-modality.xml}
 * for table shape + seeded rows. The SPA's Modality Admin view reads
 * this for the catalog table and POSTs / PUTs / DELETEs for write ops.
 * The Per-Eye Baselines + Patient Measurement endpoints read it to
 * resolve {@code modalityCode} → item OID(s).
 *
 * <h2>Authorization</h2>
 *
 * <ul>
 *   <li>{@code GET}: any authenticated user (the catalog feeds the
 *       baselines panel, which Investigators read).</li>
 *   <li>{@code POST / PUT / DELETE}: Administrator only — the catalog
 *       is institution-wide config, not study-scoped data, so the
 *       sysadmin gate matches the legacy convention for other
 *       institution-wide tables (audit_log_event_type, role
 *       definitions, etc.).</li>
 * </ul>
 *
 * <h2>Audit</h2>
 *
 * <p>One {@code audit_log_event} row per write of type 58 (created), 59
 * (updated), or 60 (deleted) — seeded by {@code lc-muw-2026-06-08-modality.xml}.
 * The pattern mirrors {@link SubjectExportApiController#emitExportAudit}:
 * direct JDBC INSERT with WARN-and-continue on failure (loss of one
 * audit row is annoying but should not refuse a successful write).
 */
@RestController
@RequestMapping("/api/v1/modalities")
@Tag(name = "Modalities",
     description = "Institution-wide modality catalog (per-eye measurement families).")
public class ModalitiesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ModalitiesApiController.class);

    /** Soft-delete sentinel — matches {@code core.Status.DELETED.getId()}. */
    private static final int STATUS_DELETED = 5;

    /** Active sentinel — matches {@code core.Status.AVAILABLE.getId()}. */
    private static final int STATUS_AVAILABLE = 1;

    static final int AUDIT_TYPE_MODALITY_CREATED = 58;
    static final int AUDIT_TYPE_MODALITY_UPDATED = 59;
    static final int AUDIT_TYPE_MODALITY_DELETED = 60;

    private static final Set<String> ALLOWED_DATA_TYPES = Set.of("numeric", "categorical");

    private final DataSource dataSource;

    @Autowired
    public ModalitiesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Write-path request body for {@link #create} + {@link #update}.
     *
     * <p>{@code code} is immutable on update (the PUT endpoint ignores
     * the body's {@code code} and re-reads the existing row's code).
     * The shape is shared because the SPA's form populates the same
     * fields on edit; nullable per-eye OIDs let single-eye modalities
     * elide the unused side.
     */
    public record ModalityWriteRequest(
            String code,
            String labelEn,
            String labelDe,
            Integer ordinal,
            String itemOidOd,
            String itemOidOs,
            String dataType,
            String unit
    ) {}

    /* =============================================================== */
    /* GET — list active                                                */
    /* =============================================================== */

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array",
                         implementation = ModalityDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        List<ModalityDto> out = new ArrayList<>();
        String sql = "SELECT modality_id, code, label_en, label_de, ordinal, "
                + "       item_oid_od, item_oid_os, data_type, unit "
                + "  FROM modality "
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
            LOG.error("Failed to list modalities: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to load modality catalog — see server log."));
        }
        return ResponseEntity.ok(out);
    }

    /* =============================================================== */
    /* POST — create                                                    */
    /* =============================================================== */

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = ModalityDto.class)))
    public ResponseEntity<?> create(@RequestBody(required = false) ModalityWriteRequest body,
                                    HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        ResponseEntity<?> roleRefusal = refuseIfNotAdmin(session);
        if (roleRefusal != null) return roleRefusal;
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Request body is required."));
        }
        // Shape validation.
        String code = trim(body.code());
        if (code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "code is required."));
        }
        if (code.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "code must be ≤ 64 characters."));
        }
        ResponseEntity<?> shapeError = validateShape(body, /* requireCode */ false);
        if (shapeError != null) return shapeError;

        // Uniqueness — collide on active OR deleted rows. The unique
        // constraint covers both, so attempting to create a code that
        // matches a soft-deleted row would surface as a DB-level UNIQUE
        // violation (500) without this pre-check. Surface the friendly
        // 409 instead.
        try (Connection c = dataSource.getConnection()) {
            if (codeExists(c, code, /* excludeModalityId */ 0)) {
                return ResponseEntity.status(409).body(Map.of(
                        "message", "A modality with code '" + code + "' already exists."));
            }
            // OID existence (any non-empty value must resolve through ItemDAO).
            ResponseEntity<?> oidError = validateOids(body.itemOidOd(), body.itemOidOs());
            if (oidError != null) return oidError;

            int modalityId = insertModality(c, code, body, currentUser.getId());
            emitAudit(c, AUDIT_TYPE_MODALITY_CREATED, currentUser.getId(), modalityId,
                    code, /* oldVal */ "", buildAuditNewVal(body));
            ModalityDto out = loadById(c, modalityId);
            LOG.info("Modality created: id={} code={} by user={}",
                    modalityId, code, currentUser.getName());
            return ResponseEntity.status(201).body(out);
        } catch (SQLException e) {
            LOG.error("Failed to create modality {}: {}", code, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to create modality — see server log."));
        }
    }

    /* =============================================================== */
    /* PUT — update                                                     */
    /* =============================================================== */

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = ModalityDto.class)))
    public ResponseEntity<?> update(@PathVariable("id") int id,
                                    @RequestBody(required = false) ModalityWriteRequest body,
                                    HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        ResponseEntity<?> roleRefusal = refuseIfNotAdmin(session);
        if (roleRefusal != null) return roleRefusal;
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Request body is required."));
        }
        ResponseEntity<?> shapeError = validateShape(body, /* requireCode */ false);
        if (shapeError != null) return shapeError;

        try (Connection c = dataSource.getConnection()) {
            ModalityDto existing = loadByIdActive(c, id);
            if (existing == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Modality with id=" + id + " not found."));
            }
            ResponseEntity<?> oidError = validateOids(body.itemOidOd(), body.itemOidOs());
            if (oidError != null) return oidError;

            String oldVal = buildAuditValOf(existing);
            updateModality(c, id, body, currentUser.getId());
            emitAudit(c, AUDIT_TYPE_MODALITY_UPDATED, currentUser.getId(), id,
                    existing.code(), oldVal, buildAuditNewVal(body, existing.code()));
            ModalityDto out = loadById(c, id);
            LOG.info("Modality updated: id={} code={} by user={}",
                    id, existing.code(), currentUser.getName());
            return ResponseEntity.ok(out);
        } catch (SQLException e) {
            LOG.error("Failed to update modality id={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to update modality — see server log."));
        }
    }

    /* =============================================================== */
    /* DELETE — soft delete                                             */
    /* =============================================================== */

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204")
    public ResponseEntity<?> delete(@PathVariable("id") int id, HttpSession session) {
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        if (currentUser == null || currentUser.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        ResponseEntity<?> roleRefusal = refuseIfNotAdmin(session);
        if (roleRefusal != null) return roleRefusal;

        try (Connection c = dataSource.getConnection()) {
            ModalityDto existing = loadByIdActive(c, id);
            if (existing == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "message", "Modality with id=" + id + " not found."));
            }
            softDelete(c, id, currentUser.getId());
            emitAudit(c, AUDIT_TYPE_MODALITY_DELETED, currentUser.getId(), id,
                    existing.code(), buildAuditValOf(existing), "");
            LOG.info("Modality soft-deleted: id={} code={} by user={}",
                    id, existing.code(), currentUser.getName());
            return ResponseEntity.noContent().build();
        } catch (SQLException e) {
            LOG.error("Failed to delete modality id={}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to delete modality — see server log."));
        }
    }

    /* =============================================================== */
    /* Internal helpers                                                 */
    /* =============================================================== */

    /**
     * 403 if the session role isn't {@link Role#ADMIN}. Mirrors the
     * existing sibling pattern in {@link StudiesApiController#create}
     * (sysadmin-only) — institution-wide configuration tables stay
     * tightly gated even when the SPA convenience function lets every
     * authenticated user READ them.
     */
    private static ResponseEntity<?> refuseIfNotAdmin(HttpSession session) {
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        int roleId = (currentRole != null && currentRole.getRole() != null)
                ? currentRole.getRole().getId() : 0;
        if (roleId != Role.ADMIN.getId()) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit modifying the modality catalog — sysadmin only."));
        }
        return null;
    }

    private ResponseEntity<?> validateShape(ModalityWriteRequest body, boolean requireCode) {
        String labelEn = trim(body.labelEn());
        if (labelEn.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "labelEn is required."));
        }
        if (labelEn.length() > 120) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "labelEn must be ≤ 120 characters."));
        }
        String labelDe = trim(body.labelDe());
        if (labelDe.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "labelDe is required."));
        }
        if (labelDe.length() > 120) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "labelDe must be ≤ 120 characters."));
        }
        String dataType = trim(body.dataType());
        if (dataType.isEmpty() || !ALLOWED_DATA_TYPES.contains(dataType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "dataType must be one of " + ALLOWED_DATA_TYPES + " (got '"
                            + body.dataType() + "')."));
        }
        String oidOd = trim(body.itemOidOd());
        String oidOs = trim(body.itemOidOs());
        if (oidOd.isEmpty() && oidOs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "At least one of itemOidOd / itemOidOs must be set."));
        }
        if (oidOd.length() > 64 || oidOs.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "itemOidOd / itemOidOs must be ≤ 64 characters."));
        }
        String unit = trim(body.unit());
        if (unit.length() > 32) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "unit must be ≤ 32 characters."));
        }
        return null;
    }

    /**
     * Check that any non-empty OID resolves through {@link ItemDAO#findByOid}.
     * Returns a 400 with the failing OID surfaced in the message when a
     * lookup misses; null when all set OIDs resolve.
     */
    private ResponseEntity<?> validateOids(String itemOidOd, String itemOidOs) {
        ItemDAO itemDAO = new ItemDAO(dataSource);
        String od = trim(itemOidOd);
        String os = trim(itemOidOs);
        if (!od.isEmpty()) {
            @SuppressWarnings("rawtypes")
            ArrayList<ItemBean> matches = itemDAO.findByOid(od);
            if (matches == null || matches.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Unknown item OID '" + od + "' (itemOidOd)."));
            }
        }
        if (!os.isEmpty()) {
            @SuppressWarnings("rawtypes")
            ArrayList<ItemBean> matches = itemDAO.findByOid(os);
            if (matches == null || matches.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Unknown item OID '" + os + "' (itemOidOs)."));
            }
        }
        return null;
    }

    private boolean codeExists(Connection c, String code, int excludeModalityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM modality WHERE code = ? AND modality_id <> ?")) {
            ps.setString(1, code);
            ps.setInt(2, excludeModalityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int insertModality(Connection c, String code, ModalityWriteRequest body,
                               int actorUserId) throws SQLException {
        String sql = "INSERT INTO modality (code, label_en, label_de, ordinal, "
                + "item_oid_od, item_oid_os, data_type, unit, status_id, "
                + "date_created, created_by_user_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setString(2, trim(body.labelEn()));
            ps.setString(3, trim(body.labelDe()));
            ps.setInt(4, body.ordinal() == null ? 0 : body.ordinal());
            setStringOrNull(ps, 5, body.itemOidOd());
            setStringOrNull(ps, 6, body.itemOidOs());
            ps.setString(7, trim(body.dataType()));
            setStringOrNull(ps, 8, body.unit());
            ps.setInt(9, STATUS_AVAILABLE);
            ps.setInt(10, actorUserId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
                throw new SQLException("Modality insert returned no PK.");
            }
        }
    }

    private void updateModality(Connection c, int modalityId, ModalityWriteRequest body,
                                int actorUserId) throws SQLException {
        // code is immutable — leave it untouched.
        String sql = "UPDATE modality SET label_en = ?, label_de = ?, ordinal = ?, "
                + "item_oid_od = ?, item_oid_os = ?, data_type = ?, unit = ?, "
                + "date_updated = now(), updated_by_user_id = ? "
                + "WHERE modality_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trim(body.labelEn()));
            ps.setString(2, trim(body.labelDe()));
            ps.setInt(3, body.ordinal() == null ? 0 : body.ordinal());
            setStringOrNull(ps, 4, body.itemOidOd());
            setStringOrNull(ps, 5, body.itemOidOs());
            ps.setString(6, trim(body.dataType()));
            setStringOrNull(ps, 7, body.unit());
            ps.setInt(8, actorUserId);
            ps.setInt(9, modalityId);
            ps.executeUpdate();
        }
    }

    private void softDelete(Connection c, int modalityId, int actorUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE modality SET status_id = ?, date_updated = now(), updated_by_user_id = ? "
                        + "WHERE modality_id = ?")) {
            ps.setInt(1, STATUS_DELETED);
            ps.setInt(2, actorUserId);
            ps.setInt(3, modalityId);
            ps.executeUpdate();
        }
    }

    private ModalityDto loadById(Connection c, int modalityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT modality_id, code, label_en, label_de, ordinal, "
                        + "item_oid_od, item_oid_os, data_type, unit "
                        + "FROM modality WHERE modality_id = ?")) {
            ps.setInt(1, modalityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readRow(rs);
                return null;
            }
        }
    }

    /** Variant of {@link #loadById} that excludes soft-deleted rows (for 404 on PUT/DELETE). */
    private ModalityDto loadByIdActive(Connection c, int modalityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT modality_id, code, label_en, label_de, ordinal, "
                        + "item_oid_od, item_oid_os, data_type, unit "
                        + "FROM modality WHERE modality_id = ? AND status_id = ?")) {
            ps.setInt(1, modalityId);
            ps.setInt(2, STATUS_AVAILABLE);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readRow(rs);
                return null;
            }
        }
    }

    private static ModalityDto readRow(ResultSet rs) throws SQLException {
        return new ModalityDto(
                rs.getInt("modality_id"),
                rs.getString("code"),
                rs.getString("label_en"),
                rs.getString("label_de"),
                rs.getInt("ordinal"),
                rs.getString("item_oid_od"),
                rs.getString("item_oid_os"),
                rs.getString("data_type"),
                rs.getString("unit"));
    }

    private static void setStringOrNull(PreparedStatement ps, int idx, String raw) throws SQLException {
        String v = trim(raw);
        if (v.isEmpty()) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    /**
     * Audit emit. Pipe-encodes the audited fields into the
     * {@code audit_log_event.old_value} / {@code new_value} columns
     * (4000-char cap each, well above the modality row's combined
     * field width). Failures are logged at WARN; we do NOT roll back
     * the underlying write — matches the SubjectExportApiController
     * pattern. Loss of one audit row should not refuse a successful
     * configuration change.
     */
    private void emitAudit(Connection c, int auditType, int actorUserId, int modalityId,
                           String code, String oldVal, String newVal) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                        + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, 'modality', ?, ?, ?, ?)")) {
            ps.setInt(1, auditType);
            ps.setInt(2, actorUserId);
            ps.setInt(3, modalityId);
            ps.setString(4, code == null ? "" : code);
            ps.setString(5, truncate(oldVal, 4000));
            ps.setString(6, truncate(newVal, 4000));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write modality audit (type={}, id={}, code={}): {}",
                    auditType, modalityId, code, e.getMessage());
        }
    }

    private static String buildAuditValOf(ModalityDto m) {
        return nz(m.code()) + "|" + nz(m.labelEn()) + "|" + nz(m.labelDe()) + "|"
                + m.ordinal() + "|"
                + nz(m.itemOidOd()) + "|" + nz(m.itemOidOs()) + "|"
                + nz(m.dataType()) + "|" + nz(m.unit());
    }

    private static String buildAuditNewVal(ModalityWriteRequest body) {
        return buildAuditNewVal(body, body.code());
    }

    private static String buildAuditNewVal(ModalityWriteRequest body, String code) {
        return nz(trim(code)) + "|" + nz(trim(body.labelEn())) + "|" + nz(trim(body.labelDe())) + "|"
                + (body.ordinal() == null ? 0 : body.ordinal()) + "|"
                + nz(trim(body.itemOidOd())) + "|" + nz(trim(body.itemOidOs())) + "|"
                + nz(trim(body.dataType())) + "|" + nz(trim(body.unit()));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
