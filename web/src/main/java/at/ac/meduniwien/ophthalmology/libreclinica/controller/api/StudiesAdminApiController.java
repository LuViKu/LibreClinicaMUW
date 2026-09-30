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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Every study on the platform, for the system administrator: the SPA
 * replacement of the legacy {@code /ListStudy} ("Administer Studies").
 *
 * <p>{@code GET /api/v1/studies} answers "where am I bound?" and is
 * empty for an administrator who holds no study role. This endpoint
 * answers "what is there?": every top-level study with its sites nested
 * under it, removed ones included, with their status. The admin study
 * list, the study picker and the role-grant picker all read it for a
 * system administrator.
 *
 * <p>Sysadmin only, like {@code ListStudyServlet}: anonymous 401,
 * anyone else 403.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Studies", description = "User's available studies.")
public class StudiesAdminApiController {

    private final DataSource dataSource;

    @Autowired
    public StudiesAdminApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping(value = "/studies", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = AdminStudyDto.class)))
    public ResponseEntity<?> list(HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (!me.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Only a system administrator may list every study"));
        }

        // findAll is ordered by name, so parents and the sites under each
        // come out alphabetically.
        List<StudyBean> all = new StudyDAO(dataSource).findAll();
        Map<Integer, List<AdminStudyDto>> sitesByParent = new LinkedHashMap<>();
        Map<Integer, String> oidById = new LinkedHashMap<>();
        for (StudyBean s : all) oidById.put(s.getId(), s.getOid());
        for (StudyBean s : all) {
            if (s.getParentStudyId() > 0) {
                sitesByParent.computeIfAbsent(s.getParentStudyId(), k -> new ArrayList<>())
                        .add(toDto(s, oidById.get(s.getParentStudyId()), List.of()));
            }
        }
        List<AdminStudyDto> out = new ArrayList<>();
        for (StudyBean s : all) {
            if (s.getParentStudyId() > 0) continue;
            out.add(toDto(s, null, sitesByParent.getOrDefault(s.getId(), List.of())));
        }
        return ResponseEntity.ok(out);
    }

    private static AdminStudyDto toDto(StudyBean s, String parentOid, List<AdminStudyDto> sites) {
        return new AdminStudyDto(
                s.getOid(),
                s.getName(),
                s.getIdentifier(),
                s.getPrincipalInvestigator(),
                s.getCreatedDate() == null ? null
                        : java.time.Instant.ofEpochMilli(s.getCreatedDate().getTime())
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate().toString(),
                statusKey(s.getStatus()),
                parentOid,
                sites);
    }

    /** A locale-independent key; {@code Status.getName()} is localised. */
    static String statusKey(Status status) {
        if (status == null) return "UNKNOWN";
        return switch (status.getId()) {
            case 1 -> "AVAILABLE";
            case 2 -> "UNAVAILABLE";
            case 3 -> "PRIVATE";
            case 4 -> "PENDING";
            case 5 -> "REMOVED";
            case 6 -> "LOCKED";
            case 7 -> "AUTO_REMOVED";
            case 9 -> "FROZEN";
            default -> "UNKNOWN";
        };
    }
}
