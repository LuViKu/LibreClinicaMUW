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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationModeStartup;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;

/**
 * DR-040: the location mode at startup ({@link LocationModeStartup}) and the
 * default location that follows it ({@link LocationScope#defaultLocationId}).
 * Single mode refuses a database with several active locations and gives the
 * one location to what has none; multi mode leaves everything as it is and has
 * no default location.
 */
class LocationModeStartupDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static int defaultId;
    private static int studyId;
    private static Integer studyLocationBefore;

    @BeforeAll
    static void find() throws Exception {
        defaultId = intQuery("SELECT location_id FROM location WHERE code = 'MUW-AUGEN'");
        studyId = intQuery("SELECT min(study_id) FROM study");
        try (Connection c = DATA_SOURCE.getConnection()) {
            studyLocationBefore = LocationScope.locationOfStudy(c, studyId);
        }
    }

    @AfterEach
    void clean() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_log_event_type_id = " + LocationModeStartup.AUDIT_LOCATION_BACKFILLED);
        exec("DELETE FROM ingest_item WHERE device = 'mode-it'");
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source' AND entity_name LIKE 'MODE-IT%'");
        exec("DELETE FROM ingest_source WHERE source_key LIKE 'MODE-IT%'");
        exec("DELETE FROM location WHERE code LIKE 'MODE-IT%'");
        exec("UPDATE location SET status_id = 1 WHERE location_id = " + defaultId);
        exec("UPDATE study SET location_id = " + (studyLocationBefore == null ? "NULL" : studyLocationBefore)
                + " WHERE study_id = " + studyId);
        LocationMode.set(LocationMode.SINGLE);
    }

    @AfterAll
    static void backToSingle() {
        LocationMode.set(LocationMode.SINGLE);
    }

    /* ---------------------------------------------------------------- */
    /* the setting                                                       */
    /* ---------------------------------------------------------------- */

    @Test
    void theSettingIsSingleOrMultiAndNothingElse() {
        assertEquals(LocationMode.SINGLE, LocationMode.parse(null));
        assertEquals(LocationMode.SINGLE, LocationMode.parse("  "));
        assertEquals(LocationMode.SINGLE, LocationMode.parse("single"));
        assertEquals(LocationMode.MULTI, LocationMode.parse(" Multi "));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LocationMode.parse("multiple"));
        assertTrue(e.getMessage().contains("LIBRECLINICA_DEPLOYMENT_LOCATIONS"), e.getMessage());
        assertEquals("single", LocationMode.SINGLE.wire());
        assertEquals("multi", LocationMode.MULTI.wire());
    }

    /* ---------------------------------------------------------------- */
    /* the check                                                         */
    /* ---------------------------------------------------------------- */

    @Test
    void singleModeWithTwoActiveLocationsRefusesToStartAndSaysWhatToDo() throws Exception {
        exec("INSERT INTO location (code, name, status_id, date_created) VALUES ('MODE-IT-B', 'Mode B', 1, now())");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LocationModeStartup.apply(DATA_SOURCE, LocationMode.SINGLE));
        assertTrue(e.getMessage().contains("2 locations are active"), e.getMessage());
        assertTrue(e.getMessage().contains("LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi"), e.getMessage());

        // A disabled second location is no obstacle.
        exec("UPDATE location SET status_id = 5 WHERE code = 'MODE-IT-B'");
        assertEquals(defaultId, LocationModeStartup.apply(DATA_SOURCE, LocationMode.SINGLE).locationId());
    }

    @Test
    void multiModeStartsWithAnyNumberOfLocationsAndPlacesNothing() throws Exception {
        exec("INSERT INTO location (code, name, status_id, date_created) VALUES ('MODE-IT-B', 'Mode B', 1, now())");
        exec("UPDATE study SET location_id = NULL WHERE study_id = " + studyId);
        LocationModeStartup.Result r = LocationModeStartup.apply(DATA_SOURCE, LocationMode.MULTI);
        assertEquals(List.of(), r.placed());
        assertNull(studyLocation(), "multi mode leaves a study without a place as it is");
    }

    /* ---------------------------------------------------------------- */
    /* the placement                                                     */
    /* ---------------------------------------------------------------- */

    @Test
    void singleModePlacesWhatHasNoPlaceOnceAndAuditsEachTable() throws Exception {
        // Settle whatever the fixtures left without a place, so the counts below are this test's own.
        LocationModeStartup.apply(DATA_SOURCE, LocationMode.SINGLE);
        exec("DELETE FROM audit_log_event WHERE audit_log_event_type_id = " + LocationModeStartup.AUDIT_LOCATION_BACKFILLED);
        exec("UPDATE study SET location_id = NULL WHERE study_id = " + studyId);
        exec("INSERT INTO ingest_source (kind, source_key, display_name, created_at) "
                + "VALUES ('dicom_ae', 'MODE-IT-AE', 'MODE-IT-AE', now())");
        long unfiled = insertItem("UNBOUND", null);
        long dismissed = insertItem("DISMISSED", null);
        long bound = insertItem("BOUND", null);

        LocationModeStartup.Result r = LocationModeStartup.apply(DATA_SOURCE, LocationMode.SINGLE);

        assertEquals(defaultId, r.locationId());
        assertEquals(defaultId, studyLocation());
        assertEquals(defaultId, intQuery("SELECT location_id FROM ingest_source WHERE source_key = 'MODE-IT-AE'"));
        assertEquals(defaultId, originOf(unfiled));
        assertEquals(defaultId, originOf(dismissed));
        assertNull(originOfOrNull(bound), "a filed file belongs where its study is; it is not touched");

        List<String> audited = backfillAudits();
        assertTrue(audited.contains("study=1"), audited.toString());
        assertTrue(audited.contains("ingest_source=1"), audited.toString());
        assertTrue(audited.contains("ingest_item=2"), audited.toString());

        // A second start finds nothing left and writes nothing.
        LocationModeStartup.Result again = LocationModeStartup.apply(DATA_SOURCE, LocationMode.SINGLE);
        assertEquals(List.of(), again.placed());
        assertEquals(audited.size(), backfillAudits().size());
    }

    @Test
    void singleModeWithNoActiveLocationStartsAndPlacesNothing() throws Exception {
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + defaultId);
        exec("UPDATE study SET location_id = NULL WHERE study_id = " + studyId);
        LocationModeStartup.Result r = LocationModeStartup.apply(DATA_SOURCE, LocationMode.SINGLE);
        assertNull(r.locationId());
        assertNull(studyLocation());
    }

    /* ---------------------------------------------------------------- */
    /* the default location follows the mode                             */
    /* ---------------------------------------------------------------- */

    @Test
    void theDefaultLocationIsTheOneInSingleModeAndNoneInMulti() throws Exception {
        LocationScope scope = new LocationScope(DATA_SOURCE);
        assertEquals(defaultId, scope.defaultLocationId());
        LocationMode.set(LocationMode.MULTI);
        assertNull(scope.defaultLocationId(), "multi mode: what has no place belongs to none, with one location too");
        exec("UPDATE study SET location_id = NULL WHERE study_id = " + studyId);
        try (Connection c = DATA_SOURCE.getConnection()) {
            assertTrue(!LocationScope.studyIdsAtLocation(c, defaultId).contains(studyId),
                    "multi mode: a study without a place is at none");
            LocationMode.set(LocationMode.SINGLE);
            assertTrue(LocationScope.studyIdsAtLocation(c, defaultId).contains(studyId),
                    "single mode: it is at the one location");
        }
    }

    @Test
    void anAutomaticSourceTakesTheOneLocationInSingleModeAndNoneInMulti() throws Exception {
        IngestSourceService sources = new IngestSourceService(DATA_SOURCE);
        IngestSourceService.Source single = sources.resolveAutoSource(IngestSourceService.KIND_DICOM_AE, "MODE-IT-ONE",
                "MODE-IT-ONE");
        assertEquals(defaultId, single.locationId());
        LocationMode.set(LocationMode.MULTI);
        IngestSourceService.Source multi = sources.resolveAutoSource(IngestSourceService.KIND_DICOM_AE, "MODE-IT-TWO",
                "MODE-IT-TWO");
        assertNull(multi.locationId());
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                           */
    /* ---------------------------------------------------------------- */

    private static long insertItem(String status, Integer origin) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ingest_item (kind, source_kind, stored_path, status, device, received_at, "
                             + "origin_location_id) VALUES ('image', 'upload', ?, ?, 'mode-it', now(), ?) "
                             + "RETURNING ingest_item_id")) {
            ps.setString(1, "/x/mode-it-" + System.nanoTime());
            ps.setString(2, status);
            if (origin == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setInt(3, origin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Integer studyLocation() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            return LocationScope.locationOfStudy(c, studyId);
        }
    }

    private static int originOf(long item) throws SQLException {
        return intQuery("SELECT origin_location_id FROM ingest_item WHERE ingest_item_id = " + item);
    }

    private static Integer originOfOrNull(long item) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT origin_location_id FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, item);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static List<String> backfillAudits() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT new_value, user_id, audit_table, entity_id FROM audit_log_event "
                             + "WHERE audit_log_event_type_id = ? ORDER BY audit_id")) {
            ps.setInt(1, LocationModeStartup.AUDIT_LOCATION_BACKFILLED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getInt(2);
                    assertTrue(rs.wasNull(), "the system did it: no user");
                    assertEquals("location", rs.getString(3));
                    assertEquals(defaultId, rs.getInt(4));
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
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
             java.sql.Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
