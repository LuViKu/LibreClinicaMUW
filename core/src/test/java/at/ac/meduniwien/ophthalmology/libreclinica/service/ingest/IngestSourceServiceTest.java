/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.IssuedToken;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Reason;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Refusal;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService.Source;

/**
 * DR-040 phase 2 — which token the device endpoints accept, and what looking
 * one up writes.
 *
 * <p>The database is replaced by a recording fake, so these pin the decisions
 * (a revoked uploader, a disabled or missing location, an unknown token are all
 * "no such source"; {@code last_used_at} is renewed at most once a minute and
 * only when it is that old; a lookup binds the hash and never the token; a
 * failure logs neither). The SQL itself, the uniqueness and the transactions
 * are exercised against PostgreSQL in {@code IngestSourceApiDatabaseIT}.
 */
public class IngestSourceServiceTest {

    private static final String TOKEN = "mwu_" + "k".repeat(43);

    private Fake db;
    private IngestSourceService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @Before
    public void setUp() {
        db = new Fake();
        service = new IngestSourceService(db.dataSource());
        logger = (Logger) LoggerFactory.getLogger(IngestSourceService.class);
        logger.setLevel(Level.ALL);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @After
    public void tearDown() {
        logger.detachAppender(logs);
    }

    /* ---------------------------- the lookup ---------------------------- */

    @Test
    public void aStringThatIsNotShapedLikeATokenIsRefusedWithoutTouchingTheDatabase() {
        for (String bad : new String[] {null, "", "mwu_short", "x".repeat(8192), "mwu_" + "k".repeat(42) + "="}) {
            assertEquals(Optional.empty(), service.findActiveByToken(bad));
        }
        assertEquals("no connection was even opened", 0, db.connections);
    }

    @Test
    public void anUnknownTokenIsRefused() {
        db.row = null;
        assertEquals(Optional.empty(), service.findActiveByToken(TOKEN));
        assertEquals(1, db.selects());
        assertEquals("nothing is written for a stranger", 0, db.updates.size());
    }

    @Test
    public void anActiveUploaderAtAnAvailableLocationIsAcceptedWithItsLocation() {
        db.row = row(7L, 3, false, 1, 5.0);

        Optional<Source> found = service.findActiveByToken(TOKEN);

        assertTrue(found.isPresent());
        Source s = found.get();
        assertEquals(7L, s.id());
        assertEquals("uploader", s.kind());
        assertNull(s.sourceKey());
        assertEquals("Clarus 1", s.displayName());
        assertEquals(Integer.valueOf(3), s.locationId());
        assertEquals("kkkkkkkk", s.tokenHint());
        assertEquals("active", s.status());
        assertTrue(s.active());
    }

    @Test
    public void theLookupBindsTheHashAndNeverTheToken() {
        db.row = row(7L, 3, false, 1, 5.0);

        service.findActiveByToken(TOKEN);

        assertEquals(List.of(UploaderTokens.hash(TOKEN)), db.bound.get(0));
        for (List<Object> params : db.bound) {
            assertFalse("the token itself was sent to the database: " + params, params.contains(TOKEN));
        }
        for (String sql : db.prepared) {
            assertFalse("the token is in the SQL text", sql.contains(TOKEN));
        }
    }

    @Test
    public void aRevokedUploaderIsRefused() {
        db.row = row(7L, 3, true, 1, 5.0);
        assertEquals(Optional.empty(), service.findActiveByToken(TOKEN));
        assertEquals(0, db.updates.size());
    }

    @Test
    public void anUploaderAtADisabledLocationIsRefused() {
        db.row = row(7L, 3, false, 5, 5.0);
        assertEquals(Optional.empty(), service.findActiveByToken(TOKEN));
        db.row = row(7L, 3, false, 7, 5.0);
        assertEquals("auto-removed too", Optional.empty(), service.findActiveByToken(TOKEN));
    }

    @Test
    public void anUploaderWithoutALocationIsRefused() {
        db.row = row(7L, null, false, null, 5.0);
        assertEquals(Optional.empty(), service.findActiveByToken(TOKEN));
    }

    @Test
    public void theRulesAsFunctions() {
        assertTrue(IngestSourceService.usable(false, 3, 1));
        assertFalse("revoked", IngestSourceService.usable(true, 3, 1));
        assertFalse("no location", IngestSourceService.usable(false, null, null));
        assertFalse("location missing from the join", IngestSourceService.usable(false, 3, null));
        assertFalse("location disabled", IngestSourceService.usable(false, 3, 5));
        assertFalse("location auto-removed", IngestSourceService.usable(false, 3, 7));
        assertFalse("a status that is not available", IngestSourceService.usable(false, 3, 2));
    }

    /* ------------------------ last_used_at throttle ---------------------- */

    @Test
    public void aRecentLastUsedIsNotWrittenAgain() {
        db.row = row(7L, 3, false, 1, 5.0);
        assertTrue(service.findActiveByToken(TOKEN).isPresent());
        db.row = row(7L, 3, false, 1, 59.9);
        assertTrue(service.findActiveByToken(TOKEN).isPresent());
        assertEquals("an ordinary request reads one row and writes none", 0, db.updates.size());
    }

    @Test
    public void aLastUsedOlderThanAMinuteIsRenewedOnceAndNeverUsedIsRenewedToo() {
        db.row = row(7L, 3, false, 1, 60.0);
        assertTrue(service.findActiveByToken(TOKEN).isPresent());
        assertEquals(1, db.updates.size());
        assertTrue(db.updates.get(0), db.updates.get(0).startsWith("UPDATE ingest_source SET last_used_at = now()"));
        assertTrue("the statement repeats the condition, so two requests write once: " + db.updates.get(0),
                db.updates.get(0).contains("last_used_at IS NULL OR last_used_at < now() - interval '60 seconds'"));
        assertEquals(List.of(7L), db.bound.get(db.bound.size() - 1));

        db.row = row(7L, 3, false, 1, null);
        assertTrue(service.findActiveByToken(TOKEN).isPresent());
        assertEquals("never used is due", 2, db.updates.size());
    }

    @Test
    public void theThrottleAsAFunction() {
        assertTrue(IngestSourceService.touchDue(null));
        assertFalse(IngestSourceService.touchDue(0.0));
        assertFalse(IngestSourceService.touchDue(59.999));
        assertTrue(IngestSourceService.touchDue(60.0));
        assertTrue(IngestSourceService.touchDue(86_400.0));
    }

    @Test
    public void aRenewalThatFailsDoesNotFailTheRequest() {
        db.row = row(7L, 3, false, 1, 3600.0);
        db.failUpdates = true;
        assertTrue(service.findActiveByToken(TOKEN).isPresent());
    }

    /* ------------------------------ failures ----------------------------- */

    @Test
    public void aDatabaseThatCannotBeReachedRefusesTheTokenAndLogsNeitherTheTokenNorItsHash() {
        db.failOnConnect = true;

        assertEquals(Optional.empty(), service.findActiveByToken(TOKEN));

        assertFalse("something should have been logged", logs.list.isEmpty());
        for (ILoggingEvent e : logs.list) {
            String all = e.getFormattedMessage() + " " + e.getMessage() + " " + String.valueOf(e.getThrowableProxy())
                    + " " + java.util.Arrays.toString(e.getArgumentArray());
            assertFalse("the token is in a log line: " + all, all.contains(TOKEN));
            assertFalse("the hash is in a log line: " + all, all.contains(UploaderTokens.hash(TOKEN)));
            assertFalse("the database's message is in a log line: " + all, all.contains("connection refused"));
        }
    }

    @Test
    public void theLogOfAGoodLookupHoldsNeitherTheTokenNorItsHash() {
        db.row = row(7L, 3, false, 1, 3600.0);
        service.findActiveByToken(TOKEN);
        for (ILoggingEvent e : logs.list) {
            String all = e.getFormattedMessage();
            assertFalse(all, all.contains(TOKEN) || all.contains(UploaderTokens.hash(TOKEN)));
        }
    }

    @Test
    public void aTokenThatWasJustIssuedIsNotPrintedByItsOwnToString() {
        Source source = new Source(1L, "uploader", null, "Clarus 1", 3, "kkkkkkkk", "active");
        String printed = new IssuedToken(source, TOKEN).toString();
        assertFalse(printed, printed.contains(TOKEN));
        assertFalse(printed, printed.contains(TOKEN.substring(4)));
        assertTrue(printed, printed.contains("Clarus 1"));
    }

    /* ------------------------------ validation --------------------------- */

    @Test
    public void aNameIsTrimmedAndBoundedAndVisible() {
        assertEquals("Clarus 1", IngestSourceService.cleanName("  Clarus 1 \n", "displayName"));
        assertEquals("a".repeat(64), IngestSourceService.cleanName("a".repeat(64), "displayName"));
        assertEquals("64 code points are 64 characters, however many chars Java needs",
                "👁".repeat(64), IngestSourceService.cleanName("👁".repeat(64), "displayName"));
        refused(() -> IngestSourceService.cleanName(null, "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("   ", "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("a".repeat(65), "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("two\nlines", "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("tab\there", "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("zero​width", "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("right‮to-left", "displayName"), "displayName");
        refused(() -> IngestSourceService.cleanName("lone\uD800surrogate", "displayName"), "displayName");
    }

    @Test
    public void aDicomAeTitleIsOneToSixteenOfTheAllowedCharacters() {
        assertEquals("CLARUS", IngestSourceService.cleanKey("dicom_ae", "  CLARUS "));
        assertEquals("a.B_c-1 2", IngestSourceService.cleanKey("dicom_ae", "a.B_c-1 2"));
        assertEquals("A".repeat(16), IngestSourceService.cleanKey("dicom_ae", "A".repeat(16)));
        for (String bad : new String[] {null, "", "   ", "A".repeat(17), "ÄRZTE", "back\\slash", "semi;colon",
                "new\nline", "a/b", "quote'", "x\u0000y"}) {
            refused(() -> IngestSourceService.cleanKey("dicom_ae", bad), "sourceKey");
        }
    }

    @Test
    public void aRemidioAccountKeyIsOneToSixtyFourVisibleCharacters() {
        assertEquals("lab@clinic.example", IngestSourceService.cleanKey("remidio", " lab@clinic.example "));
        assertEquals("k".repeat(64), IngestSourceService.cleanKey("remidio", "k".repeat(64)));
        refused(() -> IngestSourceService.cleanKey("remidio", ""), "sourceKey");
        refused(() -> IngestSourceService.cleanKey("remidio", "k".repeat(65)), "sourceKey");
        refused(() -> IngestSourceService.cleanKey("remidio", "bad\u0007bell"), "sourceKey");
    }

    @Test
    public void onlyAutomaticKindsCanBeAutoSources() {
        for (String kind : new String[] {"uploader", "portal", "other", null}) {
            try {
                service.upsertAutoSource(kind, "CLARUS", "Clarus");
                fail("kind " + kind + " should be refused");
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        assertEquals("a refused kind never reaches the database", 0, db.connections);
    }

    @Test
    public void aBadAeTitleIsRefusedBeforeTheDatabase() {
        try {
            service.upsertAutoSource("dicom_ae", "THIS TITLE IS TOO LONG", "x");
            fail();
        } catch (Refusal r) {
            assertEquals(Reason.INVALID, r.reason());
            assertEquals("sourceKey", r.field());
        }
        assertEquals(0, db.connections);
    }

    @Test
    public void anExistingAutoSourceIsReturnedWithoutWritingOrAuditing() {
        db.row = autoRow(9L, "dicom_ae", "CLARUS", null, null, 5.0);

        long id = service.upsertAutoSource("dicom_ae", "CLARUS", "ignored");
        assertEquals(9L, id);
        Source source = service.resolveAutoSource("dicom_ae", "CLARUS", "ignored");
        assertEquals("CLARUS", source.sourceKey());
        assertNull("unassigned", source.locationId());

        assertEquals("a sender seen a moment ago is neither renewed nor created", 0, db.updates.size());
        for (String sql : db.prepared) assertFalse(sql, sql.startsWith("INSERT"));
    }

    private static void refused(Runnable action, String field) {
        try {
            action.run();
            fail("expected a refusal on " + field);
        } catch (Refusal r) {
            assertEquals(Reason.INVALID, r.reason());
            assertEquals(field, r.field());
        }
    }

    /* ----------------------------- the fake ------------------------------ */

    private static Map<String, Object> row(Long id, Integer location, boolean revoked, Integer locationStatus,
                                           Double usedAge) {
        Map<String, Object> m = new HashMap<>();
        m.put("ingest_source_id", id);
        m.put("kind", "uploader");
        m.put("source_key", null);
        m.put("display_name", "Clarus 1");
        m.put("location_id", location);
        m.put("token_hint", "kkkkkkkk");
        m.put("revoked", revoked);
        m.put("location_status", locationStatus);
        m.put("used_age", usedAge);
        return m;
    }

    private static Map<String, Object> autoRow(Long id, String kind, String key, Integer location,
                                               Integer locationStatus, Double usedAge) {
        Map<String, Object> m = row(id, location, false, locationStatus, usedAge);
        m.put("kind", kind);
        m.put("source_key", key);
        m.put("display_name", key);
        m.put("token_hint", null);
        return m;
    }

    /** A recording stand-in for the JDBC calls the lookup makes. */
    private static final class Fake {
        Map<String, Object> row;
        boolean failOnConnect;
        boolean failUpdates;
        int connections;
        final List<String> prepared = new ArrayList<>();
        final List<List<Object>> bound = new ArrayList<>();
        final List<String> updates = new ArrayList<>();

        int selects() {
            return (int) prepared.stream().filter(s -> s.startsWith("SELECT")).count();
        }

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            if (failOnConnect) {
                                throw new SQLException("connection refused for " + TOKEN + " "
                                        + UploaderTokens.hash(TOKEN), "08001");
                            }
                            connections++;
                            return connection();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> statement((String) args[0]);
                        case "close", "setAutoCommit", "commit", "rollback" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement(String sql) {
            prepared.add(sql);
            List<Object> params = new ArrayList<>();
            bound.add(params);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setObject", "setString", "setLong", "setInt" -> {
                                params.add(args[1]);
                                return null;
                            }
                            case "executeQuery" -> {
                                return resultSet(sql.startsWith("SELECT") ? row : null);
                            }
                            case "executeUpdate" -> {
                                if (failUpdates) throw new SQLException("update refused", "40001");
                                updates.add(sql);
                                return 1;
                            }
                            case "close" -> {
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private static ResultSet resultSet(Map<String, Object> row) {
            boolean[] served = {false};
            Object[] last = {null};
            return (ResultSet) Proxy.newProxyInstance(Fake.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("next".equals(name)) {
                            boolean has = row != null && !served[0];
                            served[0] = true;
                            return has;
                        }
                        if ("wasNull".equals(name)) return last[0] == null;
                        if ("close".equals(name)) return null;
                        if (name.startsWith("get") && args != null && args[0] instanceof String label) {
                            Object v = row.get(label);
                            last[0] = v;
                            return switch (name) {
                                case "getString" -> v;
                                case "getInt" -> v == null ? 0 : ((Number) v).intValue();
                                case "getLong" -> v == null ? 0L : ((Number) v).longValue();
                                case "getDouble" -> v == null ? 0.0 : ((Number) v).doubleValue();
                                case "getBoolean" -> v != null && (Boolean) v;
                                default -> throw new UnsupportedOperationException(name);
                            };
                        }
                        throw new UnsupportedOperationException(name);
                    });
        }
    }
}
