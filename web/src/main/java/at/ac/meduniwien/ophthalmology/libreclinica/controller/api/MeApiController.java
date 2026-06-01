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
public class MeApiController {

    private static final Logger LOG = LoggerFactory.getLogger(MeApiController.class);

    /** Locales the SPA's first-login picker offers; mirrors the i18n bundle list. */
    private static final List<String> ALLOWED_LOCALES = List.of("de-AT", "de", "en");

    private final DataSource dataSource;

    @Autowired
    public MeApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
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

        // Refresh the session-bound bean so subsequent /me requests
        // see the new state without round-tripping the auth flow.
        ub.setFirstName(displayName);
        ub.setTime_zone(timezone);
        session.setAttribute("userBean", ub);

        LOG.info("Profile updated for user={} (locale={}, timezone={})",
                ub.getName(), locale, timezone);

        return getMe(session);
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
