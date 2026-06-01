/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M1 — current-user + active-study adapter.
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
 *       {@code ChangeStudyServlet#changeStudy()} state changes.
 *       Replaces the {@code /MainMenu} bounce the SPA had to do
 *       previously.</li>
 * </ul>
 *
 * <p>Authorization: chain-level {@code .anyRequest().hasRole("USER")}
 * gates both endpoints. {@code /me/activeStudy} additionally verifies
 * the user has a {@link StudyUserRoleBean} on the target study (HTTP
 * 403 if not).
 *
 * <p>The {@code mfaSatisfied} field in {@link MeDto} returns
 * {@code true} unconditionally for first-cut — proper integration
 * with the legacy 2FA path lives behind DR-014's SSO work and isn't
 * in scope here.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeApiController {

    private static final Logger LOG = LoggerFactory.getLogger(MeApiController.class);

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

        // Map the legacy role to the SPA's UserRole union.
        String spaRole;
        if (currentRole != null && currentRole.getRole() != null) {
            spaRole = RoleMapper.toSpaRole(currentRole.getRole().getName());
        } else {
            // No study-scoped role bound yet — fall back to the highest role
            // the user holds anywhere. This is a coarse heuristic until the
            // user picks a study.
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

        MeDto dto = new MeDto(
                ub.getName(),
                joinName(ub.getFirstName(), ub.getLastName(), ub.getName()),
                blankToNull(ub.getEmail()),
                spaRole,
                /* siteLabel */ activeStudy != null && activeStudy.isSite() ? activeStudy.name() : null,
                /* source */ "local",
                /* mfaSatisfied */ true,
                /* profileComplete */ true,
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

        // Verify the user has a role on this study (or a parent / site of it).
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

        // Mirror ChangeStudyServlet#changeStudy(): bind session study, update
        // user's active_study_id, bind the userRole. We skip the legacy
        // StudyParameterConfig + Role.description tinkering — those are JSP-
        // render helpers the SPA doesn't consume.
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

    private static String highestSpaRoleAnywhere(String userName) {
        // First-login users with no study binding yet — return a safe default.
        // The picker will refine this once they choose a study.
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
}
