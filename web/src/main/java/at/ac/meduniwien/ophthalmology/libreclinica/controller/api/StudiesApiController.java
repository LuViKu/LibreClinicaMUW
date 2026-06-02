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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M1 — list studies the current user has a role on.
 *
 * <p>{@code GET /pages/api/v1/studies}. Powers the SPA study-picker
 * shown after login when the user has no active study bound yet
 * (or wants to switch). Returns one row per (study × user-role)
 * pairing — for the seeded {@code root} account in the demo dataset
 * that's the Default Study with both an {@code admin} and a
 * {@code director} role grant. The SPA dedupes to one entry per
 * study, surfacing the highest-precedence role.
 *
 * <p>Each entry includes the role label translated into the SPA's
 * 5-value {@code UserRole} union so the picker can colour-code by
 * role chip without needing a second lookup.
 */
@RestController
@RequestMapping("/api/v1/studies")
@Tag(name = "Studies", description = "User's available studies.")
public class StudiesApiController {

    private final DataSource dataSource;

    @Autowired
    public StudiesApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = StudyOptionDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null || ub.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }

        StudyDAO studyDAO = new StudyDAO(dataSource);
        UserAccountDAO userDAO = new UserAccountDAO(dataSource);

        ArrayList<StudyBean> allStudies = studyDAO.findAll();
        ArrayList<StudyUserRoleBean> grants = userDAO.findStudyByUser(ub.getName(), allStudies);

        // Index studies by id for parent-name lookup, and by id for role join.
        Map<Integer, StudyBean> studyById = new HashMap<>();
        for (StudyBean s : allStudies) studyById.put(s.getId(), s);

        int activeStudyId = ub.getActiveStudyId();
        List<StudyOptionDto> out = new ArrayList<>();
        for (StudyUserRoleBean r : grants) {
            StudyBean s = studyById.get(r.getStudyId());
            if (s == null) continue;
            boolean isSite = s.getParentStudyId() > 0;
            StudyBean parent = isSite ? studyById.get(s.getParentStudyId()) : null;
            out.add(new StudyOptionDto(
                    s.getOid(),
                    s.getName(),
                    parent == null ? null : parent.getOid(),
                    parent == null ? null : parent.getName(),
                    r.getRole() == null ? "Investigator"
                            : RoleMapper.toSpaRole(r.getRole().getName()),
                    isSite,
                    s.getId() == activeStudyId
            ));
        }
        return ResponseEntity.ok(out);
    }
}
