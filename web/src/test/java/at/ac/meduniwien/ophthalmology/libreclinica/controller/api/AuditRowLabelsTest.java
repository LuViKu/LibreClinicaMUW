/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Reading an audit row for what it records (2026-09-27).
 *
 * <p>Pinned: the three ids MUW wrote with a second meaning read as the type
 * they record, and the heritage rows under the same ids stay as they are; a
 * dismissal's reason does not move a file row into the reason-for-change
 * bucket; a failure's packed error reads as one line; an ingest row's visit
 * is found in its values; and a visit is named by its definition.
 */
class AuditRowLabelsTest {

    @Test
    void elevenIsAReopenOrARestoreOnlyWhenMuwWroteIt() {
        assertEquals(AuditTypeIds.EVENT_CRF_REOPENED,
                AuditRowLabels.effectiveType(11, "event_crf", "date_completed", "2026-09-01T10:00:00Z", ""));
        assertEquals(AuditTypeIds.EVENT_CRF_RESTORED,
                AuditRowLabels.effectiveType(11, "event_crf", "status_id", "AUTO_DELETED", "AVAILABLE"));
        // The event_crf trigger: a double data entry completed (4 -> 2).
        assertEquals(11, AuditRowLabels.effectiveType(11, "event_crf", "Status", "4", "2"));
    }

    @Test
    void twentySevenIsAReasonForChangeOnlyOnAnItem() {
        assertEquals(AuditTypeIds.ITEM_DATA_REASON_FOR_CHANGE,
                AuditRowLabels.effectiveType(27, "item_data", "I_BCVA", "20", "25"));
        // The study_subject trigger: moved to another site.
        assertEquals(27, AuditRowLabels.effectiveType(27, "study_subject", "Study id", "S_A", "S_B"));
    }

    @Test
    void aRestoreWrittenUnderTheDismissTypeIsARestore() {
        assertEquals(AuditTypeIds.INGEST_RESTORE, AuditRowLabels.effectiveType(
                AuditTypeIds.IMAGE_DISMISS, "ingest_item", "status", "DISMISSED;reason=blurred", "UNBOUND"));
        assertEquals(AuditTypeIds.IMAGE_DISMISS, AuditRowLabels.effectiveType(
                AuditTypeIds.IMAGE_DISMISS, "ingest_item", "status", "UNBOUND", "DISMISSED"));
        // The retention sweep's summary has no old value.
        assertEquals(AuditTypeIds.IMAGE_DISMISS, AuditRowLabels.effectiveType(
                AuditTypeIds.IMAGE_DISMISS, "ingest_item", "Retention sweep", null, "purged 3"));
    }

    @Test
    void everythingElseIsWhatItsIdSays() {
        assertEquals(24, AuditRowLabels.effectiveType(24, "study_event", "Start date", "2026-09-23", "2026-09-24"));
        assertEquals(61, AuditRowLabels.effectiveType(61, "legacy_servlet", "/x", "", "a|b|c"));
    }

    @Test
    void aReasonMakesADataChangeAReasonForChangeButNotAFileRow() {
        assertEquals("reason-for-change", AuditRowLabels.variant(1, "item_data", "typo"));
        assertEquals("data", AuditRowLabels.variant(AuditTypeIds.IMAGE_DISMISS, "ingest_item", "test exposure"));
        assertEquals("reason-for-change",
                AuditRowLabels.variant(AuditTypeIds.ITEM_DATA_REASON_FOR_CHANGE, "item_data", null));
        assertEquals("admin", AuditRowLabels.variant(27, "study_subject", null));
        assertEquals("data", AuditRowLabels.variant(AuditTypeIds.EVENT_CRF_REOPENED, "event_crf", null));
    }

    @Test
    void aFailureReadsAsOneLine() {
        assertEquals("java.sql.SQLException: connection reset · request 3f2a",
                AuditRowLabels.formatFailure("java.sql.SQLException|connection reset|3f2a"));
        assertEquals("java.lang.IllegalStateException",
                AuditRowLabels.formatFailure("java.lang.IllegalStateException||"));
        // The message may itself contain the separator; the request id is last.
        assertEquals("E: a|b · request r", AuditRowLabels.formatFailure("E|a|b|r"));
        assertEquals("E: only a message", AuditRowLabels.formatFailure("E|only a message"));
        assertEquals("not packed", AuditRowLabels.formatFailure("not packed"));
        assertNull(AuditRowLabels.formatFailure(""));
        assertTrue(AuditRowLabels.isFailure(61));
        assertTrue(AuditRowLabels.isFailure(62));
        assertFalse(AuditRowLabels.isFailure(128));
    }

    @Test
    void anIngestRowNamesItsVisitInItsValues() {
        assertEquals(812, AuditRowLabels.studyEventIdIn("BOUND;match_policy=manual;study_event_id=812", "UNBOUND"));
        assertEquals(77, AuditRowLabels.studyEventIdIn("UNBOUND;cleared=CLEARED", "BOUND;study_event_id=77"));
        assertNull(AuditRowLabels.studyEventIdIn("DISMISSED", "UNBOUND"));
        assertNull(AuditRowLabels.studyEventIdIn("BOUND;study_event_id=0", null));
        assertNull(AuditRowLabels.studyEventIdIn("BOUND;other_study_event_id=5", null));
    }

    @Test
    void aBareMarkerIsNotAReference() {
        assertTrue(AuditRowLabels.isBareMarker("status"));
        assertTrue(AuditRowLabels.isBareMarker("retinal_jobs"));
        assertTrue(AuditRowLabels.isBareMarker(null));
        assertFalse(AuditRowLabels.isBareMarker("file #482 · remidio · image"));
        assertFalse(AuditRowLabels.isBareMarker("Retention sweep"));
    }

    @Test
    void aVisitIsNamedByItsDefinition() {
        assertEquals("Baseline", AuditRowLabels.visitLabel("Baseline", 1, false));
        assertEquals("Follow-up #3", AuditRowLabels.visitLabel("Follow-up", 3, true));
        assertNull(AuditRowLabels.visitLabel(" ", 1, false));
    }
}
