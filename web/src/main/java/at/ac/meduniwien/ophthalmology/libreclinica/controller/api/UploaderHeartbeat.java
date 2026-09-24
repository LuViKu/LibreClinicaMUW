/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DR-033 — what an uploader on an acquisition PC may say about itself, and
 * what the System Status page makes of it.
 *
 * <p>A heartbeat arrives unauthenticated, from a program the platform did
 * not build the PC for, so every field is read leniently and bounded:
 * a malformed optional field is dropped rather than refusing the whole
 * heartbeat (a PC that cannot report its disk must still be seen as alive),
 * while the two fields that identify the sender — the instance id and the
 * program kind — must be well-formed or the heartbeat is refused.
 *
 * <p>Times are sent as ages ("seconds since the last upload"), never as
 * clock readings: the PC's clock is not the server's, and an acquisition PC
 * that drifts by an hour would otherwise report uploads from the future.
 *
 * <p>Pure: no database, no configuration. The controllers call it.
 */
final class UploaderHeartbeat {

    private UploaderHeartbeat() {
    }

    static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._+-]{1,32}$");
    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");
    /** Characters kept from a PC's name; anything else becomes '-'. */
    static final Pattern NAME_DISALLOWED = Pattern.compile("[^A-Za-z0-9 ._()-]");

    static final int MAX_NAME = 64;
    static final int MAX_PROBLEMS = 10;
    static final int DEFAULT_INTERVAL_SEC = 120;
    static final int MIN_INTERVAL_SEC = 30;
    static final int MAX_INTERVAL_SEC = 3600;
    static final long MAX_AGE_SEC = 10L * 366 * 24 * 3600;
    static final int MAX_COUNT = 1_000_000;
    static final long MAX_BYTES = 1L << 60;

    /** Why a program stopped: the menu's Exit, or Windows ending the session (logoff, shutdown). */
    static final Set<String> STOP_REASONS = Set.of("exit", "session-end");

    /** A PC is offline when no heartbeat came for three intervals, and never sooner than this. */
    static final long MIN_OFFLINE_AFTER_SEC = 300;
    /** Files waiting this long while the uploader runs mean something is stuck. */
    static final int STALE_PENDING_MINUTES = 30;
    /** Disk is low below the larger of 5 GiB and 5 % of the disk. */
    static final long LOW_DISK_FLOOR_BYTES = 5L * 1024 * 1024 * 1024;
    static final double LOW_DISK_FRACTION = 0.05;

    /** The heartbeat's identifying fields were missing or malformed. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /** One heartbeat, every field already bounded. Nullable boxes = "not reported". */
    record Parsed(String instanceUid, String kind, String name, String version,
                  boolean running, String stopReason, boolean enabled, int intervalSec,
                  Long secondsSinceActivity, Long secondsSinceUpload,
                  Integer uploadedToday, Integer pendingFiles, Integer oldestPendingMinutes,
                  Integer failedFiles, Long diskFreeBytes, Long diskTotalBytes,
                  List<String> problems) {
    }

    static Parsed parse(JsonNode n) throws Invalid {
        if (n == null || !n.isObject()) throw new Invalid("the heartbeat must be a JSON object");
        String uid = text(n, "instanceId");
        if (uid == null || !UUID.matcher(uid).matches()) {
            throw new Invalid("instanceId must be a UUID");
        }
        String kind = text(n, "kind");
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new Invalid("kind must be a lower-case program name such as export-watcher");
        }
        String version = text(n, "version");
        if (version != null && !VERSION.matcher(version).matches()) version = null;

        boolean running = bool(n, "running", true);
        String stopReason = running ? null : text(n, "stopReason");
        if (stopReason != null && !STOP_REASONS.contains(stopReason)) stopReason = "exit";
        if (!running && stopReason == null) stopReason = "exit";

        Long free = bytes(n, "diskFreeBytes");
        Long total = bytes(n, "diskTotalBytes");
        if (free != null && total != null && free > total) {
            free = null;
            total = null;
        }

        return new Parsed(
                uid.toLowerCase(Locale.ROOT),
                kind,
                sanitizeName(text(n, "name")),
                version,
                running,
                stopReason,
                bool(n, "enabled", true),
                clampInterval(n.get("heartbeatIntervalSec")),
                age(n, "secondsSinceActivity"),
                age(n, "secondsSinceUpload"),
                count(n, "uploadedToday"),
                count(n, "pendingFiles"),
                count(n, "oldestPendingMinutes"),
                count(n, "failedFiles"),
                free,
                total,
                problems(n.get("problems")));
    }

    /**
     * A PC's name as the page may show it: letters, digits, space and
     * {@code ._()-}; anything else (an umlaut, a control character, markup)
     * becomes a dash. Never empty.
     */
    static String sanitizeName(String raw) {
        if (raw == null) return "unnamed";
        String s = NAME_DISALLOWED.matcher(raw.strip()).replaceAll("-").replaceAll("-{2,}", "-").strip();
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).strip();
        return s.isEmpty() || s.equals("-") ? "unnamed" : s;
    }

    static String joinProblems(List<String> problems) {
        return problems.isEmpty() ? null : String.join(",", problems);
    }

    static List<String> splitProblems(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) return out;
        for (String s : stored.split(",")) {
            if (CODE.matcher(s).matches()) out.add(s);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* status                                                              */
    /* ------------------------------------------------------------------ */

    /** What the page needs to classify one uploader. */
    record Observed(long secondsSinceSeen, int intervalSec, boolean running, boolean enabled,
                    List<String> problems, Integer failedFiles, Integer oldestPendingMinutes,
                    Long diskFreeBytes, Long diskTotalBytes) {
    }

    /**
     * The uploader's state and the reasons behind a warning.
     *
     * @param status one of {@code ok}, {@code warning}, {@code disabled},
     *               {@code stopped}, {@code offline}
     * @param issues coded reasons, the program's own first, then what the
     *               server derived; the page translates known codes
     */
    record Status(String status, List<String> issues) {
    }

    static Status classify(Observed o) {
        // The last word was "I am stopping": that stays true however long ago
        // it was said, so it outranks offline.
        if (!o.running()) return new Status("stopped", List.of());
        long offlineAfter = Math.max(3L * o.intervalSec(), MIN_OFFLINE_AFTER_SEC);
        if (o.secondsSinceSeen() > offlineAfter) return new Status("offline", List.of());

        LinkedHashSet<String> issues = new LinkedHashSet<>(o.problems());
        if (o.failedFiles() != null && o.failedFiles() > 0) issues.add("files-failed");
        if (o.enabled() && o.oldestPendingMinutes() != null
                && o.oldestPendingMinutes() >= STALE_PENDING_MINUTES) {
            issues.add("pending-stale");
        }
        if (diskLow(o.diskFreeBytes(), o.diskTotalBytes())) issues.add("disk-low");

        if (!o.enabled()) return new Status("disabled", List.copyOf(issues));
        return new Status(issues.isEmpty() ? "ok" : "warning", List.copyOf(issues));
    }

    static boolean diskLow(Long free, Long total) {
        if (free == null || total == null || total <= 0) return false;
        long threshold = Math.max(LOW_DISK_FLOOR_BYTES, (long) (total * LOW_DISK_FRACTION));
        return free < threshold;
    }

    /* ------------------------------------------------------------------ */
    /* lenient field readers                                               */
    /* ------------------------------------------------------------------ */

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(JsonNode n, String field, boolean fallback) {
        JsonNode v = n.get(field);
        return v != null && v.isBoolean() ? v.asBoolean() : fallback;
    }

    private static int clampInterval(JsonNode v) {
        if (v == null || !v.canConvertToInt()) return DEFAULT_INTERVAL_SEC;
        return Math.max(MIN_INTERVAL_SEC, Math.min(MAX_INTERVAL_SEC, v.asInt()));
    }

    private static Long age(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) return null;
        long s = v.asLong();
        return s < 0 || s > MAX_AGE_SEC ? null : s;
    }

    private static Integer count(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) return null;
        long c = v.asLong();
        if (c < 0) return null;
        return (int) Math.min(c, MAX_COUNT);
    }

    private static Long bytes(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) return null;
        long b = v.asLong();
        return b < 0 || b > MAX_BYTES ? null : b;
    }

    private static List<String> problems(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || !v.isArray()) return out;
        for (JsonNode e : v) {
            if (out.size() >= MAX_PROBLEMS) break;
            if (!e.isTextual()) continue;
            String code = e.asText().strip();
            if (CODE.matcher(code).matches() && !out.contains(code)) out.add(code);
        }
        return out;
    }
}
