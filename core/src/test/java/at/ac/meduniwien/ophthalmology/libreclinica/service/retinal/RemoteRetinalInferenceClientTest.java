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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * DR-022 {@link RemoteRetinalInferenceClient} contract test.
 *
 * <p>Stubs the sidecar with the JDK's built-in {@link HttpServer} — no
 * external dep. Tests subclass the client to override the
 * {@code datainfo.properties}-backed config readers because
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources}
 * has no setter API (read-only at runtime).
 */
public class RemoteRetinalInferenceClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private Path e2eFile;
    private TestClient client;

    /** Overridable wrapper so the test can inject config without touching the
     *  static {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources}. */
    private static class TestClient extends RemoteRetinalInferenceClient {
        String url;
        String token = "test-token";
        Duration timeout = Duration.ofSeconds(10);

        @Override protected String remoteUrl() { return url == null ? "" : url; }
        @Override protected String remoteToken() { return token == null ? "" : token; }
        @Override protected Duration remoteTimeout() { return timeout; }
    }

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(null);
        server.start();

        e2eFile = Files.createTempFile("remote-client-test-", ".e2e");
        Files.writeString(e2eFile, "FAKE-E2E-BYTES");

        client = new TestClient();
        client.url = "http://127.0.0.1:" + port;
    }

    @After
    public void tearDown() throws IOException {
        if (server != null) server.stop(0);
        if (e2eFile != null) Files.deleteIfExists(e2eFile);
    }

    private void registerJsonHandler(String path, int status, String body,
                                     AtomicReference<Map<String, String>> capturedHeaders) {
        server.createContext(path, exchange -> {
            if (capturedHeaders != null) {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
                capturedHeaders.set(headers);
            }
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
    }

    private String envelopeJson(byte[] artifactBytes) throws IOException {
        String content64 = Base64.getEncoder().encodeToString(artifactBytes);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("model_version", "fluid-1.3.0");
        envelope.put("primary_metric_value", 6.84);
        envelope.put("primary_metric_unit", "mm³");
        envelope.put("output_payload", Map.of(
                "total_fluid_volume_mm3", 6.84,
                "irf_mm3", 1.2
        ));
        envelope.put("confidence", 0.9);
        envelope.put("task", "fluid");
        envelope.put("laterality", "OD");
        envelope.put("artifacts", List.of(Map.of(
                "name", "fluid_labels.npy",
                "media_type", "application/octet-stream",
                "content_base64", content64
        )));
        return MAPPER.writeValueAsString(envelope);
    }

    // --- tests ---------------------------------------------------------------

    @Test
    public void isConfigured_returnsTrueWhenUrlSet() {
        assertTrue(client.isConfigured());
    }

    @Test
    public void isConfigured_returnsFalseWhenUrlBlank() {
        client.url = "";
        assertFalse(client.isConfigured());
    }

    @Test
    public void runRemote_happyPath_decodesEnvelopeAndArtifacts() throws IOException {
        byte[] artifactBytes = "NUMPY-BLOB".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        registerJsonHandler("/run", 200, envelopeJson(artifactBytes), headers);

        RemoteRunResult result = client.runRemote(42L, "fluid", e2eFile.toString(), "OD");

        assertNotNull(result);
        assertEquals("fluid-1.3.0", result.modelVersion());
        assertEquals(6.84, result.primaryMetricValue(), 0.0001);
        assertEquals("mm³", result.primaryMetricUnit());
        assertEquals(0.9, result.confidence(), 0.0001);
        assertEquals("fluid", result.task());
        assertEquals("OD", result.laterality());
        assertEquals(6.84, ((Number) result.outputPayload().get("total_fluid_volume_mm3")).doubleValue(), 0.0001);
        assertEquals(1, result.artifacts().size());
        RemoteRunResult.Artifact a = result.artifacts().get(0);
        assertEquals("fluid_labels.npy", a.name());
        assertEquals("application/octet-stream", a.mediaType());
        assertEquals(new String(artifactBytes, StandardCharsets.UTF_8),
                     new String(a.content(), StandardCharsets.UTF_8));
    }

    @Test
    public void runRemote_propagatesAuthAndIdempotencyHeaders() throws IOException {
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        registerJsonHandler("/run", 200, envelopeJson(new byte[]{1, 2, 3}), headers);

        client.runRemote(101L, "fluid", e2eFile.toString(), "OS");

        Map<String, String> h = headers.get();
        assertNotNull(h);
        // HTTP header keys come back lowercased by HttpServer.
        String tokenHeader = h.get("X-muw-inference-token");
        if (tokenHeader == null) tokenHeader = h.get("X-MUW-Inference-Token");
        assertEquals("test-token", tokenHeader);
        String idem = h.get("Idempotency-key");
        if (idem == null) idem = h.get("Idempotency-Key");
        assertNotNull(idem);
        assertTrue("idempotency key combines jobId + filename: " + idem,
                idem.startsWith("101-") && idem.endsWith(".e2e"));
    }

    @Test
    public void runRemote_returnsNullOnNon2xx() {
        registerJsonHandler("/run", 500, "{\"detail\":\"boom\"}", null);
        RemoteRunResult result = client.runRemote(42L, "fluid", e2eFile.toString(), "OD");
        assertNull(result);
    }

    @Test
    public void runRemote_returnsNullWhenUrlUnset() {
        client.url = "";
        RemoteRunResult result = client.runRemote(42L, "fluid", e2eFile.toString(), "OD");
        assertNull(result);
    }

    @Test
    public void runRemote_returnsNullWhenTokenUnset() {
        client.token = "";
        RemoteRunResult result = client.runRemote(42L, "fluid", e2eFile.toString(), "OD");
        assertNull(result);
    }

    @Test
    public void runRemote_returnsNullWhenE2eFileMissing() {
        registerJsonHandler("/run", 200, "{}", null);
        RemoteRunResult result = client.runRemote(42L, "fluid", "/nonexistent/path.e2e", "OD");
        assertNull(result);
    }

    @Test
    public void runRemote_handlesEnvelopeWithoutArtifacts() throws IOException {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("model_version", "fluid-1.3.0");
        envelope.put("primary_metric_value", 6.84);
        envelope.put("primary_metric_unit", "mm³");
        envelope.put("output_payload", Map.of());
        envelope.put("confidence", 0.9);
        envelope.put("task", "fluid");
        envelope.put("laterality", "OD");
        registerJsonHandler("/run", 200, MAPPER.writeValueAsString(envelope), null);

        RemoteRunResult result = client.runRemote(42L, "fluid", e2eFile.toString(), "OD");
        assertNotNull(result);
        assertEquals(0, result.artifacts().size());
    }
}