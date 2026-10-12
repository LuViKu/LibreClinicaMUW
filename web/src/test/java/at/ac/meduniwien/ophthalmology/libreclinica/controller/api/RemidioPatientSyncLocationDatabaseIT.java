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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioDashboardClient;

/**
 * DR-040 phase 3: the Remidio patient sync pushes only the subjects of studies
 * at the account's location; in multi-location mode an account nobody has
 * placed pushes nothing. Runs on the demo schedule: visits 5 (M-002) and 10
 * (M-004) are open on 2020-11-02..08.
 */
class RemidioPatientSyncLocationDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final LocalDate FROM = LocalDate.parse("2020-11-02");
    private static final LocalDate TO = LocalDate.parse("2020-11-08");
    private static final long KNOWN_PATIENT = 424242L;

    private static int studyOf5;
    private static int studyOf10;
    private static int home;
    private static int locB;
    private static int before;

    private RemidioDashboardClient client;

    @BeforeAll
    static void seed() throws Exception {
        studyOf5 = intQuery("SELECT ss.study_id FROM study_event se JOIN study_subject ss "
                + "ON ss.study_subject_id = se.study_subject_id WHERE se.study_event_id = 5");
        studyOf10 = intQuery("SELECT ss.study_id FROM study_event se JOIN study_subject ss "
                + "ON ss.study_subject_id = se.study_subject_id WHERE se.study_event_id = 10");
        home = intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        before = intQuery("SELECT COALESCE(location_id, 0) FROM study WHERE study_id = " + studyOf5);
        // Disabled until a multi-mode test needs it: single mode has exactly one active location.
        exec("INSERT INTO location (code, name, status_id, date_created) VALUES ('RS-B', 'Remidio B', 5, now())");
        locB = intQuery("SELECT location_id FROM location WHERE code = 'RS-B'");
    }

    @BeforeEach
    void aClientThatKnowsEveryPatient() throws Exception {
        client = knowingClient();
    }

    @AfterEach
    void clean() throws Exception {
        LocationMode.set(LocationMode.SINGLE);
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locB);
        exec("UPDATE study SET location_id = " + (before == 0 ? "NULL" : String.valueOf(before))
                + " WHERE study_id = " + studyOf5);
        exec("DELETE FROM remidio_patient WHERE remidio_patient_id IN (" + KNOWN_PATIENT + ", " + (KNOWN_PATIENT + 1) + ")");
    }

    /** A dashboard that already has every patient: one id per label, so nothing is created. */
    private static RemidioDashboardClient knowingClient() throws Exception {
        RemidioDashboardClient c = mock(RemidioDashboardClient.class, RETURNS_DEEP_STUBS);
        when(c.findPatientId("M-002")).thenReturn(Optional.of(KNOWN_PATIENT));
        when(c.findPatientId("M-004")).thenReturn(Optional.of(KNOWN_PATIENT + 1));
        when(c.findPatientId(org.mockito.ArgumentMatchers.argThat(l -> !"M-002".equals(l) && !"M-004".equals(l))))
                .thenReturn(Optional.empty());
        return c;
    }

    @Test
    void inMultiModeAnUnplacedAccountPushesNothing() throws Exception {
        LocationMode.set(LocationMode.MULTI);
        RemidioPatientSyncService.Summary s =
                new RemidioPatientSyncService(DATA_SOURCE, client, null).sync(FROM, TO, false);
        assertEquals(0, s.visits());
        verifyNoInteractions(client);
    }

    @Test
    void anAccountPushesOnlyTheSubjectsAtItsLocation() throws Exception {
        LocationMode.set(LocationMode.MULTI);
        exec("UPDATE location SET status_id = 1 WHERE location_id = " + locB);
        exec("UPDATE study SET location_id = " + locB + " WHERE study_id = " + studyOf5);

        new RemidioPatientSyncService(DATA_SOURCE, client, locB).sync(FROM, TO, false);
        verify(client).findPatientId("M-002");
        if (studyOf10 != studyOf5) verify(client, never()).findPatientId("M-004");

        RemidioDashboardClient atHome = knowingClient();
        exec("DELETE FROM remidio_patient WHERE remidio_patient_id IN (" + KNOWN_PATIENT + ", " + (KNOWN_PATIENT + 1) + ")");
        new RemidioPatientSyncService(DATA_SOURCE, atHome, home).sync(FROM, TO, false);
        verify(atHome, never()).findPatientId("M-002");
    }

    @Test
    void inSingleModeTheAccountPushesTheWholeScheduleAsBefore() throws Exception {
        RemidioPatientSyncService.Summary s =
                new RemidioPatientSyncService(DATA_SOURCE, client, null).sync(FROM, TO, false);
        assertEquals(2, s.visits());
        verify(client).findPatientId("M-002");
        verify(client).findPatientId("M-004");
    }

    private static int intQuery(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }
}
