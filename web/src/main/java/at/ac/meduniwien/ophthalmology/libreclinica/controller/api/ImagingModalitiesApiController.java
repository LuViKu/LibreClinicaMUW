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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;

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
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * P3.4 — a study's imaging catalogue, maintained by its administrator.
 *
 * <p>Which acquisitions a study performs, which device does each, and which
 * CRF box each one ticks. Before this the answer was two rows a developer had
 * put in a migration, so onboarding a study meant a code change; the point of
 * the catalogue is that it does not.
 *
 * <p>This is configuration, not clinical data, but it decides what the platform
 * writes into a CRF without anybody typing it — so every change is audited, and
 * a binding's item OID is checked to exist before it is accepted. A binding
 * pointing at nothing does not fail loudly; it simply stops ticking, which
 * looks exactly like a modality that was not performed.
 *
 * <p>Deleting is a status change, not a DELETE. Files filed under a modality
 * keep naming it, and an audit row explaining a CRF value has to stay
 * resolvable after somebody tidies the catalogue.
 */
@RestController
@RequestMapping("/api/v1/studies/{studyOid}/imaging-modalities")
@Tag(name = "Imaging modalities",
     description = "Per-study catalogue of imaging acquisitions and the CRF items they tick (P3.4).")
public class ImagingModalitiesApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ImagingModalitiesApiController.class);

    /** Roles a binding may fill on a modality's checklist row. */
    private static final Set<String> ROLES =
            Set.of("performed", "not_performed_reason", "initials");

    /** What a file may be, for {@code kinds_accepted}. */
    private static final Set<String> KINDS = Set.of("e2e", "dicom", "image", "other");

    private static final Set<String> LATERALITIES = Set.of("OU", "OD", "OS");

    private final DataSource dataSource;

    @Autowired
    public ImagingModalitiesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ----- DTOs -----

    public record BindingDto(int id, String role, String laterality, String itemOid,
                             String performedValue) {}

    public record ModalityDto(int id, String code, String labelDe, String labelEn,
                              String device, String kindsAccepted, boolean lateralityRequired,
                              String autoMatchAeTitle, int ordinal, int statusId,
                              List<BindingDto> bindings) {}

    public record ModalityWriteRequest(String code, String labelDe, String labelEn,
                                       String device, String kindsAccepted,
                                       Boolean lateralityRequired, String autoMatchAeTitle,
                                       Integer ordinal) {}

    public record BindingWriteRequest(String role, String laterality, String itemOid,
                                      String performedValue) {}

    // ----- GET -----

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@PathVariable("studyOid") String studyOid, HttpSession session) {
        ResponseEntity<?> guard = guardRead(session);
        if (guard != null) return guard;

        try (Connection c = dataSource.getConnection()) {
            Integer studyId = studyIdOf(c, studyOid);
            if (studyId == null) {
                return ResponseEntity.status(404).body(Map.of("message", "no study " + studyOid));
            }
            return ResponseEntity.ok(Map.of("modalities", loadAll(c, studyId)));
        } catch (SQLException e) {
            LOG.error("imaging-modality list failed for {}: {}", studyOid, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not list modalities"));
        }
    }

    // ----- POST -----

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@PathVariable("studyOid") String studyOid,
                                    @RequestBody(required = false) ModalityWriteRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = guardWrite(session);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Request body is required."));
        }
        String code = normaliseCode(body.code());
        ResponseEntity<?> shape = validate(body, code, true);
        if (shape != null) return shape;

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        try (Connection c = dataSource.getConnection()) {
            Integer studyId = studyIdOf(c, studyOid);
            if (studyId == null) {
                return ResponseEntity.status(404).body(Map.of("message", "no study " + studyOid));
            }
            if (codeExists(c, studyId, code, 0)) {
                return ResponseEntity.status(409).body(Map.of(
                        "message", "This study already has a modality with code '" + code + "'."));
            }
            int id = insert(c, studyId, code, body, user.getId());
            emitAudit(c, AuditTypeIds.IMAGING_MODALITY_CREATED, user.getId(), id, code, "",
                    describe(body));
            LOG.info("imaging modality created: study={} code={} by={}", studyOid, code, user.getName());
            return ResponseEntity.status(201).body(loadOne(c, id));
        } catch (SQLException e) {
            LOG.error("imaging-modality create failed for {}: {}", studyOid, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not create the modality"));
        }
    }

    // ----- PUT -----

    @PutMapping(value = "/{id:[0-9]+}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("id") int id,
                                    @RequestBody(required = false) ModalityWriteRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = guardWrite(session);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Request body is required."));
        }
        String code = normaliseCode(body.code());
        ResponseEntity<?> shape = validate(body, code, true);
        if (shape != null) return shape;

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        try (Connection c = dataSource.getConnection()) {
            Integer studyId = studyIdOf(c, studyOid);
            if (studyId == null || !belongsToStudy(c, id, studyId)) {
                return ResponseEntity.status(404).body(Map.of("message", "no modality " + id + " in " + studyOid));
            }
            if (codeExists(c, studyId, code, id)) {
                return ResponseEntity.status(409).body(Map.of(
                        "message", "This study already has a modality with code '" + code + "'."));
            }
            ModalityDto before = loadOne(c, id);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE imaging_modality SET code=?, label_de=?, label_en=?, device=?, "
                            + "kinds_accepted=?, laterality_required=?, auto_match_ae_title=?, "
                            + "ordinal=?, date_updated=NOW(), updated_by_user_id=? "
                            + " WHERE imaging_modality_id=?")) {
                ps.setString(1, code);
                ps.setString(2, trim(body.labelDe()));
                ps.setString(3, trim(body.labelEn()));
                ps.setString(4, emptyToNull(body.device()));
                ps.setString(5, normaliseKinds(body.kindsAccepted()));
                ps.setBoolean(6, Boolean.TRUE.equals(body.lateralityRequired()));
                ps.setString(7, emptyToNull(body.autoMatchAeTitle()));
                ps.setInt(8, body.ordinal() == null ? 0 : body.ordinal());
                ps.setInt(9, user.getId());
                ps.setInt(10, id);
                ps.executeUpdate();
            }
            emitAudit(c, AuditTypeIds.IMAGING_MODALITY_UPDATED, user.getId(), id, code,
                    before == null ? "" : describe(before), describe(body));
            return ResponseEntity.ok(loadOne(c, id));
        } catch (SQLException e) {
            LOG.error("imaging-modality update failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not update the modality"));
        }
    }

    // ----- DELETE (retire) -----

    /**
     * Retire a modality.
     *
     * <p>A status change rather than a DELETE: files filed under it keep naming
     * it, and an audit row explaining a CRF value has to stay resolvable after
     * somebody tidies the catalogue. A retired modality ticks nothing — the
     * ticker only reads active rows — which is what retiring it means.
     */
    @DeleteMapping(value = "/{id:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retire(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("id") int id, HttpSession session) {
        ResponseEntity<?> guard = guardWrite(session);
        if (guard != null) return guard;

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        try (Connection c = dataSource.getConnection()) {
            Integer studyId = studyIdOf(c, studyOid);
            if (studyId == null || !belongsToStudy(c, id, studyId)) {
                return ResponseEntity.status(404).body(Map.of("message", "no modality " + id + " in " + studyOid));
            }
            int n;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE imaging_modality SET status_id = 5, date_updated = NOW(), "
                            + "updated_by_user_id = ? WHERE imaging_modality_id = ? AND status_id = 1")) {
                ps.setInt(1, user.getId());
                ps.setInt(2, id);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                return ResponseEntity.status(409).body(Map.of("message", "modality " + id + " is already retired"));
            }
            emitAudit(c, AuditTypeIds.IMAGING_MODALITY_UPDATED, user.getId(), id, "", "active", "retired");
            return ResponseEntity.ok(Map.of("id", id, "statusId", 5));
        } catch (SQLException e) {
            LOG.error("imaging-modality retire failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not retire the modality"));
        }
    }

    // ----- bindings -----

    /**
     * Add or replace the binding for one (role, eye) of a modality.
     *
     * <p>Upsert rather than create: the unique key is (modality, role, eye),
     * so "set the performed box for OD" is one operation whether or not one
     * was set before. An admin editing a typo should not have to delete first.
     */
    @PutMapping(value = "/{id:[0-9]+}/bindings", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> putBinding(@PathVariable("studyOid") String studyOid,
                                        @PathVariable("id") int id,
                                        @RequestBody(required = false) BindingWriteRequest body,
                                        HttpSession session) {
        ResponseEntity<?> guard = guardWrite(session);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Request body is required."));
        }
        String role = lower(body.role());
        String laterality = upper(body.laterality());
        String itemOid = trim(body.itemOid());
        if (!ROLES.contains(role)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "role must be one of " + ROLES));
        }
        if (laterality.isEmpty()) laterality = "OU";
        if (!LATERALITIES.contains(laterality)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "laterality must be one of " + LATERALITIES));
        }
        if (itemOid.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "itemOid is required."));
        }
        // Checked because a binding that points at nothing does not fail
        // loudly — it silently stops ticking, which reads as "not performed".
        ArrayList<ItemBean> matches = new ItemDAO(dataSource).findByOid(itemOid);
        if (matches == null || matches.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Unknown item OID '" + itemOid + "'."));
        }

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        try (Connection c = dataSource.getConnection()) {
            Integer studyId = studyIdOf(c, studyOid);
            if (studyId == null || !belongsToStudy(c, id, studyId)) {
                return ResponseEntity.status(404).body(Map.of("message", "no modality " + id + " in " + studyOid));
            }
            String previous = existingBindingOid(c, id, role, laterality);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO imaging_modality_item_binding "
                            + "(imaging_modality_id, role, laterality, item_oid, performed_value, created_by_user_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (imaging_modality_id, role, laterality) DO UPDATE "
                            + "   SET item_oid = EXCLUDED.item_oid, "
                            + "       performed_value = EXCLUDED.performed_value")) {
                ps.setInt(1, id);
                ps.setString(2, role);
                ps.setString(3, laterality);
                ps.setString(4, itemOid);
                ps.setString(5, defaultIfBlank(body.performedValue(), "1"));
                ps.setInt(6, user.getId());
                ps.executeUpdate();
            }
            // Which box a camera ticks changed from this moment on; without
            // this row, "why did this value appear" has no answer later.
            emitAudit(c, AuditTypeIds.IMAGING_MODALITY_BINDING_CHANGED, user.getId(), id,
                    role + "/" + laterality, previous == null ? "" : previous, itemOid);
            return ResponseEntity.ok(loadOne(c, id));
        } catch (SQLException e) {
            LOG.error("imaging-modality binding write failed for {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not save the binding"));
        }
    }

    @DeleteMapping(value = "/{id:[0-9]+}/bindings/{bindingId:[0-9]+}",
                   produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteBinding(@PathVariable("studyOid") String studyOid,
                                           @PathVariable("id") int id,
                                           @PathVariable("bindingId") int bindingId,
                                           HttpSession session) {
        ResponseEntity<?> guard = guardWrite(session);
        if (guard != null) return guard;

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        try (Connection c = dataSource.getConnection()) {
            Integer studyId = studyIdOf(c, studyOid);
            if (studyId == null || !belongsToStudy(c, id, studyId)) {
                return ResponseEntity.status(404).body(Map.of("message", "no modality " + id + " in " + studyOid));
            }
            String removed = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM imaging_modality_item_binding "
                            + " WHERE imaging_modality_item_binding_id = ? AND imaging_modality_id = ? "
                            + "RETURNING role || '/' || laterality || ' → ' || item_oid")) {
                ps.setInt(1, bindingId);
                ps.setInt(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) removed = rs.getString(1);
                }
            }
            if (removed == null) {
                return ResponseEntity.status(404).body(Map.of("message", "no binding " + bindingId));
            }
            emitAudit(c, AuditTypeIds.IMAGING_MODALITY_BINDING_CHANGED, user.getId(), id,
                    "removed", removed, "");
            return ResponseEntity.ok(loadOne(c, id));
        } catch (SQLException e) {
            LOG.error("imaging-modality binding delete failed for {}: {}", bindingId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "could not remove the binding"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* guards                                                              */
    /* ------------------------------------------------------------------ */

    /** Anyone signed in may read the catalogue; it names no patient. */
    private static ResponseEntity<?> guardRead(HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        return null;
    }

    /**
     * Changing the catalogue changes what the platform writes into CRFs, so it
     * is a data manager's or an administrator's to change.
     */
    private static ResponseEntity<?> guardWrite(HttpSession session) {
        ResponseEntity<?> unauthenticated = guardRead(session);
        if (unauthenticated != null) return unauthenticated;
        var role = (at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean)
                session.getAttribute("userRole");
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        boolean admin = user != null && user.isSysAdmin();
        boolean dataManager = role != null && role.getRole() != null
                && role.getRole().getId()
                   == at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role.STUDYDIRECTOR.getId();
        if (!admin && !dataManager) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Your role may not change a study's imaging catalogue"));
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* persistence                                                         */
    /* ------------------------------------------------------------------ */

    private static Integer studyIdOf(Connection c, String studyOid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_id FROM study WHERE oc_oid = ?")) {
            ps.setString(1, studyOid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.valueOf(rs.getInt(1)) : null;
            }
        }
    }

    private static boolean belongsToStudy(Connection c, int modalityId, int studyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM imaging_modality WHERE imaging_modality_id = ? AND study_id = ?")) {
            ps.setInt(1, modalityId);
            ps.setInt(2, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Collides on retired rows too — the unique constraint does not care. */
    private static boolean codeExists(Connection c, int studyId, String code, int excludeId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM imaging_modality WHERE study_id = ? AND upper(code) = upper(?) "
                        + "  AND imaging_modality_id <> ?")) {
            ps.setInt(1, studyId);
            ps.setString(2, code);
            ps.setInt(3, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int insert(Connection c, int studyId, String code,
                              ModalityWriteRequest b, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO imaging_modality (study_id, code, label_de, label_en, device, "
                        + "kinds_accepted, laterality_required, auto_match_ae_title, ordinal, "
                        + "status_id, created_by_user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?) RETURNING imaging_modality_id")) {
            ps.setInt(1, studyId);
            ps.setString(2, code);
            ps.setString(3, trim(b.labelDe()));
            ps.setString(4, trim(b.labelEn()));
            ps.setString(5, emptyToNull(b.device()));
            ps.setString(6, normaliseKinds(b.kindsAccepted()));
            ps.setBoolean(7, Boolean.TRUE.equals(b.lateralityRequired()));
            ps.setString(8, emptyToNull(b.autoMatchAeTitle()));
            ps.setInt(9, b.ordinal() == null ? 0 : b.ordinal());
            ps.setInt(10, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String existingBindingOid(Connection c, int modalityId, String role,
                                             String laterality) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_oid FROM imaging_modality_item_binding "
                        + " WHERE imaging_modality_id = ? AND role = ? AND laterality = ?")) {
            ps.setInt(1, modalityId);
            ps.setString(2, role);
            ps.setString(3, laterality);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<ModalityDto> loadAll(Connection c, int studyId) throws SQLException {
        Map<Integer, List<BindingDto>> bindings = loadBindings(c, studyId);
        List<ModalityDto> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT imaging_modality_id, code, label_de, label_en, device, kinds_accepted, "
                        + "laterality_required, auto_match_ae_title, ordinal, status_id "
                        + "  FROM imaging_modality WHERE study_id = ? ORDER BY ordinal, code")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    out.add(new ModalityDto(id, rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getString(8),
                            rs.getInt(9), rs.getInt(10),
                            bindings.getOrDefault(id, List.of())));
                }
            }
        }
        return out;
    }

    private static Map<Integer, List<BindingDto>> loadBindings(Connection c, int studyId)
            throws SQLException {
        Map<Integer, List<BindingDto>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT b.imaging_modality_id, b.imaging_modality_item_binding_id, b.role, "
                        + "b.laterality, b.item_oid, b.performed_value "
                        + "  FROM imaging_modality_item_binding b "
                        + "  JOIN imaging_modality im ON im.imaging_modality_id = b.imaging_modality_id "
                        + " WHERE im.study_id = ? "
                        + " ORDER BY b.role, b.laterality")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>())
                       .add(new BindingDto(rs.getInt(2), rs.getString(3), rs.getString(4),
                               rs.getString(5), rs.getString(6)));
                }
            }
        }
        return out;
    }

    private static ModalityDto loadOne(Connection c, int id) throws SQLException {
        Integer studyId;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_id FROM imaging_modality WHERE imaging_modality_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                studyId = rs.next() ? rs.getInt(1) : null;
            }
        }
        if (studyId == null) return null;
        return loadAll(c, studyId).stream().filter(m -> m.id() == id).findFirst().orElse(null);
    }

    /** Never throws: a saved change must not be undone by a failed log. */
    private static void emitAudit(Connection c, int type, int userId, int modalityId,
                                  String entityName, String oldValue, String newValue) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                        + "audit_table, entity_id, entity_name, old_value, new_value) "
                        + "VALUES (?, now(), ?, 'imaging_modality', ?, ?, ?, ?)")) {
            ps.setInt(1, type);
            ps.setInt(2, userId);
            ps.setInt(3, modalityId);
            ps.setString(4, entityName);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not audit imaging-modality change {}: {}", modalityId, e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* validation + shaping                                                */
    /* ------------------------------------------------------------------ */

    private ResponseEntity<?> validate(ModalityWriteRequest b, String code, boolean requireLabels) {
        if (code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "code is required."));
        }
        if (code.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("message", "code must be ≤ 64 characters."));
        }
        if (requireLabels && (trim(b.labelDe()).isEmpty() || trim(b.labelEn()).isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "labelDe and labelEn are required."));
        }
        for (String k : splitKinds(b.kindsAccepted())) {
            if (!KINDS.contains(k)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "kindsAccepted must be a comma-separated subset of " + KINDS));
            }
        }
        return null;
    }

    private static List<String> splitKinds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String k = part.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    /** Stored lower-case and de-spaced so the ticker's comparison is simple. */
    private static String normaliseKinds(String raw) {
        return String.join(",", splitKinds(raw));
    }

    private static String normaliseCode(String raw) {
        return trim(raw).toUpperCase(Locale.ROOT);
    }

    private static String describe(ModalityWriteRequest b) {
        return "code=" + trim(b.code()) + ";device=" + trim(b.device())
                + ";kinds=" + normaliseKinds(b.kindsAccepted())
                + ";ae=" + trim(b.autoMatchAeTitle());
    }

    private static String describe(ModalityDto d) {
        return "code=" + d.code() + ";device=" + (d.device() == null ? "" : d.device())
                + ";kinds=" + (d.kindsAccepted() == null ? "" : d.kindsAccepted())
                + ";ae=" + (d.autoMatchAeTitle() == null ? "" : d.autoMatchAeTitle());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String lower(String s) {
        return trim(s).toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return trim(s).toUpperCase(Locale.ROOT);
    }

    private static String emptyToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }

    private static String defaultIfBlank(String s, String fallback) {
        String t = trim(s);
        return t.isEmpty() ? fallback : t;
    }
}
