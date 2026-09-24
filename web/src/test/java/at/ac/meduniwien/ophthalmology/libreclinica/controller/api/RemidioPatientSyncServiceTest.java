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

import java.time.Instant;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** The sync's placeholder identity and its exam timestamp. */
class RemidioPatientSyncServiceTest {

    @Test
    void sexIsOneOfTheTwoValuesTheDashboardKnows() {
        assertEquals("FEMALE", RemidioPatientSyncService.sexOf("f"));
        assertEquals("FEMALE", RemidioPatientSyncService.sexOf("Female"));
        assertEquals("MALE", RemidioPatientSyncService.sexOf("m"));
        assertEquals("MALE", RemidioPatientSyncService.sexOf("o"), "no third value: the placeholder");
        assertEquals("MALE", RemidioPatientSyncService.sexOf(null));
    }

    @Test
    void theLastNameIsTheStudyOrAPlaceholder() {
        assertEquals("HealthAEye", RemidioPatientSyncService.lastNameFor("HealthAEye"));
        assertEquals("STUDY", RemidioPatientSyncService.lastNameFor("  "));
        assertEquals(40, RemidioPatientSyncService.lastNameFor("x".repeat(80)).length());
    }

    @Test
    void theExamIsNamedForThePhotographer() {
        assertEquals("HAE-002 Baseline", RemidioPatientSyncService.examNameFor("HAE-002", "Baseline"));
        assertEquals("HAE-002", RemidioPatientSyncService.examNameFor("HAE-002", null));
        assertEquals(40, RemidioPatientSyncService.examNameFor("HAE-002", "V".repeat(60)).length());
    }

    @Test
    void theExamTimestampIsTheVisitInTheClinicsZone() {
        // 09:30 in Vienna on 2026-09-24 (CEST, UTC+2) is 07:30Z.
        assertEquals(Instant.parse("2026-09-24T07:30:00Z").toEpochMilli(),
                RemidioPatientSyncService.examDateMs("2026-09-24", LocalTime.of(9, 30)));
        // a date-only visit sits at noon, so it is the same calendar day everywhere nearby
        assertEquals(Instant.parse("2026-09-24T10:00:00Z").toEpochMilli(),
                RemidioPatientSyncService.examDateMs("2026-09-24", null));
    }
}
