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
        String prepUrl = "";
        String prepToken = "";

        @Override protected String remoteUrl() { return url == null ? "" : url; }
        @Override protected String remoteToken() { return token == null ? "" : token; }
        @Override protected Duration remoteTimeout() { return timeout; }
        @Override protected String preprocessUrl() { return prepUrl == null ? "" : prepUrl; }
        @Override protected String preprocessToken() {
            return (prepToken == null || prepToken.isBlank()) ? remoteToken() : prepToken;
        }
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

    /** Handler that captures the raw request body (for asserting what /run got)
     *  and replies with a fixed JSON body. */
    private void registerCapturingJsonHandler(String path, int status, String body,
                                              AtomicReference<byte[]> capturedBody) {
        server.createContext(path, exchange -> {
            try (var in = exchange.getRequestBody()) {
                capturedBody.set(in.readAllBytes());
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
    }

    /** Handler that replies with raw bytes under a given content type (the
     *  /preprocess sidecar returning application/dicom). */
    private void registerBinaryHandler(String path, int status, byte[] payload, String contentType) {
        registerBinaryHandler(path, status, payload, contentType, Map.of());
    }

    /** Same as above but also stamps the supplied response headers — used to
     *  simulate the DR-022 preprocess sidecar's 7 X-MUW headers. */
    private void registerBinaryHandler(String path, int status, byte[] payload,
                                       String contentType, Map<String, String> extraHeaders) {
        server.createContext(path, exchange -> {
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            extraHeaders.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
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
    public void runRemote_preprocessesE2eToDicom_whenPreprocessUrlSet() throws IOException {
        // /preprocess returns a DICOM blob (Part-10 magic) AND the 7 DR-022 headers.
        byte[] dicom = new byte[140];
        System.arraycopy("DICM".getBytes(StandardCharsets.UTF_8), 0, dicom, 128, 4);
        Map<String, String> geomHeaders = new HashMap<>();
        geomHeaders.put(PixelGeometry.HEADER_AXIAL_MM, "0.00387");
        geomHeaders.put(PixelGeometry.HEADER_LATERAL_MM, "0.01155");
        geomHeaders.put(PixelGeometry.HEADER_SLICE_MM, "0.12100");
        geomHeaders.put(PixelGeometry.HEADER_DIM_Z, "49");
        geomHeaders.put(PixelGeometry.HEADER_DIM_Y, "496");
        geomHeaders.put(PixelGeometry.HEADER_DIM_X, "512");
        geomHeaders.put(PixelGeometry.HEADER_E2E_UUID, "deadbeef-cafe-1234");
        registerBinaryHandler("/preprocess", 200, dicom, "application/dicom", geomHeaders);
        AtomicReference<byte[]> runBody = new AtomicReference<>();
        registerCapturingJsonHandler("/run", 200, envelopeJson(new byte[]{9}), runBody);

        client.prepUrl = client.url; // same test server, different path

        RemoteRunResult result = client.runRemote(7L, "fluid", e2eFile.toString(), "OD");

        assertNotNull(result);
        // The /run multipart must carry the converted DICOM (DICM magic) under the
        // bscan.dcm filename — not the raw E2E bytes.
        String received = new String(runBody.get(), StandardCharsets.ISO_8859_1);
        assertTrue("multipart carries the bscan.dcm filename", received.contains("filename=\"bscan.dcm\""));
        assertTrue("multipart carries the DICOM magic", received.contains("DICM"));
        assertFalse("raw E2E bytes must not be forwarded", received.contains("FAKE-E2E-BYTES"));
        // DR-022 geometry plumbed through to RemoteRunResult.
        assertNotNull("preprocess geometry should be parsed off response headers", result.geometry());
        assertEquals(0.00387, result.geometry().axialMm(), 1e-9);
        assertEquals(0.01155, result.geometry().lateralMm(), 1e-9);
        assertEquals(0.12100, result.geometry().sliceMm(), 1e-9);
        assertEquals(49, result.geometry().dimZ());
        assertEquals(496, result.geometry().dimY());
        assertEquals(512, result.geometry().dimX());
        assertEquals("deadbeef-cafe-1234", result.e2eUuid());
    }

    @Test
    public void runRemote_softFailsGeometry_whenPreprocessHasNoHeaders() throws IOException {
        // Preprocess returns a DICOM but doesn't stamp the X-MUW headers (old deploy).
        // Java should soft-fail: result.geometry() is null but the run still succeeds.
        byte[] dicom = new byte[140];
        System.arraycopy("DICM".getBytes(StandardCharsets.UTF_8), 0, dicom, 128, 4);
        registerBinaryHandler("/preprocess", 200, dicom, "application/dicom");
        AtomicReference<byte[]> runBody = new AtomicReference<>();
        registerCapturingJsonHandler("/run", 200, envelopeJson(new byte[]{1}), runBody);
        client.prepUrl = client.url;

        RemoteRunResult result = client.runRemote(99L, "fluid", e2eFile.toString(), "OD");

        assertNotNull("run should still succeed without geometry headers", result);
        assertNull("geometry must be null when headers missing", result.geometry());
        // The e2e UUID falls back to the derived basename when the header is absent.
        assertNotNull(result.e2eUuid());
        assertFalse(result.e2eUuid().isBlank());
    }

    @Test
    public void runRemote_returnsNull_whenPreprocessFails() {
        // /preprocess errors -> client must not POST a raw E2E to /run.
        registerBinaryHandler("/preprocess", 500, new byte[]{1}, "application/json");
        AtomicReference<byte[]> runBody = new AtomicReference<>();
        registerCapturingJsonHandler("/run", 200, "{}", runBody);

        client.prepUrl = client.url;

        RemoteRunResult result = client.runRemote(8L, "fluid", e2eFile.toString(), "OD");
        assertNull(result);
        assertNull("/run must not be called when preprocess fails", runBody.get());
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