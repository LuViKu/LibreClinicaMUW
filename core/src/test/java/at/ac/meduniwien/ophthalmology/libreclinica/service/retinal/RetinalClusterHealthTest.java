/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth.NodeSpec;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalClusterHealth.NodeStatus;

/**
 * {@link RetinalClusterHealth} — the classification is pinned as a pure
 * function; the transport is exercised once against a JDK
 * {@link HttpServer} and once against a port nothing listens on, because
 * "refused" and "timed out" are the two shapes the 2026-09 outage actually
 * had and both must come back as a result, never an exception.
 */
public class RetinalClusterHealthTest {

    private static final NodeSpec ON3 = new NodeSpec("on3", "http://on3:8000");

    private static final String FULL_HEALTH = "{\"status\":\"ok\",\"adapter\":\"apptainer\","
            + "\"model_version\":\"optima-apptainer-v1\","
            + "\"supported_tasks\":[\"bm\",\"fluid\",\"ga\",\"layers\",\"onl\",\"pr\"],"
            + "\"node\":\"on3\",\"gpu_device\":\"2\",\"gpu_name\":\"NVIDIA GeForce RTX 2080 Ti\"}";

    private HttpServer server;

    @After
    public void stop() {
        if (server != null) server.stop(0);
    }

    /* ---- parseNodes ------------------------------------------------------- */

    @Test
    public void parseNodesReadsNameUrlPairsAndTrims() {
        List<NodeSpec> specs = RetinalClusterHealth.parseNodes(
                " on3 = http://149.148.108.144:8000/ , cn6=http://149.148.108.170:8000 ,, ", "");
        assertEquals(2, specs.size());
        assertEquals("on3", specs.get(0).name());
        assertEquals("http://149.148.108.144:8000", specs.get(0).url()); // trailing slash gone
        assertEquals("cn6", specs.get(1).name());
    }

    @Test
    public void parseNodesFallsBackToRemotePushUrlAsSingleNode() {
        List<NodeSpec> specs = RetinalClusterHealth.parseNodes("", "http://nginx:8088/");
        assertEquals(1, specs.size());
        assertEquals("remote", specs.get(0).name());
        assertEquals("http://nginx:8088", specs.get(0).url());
    }

    @Test
    public void parseNodesIsEmptyWhenNothingConfigured() {
        assertTrue(RetinalClusterHealth.parseNodes(null, null).isEmpty());
        assertTrue(RetinalClusterHealth.parseNodes("  ", "  ").isEmpty());
    }

    @Test
    public void parseNodesUsesUrlAsNameWhenNoEquals() {
        List<NodeSpec> specs = RetinalClusterHealth.parseNodes("http://x:8000", null);
        assertEquals("http://x:8000", specs.get(0).name());
    }

    /* ---- classify ------------------------------------------------------------ */

    @Test
    public void classifyHealthyWhenEveryExpectedTaskRegistered() {
        NodeStatus s = RetinalClusterHealth.classify(ON3, 200, FULL_HEALTH, 12);
        assertEquals("healthy", s.state());
        assertTrue(s.missingTasks().isEmpty());
        assertEquals(6, s.supportedTasks().size());
        assertEquals("on3", s.node());
        assertEquals("2", s.gpuDevice());
        assertEquals("NVIDIA GeForce RTX 2080 Ti", s.gpuName());
        assertEquals(Long.valueOf(12), s.latencyMs());
        assertNull(s.error());
    }

    @Test
    public void classifyDegradedNamesTheMissingTasks() {
        // The exact shape of the launcher bug: bm + layers silently absent.
        String body = "{\"status\":\"ok\",\"supported_tasks\":[\"fluid\",\"ga\",\"onl\",\"pr\"]}";
        NodeStatus s = RetinalClusterHealth.classify(ON3, 200, body, 5);
        assertEquals("degraded", s.state());
        assertEquals(List.of("bm", "layers"), s.missingTasks());
        assertNull(s.node()); // older sidecar without the node fields: tolerated
    }

    @Test
    public void classifyUnhealthyOnNon200() {
        NodeStatus s = RetinalClusterHealth.classify(ON3, 503, "", 5);
        assertEquals("unhealthy", s.state());
        assertEquals("HTTP 503", s.error());
        assertEquals(RetinalClusterHealth.EXPECTED_TASKS, s.missingTasks());
    }

    @Test
    public void classifyUnhealthyOnGarbageBody() {
        assertEquals("unhealthy", RetinalClusterHealth.classify(ON3, 200, "<html>nope", 5).state());
        assertEquals("unhealthy", RetinalClusterHealth.classify(ON3, 200, null, 5).state());
        assertEquals("unhealthy", RetinalClusterHealth.classify(ON3, 200, "[1,2]", 5).state());
    }

    @Test
    public void classifyUnhealthyWhenStatusNotOk() {
        NodeStatus s = RetinalClusterHealth.classify(ON3, 200, "{\"status\":\"starting\"}", 5);
        assertEquals("unhealthy", s.state());
        assertEquals("status=starting", s.error());
    }

    /* ---- probe (transport) --------------------------------------------------- */

    @Test
    public void probeClassifiesALiveServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", ex -> {
            byte[] b = FULL_HEALTH.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        NodeSpec spec = new NodeSpec("stub", "http://127.0.0.1:" + server.getAddress().getPort());

        NodeStatus s = new RetinalClusterHealth(Duration.ofSeconds(2)).probe(spec);
        assertEquals("healthy", s.state());
        assertNotNull(s.latencyMs());
    }

    @Test
    public void probeReportsRefusedAsUnreachableNotException() throws IOException {
        int freePort;
        try (ServerSocket ss = new ServerSocket(0)) { freePort = ss.getLocalPort(); }
        NodeSpec spec = new NodeSpec("dead", "http://127.0.0.1:" + freePort);

        NodeStatus s = new RetinalClusterHealth(Duration.ofSeconds(2)).probe(spec);
        assertEquals("unreachable", s.state());
        assertNotNull(s.error());
        assertEquals(RetinalClusterHealth.EXPECTED_TASKS, s.missingTasks());
    }

    @Test
    public void probeAllPreservesOrderAndSurvivesADeadNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", ex -> {
            byte[] b = FULL_HEALTH.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        int freePort;
        try (ServerSocket ss = new ServerSocket(0)) { freePort = ss.getLocalPort(); }

        List<NodeStatus> out = new RetinalClusterHealth(Duration.ofSeconds(2)).probeAll(List.of(
                new NodeSpec("dead", "http://127.0.0.1:" + freePort),
                new NodeSpec("live", "http://127.0.0.1:" + server.getAddress().getPort())));
        assertEquals("dead", out.get(0).name());
        assertEquals("unreachable", out.get(0).state());
        assertEquals("live", out.get(1).name());
        assertEquals("healthy", out.get(1).state());
    }

    /* ---- tailLines ------------------------------------------------------------ */

    @Test
    public void tailLinesReturnsLastNOldestFirst() throws IOException {
        Path f = Files.createTempFile("monitor", ".log");
        try {
            Files.writeString(f, "a\nb\nc\nd\n");
            assertEquals(List.of("c", "d"), RetinalClusterHealth.tailLines(f, 2));
            assertEquals(List.of("a", "b", "c", "d"), RetinalClusterHealth.tailLines(f, 50));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    public void tailLinesIsEmptyForMissingFile() {
        assertTrue(RetinalClusterHealth.tailLines(Path.of("/definitely/not/here.log"), 5).isEmpty());
        assertTrue(RetinalClusterHealth.tailLines(null, 5).isEmpty());
    }
}
