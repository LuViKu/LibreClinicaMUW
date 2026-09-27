/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.core;

import static org.junit.Assert.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.Test;

/**
 * What "today" is for the clinic. Pinned: the zone defaults to Vienna and
 * falls back to it for a blank or invalid setting; and the point of the class
 * - just after midnight in Vienna, UTC still says yesterday, the clinic says
 * today, in summer and in winter.
 */
public class ClinicZoneTest {

    @Test
    public void defaultsToVienna() {
        assertEquals(ZoneId.of("Europe/Vienna"), ClinicZone.parse(null));
        assertEquals(ZoneId.of("Europe/Vienna"), ClinicZone.parse("  "));
    }

    @Test
    public void takesAConfiguredZone_andFallsBackOnAnInvalidOne() {
        assertEquals(ZoneId.of("Europe/Berlin"), ClinicZone.parse(" Europe/Berlin "));
        assertEquals(ClinicZone.DEFAULT, ClinicZone.parse("Vienna/Austria"));
    }

    @Test
    public void justAfterMidnightInViennaItIsAlreadyTodayThere() {
        // 00:30 on 25 September in Vienna (UTC+2, summer) = 22:30 UTC on the 24th.
        Clock summer = Clock.fixed(Instant.parse("2026-09-24T22:30:00Z"), ZoneOffset.UTC);
        assertEquals(LocalDate.of(2026, 9, 24), LocalDate.now(summer));
        assertEquals(LocalDate.of(2026, 9, 25), ClinicZone.today(summer));

        // 00:30 on 5 January in Vienna (UTC+1, winter) = 23:30 UTC on the 4th.
        Clock winter = Clock.fixed(Instant.parse("2027-01-04T23:30:00Z"), ZoneOffset.UTC);
        assertEquals(LocalDate.of(2027, 1, 4), LocalDate.now(winter));
        assertEquals(LocalDate.of(2027, 1, 5), ClinicZone.today(winter));
    }

    @Test
    public void duringTheDayBothAgree() {
        Clock noon = Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);
        assertEquals(LocalDate.of(2026, 9, 24), ClinicZone.today(noon));
    }
}
