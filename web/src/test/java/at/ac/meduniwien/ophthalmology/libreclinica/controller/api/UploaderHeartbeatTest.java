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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Invalid;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Observed;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Parsed;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Status;

/**
 * DR-033 — what a heartbeat may carry, and how the page reads it.
 *
 * <p>Pinned: the two identifying fields refuse a heartbeat when malformed,
 * everything else is dropped rather than refusing it (a PC that cannot read
 * its disk must still count as alive); a PC's name can never carry markup or
 * an umlaut into the page; and the status rules — "stopped" outranks
 * offline, offline needs three missed intervals and never less than five
 * minutes, a disabled program still shows its problems, and the server adds
 * what it can see (failed files, stale queue, low disk).
 */
class UploaderHeartbeatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UID = "8C3E1F2A-4B5C-4D6E-8F70-112233445566";

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    private static Parsed parse(String s) throws Exception {
        return UploaderHeartbeat.parse(json(s));
    }

    /* ---- parsing ------------------------------------------------------ */

    @Test
    void aFullHeartbeatIsReadField_by_field() throws Exception {
        Parsed p = parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"export-watcher\",\"name\":\"CLARUS-PC\","
                + "\"version\":\"2026-09-24\",\"running\":true,\"enabled\":true,\"heartbeatIntervalSec\":120,"
                + "\"secondsSinceActivity\":15,\"secondsSinceUpload\":300,\"uploadedToday\":12,\"pendingFiles\":2,"
                + "\"oldestPendingMinutes\":1,\"failedFiles\":0,\"diskFreeBytes\":1000,\"diskTotalBytes\":5000,"
                + "\"problems\":[\"server-unreachable\"]}");

        assertEquals(UID.toLowerCase(), p.instanceUid());
        assertEquals("export-watcher", p.kind());
        assertEquals("CLARUS-PC", p.name());
        assertEquals("2026-09-24", p.version());
        assertTrue(p.running());
        assertNull(p.stopReason());
        assertEquals(120, p.intervalSec());
        assertEquals(15L, p.secondsSinceActivity());
        assertEquals(300L, p.secondsSinceUpload());
        assertEquals(12, p.uploadedToday());
        assertEquals(2, p.pendingFiles());
        assertEquals(1000L, p.diskFreeBytes());
        assertEquals(List.of("server-unreachable"), p.problems());
    }

    @Test
    void anInstanceIdThatIsNotAUuidIsRefused() {
        assertThrows(Invalid.class, () -> parse("{\"instanceId\":\"CLARUS-PC\",\"kind\":\"export-watcher\"}"));
        assertThrows(Invalid.class, () -> parse("{\"kind\":\"export-watcher\"}"));
    }

    @Test
    void aMissingOrMalformedKindIsRefused() {
        assertThrows(Invalid.class, () -> parse("{\"instanceId\":\"" + UID + "\"}"));
        assertThrows(Invalid.class, () -> parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"Export Watcher\"}"));
        assertThrows(Invalid.class, () -> parse("[1,2,3]"));
    }

    @Test
    void malformedOptionalFieldsAreDroppedNotFatal() throws Exception {
        Parsed p = parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"optomed-bridge\","
                + "\"version\":\"<script>\",\"pendingFiles\":\"many\",\"failedFiles\":-3,"
                + "\"secondsSinceUpload\":-5,\"diskFreeBytes\":9000,\"diskTotalBytes\":10,"
                + "\"heartbeatIntervalSec\":5,\"problems\":[\"ok-code\",\"Bad Code\",42,\"ok-code\"]}");

        assertNull(p.version());
        assertNull(p.pendingFiles());
        assertNull(p.failedFiles());
        assertNull(p.secondsSinceUpload());
        // free > total cannot both be right: neither is kept
        assertNull(p.diskFreeBytes());
        assertNull(p.diskTotalBytes());
        assertEquals(UploaderHeartbeat.MIN_INTERVAL_SEC, p.intervalSec());
        assertEquals(List.of("ok-code"), p.problems());
    }

    @Test
    void atMostTenProblemsAreKept() throws Exception {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < 25; i++) codes.append(i == 0 ? "" : ",").append("\"code-").append(i).append('"');
        Parsed p = parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"export-watcher\",\"problems\":[" + codes + "]}");

        assertEquals(UploaderHeartbeat.MAX_PROBLEMS, p.problems().size());
        // and they fit the column once joined
        assertTrue(UploaderHeartbeat.joinProblems(p.problems()).length() <= 440);
    }

    @Test
    void aStoppingProgramNamesWhy_andAnUnknownReasonReadsAsExit() throws Exception {
        Parsed shutdown = parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"export-watcher\","
                + "\"running\":false,\"stopReason\":\"session-end\"}");
        Parsed odd = parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"export-watcher\","
                + "\"running\":false,\"stopReason\":\"crashed-hard\"}");
        Parsed running = parse("{\"instanceId\":\"" + UID + "\",\"kind\":\"export-watcher\","
                + "\"running\":true,\"stopReason\":\"exit\"}");

        assertEquals("session-end", shutdown.stopReason());
        assertEquals("exit", odd.stopReason());
        assertNull(running.stopReason());
    }

    @Test
    void aPcNameCannotCarryMarkupOrUmlauts() {
        assertEquals("CLARUS-PC", UploaderHeartbeat.sanitizeName("  CLARUS-PC "));
        assertEquals("K-se-PC (Raum 2.14)", UploaderHeartbeat.sanitizeName("Käse-PC (Raum 2.14)"));
        assertEquals("-img src-x onerror-alert(1)-", UploaderHeartbeat.sanitizeName("<img src=x onerror=alert(1)>"));
        assertEquals("unnamed", UploaderHeartbeat.sanitizeName("   "));
        assertEquals("unnamed", UploaderHeartbeat.sanitizeName(null));
        assertEquals("unnamed", UploaderHeartbeat.sanitizeName("äöü"));
        assertEquals(UploaderHeartbeat.MAX_NAME, UploaderHeartbeat.sanitizeName("x".repeat(200)).length());
    }

    @Test
    void storedProblemsRoundTripAndJunkIsDropped() {
        assertEquals(List.of("a-b", "cd"), UploaderHeartbeat.splitProblems("a-b,cd,NOT OK"));
        assertEquals(List.of(), UploaderHeartbeat.splitProblems(null));
        assertNull(UploaderHeartbeat.joinProblems(List.of()));
    }

    /* ---- status ------------------------------------------------------- */

    private static Observed seen(long secondsAgo) {
        return new Observed(secondsAgo, 120, true, true, List.of(), 0, null, null, null);
    }

    @Test
    void aFreshHeartbeatWithNothingWrongIsOk() {
        Status s = UploaderHeartbeat.classify(seen(30));
        assertEquals("ok", s.status());
        assertTrue(s.issues().isEmpty());
    }

    @Test
    void offlineAfterThreeMissedIntervalsButNeverBeforeFiveMinutes() {
        assertEquals("ok", UploaderHeartbeat.classify(seen(299)).status());
        assertEquals("ok", UploaderHeartbeat.classify(seen(360)).status());
        assertEquals("offline", UploaderHeartbeat.classify(seen(361)).status());

        Observed fast = new Observed(301, 30, true, true, List.of(), 0, null, null, null);
        assertEquals("offline", UploaderHeartbeat.classify(fast).status());
        Observed fastRecent = new Observed(299, 30, true, true, List.of(), 0, null, null, null);
        assertEquals("ok", UploaderHeartbeat.classify(fastRecent).status());
    }

    @Test
    void stoppedOutranksOffline() {
        Observed stoppedLongAgo = new Observed(86_400, 120, false, true, List.of(), 0, null, null, null);
        assertEquals("stopped", UploaderHeartbeat.classify(stoppedLongAgo).status());
    }

    @Test
    void theServerAddsWhatItCanSee() {
        long gib = 1024L * 1024 * 1024;
        Observed o = new Observed(10, 120, true, true, List.of("server-unreachable"), 3, 45, 2 * gib, 250 * gib);
        Status s = UploaderHeartbeat.classify(o);

        assertEquals("warning", s.status());
        assertEquals(List.of("server-unreachable", "files-failed", "pending-stale", "disk-low"), s.issues());
    }

    @Test
    void aDisabledProgramStaysDisabledButKeepsItsProblems_andAQueueWhileOffIsNotStale() {
        Observed o = new Observed(10, 120, true, false, List.of("watch-folder-missing"), 0, 600, null, null);
        Status s = UploaderHeartbeat.classify(o);

        assertEquals("disabled", s.status());
        assertEquals(List.of("watch-folder-missing"), s.issues());
    }

    @Test
    void lowDiskIsTheLargerOfFiveGibAndFivePercent() {
        long gib = 1024L * 1024 * 1024;
        // 250 GiB disk: threshold 12.5 GiB
        assertTrue(UploaderHeartbeat.diskLow(12 * gib, 250 * gib));
        assertFalse(UploaderHeartbeat.diskLow(13 * gib, 250 * gib));
        // 40 GiB disk: threshold 5 GiB, not 2 GiB
        assertTrue(UploaderHeartbeat.diskLow(4 * gib, 40 * gib));
        assertFalse(UploaderHeartbeat.diskLow(6 * gib, 40 * gib));
        // unknown figures never warn
        assertFalse(UploaderHeartbeat.diskLow(null, 40 * gib));
        assertFalse(UploaderHeartbeat.diskLow(1L, 0L));
    }
}
