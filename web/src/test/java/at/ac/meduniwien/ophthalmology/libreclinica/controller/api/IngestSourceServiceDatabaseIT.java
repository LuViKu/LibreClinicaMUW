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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Failure;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.IssuedToken;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Reason;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Refusal;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Source;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.UploaderTokens;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DR-040 phase 2 — {@code IngestSourceService}, the API the device endpoints
 * and the administration endpoints share, against a real PostgreSQL.
 *
 * <p>{@link IngestSourceApiDatabaseIT} drives the same code through HTTP; this
 * pins what only a caller of the service sees: the refusals and their reasons,
 * the automatic sources (DICOM AE titles, the Remidio account) that the
 * receivers create on first sight, the instance a token reports as, and that a
 * database failure never carries a value out of a statement that handled a
 * token.
 */
@SuppressWarnings("null")
class IngestSourceServiceDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int ACTOR = 1;

    private static int locA;
    private static int locB;
    private static IngestSourceService service;

    @BeforeAll
    static void seed() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection()) {
            locA = LifecycleFixtures.insertOne(c,
                    "INSERT INTO location (code, name) VALUES ('ISS-A', 'ISS Alpha') RETURNING location_id");
            locB = LifecycleFixtures.insertOne(c,
                    "INSERT INTO location (code, name) VALUES ('ISS-B', 'ISS Beta') RETURNING location_id");
        }
        service = new IngestSourceService(DATA_SOURCE);
    }

    @BeforeEach
    void reset() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_table = 'ingest_source'");
        exec("DELETE FROM ingest_item WHERE device = 'iss-cam'");
        exec("DELETE FROM ingest_source");
        exec("UPDATE location SET status_id = 1 WHERE location_id IN (" + locA + ", " + locB + ")");
    }

    /* ====================================================================== */
    /* Issuing, and the refusals of every administration call                  */
    /* ====================================================================== */

    @Test
    void issuingReturnsTheSourceAndTheOneCopyOfTheToken() throws Exception {
        IssuedToken issued = service.issueUploader(locA, " Clarus 1 ", ACTOR);

        Source s = issued.source();
        assertTrue(s.id() > 0);
        assertEquals("uploader", s.kind());
        assertNull(s.sourceKey());
        assertEquals("Clarus 1", s.displayName());
        assertEquals(Integer.valueOf(locA), s.locationId());
        assertEquals("active", s.status());
        assertTrue(UploaderTokens.wellFormed(issued.token()));
        assertEquals(UploaderTokens.hint(issued.token()), s.tokenHint());
        assertFalse(issued.toString().contains(issued.token()), "printing the object leaks nothing");
        assertEquals(s, service.find(s.id()).orElseThrow());
        assertEquals(s, service.findActiveByToken(issued.token()).orElseThrow());
        assertTrue(service.find(s.id() + 1000).isEmpty());
    }

    @Test
    void everyAdministrationCallNamesWhyItRefuses() throws Exception {
        long up = service.issueUploader(locA, "Clarus 1", ACTOR).source().id();
        service.issueUploader(locA, "Clarus 2", ACTOR);
        long ae = service.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        exec("UPDATE location SET status_id = 5 WHERE location_id = " + locB);

        assertReason(Reason.LOCATION_NOT_FOUND, () -> service.issueUploader(99999, "x", ACTOR));
        assertReason(Reason.LOCATION_UNAVAILABLE, () -> service.issueUploader(locB, "x", ACTOR));
        assertReason(Reason.NAME_TAKEN, () -> service.issueUploader(locA, "CLARUS 1", ACTOR));
        assertReason(Reason.INVALID, () -> service.issueUploader(locA, "  ", ACTOR));
        assertReason(Reason.INVALID, () -> service.issueUploader(locA, "x".repeat(65), ACTOR));
        assertReason(Reason.INVALID, () -> service.issueUploader(locA, "a\nb", ACTOR));

        assertReason(Reason.NOT_FOUND, () -> service.replaceToken(99999, ACTOR));
        assertReason(Reason.NOT_FOUND, () -> service.revoke(99999, ACTOR));
        assertReason(Reason.NOT_FOUND, () -> service.rename(99999, "x", ACTOR));
        assertReason(Reason.NOT_FOUND, () -> service.assignLocation(99999, locA, ACTOR));
        assertReason(Reason.NOT_FOUND, () -> service.replaceToken(ae, ACTOR), "a DICOM AE title has no token");
        assertReason(Reason.NOT_FOUND, () -> service.revoke(ae, ACTOR));
        assertReason(Reason.NOT_FOUND, () -> service.rename(ae, "x", ACTOR));
        assertReason(Reason.NAME_TAKEN, () -> service.rename(up, "clarus 2", ACTOR));
        assertReason(Reason.INVALID, () -> service.rename(up, "", ACTOR));
        assertReason(Reason.LOCATION_UNAVAILABLE, () -> service.assignLocation(ae, locB, ACTOR));
        assertReason(Reason.LOCATION_UNAVAILABLE, () -> service.assignLocation(ae, 99999, ACTOR));
        assertReason(Reason.INVALID, () -> service.assignLocation(up, null, ACTOR), "an uploader keeps a location");
        assertEquals(0, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source' "
                + "AND audit_log_event_type_id <> 201"), "refusals are not audited");
    }

    private static void assertReason(Reason expected, Runnable call, String... why) {
        Refusal r = assertThrows(Refusal.class, call::run, why.length == 0 ? expected.name() : why[0]);
        assertEquals(expected, r.reason(), r.getMessage());
    }

    /* ====================================================================== */
    /* Senders that have no credential                                         */
    /* ====================================================================== */

    @Test
    void anAutomaticSourceIsCreatedUnassignedOnFirstSightAndAuditedAsTheSystem() throws Exception {
        long id = service.upsertAutoSource("dicom_ae", "  CLARUS ", "Clarus camera");

        Source s = service.find(id).orElseThrow();
        assertEquals("dicom_ae", s.kind());
        assertEquals("CLARUS", s.sourceKey());
        assertEquals("Clarus camera", s.displayName());
        assertNull(s.locationId(), "a new sender is not at any place yet");
        assertNull(s.tokenHint());
        assertEquals("active", s.status());
        assertNull(one("SELECT token_hash FROM ingest_source WHERE ingest_source_id = " + id));
        assertNotNull(one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id), "seen just now");

        assertEquals(1, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source'"));
        assertEquals(201, count("SELECT audit_log_event_type_id FROM audit_log_event WHERE audit_table = 'ingest_source'"));
        assertEquals(id, count("SELECT entity_id FROM audit_log_event WHERE audit_table = 'ingest_source'"));
        assertNull(one("SELECT user_id FROM audit_log_event WHERE audit_table = 'ingest_source'"), "the system did it");
        assertEquals("kind=dicom_ae; key=CLARUS; location=unassigned",
                one("SELECT new_value FROM audit_log_event WHERE audit_table = 'ingest_source'"));
        assertEquals("Clarus camera", one("SELECT entity_name FROM audit_log_event WHERE audit_table = 'ingest_source'"));
    }

    @Test
    void seeingASenderAgainIsTheSameSourceWithItsNameAndLocationKept() throws Exception {
        long id = service.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        service.assignLocation(id, locA, ACTOR);

        assertEquals(id, service.upsertAutoSource("dicom_ae", "CLARUS", "A different name"));
        Source s = service.resolveAutoSource("dicom_ae", "CLARUS", null);
        assertEquals(id, s.id());
        assertEquals("Clarus camera", s.displayName());
        assertEquals(Integer.valueOf(locA), s.locationId(), "the receiver learns the sender's place in one call");
        assertNotEquals(id, service.upsertAutoSource("dicom_ae", "SPECTRALIS", null), "another title, another source");
        assertNotEquals(id, service.upsertAutoSource("remidio", "CLARUS", null), "the key is per kind");
        assertEquals("SPECTRALIS", service.find(service.upsertAutoSource("dicom_ae", "SPECTRALIS", null))
                .orElseThrow().displayName(), "a blank name defaults to the key");
        assertEquals(3, count("SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = 201"),
                "one row per source created, none for a source seen again");
    }

    @Test
    void manyCallersSeeingTheSameNewSenderGetOneSource() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.upsertAutoSource("dicom_ae", "RACER", "Racer");
            }));
        }
        go.countDown();
        Set<Long> ids = new HashSet<>();
        for (Future<Long> f : results) ids.add(f.get());
        pool.shutdown();

        assertEquals(1, ids.size(), ids.toString());
        assertEquals(1, count("SELECT count(*) FROM ingest_source WHERE source_key = 'RACER'"));
        assertEquals(1, count("SELECT count(*) FROM audit_log_event WHERE audit_table = 'ingest_source'"));
    }

    @Test
    void aBadKeyOrKindIsRefusedBeforeAnyRowIsMade() throws Exception {
        for (String bad : new String[] {"", "TOO LONG AE TITLE!!", "café", "back\\slash"}) {
            Refusal r = assertThrows(Refusal.class, () -> service.upsertAutoSource("dicom_ae", bad, "x"), bad);
            assertEquals(Reason.INVALID, r.reason());
            assertEquals("sourceKey", r.field());
        }
        assertThrows(IllegalArgumentException.class, () -> service.upsertAutoSource("uploader", "X", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.upsertAutoSource("portal", "X", "x"));
        assertEquals(0, count("SELECT count(*) FROM ingest_source"));
    }

    @Test
    void theLastSeenOfASenderIsRenewedAtMostOnceAMinute() throws Exception {
        long id = service.upsertAutoSource("remidio", "main-account", null);
        Object first = one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id);

        service.upsertAutoSource("remidio", "main-account", null);
        assertEquals(first, one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id));

        exec("UPDATE ingest_source SET last_used_at = now() - interval '5 minutes' WHERE ingest_source_id = " + id);
        Object stale = one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id);
        service.upsertAutoSource("remidio", "main-account", null);
        assertNotEquals(stale, one("SELECT last_used_at FROM ingest_source WHERE ingest_source_id = " + id));
    }

    /* ====================================================================== */
    /* The heartbeat instance                                                  */
    /* ====================================================================== */

    @Test
    void anUploaderRecordsTheInstanceItsTokenReportsAs() throws Exception {
        long id = service.issueUploader(locA, "Clarus 1", ACTOR).source().id();

        service.noteInstance(id, "0b9f4c1e-5d7a-4a43-9a61-2f1a6f0c7d11");
        assertEquals("0b9f4c1e-5d7a-4a43-9a61-2f1a6f0c7d11",
                one("SELECT instance_uid FROM ingest_source WHERE ingest_source_id = " + id));
        service.noteInstance(id, null);
        service.noteInstance(id, "  ");
        service.noteInstance(id, "x".repeat(37));
        assertEquals("0b9f4c1e-5d7a-4a43-9a61-2f1a6f0c7d11",
                one("SELECT instance_uid FROM ingest_source WHERE ingest_source_id = " + id), "bad values are ignored");
        service.noteInstance(id + 1000, "0b9f4c1e-5d7a-4a43-9a61-2f1a6f0c7d11"); // no such source: no error
    }

    /* ====================================================================== */
    /* The files a source delivered                                            */
    /* ====================================================================== */

    @Test
    void aFileCarriesTheSourceThatDeliveredItAndASourceWithFilesCannotBeDeleted() throws Exception {
        long id = service.upsertAutoSource("dicom_ae", "CLARUS", "Clarus camera");
        long stamped;
        long unstamped;
        try (Connection c = DATA_SOURCE.getConnection()) {
            stamped = IngestItemRepository.newItem(IngestArtifactStore.Kind.DICOM, "dicom", "/x/iss-stamped.dcm")
                    .device("iss-cam").ingestSourceId(id).insert(c);
            unstamped = IngestItemRepository.newItem(IngestArtifactStore.Kind.IMAGE, "upload", "/x/iss-plain.jpg")
                    .device("iss-cam").ingestSourceId(null).insert(c);
        }

        assertEquals(id, count("SELECT ingest_source_id FROM ingest_item WHERE ingest_item_id = " + stamped));
        assertNull(one("SELECT ingest_source_id FROM ingest_item WHERE ingest_item_id = " + unstamped));
        SQLException refused = assertThrows(SQLException.class,
                () -> exec("DELETE FROM ingest_source WHERE ingest_source_id = " + id));
        assertEquals("23503", refused.getSQLState(), "the key keeps a file's provenance");
    }

    /* ====================================================================== */
    /* A failing database                                                      */
    /* ====================================================================== */

    @Test
    void aStatementThatFailsOnAnIntegrityErrorLeavesNoValueInTheFailure() throws Exception {
        String secret = "mwu_" + "s".repeat(43);
        DataSource quoting = failingOn("INSERT INTO ingest_source (kind, display_name",
                "duplicate key value violates unique constraint \"ux_ingest_source_token_hash\" "
                        + "Detail: Key (token_hash)=(" + UploaderTokens.hash(secret) + ") already exists.", "23505");

        Failure f = assertThrows(Failure.class, () -> new IngestSourceService(quoting).issueUploader(locA, "x", ACTOR));

        assertEquals("23505", f.sqlState());
        assertEquals("issue the uploader", f.what());
        assertNull(f.getCause(), "an integrity error quotes the offending value, so it is not kept as a cause");
        assertFalse(f.getMessage().contains(UploaderTokens.hash(secret)), f.getMessage());
        assertEquals(0, count("SELECT count(*) FROM ingest_source"));

        DataSource down = failingOn("INSERT INTO ingest_source (kind, display_name", "connection lost", "08006");
        Failure g = assertThrows(Failure.class, () -> new IngestSourceService(down).issueUploader(locA, "x", ACTOR));
        assertNotNull(g.getCause(), "other failures keep their cause");
    }

    @Test
    void twoRequestsForOneNameThatBothPassedTheCheckAreANameTakenNotAFailure() throws Exception {
        DataSource racing = failingOn("INSERT INTO ingest_source (kind, display_name",
                "duplicate key value violates unique constraint \"ux_ingest_source_uploader_name\"", "23505");
        Refusal r = assertThrows(Refusal.class, () -> new IngestSourceService(racing).issueUploader(locA, "x", ACTOR));
        assertEquals(Reason.NAME_TAKEN, r.reason());
        assertEquals("displayName", r.field());
    }

    /* ====================================================================== */
    /* Database                                                                */
    /* ====================================================================== */

    /** The first column of the first row, null when there is none or it is NULL. */
    private static Object one(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private static int count(String sql) throws SQLException {
        return LifecycleFixtures.intQuery(sql);
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    /** The database, except that preparing a statement that starts with {@code sqlPrefix} fails. */
    private static DataSource failingOn(String sqlPrefix, String message, String sqlState) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, DATA_SOURCE, args);
                    if (!"getConnection".equals(method.getName())) return result;
                    Connection real = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (connProxy, connMethod, connArgs) -> {
                                if ("prepareStatement".equals(connMethod.getName()) && connArgs != null
                                        && connArgs[0] instanceof String sql && sql.startsWith(sqlPrefix)) {
                                    throw new SQLException(message, sqlState);
                                }
                                return invoke(connMethod, real, connArgs);
                            });
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
