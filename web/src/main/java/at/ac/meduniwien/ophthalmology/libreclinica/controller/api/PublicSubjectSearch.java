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
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectFinder;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.StudySubjectMatch;

/**
 * Label-prefix subject lookup for the unauthenticated upload portals.
 *
 * <p>The portals let an operator standing at a camera find the subject they are
 * about to image. Until now their patient-search dialog called the staff
 * endpoint {@code GET /api/v1/study-subjects/search}, which is session-gated —
 * so on a portal page it simply returned 401 and the dialog stayed empty. This
 * is the public counterpart, deliberately narrower than the staff one:
 *
 * <ul>
 *   <li><strong>Minimum prefix length.</strong> A one-character query would
 *       enumerate the register; three characters means the operator already
 *       knows the label they are looking for.</li>
 *   <li><strong>Hard row cap.</strong> At most {@value #MAX_LIMIT} rows, so a
 *       short prefix cannot be used to page through the study.</li>
 *   <li><strong>Label-only projection.</strong> Subject label, study and site —
 *       never gender, date of birth, OID or enrolment date. The label is the
 *       pseudonym the EDC already prints on the worklist the camera pulls.</li>
 *   <li><strong>Never logs the query.</strong> It is operator-supplied text
 *       about a patient.</li>
 * </ul>
 *
 * <p>Both portals sit behind the institutional reverse proxy and the
 * per-portal token-bucket in {@code PublicOctUploadRateLimitFilter}, which
 * applies to these paths too.
 */
final class PublicSubjectSearch {

    /** Below this, a query would enumerate rather than look up. */
    static final int MIN_PREFIX_LENGTH = 3;

    /** Hard ceiling regardless of what the caller asks for. */
    static final int MAX_LIMIT = 10;

    private PublicSubjectSearch() {}

    /** One hit — the least a portal needs to tell two subjects apart. */
    record Hit(int studySubjectId, String label, String studyName, String siteName) {}

    /**
     * @param q     label prefix, trimmed by the caller or here
     * @param limit requested row cap; clamped to 1..{@value #MAX_LIMIT}
     * @return 200 with {@code {"subjects": [...]}}, or 400 when the prefix is too short
     */
    static ResponseEntity<?> search(StudySubjectFinder finder, String q, Integer limit) {
        String prefix = q == null ? "" : q.trim();
        if (prefix.length() < MIN_PREFIX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "q must be at least " + MIN_PREFIX_LENGTH + " characters"));
        }
        int clamped = limit == null ? MAX_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));

        List<Hit> hits = new ArrayList<>();
        for (StudySubjectMatch m : finder.findByLabelPrefix(prefix, clamped)) {
            hits.add(new Hit(m.studySubjectId(), m.subjectLabel(), m.studyName(), m.siteName()));
        }
        return ResponseEntity.ok(Map.of("subjects", hits));
    }
}
