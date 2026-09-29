/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * "Does this record belong to the study the session is working in?" for the
 * heritage Spring MVC controllers that take record ids from the request.
 *
 * <p>The rule is the legacy study tree, the same one those controllers use to
 * list records: a record belongs when its study subject sits in the current
 * study itself or — when the current study is a parent — in one of that
 * parent's sites. A session pointed at a site sees only that site.
 *
 * <p>Unknown ids and lookup failures answer "no": an unanswerable access
 * question is a refusal.
 */
public class StudyTreeScope {

    private static final Logger LOG = LoggerFactory.getLogger(StudyTreeScope.class);

    private static final String EVENT_CRF_STUDY =
            "SELECT s.study_id, s.parent_study_id FROM event_crf ec"
            + " JOIN study_event se ON se.study_event_id = ec.study_event_id"
            + " JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id"
            + " JOIN study s ON s.study_id = ss.study_id"
            + " WHERE ec.event_crf_id = ?";

    private static final String STUDY_SUBJECT_STUDY =
            "SELECT s.study_id, s.parent_study_id FROM study_subject ss"
            + " JOIN study s ON s.study_id = ss.study_id"
            + " WHERE ss.study_subject_id = ?";

    private static final String EVENT_CRF_CRF =
            "SELECT cv.crf_id FROM event_crf ec"
            + " JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
            + " WHERE ec.event_crf_id = ?";

    private static final String CRF_VERSION_CRF =
            "SELECT crf_id FROM crf_version WHERE crf_version_id = ?";

    private final DataSource dataSource;

    public StudyTreeScope(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** True when the event CRF's subject is in {@code currentStudy} or one of its sites. */
    public boolean containsEventCrf(StudyBean currentStudy, int eventCrfId) {
        return inTree(currentStudy, lookupStudy(EVENT_CRF_STUDY, eventCrfId));
    }

    /** True when the study subject is in {@code currentStudy} or one of its sites. */
    public boolean containsStudySubject(StudyBean currentStudy, int studySubjectId) {
        return inTree(currentStudy, lookupStudy(STUDY_SUBJECT_STUDY, studySubjectId));
    }

    /** The CRF an event CRF's current version belongs to, or null when unknown. */
    public Integer crfIdOfEventCrf(int eventCrfId) {
        return lookupInt(EVENT_CRF_CRF, eventCrfId);
    }

    /** The CRF a CRF version belongs to, or null when unknown. */
    public Integer crfIdOfVersion(int crfVersionId) {
        return lookupInt(CRF_VERSION_CRF, crfVersionId);
    }

    /**
     * The tree rule itself.
     *
     * @param owner {@code {study_id, parent_study_id}} of the record's study, or null
     */
    static boolean inTree(StudyBean currentStudy, int[] owner) {
        if (currentStudy == null || currentStudy.getId() <= 0 || owner == null) {
            return false;
        }
        int current = currentStudy.getId();
        return owner[0] == current || owner[1] == current;
    }

    private int[] lookupStudy(String sql, int id) {
        if (id <= 0) {
            return null;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int studyId = rs.getInt(1);
                int parentId = rs.getInt(2); // NULL reads as 0: a top-level study
                return new int[] {studyId, parentId};
            }
        } catch (SQLException e) {
            LOG.warn("study lookup failed for id {}: {}", id, e.getMessage());
            return null;
        }
    }

    private Integer lookupInt(String sql, int id) {
        if (id <= 0) {
            return null;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int value = rs.getInt(1);
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            LOG.warn("lookup failed for id {}: {}", id, e.getMessage());
            return null;
        }
    }
}
