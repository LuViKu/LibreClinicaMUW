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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M1 + E.5 B1 — current-user + active-study + profile adapter.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /pages/api/v1/me} — returns the authenticated user
 *       and the bound active study (if any). The SPA calls this on
 *       app boot to populate the auth store, replacing the previous
 *       sessionStorage-based mock identity hydration.</li>
 *   <li>{@code POST /pages/api/v1/me/activeStudy} — binds the
 *       session-scoped {@code "study"} attribute to the requested
 *       study OID, mirroring the legacy
 *       {@code ChangeStudyServlet#changeStudy()} state changes.</li>
 *   <li>{@code PUT /pages/api/v1/me/profile} — persists the user's
 *       self-edited profile fields ({@code displayName}, {@code
 *       locale}, {@code timezone}). Mirrors the regulatory scope of
 *       the legacy {@code UpdateProfileServlet} but writes only the
 *       three fields the SPA's first-login wizard exposes.</li>
 * </ul>
 *
 * <p>Authorization: chain-level {@code .anyRequest().hasRole("USER")}
 * gates all three. {@code /me/activeStudy} additionally verifies the
 * user has a {@link StudyUserRoleBean} on the target study (HTTP 403
 * if not).
 *
 * <p>{@code mfaSatisfied} returns {@code true} unconditionally for
 * first-cut — proper integration with the legacy 2FA path lives
 * behind DR-014's SSO work and isn't in scope here.
 *
 * <p><strong>B1 implementation note — why a direct JDBC UPDATE
 * instead of {@code UserAccountDAO.update()}:</strong> the legacy
 * DAO's UPDATE SQL writes 26 columns at once (everything the
 * UpdateProfileServlet touches plus password/2FA/api-key state).
 * Re-marshalling the bean and shipping every column on a profile
 * edit is a foot-gun if anything else mutated the bean between
 * load and save. The PUT path scopes its writes to exactly the
 * three columns the SPA's first-login wizard claims to own —
 * first_name, locale, time_zone. {@code last_name} stays empty on
 * SPA-managed users; admins can still set it via the legacy servlet
 * if they need a separate sortable surname column.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "Current user + active study + profile.")
public class MeApiController {

    private static final Logger LOG = LoggerFactory.getLogger(MeApiController.class);

    /** Locales the SPA's first-login picker offers; mirrors the i18n bundle list. */
    private static final List<String> ALLOWED_LOCALES = List.of("de-AT", "de", "en");

    /**
     * audit_log_event_type_id for user-account profile edits via the SPA.
     * Seeded by {@code lc-muw-2026-06-03-audit-event-type-user-profile.xml}.
     * Postgres has no audit trigger on {@code user_account} updates (only
     * the Oracle-only sequence-id trigger {@code user_account_bef_trg}
     * exists, and that's for ID generation, not auditing). The PUT
     * endpoint therefore emits one row per modified field directly,
     * matching the shape the {@code study_subject_trigger} writes for
     * study_subject column changes (audit_table / entity_id / entity_name
     * / old_value / new_value).
     */
    private static final int AUDIT_TYPE_USER_PROFILE_UPDATED = 50;

    private final DataSource dataSource;

    @Autowired
    public MeApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = MeDto.class)))
    public ResponseEntity<?> getMe(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        String spaRole;
        if (currentRole != null && currentRole.getRole() != null) {
            spaRole = RoleMapper.toSpaRole(currentRole.getRole().getName());
        } else {
            spaRole = highestSpaRoleAnywhere(ub.getName());
        }

        MeDto.ActiveStudyDto activeStudy = null;
        if (currentStudy != null && currentStudy.getId() > 0) {
            activeStudy = new MeDto.ActiveStudyDto(
                    currentStudy.getOid(),
                    currentStudy.getName(),
                    currentStudy.getParentStudyId() > 0
            );
        }

        String locale = readLocale(ub.getId());
        String timezone = blankToNull(ub.getTime_zone());

        MeDto dto = new MeDto(
                ub.getName(),
                joinName(ub.getFirstName(), ub.getLastName(), ub.getName()),
                blankToNull(ub.getEmail()),
                spaRole,
                /* siteLabel */ activeStudy != null && activeStudy.isSite() ? activeStudy.name() : null,
                /* source */ "local",
                /* mfaSatisfied */ true,
                /* profileComplete */ true,
                locale,
                timezone,
                activeStudy
        );

        return ResponseEntity.ok(dto);
    }

    @PostMapping("/activeStudy")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = MeDto.class)))
    public ResponseEntity<?> setActiveStudy(@RequestBody ActiveStudyRequest body, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null || body.oid() == null || body.oid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing 'oid' in request body"));
        }

        StudyDAO studyDAO = new StudyDAO(dataSource);
        StudyBean target = studyDAO.findByOid(body.oid());
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + body.oid() + "'"));
        }

        UserAccountDAO userDAO = new UserAccountDAO(dataSource);
        ArrayList<StudyBean> allStudies = studyDAO.findAll();
        ArrayList<StudyUserRoleBean> roles = userDAO.findStudyByUser(ub.getName(), allStudies);
        StudyUserRoleBean grantedRole = null;
        for (StudyUserRoleBean r : roles) {
            if (r.getStudyId() == target.getId()) {
                grantedRole = r;
                break;
            }
        }
        if (grantedRole == null) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "User has no role on study '" + body.oid() + "'"));
        }

        session.setAttribute("study", target);
        ub.setActiveStudyId(target.getId());
        ub.setUpdater(ub);
        ub.setUpdatedDate(new java.util.Date());
        userDAO.update(ub);
        session.setAttribute("userBean", ub);
        session.setAttribute("userRole", grantedRole);

        LOG.info("Active study bound to oid={} (study_id={}) for user={}",
                target.getOid(), target.getId(), ub.getName());

        return getMe(session);
    }

    @PutMapping("/profile")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = MeDto.class)))
    public ResponseEntity<?> updateProfile(@RequestBody ProfileUpdateRequest body, HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Empty request body"));
        }

        List<Map<String, String>> errors = new ArrayList<>();
        String displayName = body.displayName() == null ? null : body.displayName().trim();
        if (displayName == null || displayName.isEmpty()) {
            errors.add(Map.of("field", "displayName", "message", "Display name is required"));
        } else if (displayName.length() > 50) {
            errors.add(Map.of("field", "displayName", "message",
                    "Display name must be 50 characters or fewer"));
        }

        String locale = body.locale() == null ? null : body.locale().trim();
        if (locale == null || locale.isEmpty()) {
            errors.add(Map.of("field", "locale", "message", "Locale is required"));
        } else if (!ALLOWED_LOCALES.contains(locale)) {
            errors.add(Map.of("field", "locale", "message",
                    "Locale must be one of: " + String.join(", ", ALLOWED_LOCALES)));
        }

        String timezone = body.timezone() == null ? null : body.timezone().trim();
        if (timezone == null || timezone.isEmpty()) {
            errors.add(Map.of("field", "timezone", "message", "Timezone is required"));
        } else if (!isValidTimezone(timezone)) {
            errors.add(Map.of("field", "timezone", "message",
                    "Timezone is not a recognised IANA zone id"));
        }

        if (!errors.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("message", "Validation failed");
            resp.put("errors", errors);
            return ResponseEntity.badRequest().body(resp);
        }

        // Capture the BEFORE state so the audit row records the actual
        // diff. first_name + time_zone come straight off the session bean
        // (those columns are session-mirrored on login); locale isn't on
        // the bean — fetch it from the DB the same way GET /me does.
        // Capture before the UPDATE runs so a partial failure can't show
        // post-update values as "old".
        String oldFirstName = nullToEmpty(ub.getFirstName());
        String oldLocale = nullToEmpty(readLocale(ub.getId()));
        String oldTimezone = nullToEmpty(ub.getTime_zone());

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE user_account SET first_name = ?, locale = ?, time_zone = ?, "
                             + "update_id = ?, date_updated = now() WHERE user_id = ?")) {
            ps.setString(1, displayName);
            ps.setString(2, locale);
            ps.setString(3, timezone);
            ps.setInt(4, ub.getId());
            ps.setInt(5, ub.getId());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                LOG.warn("Profile update affected 0 rows for user_id={}", ub.getId());
                return ResponseEntity.status(404).body(Map.of("message",
                        "User row not found — session may be stale, please re-authenticate"));
            }
        } catch (SQLException e) {
            LOG.error("Failed to update profile for user_id={}", ub.getId(), e);
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist profile: " + e.getMessage()));
        }

        // Emit one audit_log_event row per field that actually changed.
        // Postgres has no trigger on user_account updates (only the
        // Oracle-only sequence trigger user_account_bef_trg exists), so
        // the regulatory trail is the controller's responsibility. The
        // row shape mirrors study_subject_trigger: audit_table /
        // entity_id / entity_name / old_value / new_value, with
        // audit_log_event_type_id = 50 ("user_account_profile_updated").
        // The actor is the same as the target since /me is self-edit.
        // Failures here are logged but do NOT roll back the UPDATE —
        // losing an audit row is annoying; losing the user's profile
        // edit on top of an audit-write hiccup is worse.
        emitProfileAudit(ub.getId(), "first_name", oldFirstName, displayName);
        emitProfileAudit(ub.getId(), "locale",     oldLocale,    locale);
        emitProfileAudit(ub.getId(), "time_zone",  oldTimezone,  timezone);

        // Refresh the session-bound bean so subsequent /me requests
        // see the new state without round-tripping the auth flow.
        ub.setFirstName(displayName);
        ub.setTime_zone(timezone);
        session.setAttribute("userBean", ub);

        LOG.info("Profile updated for user={} (locale={}, timezone={})",
                ub.getName(), locale, timezone);

        return getMe(session);
    }

    /**
     * Insert one {@code audit_log_event} row when the given column's
     * old/new pair differs. Skips no-op writes (no row when the user
     * submitted the same value they already had).
     *
     * <p>Direct JDBC because {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO#create}
     * only persists {@code audit_table / user_id / entity_id /
     * reason_for_change / action_message} — it drops the {@code
     * audit_log_event_type_id / old_value / new_value / entity_name}
     * fields that an audit row needs to be parseable by the SPA's
     * Audit Log view (see the comment block at AuditEventDAO line 200ff:
     * "new query needs to be ..." — never landed). Using raw SQL here
     * matches what the existing {@code study_subject_trigger} writes.
     */
    private void emitProfileAudit(int userId, String columnName,
                                  String oldValue, String newValue) {
        if (oldValue.equals(newValue)) {
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'user_account', ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_USER_PROFILE_UPDATED);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setString(4, columnName);
            ps.setString(5, oldValue);
            ps.setString(6, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Don't propagate — the profile update has already
            // succeeded; a missed audit row shouldn't make the user
            // think the save failed.
            LOG.warn("Failed to write audit row for user_id={} column={}: {}",
                    userId, columnName, e.getMessage());
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private String readLocale(int userId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT locale FROM user_account WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    return (v == null || v.isBlank()) ? null : v;
                }
            }
        } catch (SQLException e) {
            LOG.debug("readLocale fallback for user_id={}: {}", userId, e.getMessage());
        }
        return null;
    }

    private static boolean isValidTimezone(String tz) {
        try {
            ZoneId.of(tz);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String highestSpaRoleAnywhere(String userName) {
        return "Investigator";
    }

    private static String joinName(String first, String last, String fallback) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String joined = (f + " " + l).trim();
        return joined.isEmpty() ? fallback : joined;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Body of POST /pages/api/v1/me/activeStudy. */
    public record ActiveStudyRequest(String oid) {}

    /** Body of PUT /pages/api/v1/me/profile. */
    public record ProfileUpdateRequest(
            String displayName,
            String locale,
            String timezone
    ) {}
}
