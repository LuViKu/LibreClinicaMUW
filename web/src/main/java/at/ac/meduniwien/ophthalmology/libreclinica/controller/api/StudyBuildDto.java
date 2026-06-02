/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Phase E.4 M12 — wire-shape for
 * {@code GET /pages/api/v1/studies/{oid}/build-status}.
 *
 * <p>Mirrors the Vue SPA's {@code StudyBuildStatus} TS interface
 * in {@code web/src/spa/src/types/study.ts} byte-for-byte. The
 * 7-task ordered list drives the build-study setup tracker:
 * Create Study → CRF → Events → Groups → Rules → Sites → Users.
 */
@Schema(name = "StudyBuildDto")
public record StudyBuildDto(
        String studyOid,
        String studyName,
        String studyVersion,
        int sites,
        int enrolledSubjects,
        List<StudyBuildTaskDto> tasks
) {
    /**
     * One task in the setup tracker. {@code status} ∈
     * {@code not-started | in-progress | complete}; {@code count}
     * is the entity count that backs the status; {@code to} is a
     * deep-link SPA route or null.
     *
     * @param id      one of: {@code create-study | crf | events |
     *                groups | rules | sites | users}
     * @param count   entity count or null when not measurable
     * @param status  one of: {@code not-started | in-progress | complete}
     * @param to      deep-link route, or null when the supporting
     *                view doesn't exist yet
     */
    @Schema(name = "StudyBuildTaskDto")
    public record StudyBuildTaskDto(
            String id,
            Integer count,
            String status,
            String to
    ) {}
}
