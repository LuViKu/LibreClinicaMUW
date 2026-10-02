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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/** The study-tree rule; the SQL is covered by {@code StudyTreeScopeDatabaseIT}. */
class StudyTreeScopeTest {

    private static StudyBean study(int id, int parentId) {
        StudyBean s = new StudyBean();
        s.setId(id);
        s.setParentStudyId(parentId);
        return s;
    }

    @Test
    void aParentStudySessionCoversItselfAndItsSites() {
        StudyBean parent = study(10, 0);
        assertTrue(StudyTreeScope.inTree(parent, new int[] {10, 0}), "record in the parent");
        assertTrue(StudyTreeScope.inTree(parent, new int[] {11, 10}), "record in a site of the parent");
        assertFalse(StudyTreeScope.inTree(parent, new int[] {20, 0}), "record in another study");
        assertFalse(StudyTreeScope.inTree(parent, new int[] {21, 20}), "record in another study's site");
    }

    @Test
    void aSiteSessionCoversOnlyThatSite() {
        StudyBean site = study(11, 10);
        assertTrue(StudyTreeScope.inTree(site, new int[] {11, 10}));
        assertFalse(StudyTreeScope.inTree(site, new int[] {10, 0}), "the parent's own records");
        assertFalse(StudyTreeScope.inTree(site, new int[] {12, 10}), "a sibling site");
    }

    @Test
    void aSiteSessionSchedulesFromItsParentsEventDefinitions() {
        assertEquals(10, StudyTreeScope.definitionStudy(study(11, 10)).getId(), "a site uses its parent's definitions");
        assertEquals(10, StudyTreeScope.definitionStudy(study(10, 0)).getId(), "a parent uses its own");
        assertNull(StudyTreeScope.definitionStudy(null));
    }

    @Test
    void noSessionStudyOrNoRecordMeansNo() {
        assertFalse(StudyTreeScope.inTree(null, new int[] {10, 0}));
        assertFalse(StudyTreeScope.inTree(study(0, 0), new int[] {0, 0}));
        assertFalse(StudyTreeScope.inTree(study(10, 0), null));
    }

    @Test
    void aFailingLookupIsARefusal() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("down"));
        StudyTreeScope scope = new StudyTreeScope(ds);

        assertFalse(scope.containsEventCrf(study(10, 0), 5));
        assertFalse(scope.containsStudySubject(study(10, 0), 5));
        assertFalse(scope.containsEventDefinition(study(10, 0), 5));
        assertNull(scope.crfIdOfEventCrf(5));
        assertNull(scope.crfIdOfVersion(5));
        assertFalse(scope.containsEventCrf(study(10, 0), 0), "non-positive ids never match");
    }
}
