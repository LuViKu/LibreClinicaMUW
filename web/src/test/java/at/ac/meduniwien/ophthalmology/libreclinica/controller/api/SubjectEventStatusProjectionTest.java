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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;

/**
 * Pins the projection of {@code study_event.subject_event_status_id} onto the
 * wire vocabulary against {@link SubjectEventStatus} itself.
 *
 * <p>This exists because the unauthenticated OCT portal carried a second,
 * divergent copy of the mapping: it reported id 2 (not_scheduled) as
 * "data-entry-started", had no case for id 3 so a genuinely started visit read
 * as "scheduled", and invented ids 9 and 10 that the enum does not define. The
 * portal's visit picker shows these strings to an operator choosing where to
 * file a scan, so the two views of a visit must agree.
 */
class SubjectEventStatusProjectionTest {

    @ParameterizedTest(name = "status id {0} → {1}")
    @CsvSource({
            "1, scheduled",
            "2, not-scheduled",
            "3, data-entry-started",
            "4, completed",
            "5, stopped",
            "6, skipped",
            "7, locked",
            "8, signed",
    })
    void mapsEveryDefinedStatusId(int id, String expected) {
        assertEquals(expected, EventsApiController.statusForSubjectEventStatusId(id));
    }

    /** Ids outside the enum degrade to the safe value rather than inventing one. */
    @ParameterizedTest
    @ValueSource(ints = {0, 9, 10, -1, 99})
    void mapsUndefinedIdsToNotScheduled(int id) {
        assertEquals("not-scheduled", EventsApiController.statusForSubjectEventStatusId(id));
    }

    /**
     * The id-keyed mapper and the enum-keyed one must not drift apart — every
     * value the enum defines projects identically through both.
     */
    @Test
    void idAndEnumMappersAgreeForEveryDefinedStatus() {
        for (int id = 1; id <= 8; id++) {
            SubjectEventStatus s = SubjectEventStatus.get(id);
            assertEquals(EventsApiController.statusForSubjectEventStatusId(id),
                    EventsApiController.statusForSubjectEventStatusId(s.getId()),
                    "mappers disagree for status id " + id);
        }
    }
}
