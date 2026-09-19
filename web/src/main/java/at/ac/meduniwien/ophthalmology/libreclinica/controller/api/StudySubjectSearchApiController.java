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
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3.6 — subject lookup by label prefix, for every "find the patient" modal.
 *
 * <p>Moved out of {@code RetinalResultsApiController} unchanged: it has
 * nothing to do with retinal inference and never did — it happened to be
 * written there because the viewer's patient-search modal was the first
 * caller. The path is the same, so nothing that calls it notices.
 *
 * <p>The one thing that matters here is the visibility filter. The finder
 * searches the whole subject register, and the result is intersected with the
 * caller's own scope afterwards: a label from a study the user may not see is
 * dropped rather than reported, so the response cannot be used to probe for
 * the existence of subjects elsewhere.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Subject search",
     description = "Label-prefix lookup, scoped to what the caller may see.")
@SuppressWarnings("null")
public class StudySubjectSearchApiController {

    private final StudySubjectFinder studySubjectFinder;
    private final StudyResourceAccess access;

    @Autowired
    public StudySubjectSearchApiController(@Qualifier("dataSource") DataSource dataSource,
                                           SiteVisibilityFilter siteVisibilityFilter,
                                           StudySubjectFinder studySubjectFinder) {
        this.studySubjectFinder = studySubjectFinder;
        this.access = new StudyResourceAccess(dataSource, siteVisibilityFilter);
    }

    /**
     * Staff-portal label prefix search. Backs the "Patient suchen" modal.
     *
     * @param q     label prefix (case-insensitive); blank → empty list
     * @param limit hard ceiling on rows returned; clamped to [1, 50]
     */
    @GetMapping(path = "/study-subjects/search",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchSubjects(@RequestParam("q") String q,
                                            @RequestParam(value = "limit", defaultValue = "10") int limit,
                                            HttpSession session) {
        ResponseEntity<?> guard = access.guardSession(session);
        if (guard != null) return guard;

        if (studySubjectFinder == null) {
            // Defensive: the read-only test wiring may omit the finder. The
            // production wiring always populates it.
            return ResponseEntity.ok(List.of());
        }

        int clamped = Math.max(1, Math.min(50, limit));
        String prefix = (q == null) ? "" : q.trim();
        if (prefix.isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        Set<Integer> visibleStudyIds = access.visibleStudyIds(session);

        List<StudySubjectMatch> matches = studySubjectFinder.findByLabelPrefix(prefix, clamped);
        List<Map<String, Object>> out = new ArrayList<>(matches.size());
        for (StudySubjectMatch m : matches) {
            if (!visibleStudyIds.contains(m.studyId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studySubjectId", m.studySubjectId());
            row.put("label", m.subjectLabel());
            row.put("studyId", m.studyId());
            row.put("studyName", m.studyName());
            row.put("siteName", m.siteName());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }
}
