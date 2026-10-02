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
 * heritage controllers and servlets that take record ids from the request.
 *
 * <p>The rule is the legacy study tree, the same one those controllers use to
 * list records: a record belongs when its study subject sits in the current
 * study itself or — when the current study is a parent — in one of that
 * parent's sites. A session pointed at a site sees only that site. Event
 * definitions are kept in the parent study and shared by its sites, so for them
 * a site session counts as its parent.
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

    private static final String EVENT_DEFINITION_STUDY =
            "SELECT s.study_id, s.parent_study_id FROM study_event_definition sed"
            + " JOIN study s ON s.study_id = sed.study_id"
            + " WHERE sed.study_event_definition_id = ?";

    private static final String EVENT_DEFINITION_CRF_STUDY =
            "SELECT s.study_id, s.parent_study_id FROM event_definition_crf edc"
            + " JOIN study_event_definition sed ON sed.study_event_definition_id = edc.study_event_definition_id"
            + " JOIN study s ON s.study_id = sed.study_id"
            + " WHERE edc.event_definition_crf_id = ?";

    private static final String ITEM_DATA_STUDY =
            "SELECT s.study_id, s.parent_study_id FROM item_data i"
            + " JOIN event_crf ec ON ec.event_crf_id = i.event_crf_id"
            + " JOIN study_event se ON se.study_event_id = ec.study_event_id"
            + " JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id"
            + " JOIN study s ON s.study_id = ss.study_id"
            + " WHERE i.item_data_id = ?";

    private static final String STUDY_EVENT_STUDY =
            "SELECT s.study_id, s.parent_study_id FROM study_event se"
            + " JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id"
            + " JOIN study s ON s.study_id = ss.study_id"
            + " WHERE se.study_event_id = ?";

    /** A subject is shared by studies; one row per study it is enrolled in. */
    private static final String SUBJECT_STUDIES =
            "SELECT s.study_id, s.parent_study_id FROM study_subject ss"
            + " JOIN study s ON s.study_id = ss.study_id"
            + " WHERE ss.subject_id = ?";

    /**
     * The studies of the record a discrepancy note is attached to, through the
     * note's map table; one row per study for a note on a subject.
     */
    private static final String DISCREPANCY_NOTE_STUDIES =
            "SELECT s.study_id, s.parent_study_id FROM study_subject ss"
            + " JOIN study s ON s.study_id = ss.study_id"
            + " WHERE ss.study_subject_id IN ("
            + " SELECT se.study_subject_id FROM dn_item_data_map m"
            + " JOIN item_data i ON i.item_data_id = m.item_data_id"
            + " JOIN event_crf ec ON ec.event_crf_id = i.event_crf_id"
            + " JOIN study_event se ON se.study_event_id = ec.study_event_id"
            + " WHERE m.discrepancy_note_id = ?"
            + " UNION SELECT se.study_subject_id FROM dn_event_crf_map m"
            + " JOIN event_crf ec ON ec.event_crf_id = m.event_crf_id"
            + " JOIN study_event se ON se.study_event_id = ec.study_event_id"
            + " WHERE m.discrepancy_note_id = ?"
            + " UNION SELECT se.study_subject_id FROM dn_study_event_map m"
            + " JOIN study_event se ON se.study_event_id = m.study_event_id"
            + " WHERE m.discrepancy_note_id = ?"
            + " UNION SELECT m.study_subject_id FROM dn_study_subject_map m"
            + " WHERE m.discrepancy_note_id = ?"
            + " UNION SELECT ss2.study_subject_id FROM dn_subject_map m"
            + " JOIN study_subject ss2 ON ss2.subject_id = m.subject_id"
            + " WHERE m.discrepancy_note_id = ?)";

    private static final String EVENT_CRF_CRF =
            "SELECT cv.crf_id FROM event_crf ec"
            + " JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
            + " WHERE ec.event_crf_id = ?";

    private static final String CRF_VERSION_CRF =
            "SELECT crf_id FROM crf_version WHERE crf_version_id = ?";

    private static final String RULE_SET_STUDY =
            "SELECT study_id FROM rule_set WHERE id = ?";

    private static final String RULE_SET_RULE_STUDY =
            "SELECT rs.study_id FROM rule_set_rule rsr"
            + " JOIN rule_set rs ON rs.id = rsr.rule_set_id"
            + " WHERE rsr.id = ?";

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

    /**
     * True when the event definition is one {@code currentStudy} schedules from:
     * one in the study's tree, with a site session counting as its parent.
     */
    public boolean containsEventDefinition(StudyBean currentStudy, int definitionId) {
        return inTree(definitionStudy(currentStudy), lookupStudy(EVENT_DEFINITION_STUDY, definitionId));
    }

    /**
     * True when the event definition CRF (a CRF's place in an event definition)
     * belongs to a definition {@code currentStudy} schedules from, by the rule
     * of {@link #containsEventDefinition}.
     */
    public boolean containsEventDefinitionCrf(StudyBean currentStudy, int eventDefinitionCrfId) {
        return inTree(definitionStudy(currentStudy), lookupStudy(EVENT_DEFINITION_CRF_STUDY, eventDefinitionCrfId));
    }

    /**
     * True when the record a discrepancy note is attached to belongs to
     * {@code currentStudy}'s tree. A note on a subject belongs when the subject
     * is enrolled there.
     */
    public boolean containsDiscrepancyNote(StudyBean currentStudy, int noteId) {
        return anyInTree(currentStudy, DISCREPANCY_NOTE_STUDIES, noteId, 5);
    }

    /**
     * True when the record a discrepancy note would be attached to belongs to
     * {@code currentStudy}'s tree. {@code entityType} is the note page's name for
     * it: itemData, eventCrf, studyEvent, studySub or subject. Any other name
     * answers "no".
     */
    public boolean containsNoteEntity(StudyBean currentStudy, String entityType, int entityId) {
        if ("itemData".equalsIgnoreCase(entityType)) {
            return inTree(currentStudy, lookupStudy(ITEM_DATA_STUDY, entityId));
        } else if ("eventCrf".equalsIgnoreCase(entityType)) {
            return containsEventCrf(currentStudy, entityId);
        } else if ("studyEvent".equalsIgnoreCase(entityType)) {
            return inTree(currentStudy, lookupStudy(STUDY_EVENT_STUDY, entityId));
        } else if ("studySub".equalsIgnoreCase(entityType)) {
            return containsStudySubject(currentStudy, entityId);
        } else if ("subject".equalsIgnoreCase(entityType)) {
            return anyInTree(currentStudy, SUBJECT_STUDIES, entityId, 1);
        }
        return false;
    }

    /**
     * True when the rule set belongs to {@code currentStudy}: rule sets are kept
     * on the study they were uploaded to, and a site session counts as its
     * parent.
     */
    public boolean containsRuleSet(StudyBean currentStudy, int ruleSetId) {
        return ownedByStudyOrParent(currentStudy, lookupInt(RULE_SET_STUDY, ruleSetId));
    }

    /** True when the rule set rule's rule set belongs to {@code currentStudy}, by the rule of {@link #containsRuleSet}. */
    public boolean containsRuleSetRule(StudyBean currentStudy, int ruleSetRuleId) {
        return ownedByStudyOrParent(currentStudy, lookupInt(RULE_SET_RULE_STUDY, ruleSetRuleId));
    }

    private static boolean ownedByStudyOrParent(StudyBean currentStudy, Integer ownerStudyId) {
        if (currentStudy == null || currentStudy.getId() <= 0 || ownerStudyId == null) {
            return false;
        }
        return ownerStudyId == currentStudy.getId()
                || (currentStudy.getParentStudyId() > 0 && ownerStudyId == currentStudy.getParentStudyId());
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

    /** The study whose event definitions {@code currentStudy} uses: itself, or a site's parent. */
    static StudyBean definitionStudy(StudyBean currentStudy) {
        if (currentStudy == null || currentStudy.getParentStudyId() <= 0) {
            return currentStudy;
        }
        StudyBean parent = new StudyBean();
        parent.setId(currentStudy.getParentStudyId());
        return parent;
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

    /**
     * Whether any row of {@code sql} ({@code study_id, parent_study_id}) is in
     * the tree; {@code id} fills each of its {@code placeholders}.
     */
    private boolean anyInTree(StudyBean currentStudy, String sql, int id, int placeholders) {
        if (id <= 0) {
            return false;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= placeholders; i++) {
                ps.setInt(i, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (inTree(currentStudy, new int[] {rs.getInt(1), rs.getInt(2)})) {
                        return true;
                    }
                }
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("study lookup failed for id {}: {}", id, e.getMessage());
            return false;
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
