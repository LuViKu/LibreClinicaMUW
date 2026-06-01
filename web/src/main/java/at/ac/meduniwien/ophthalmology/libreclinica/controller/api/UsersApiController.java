/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M12 — Data-Manager users adapter.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /pages/api/v1/users?role=…&siteOid=…&active=…}
 *       — lists all users bound to the session-bound active study,
 *       one row per (user × study-role) assignment. Server-side
 *       filters narrow before the wire; the SPA additionally runs
 *       the same filters client-side for snappy dropdown UX.</li>
 * </ul>
 *
 * <p>Auth source derivation:
 * <ul>
 *   <li>{@code external_id_provider} non-blank → {@code sso}</li>
 *   <li>{@code authtype} contains "ldap" (case-insensitive) → {@code ldap}</li>
 *   <li>{@code last_visit_date} is null → {@code pending-invite}</li>
 *   <li>else → {@code local}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Study-user roster (Manage Users).")
public class UsersApiController {

    private static final Logger LOG = LoggerFactory.getLogger(UsersApiController.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public UsersApiController(@Qualifier("dataSource") DataSource dataSource,
                              SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "role", required = false) String roleFilter,
            @RequestParam(value = "siteOid", required = false) String siteOidFilter,
            @RequestParam(value = "active", required = false) Boolean activeFilter,
            HttpSession session) {

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }

        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);

        // A4 — per-site visibility. The legacy
        // findAllUsersByStudy(parent) already returns roles for the
        // parent + every site under it; for narrower views (Monitor
        // with one site grant) we filter the result by the user's
        // visible study set.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                me, currentStudy, currentRole);

        // Roles scoped to the active study + its sites. The DAO
        // returns one StudyUserRoleBean per (user, study) pair.
        ArrayList<StudyUserRoleBean> bindings = userDao.findAllUsersByStudy(currentStudy.getId());

        Map<Integer, UserAccountBean> userCache = new HashMap<>();
        Map<Integer, StudyBean> studyCache = new HashMap<>();
        List<StudyUserDto> out = new ArrayList<>();

        for (StudyUserRoleBean sur : bindings) {
            // A4 — skip bindings outside the visible study tree.
            if (!visibleStudyIds.contains(sur.getStudyId())) continue;

            UserAccountBean ua = userCache.computeIfAbsent(sur.getUserAccountId(),
                    id -> userDao.findByPK(id));
            if (ua == null || ua.getId() == 0) continue;

            StudyBean roleStudy = sur.getStudyId() > 0
                    ? studyCache.computeIfAbsent(sur.getStudyId(),
                        id -> (StudyBean) studyDao.findByPK(id))
                    : null;
            boolean isSite = roleStudy != null && roleStudy.getParentStudyId() > 0;

            String spaRole = sur.getRole() != null
                    ? RoleMapper.toSpaRole(sur.getRole().getName()) : "Investigator";
            String siteLabel = isSite && roleStudy != null ? roleStudy.getName() : null;
            String auth = authForUser(ua);
            String lastLogin = ua.getLastVisitDate() == null ? null
                    : ua.getLastVisitDate().toInstant().atZone(ZoneOffset.UTC)
                            .truncatedTo(ChronoUnit.SECONDS).toInstant().toString();
            boolean active = ua.getStatus() != null && ua.getStatus().getId() == Status.AVAILABLE.getId();

            if (roleFilter != null && !roleFilter.isBlank()
                    && !roleFilter.equalsIgnoreCase(spaRole)) continue;
            if (siteOidFilter != null && !siteOidFilter.isBlank()) {
                if (roleStudy == null || !siteOidFilter.equalsIgnoreCase(roleStudy.getOid())) continue;
            }
            if (activeFilter != null && active != activeFilter) continue;

            out.add(new StudyUserDto(
                    String.valueOf(ua.getId()),
                    nullToEmpty(ua.getName()),
                    displayName(ua),
                    blankToNull(ua.getEmail()),
                    spaRole,
                    siteLabel,
                    auth,
                    lastLogin,
                    active));
        }

        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private static String authForUser(UserAccountBean ua) {
        String provider = ua.getExternalIdProvider();
        if (provider != null && !provider.isBlank()) return "sso";
        String authtype = ua.getAuthtype();
        if (authtype != null && authtype.toLowerCase().contains("ldap")) return "ldap";
        if (ua.getLastVisitDate() == null) return "pending-invite";
        return "local";
    }

    private static String displayName(UserAccountBean ua) {
        String first = ua.getFirstName() == null ? "" : ua.getFirstName().trim();
        String last = ua.getLastName() == null ? "" : ua.getLastName().trim();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? nullToEmpty(ua.getName()) : joined;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
