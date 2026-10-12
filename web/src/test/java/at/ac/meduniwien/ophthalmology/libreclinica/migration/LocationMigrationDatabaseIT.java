/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.DefaultResourceLoader;

import at.ac.meduniwien.ophthalmology.libreclinica.config.LaxParsingSpringLiquibase;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.exception.LiquibaseException;
import liquibase.lockservice.LockServiceFactory;
import liquibase.parser.ChangeLogParserConfiguration;
import liquibase.parser.ChangeLogParserConfiguration.ChangelogParseMode;

/**
 * DR-040 phase 1 — the location changesets on a database that already holds
 * studies, sites and ingest items, which is every deployment.
 *
 * <p>The fresh database the base class builds runs the whole changelog on an
 * empty schema (plus the demo seed), so it proves nothing about rows that were
 * there before the locations existed. This rewinds the location changesets,
 * adds a world without locations (a parent study with a site, a single-site
 * study, a removed study, and ingest items in every state), and applies the
 * changelog again: every row must come out with a location, the key and the
 * index must be in place, and a second run must change nothing.
 *
 * <p>The rewinding scenario runs first and once: Liquibase keeps state per JVM
 * that a no-op update before a rewind leaves stale, and the test database is
 * this class's own.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocationMigrationDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String LOCATIONS_FILE = "lc-muw-2026-10-11-locations.xml";

    @Test
    @Order(1)
    void theChangelogGivesEveryExistingStudyAndIngestItemTheDefaultLocation() throws Exception {
        rewindLocations();
        assertEquals(0, count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'location'"),
                "the rewind should have dropped the location table");
        assertEquals(0, count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = 'study' AND column_name = 'location_id'"));
        assertEquals(0, count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = 'ingest_item' AND column_name = 'origin_location_id'"));

        World w = seedWorldWithoutLocations();
        int itemsBefore = count("SELECT count(*) FROM ingest_item");
        int studiesBefore = count("SELECT count(*) FROM study");

        applyChangelog();

        assertEquals(1, count("SELECT count(*) FROM location"), "exactly one location is created");
        assertEquals("MUW-AUGEN", text("SELECT code FROM location"));
        assertEquals("Medizinische Universität Wien – Augenklinik", text("SELECT name FROM location"));
        assertEquals(1, count("SELECT status_id FROM location"), "the location is available");
        int only = count("SELECT location_id FROM location");

        assertEquals(studiesBefore, count("SELECT count(*) FROM study"), "no study row was added or lost");
        assertEquals(0, count("SELECT count(*) FROM study WHERE location_id IS NULL"),
                "no study, site or removed study is left without a location");
        assertEquals(studiesBefore, count("SELECT count(*) FROM study WHERE location_id = " + only),
                "every study points at the one location");
        assertEquals(itemsBefore, count("SELECT count(*) FROM ingest_item"), "no ingest item was added or lost");
        assertEquals(0, count("SELECT count(*) FROM ingest_item WHERE origin_location_id IS NULL"),
                "no ingest item is left without a location");
        for (int id : new int[] {w.itemUnbound, w.itemDismissed, w.itemBound}) {
            assertEquals(only, count("SELECT origin_location_id FROM ingest_item WHERE ingest_item_id = " + id),
                    "ingest item " + id + " carries the location");
        }

        // The keys and the index of the two large tables are in place.
        assertEquals(2, count("SELECT count(*) FROM pg_constraint WHERE conname IN"
                + " ('fk_study_location', 'fk_ingest_item_origin_location')"));
        assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_ingest_item_origin_location'"));
        // A study cannot be pointed at a location that does not exist.
        assertFalse(update("UPDATE study SET location_id = 999999 WHERE study_id = " + w.parent),
                "the foreign key refuses an unknown location");
        // The code is unique without regard to case.
        assertFalse(update("INSERT INTO location (code, name) VALUES ('muw-augen', 'Duplicate')"),
                "the code is unique case-insensitively");

        // The assignment table is there with its Standort-Admin column, defaulting to false.
        assertEquals(1, count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = 'user_location' AND column_name = 'can_manage_sources'"));
        assertTrue(update("INSERT INTO user_location (user_account_id, location_id) VALUES (1, " + only + ")"));
        assertEquals(0, count("SELECT count(*) FROM user_location WHERE can_manage_sources"));
        assertFalse(update("INSERT INTO user_location (user_account_id, location_id) VALUES (1, " + only + ")"),
                "an assignment is unique per user and location");

        // The backfill is safe to re-enter by hand, as a retry after a crash would: no second default
        // location, and a site an administrator has since moved is not pulled back.
        int other = insertReturningId(
                "INSERT INTO location (code, name) VALUES ('OTHER', 'Other place') RETURNING location_id");
        assertTrue(update("UPDATE study SET location_id = " + other + " WHERE study_id = " + w.site));
        assertTrue(update("INSERT INTO location (code, name, status_id) SELECT 'MUW-AUGEN', 'x', 1"
                + " WHERE NOT EXISTS (SELECT 1 FROM location)"));
        assertTrue(update("UPDATE study SET location_id = (SELECT MIN(location_id) FROM location)"
                + " WHERE location_id IS NULL"));
        assertEquals(2, count("SELECT count(*) FROM location"), "no second default location");
        assertEquals(other, count("SELECT location_id FROM study WHERE study_id = " + w.site),
                "a site an administrator moved stays where it was put");
        assertTrue(update("UPDATE study SET location_id = " + only + " WHERE study_id = " + w.site));
        assertTrue(update("DELETE FROM location WHERE location_id = " + other));
    }

    @Test
    @Order(2)
    void runningTheChangelogAgainChangesNothing() throws Exception {
        int rows = count("SELECT count(*) FROM databasechangelog");
        int items = count("SELECT count(*) FROM ingest_item WHERE origin_location_id IS NULL");
        applyChangelog();
        assertEquals(rows, count("SELECT count(*) FROM databasechangelog"), "a changeset ran twice");
        assertEquals(items, count("SELECT count(*) FROM ingest_item WHERE origin_location_id IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM location"));
    }

    /* ---------------- world ---------------- */

    private record World(int parent, int site, int single, int removed,
                         int itemUnbound, int itemDismissed, int itemBound) {}

    /** Each world gets its own names: the study OID and the stored path are unique. */
    private static final AtomicInteger WORLDS = new AtomicInteger();

    private static World seedWorldWithoutLocations() throws Exception {
        String n = "LOCMIG" + WORLDS.incrementAndGet();
        int parent = insertStudy(null, n + "-P", n + " Parent", 1);
        int site = insertStudy(parent, n + "-S", n + " Site", 4);
        int single = insertStudy(null, n + "-Q", n + " Single", 1);
        int removed = insertStudy(null, n + "-R", n + " Removed", 5);
        int subject = insertReturningId("INSERT INTO subject (status_id, gender, date_created, owner_id, dob_collected)"
                + " VALUES (1, 'f', now(), 1, false) RETURNING subject_id");
        int studySubject = insertReturningId("INSERT INTO study_subject (label, subject_id, study_id, status_id,"
                + " date_created, owner_id, oc_oid, enrollment_date) VALUES ('" + n + "-1', " + subject + ", " + site
                + ", 1, now(), 1, 'SS_" + n + "1', '2026-01-01') RETURNING study_subject_id");
        int unbound = insertItem("UNBOUND", n.toLowerCase() + "-unbound", null, null);
        int dismissed = insertItem("DISMISSED", n.toLowerCase() + "-dismissed", null, parent);
        int bound = insertItem("BOUND", n.toLowerCase() + "-bound", studySubject, null);
        return new World(parent, site, single, removed, unbound, dismissed, bound);
    }

    private static int insertStudy(Integer parent, String uid, String name, int statusId) throws SQLException {
        return insertReturningId("INSERT INTO study (parent_study_id, unique_identifier, name, summary, date_created,"
                + " owner_id, type_id, status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid)"
                + " VALUES (" + (parent == null ? "NULL" : parent.toString()) + ", '" + uid + "', '" + name
                + "', '', now(), 1, 1, " + statusId + ", 1, 'PI', 'observational', 'MUW', 'S_" + uid.replace("-", "")
                + "') RETURNING study_id");
    }

    private static int insertItem(String status, String name, Integer boundSubject, Integer originStudy)
            throws SQLException {
        return insertReturningId("INSERT INTO ingest_item (kind, source_kind, device, stored_path, original_filename,"
                + " received_at, status, bound_study_subject_id, origin_study_id) VALUES ('image', 'upload', 'cam', '/tmp/"
                + name + "', '" + name + ".jpg', now(), '" + status + "', "
                + (boundSubject == null ? "NULL" : boundSubject.toString()) + ", "
                + (originStudy == null ? "NULL" : originStudy.toString()) + ") RETURNING ingest_item_id");
    }

    /* ---------------- Liquibase ---------------- */

    private static void applyChangelog() throws Exception {
        LaxParsingSpringLiquibase liquibase = new LaxParsingSpringLiquibase();
        liquibase.setDataSource(DATA_SOURCE);
        liquibase.setChangeLog("classpath:migration/master.xml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    /** Rolls back the location changesets and everything run after them. */
    private static void rewindLocations() throws Exception {
        int toRollBack = count("SELECT count(*) FROM databasechangelog WHERE orderexecuted >="
                + " (SELECT MIN(orderexecuted) FROM databasechangelog WHERE filename LIKE '%" + LOCATIONS_FILE + "')");
        assertTrue(toRollBack >= 3, "the location changesets should have run: " + toRollBack);
        Rewinder rewinder = new Rewinder();
        rewinder.setDataSource(DATA_SOURCE);
        rewinder.setChangeLog("classpath:migration/master.xml");
        rewinder.setResourceLoader(new DefaultResourceLoader());
        rewinder.rewind(toRollBack);
        // Liquibase keeps the list of ran changesets per database for the life of the JVM. The
        // rollback removed rows it still remembers, so without this the next update believes the
        // database is up to date.
        Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class).resetAll();
        LockServiceFactory.getInstance().resetAll();
    }

    /** SpringLiquibase keeps its Liquibase factory protected; a rollback needs one. */
    private static final class Rewinder extends LaxParsingSpringLiquibase {
        void rewind(int changesets) throws Exception {
            Scope.child(ChangeLogParserConfiguration.CHANGELOG_PARSE_MODE.getKey(), ChangelogParseMode.LAX,
                    () -> {
                        try (Connection c = getDataSource().getConnection()) {
                            Liquibase liquibase = createLiquibase(c);
                            liquibase.rollback(changesets, "");
                        } catch (SQLException e) {
                            throw new LiquibaseException(e);
                        }
                    });
        }
    }

    /* ---------------- SQL ---------------- */

    private static int count(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getInt(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private static int insertReturningId(String sql) throws SQLException {
        return count(sql);
    }

    /** True when the statement ran, false when the database refused it. */
    private static boolean update(String sql) {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
            return true;
        } catch (SQLException refused) {
            return false;
        }
    }
}
