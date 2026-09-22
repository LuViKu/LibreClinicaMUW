/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-node health of the remote retinal-inference cluster, for the sysadmin
 * System Status page (2026-09-22).
 *
 * <p>Exists because of what the 2026-09 outage looked like from inside the
 * app: nothing. {@code remotePushUrl} named one host, that host was dead for
 * nineteen days, and the only symptom was jobs failing with
 * "Remote /run returned null". This class turns the questions an operator
 * had to answer by hand that day — <em>is it listening, is it healthy, is it
 * missing tasks, which node and card is it actually on</em> — into one
 * probe per node that the page can render.
 *
 * <p>Two design points that are easy to get wrong:
 * <ul>
 *   <li><b>Probe the nodes, not {@code remotePushUrl}.</b> Behind the nginx
 *       failover the push URL answers {@code /health} as long as
 *       <em>either</em> node is up, so it reads "healthy" while the primary
 *       is dead and the backup carries the load. The node list is its own
 *       config key for that reason.</li>
 *   <li><b>A hung node must not hang the page.</b> Probes run in parallel
 *       with a short per-request timeout, and a timeout is a result
 *       ({@code unreachable}), not an exception.</li>
 * </ul>
 *
 * <p>The classification is a pure function of the HTTP response so it can be
 * unit-tested without a server; the I/O is the thin layer around it.
 */
public final class RetinalClusterHealth {

    /** The full task set a healthy server registers. Anything less is degraded. */
    public static final List<String> EXPECTED_TASKS =
            List.of("bm", "fluid", "ga", "layers", "onl", "pr");

    /** A node to probe: a display name and the sidecar's base URL (no {@code /health}). */
    public record NodeSpec(String name, String url) { }

    /**
     * One probe result. {@code state} is one of {@code healthy},
     * {@code degraded} (up, but not every expected task registered),
     * {@code unhealthy} (answered, but not a good {@code /health}) or
     * {@code unreachable} (no usable answer at all).
     */
    public record NodeStatus(String name,
                             String url,
                             String state,
                             List<String> supportedTasks,
                             List<String> missingTasks,
                             Long latencyMs,
                             String node,
                             String gpuDevice,
                             String gpuName,
                             String error) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Duration timeout;
    private final HttpClient http;

    public RetinalClusterHealth(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ---- config ------------------------------------------------------------

    /**
     * Parse {@code core.retinalInference.clusterNodes}:
     * {@code "on3=http://a:8000,cn6=http://b:8000"}. Whitespace around
     * names, URLs and separators is ignored; entries without {@code =}
     * take the URL as their name; empty entries are skipped.
     *
     * <p>When the list is blank the single {@code fallbackUrl}
     * ({@code remotePushUrl}) is probed under the name {@code "remote"}, so a
     * site that never configured the node list still gets one row. Both
     * blank means nothing is configured and the result is empty.
     */
    public static List<NodeSpec> parseNodes(String raw, String fallbackUrl) {
        List<NodeSpec> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String entry : raw.split(",")) {
                String e = entry.trim();
                if (e.isEmpty()) continue;
                int eq = e.indexOf('=');
                String name = eq < 0 ? e : e.substring(0, eq).trim();
                String url = eq < 0 ? e : e.substring(eq + 1).trim();
                if (url.isEmpty()) continue;
                out.add(new NodeSpec(name.isEmpty() ? url : name, stripSlash(url)));
            }
        }
        if (out.isEmpty() && fallbackUrl != null && !fallbackUrl.isBlank()) {
            out.add(new NodeSpec("remote", stripSlash(fallbackUrl.trim())));
        }
        return out;
    }

    private static String stripSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    // ---- classification (pure) ---------------------------------------------

    /** Classify a {@code /health} answer. Never throws — a bad body is {@code unhealthy}. */
    public static NodeStatus classify(NodeSpec spec, int httpStatus, String body, long latencyMs) {
        if (httpStatus != 200) {
            return new NodeStatus(spec.name(), spec.url(), "unhealthy", List.of(), EXPECTED_TASKS,
                    latencyMs, null, null, null, "HTTP " + httpStatus);
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return new NodeStatus(spec.name(), spec.url(), "unhealthy", List.of(), EXPECTED_TASKS,
                    latencyMs, null, null, null, "unparseable /health body");
        }
        if (json == null || !json.isObject()) {
            return new NodeStatus(spec.name(), spec.url(), "unhealthy", List.of(), EXPECTED_TASKS,
                    latencyMs, null, null, null, "unparseable /health body");
        }
        String node = text(json, "node");
        String gpuDevice = text(json, "gpu_device");
        String gpuName = text(json, "gpu_name");

        if (!"ok".equals(text(json, "status"))) {
            return new NodeStatus(spec.name(), spec.url(), "unhealthy", List.of(), EXPECTED_TASKS,
                    latencyMs, node, gpuDevice, gpuName, "status=" + text(json, "status"));
        }
        List<String> supported = new ArrayList<>();
        JsonNode tasks = json.get("supported_tasks");
        if (tasks != null && tasks.isArray()) {
            for (JsonNode t : tasks) supported.add(t.asText());
        }
        Collections.sort(supported);
        List<String> missing = new ArrayList<>();
        for (String t : EXPECTED_TASKS) {
            if (!supported.contains(t)) missing.add(t);
        }
        String state = missing.isEmpty() ? "healthy" : "degraded";
        return new NodeStatus(spec.name(), spec.url(), state, supported, missing,
                latencyMs, node, gpuDevice, gpuName, null);
    }

    /** The result for a node that gave no usable answer (refused, timed out, DNS…). */
    public static NodeStatus unreachable(NodeSpec spec, String error, long latencyMs) {
        return new NodeStatus(spec.name(), spec.url(), "unreachable", List.of(), EXPECTED_TASKS,
                latencyMs, null, null, null, error);
    }

    private static String text(JsonNode json, String field) {
        JsonNode n = json.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    // ---- I/O -----------------------------------------------------------------

    /** GET {@code <url>/health}; a transport failure is an {@code unreachable} result, not an exception. */
    public NodeStatus probe(NodeSpec spec) {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(spec.url() + "/health"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return classify(spec, resp.statusCode(), resp.body(), millisSince(t0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable(spec, "interrupted", millisSince(t0));
        } catch (Exception e) {
            // IOException (refused / reset), HttpTimeoutException, IllegalArgumentException
            // (bad URL) — all the same to the operator: "not answering, here's why".
            String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return unreachable(spec, why, millisSince(t0));
        }
    }

    /** Probe every node concurrently, so one dead node costs one timeout, not N. Order is preserved. */
    public List<NodeStatus> probeAll(List<NodeSpec> specs) {
        if (specs.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(specs.size());
        try {
            List<CompletableFuture<NodeStatus>> futures = new ArrayList<>();
            for (NodeSpec spec : specs) {
                futures.add(CompletableFuture.supplyAsync(() -> probe(spec), pool));
            }
            List<NodeStatus> out = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                NodeSpec spec = specs.get(i);
                try {
                    out.add(futures.get(i).join());
                } catch (RuntimeException e) {
                    out.add(unreachable(spec, "probe failed: " + e.getMessage(), null));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long millisSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    // ---- monitor log -----------------------------------------------------------

    /**
     * The last {@code max} lines of the cron monitor's log, oldest first.
     * An unreadable or missing file is an empty list; callers that need to
     * tell "no alerts" from "no log" check {@link Files#isReadable} first.
     * Reads the whole file — it holds a line per state change, not per tick,
     * so it stays small by construction.
     */
    public static List<String> tailLines(Path file, int max) {
        if (file == null || max <= 0 || !Files.isReadable(file)) return List.of();
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - max);
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (IOException e) {
            return List.of();
        }
    }
}
