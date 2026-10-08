/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

/**
 * {@link StudyTreeScope}'s lookups against the real schema (Liquibase-built,
 * demo seed). The expected owners are read along a different path than the
 * class uses (event_crf.study_subject_id rather than the study_event join).
 */
class StudyTreeScopeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static StudyBean study(int id, int parentId) {
        StudyBean s = new StudyBean();
        s.setId(id);
        s.setParentStudyId(parentId);
        return s;
    }

    /** {event_crf_id, study_subject_id, study_id, parent_study_id, crf_id} of some seeded event CRF. */
    private static int[] anyEventCrf() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ec.event_crf_id, ss.study_subject_id, s.study_id, COALESCE(s.parent_study_id, 0), cv.crf_id"
                     + " FROM event_crf ec"
                     + " JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                     + " JOIN study s ON s.study_id = ss.study_id"
                     + " JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
                     + " ORDER BY ec.event_crf_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the demo seed has event CRFs");
            return new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)};
        }
    }

    /** {study_event_definition_id, study_id} of some seeded definition of a top-level study. */
    private static int[] anyEventDefinition() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sed.study_event_definition_id, s.study_id FROM study_event_definition sed"
                     + " JOIN study s ON s.study_id = sed.study_id"
                     + " WHERE COALESCE(s.parent_study_id, 0) = 0"
                     + " ORDER BY sed.study_event_definition_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the demo seed has event definitions");
            return new int[] {rs.getInt(1), rs.getInt(2)};
        }
    }

    /** {event_definition_crf_id, study_id of its definition} of some seeded event definition CRF of a top-level study. */
    private static int[] anyEventDefinitionCrf() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT edc.event_definition_crf_id, s.study_id FROM event_definition_crf edc"
                     + " JOIN study_event_definition sed ON sed.study_event_definition_id = edc.study_event_definition_id"
                     + " JOIN study s ON s.study_id = sed.study_id"
                     + " WHERE COALESCE(s.parent_study_id, 0) = 0"
                     + " ORDER BY edc.event_definition_crf_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the demo seed has event definition CRFs");
            return new int[] {rs.getInt(1), rs.getInt(2)};
        }
    }

    /** A top-level study that is neither the given study nor its parent, or -1. */
    private static int unrelatedTopLevelStudy(int studyId, int parentId) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_id FROM study WHERE COALESCE(parent_study_id, 0) = 0"
                     + " AND study_id <> ? AND study_id <> ? ORDER BY study_id LIMIT 1")) {
            ps.setInt(1, studyId);
            ps.setInt(2, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    @Test
    void eventCrfsAndSubjectsBelongToTheirOwnStudy() throws Exception {
        int[] ec = anyEventCrf();
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);
        StudyBean owner = study(ec[2], ec[3]);

        assertTrue(scope.containsEventCrf(owner, ec[0]));
        assertTrue(scope.containsStudySubject(owner, ec[1]));
        if (ec[3] > 0) {
            // Seeded in a site: the parent study's session covers it too.
            assertTrue(scope.containsEventCrf(study(ec[3], 0), ec[0]));
        }
    }

    @Test
    void anotherStudyDoesNotCoverThem() throws Exception {
        int[] ec = anyEventCrf();
        int other = unrelatedTopLevelStudy(ec[2], ec[3]);
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);
        StudyBean foreign = other > 0 ? study(other, 0) : study(Integer.MAX_VALUE, 0);

        assertFalse(scope.containsEventCrf(foreign, ec[0]));
        assertFalse(scope.containsStudySubject(foreign, ec[1]));
    }

    @Test
    void unknownIdsAreNotInAnyStudy() throws Exception {
        int[] ec = anyEventCrf();
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);
        StudyBean owner = study(ec[2], ec[3]);

        assertFalse(scope.containsEventCrf(owner, Integer.MAX_VALUE));
        assertFalse(scope.containsStudySubject(owner, Integer.MAX_VALUE));
        assertNull(scope.crfIdOfEventCrf(Integer.MAX_VALUE));
        assertNull(scope.crfIdOfVersion(Integer.MAX_VALUE));
    }

    @Test
    void eventDefinitionsServeTheirStudyAndItsSites() throws Exception {
        int[] def = anyEventDefinition();
        int other = unrelatedTopLevelStudy(def[1], 0);
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);

        assertTrue(scope.containsEventDefinition(study(def[1], 0), def[0]));
        assertTrue(scope.containsEventDefinition(study(Integer.MAX_VALUE, def[1]), def[0]),
                "a session in one of the study's sites schedules from the parent's definitions");
        assertFalse(scope.containsEventDefinition(other > 0 ? study(other, 0) : study(Integer.MAX_VALUE, 0), def[0]));
        assertFalse(scope.containsEventDefinition(study(def[1], 0), Integer.MAX_VALUE));
    }

    @Test
    void eventDefinitionCrfsServeTheStudyOfTheirDefinitionAndItsSites() throws Exception {
        int[] edc = anyEventDefinitionCrf();
        int other = unrelatedTopLevelStudy(edc[1], 0);
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);

        assertTrue(scope.containsEventDefinitionCrf(study(edc[1], 0), edc[0]));
        assertTrue(scope.containsEventDefinitionCrf(study(Integer.MAX_VALUE, edc[1]), edc[0]),
                "a session in one of the study's sites uses the parent's definitions");
        assertFalse(scope.containsEventDefinitionCrf(other > 0 ? study(other, 0) : study(Integer.MAX_VALUE, 0), edc[0]));
        assertFalse(scope.containsEventDefinitionCrf(study(edc[1], 0), Integer.MAX_VALUE));
    }

    @Test
    void noteRecordsBelongToTheStudyOfTheirSubject() throws Exception {
        int[] ec = anyEventCrf();
        int other = unrelatedTopLevelStudy(ec[2], ec[3]);
        StudyBean owner = study(ec[2], ec[3]);
        StudyBean foreign = other > 0 ? study(other, 0) : study(Integer.MAX_VALUE, 0);
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);
        int itemData = intOf("SELECT MIN(item_data_id) FROM item_data WHERE event_crf_id = " + ec[0]);
        int event = intOf("SELECT study_event_id FROM event_crf WHERE event_crf_id = " + ec[0]);
        int subject = intOf("SELECT subject_id FROM study_subject WHERE study_subject_id = " + ec[1]);

        for (String[] record : new String[][] { {"itemData", "" + itemData}, {"eventCrf", "" + ec[0]},
                {"studyEvent", "" + event}, {"studySub", "" + ec[1]}, {"subject", "" + subject} }) {
            int id = Integer.parseInt(record[1]);
            assertTrue(scope.containsNoteEntity(owner, record[0], id), record[0]);
            assertFalse(scope.containsNoteEntity(foreign, record[0], id), record[0]);
        }
        assertFalse(scope.containsNoteEntity(owner, "somethingElse", ec[0]), "an unknown record type");
        assertFalse(scope.containsNoteEntity(owner, "itemData", Integer.MAX_VALUE));
    }

    @Test
    void aNoteBelongsToTheStudyOfTheRecordItIsOn() throws Exception {
        // A seeded note on item data, read through its map table.
        int note = intOf("SELECT MIN(discrepancy_note_id) FROM dn_item_data_map");
        int studyId = intOf("SELECT ss.study_id FROM dn_item_data_map m"
                + " JOIN item_data i ON i.item_data_id = m.item_data_id"
                + " JOIN event_crf ec ON ec.event_crf_id = i.event_crf_id"
                + " JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                + " WHERE m.discrepancy_note_id = " + note);
        int parentId = intOf("SELECT COALESCE(parent_study_id, 0) FROM study WHERE study_id = " + studyId);
        int other = unrelatedTopLevelStudy(studyId, parentId);
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);

        assertTrue(scope.containsDiscrepancyNote(study(studyId, parentId), note));
        assertFalse(scope.containsDiscrepancyNote(other > 0 ? study(other, 0) : study(Integer.MAX_VALUE, 0), note));
        assertFalse(scope.containsDiscrepancyNote(study(studyId, parentId), Integer.MAX_VALUE));
    }

    private static int intOf(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "no row for " + sql);
            return rs.getInt(1);
        }
    }

    @Test
    void crfLookupsAgree() throws Exception {
        int[] ec = anyEventCrf();
        StudyTreeScope scope = new StudyTreeScope(DATA_SOURCE);

        Integer crf = scope.crfIdOfEventCrf(ec[0]);
        assertNotNull(crf);
        assertEquals(ec[4], crf.intValue());

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT crf_version_id FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, ec[0]);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(crf, scope.crfIdOfVersion(rs.getInt(1)));
            }
        }
    }
}
