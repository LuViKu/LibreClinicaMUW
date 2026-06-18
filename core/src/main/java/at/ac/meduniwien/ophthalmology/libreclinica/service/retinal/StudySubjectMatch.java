/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

/**
 * One cross-study study_subject row resolved from a (typically
 * Heidelberg-Spectralis-supplied) patient label. Sibling to
 * {@link EventCandidate}; both back {@link StudySubjectFinder}.
 *
 * <p>The portal's {@code /resolve} endpoint may return multiple matches
 * per scan when label uniqueness is per-study rather than global
 * ({@code state = "ambiguous"}). The {@code statusId} lets the SPA dim
 * candidates in non-{@code AVAILABLE} states (e.g. SIGNED, FROZEN); the
 * service itself already filters out the removed states 5 + 7.
 *
 * @param studyId        study.study_id
 * @param studyName      study.name (display label)
 * @param studyOid       study.unique_identifier (short-code shown in chips)
 * @param studySubjectId study_subject.study_subject_id
 * @param subjectLabel   study_subject.label
 * @param siteName       parent study.name when this row belongs to a
 *                       site, else null (top-level study)
 * @param statusId       study_subject.status_id
 */
public record StudySubjectMatch(
        int studyId,
        String studyName,
        String studyOid,
        int studySubjectId,
        String subjectLabel,
        String siteName,
        int statusId
) {
}
