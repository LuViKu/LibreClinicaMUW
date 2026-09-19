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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudyBindings;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

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
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * P3.5 — what a study does, and which items it means, as settings.
 *
 * <p>Whether a study receives DICOM, accepts uploads, runs inference: until
 * now those lived in {@code datainfo.properties} on the server, so changing
 * one meant a file edit and a restart. Which CRF item an automatic write lands
 * in was worse — it was a literal in shared code.
 *
 * <p>Both are administered here. The response distinguishes what a study has
 * <em>set</em> from what it currently <em>resolves to</em>, because those are
 * different facts: an unset key means "as before", and an administrator
 * deciding whether to change something needs to see which of the two they are
 * looking at.
 *
 * <p>Writes are audited. A study that stops receiving DICOM because somebody
 * flipped a switch looks, from the inbox, exactly like a camera that stopped
 * sending — and the difference is an audit row.
 */
@RestController
@RequestMapping("/api/v1/studies/{studyOid}/settings")
@Tag(name = "Study settings",
     description = "Per-study switches and CRF item bindings (P3.5).")
public class StudySettingsApiController {

    private static final Logger LOG = LoggerFactory.getLogger(StudySettingsApiController.class);

    /**
     * The keys this release understands. An unknown key is refused.
     *
     * <p>Held in the service, not here: {@code /me} enumerates the same list
     * to tell the SPA what the active study does, and two lists would drift
     * the first time a key was added.
     */
    private static final Set<String> KNOWN_SETTINGS =
            Set.copyOf(StudySettingService.KNOWN_KEYS);

    private final DataSource dataSource;

    @Autowired
    public StudySettingsApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ----- DTOs -----

    /**
     * @param value    what this study has set, or null when it has set nothing
     * @param resolved what the platform will actually use — the study's value,
     *                 its parent's, the configuration it replaces, or the code
     *                 default
     */
    public record SettingDto(String key, String value, String resolved) {}

    public record SettingsDto(String studyOid, List<SettingDto> settings,
                              Map<String, String> itemBindings) {}

    public record WriteRequest(Map<String, String> settings, Map<String, String> itemBindings) {}

    // ----- GET -----

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable("studyOid") String studyOid, HttpSession session) {
        ResponseEntity<?> guard = guardRead(session);
        if (guard != null) return guard;

        Integer studyId = studyIdOf(studyOid);
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of("message", "no study " + studyOid));
        }
        StudySettingService svc = new StudySettingService(dataSource);
        Map<String, String> set = svc.allFor(studyId);

        List<SettingDto> out = new java.util.ArrayList<>();
        for (String key : KNOWN_SETTINGS.stream().sorted().toList()) {
            out.add(new SettingDto(key, set.get(key), svc.resolve(studyId, key)));
        }
        return ResponseEntity.ok(new SettingsDto(studyOid, out,
                new StudyBindings(dataSource).forStudy(studyId)));
    }

    // ----- PUT -----

    /**
     * Set or clear settings and bindings together.
     *
     * <p>A null value clears, restoring "as before" rather than storing
     * today's default — the two differ the moment the default does.
     *
     * <p>Applied one at a time rather than transactionally: each key is an
     * independent decision, and one rejected key should not silently discard
     * the others an administrator submitted alongside it. The response says
     * what was applied.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> put(@PathVariable("studyOid") String studyOid,
                                 @RequestBody(required = false) WriteRequest body,
                                 HttpSession session) {
        ResponseEntity<?> guard = guardWrite(session);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Request body is required."));
        }
        Integer studyId = studyIdOf(studyOid);
        if (studyId == null) {
            return ResponseEntity.status(404).body(Map.of("message", "no study " + studyOid));
        }

        // An unknown key is refused rather than stored: a typo that is quietly
        // accepted reads, forever after, as a setting that does nothing.
        if (body.settings() != null) {
            for (String key : body.settings().keySet()) {
                if (!KNOWN_SETTINGS.contains(key)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "Unknown setting '" + key + "'."));
                }
            }
        }

        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        StudySettingService svc = new StudySettingService(dataSource);
        StudyBindings bindings = new StudyBindings(dataSource);
        Map<String, String> before = svc.allFor(studyId);
        List<String> applied = new java.util.ArrayList<>();

        try {
            if (body.settings() != null) {
                for (Map.Entry<String, String> e : body.settings().entrySet()) {
                    svc.put(studyId, e.getKey(), blankToNull(e.getValue()), user.getId());
                    audit(AuditTypeIds.STUDY_SETTING_CHANGED, user.getId(), studyId, e.getKey(),
                            before.get(e.getKey()), e.getValue());
                    applied.add(e.getKey());
                }
            }
            if (body.itemBindings() != null) {
                Map<String, String> bindingsBefore = bindings.forStudy(studyId);
                for (Map.Entry<String, String> e : body.itemBindings().entrySet()) {
                    bindings.put(studyId, e.getKey(), blankToNull(e.getValue()), user.getId());
                    audit(AuditTypeIds.STUDY_SETTING_CHANGED, user.getId(), studyId, "binding:" + e.getKey(),
                            bindingsBefore.get(e.getKey()), e.getValue());
                    applied.add("binding:" + e.getKey());
                }
            }
        } catch (SQLException ex) {
            LOG.error("study-settings write failed for {}: {}", studyOid, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "could not save the settings"));
        }
        LOG.info("study settings changed: study={} keys={} by={}",
                studyOid, applied, user.getName());
        return get(studyOid, session);
    }

    /* ------------------------------------------------------------------ */

    /** Anyone signed in may read: these name switches and OIDs, not patients. */
    private static ResponseEntity<?> guardRead(HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        return null;
    }

    /**
     * Changing these decides whether a study receives images at all and where
     * an automatic write lands, so it is an administrator's or a data
     * manager's.
     */
    private static ResponseEntity<?> guardWrite(HttpSession session) {
        ResponseEntity<?> unauthenticated = guardRead(session);
        if (unauthenticated != null) return unauthenticated;
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean role = (StudyUserRoleBean) session.getAttribute("userRole");
        boolean admin = user != null && user.isSysAdmin();
        boolean dataManager = role != null && role.getRole() != null
                && role.getRole().getId() == Role.STUDYDIRECTOR.getId();
        if (!admin && !dataManager) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Your role may not change a study's settings"));
        }
        return null;
    }

    private Integer studyIdOf(String studyOid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_id FROM study WHERE oc_oid = ?")) {
            ps.setString(1, studyOid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.valueOf(rs.getInt(1)) : null;
            }
        } catch (SQLException e) {
            LOG.warn("study lookup failed for {}: {}", studyOid, e.getMessage());
            return null;
        }
    }

    /** Never throws: a saved setting must not be undone by a failed log. */
    private void audit(int type, int userId, int studyId, String key,
                       String oldValue, String newValue) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                             + "audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study_setting', ?, ?, ?, ?)")) {
            ps.setInt(1, type);
            ps.setInt(2, userId);
            ps.setInt(3, studyId);
            ps.setString(4, key);
            ps.setString(5, oldValue == null ? "" : oldValue);
            ps.setString(6, newValue == null ? "" : newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not audit study-setting change {}/{}: {}", studyId, key, e.getMessage());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
