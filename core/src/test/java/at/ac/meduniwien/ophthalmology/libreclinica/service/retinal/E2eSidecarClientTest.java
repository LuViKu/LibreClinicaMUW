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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DR-041 - the client for the converter sidecar's /e2e/describe and
 * /e2e/convert: what it sends, what it makes of the answer, and which
 * status each kind of failure maps to.
 */
public class E2eSidecarClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<JsonNode> convertRequest = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();

    private String baseUrl;
    private String configuredToken = "tok";

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/e2e/describe", ex -> {
            request.set(JSON.readTree(ex.getRequestBody()));
            token.set(ex.getRequestHeaders().getFirst("X-MUW-Inference-Token"));
            reply(ex, status, body.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/e2e/convert", ex -> {
            convertRequest.set(JSON.readTree(ex.getRequestBody()));
            token.set(ex.getRequestHeaders().getFirst("X-MUW-Inference-Token"));
            reply(ex, status, body.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, byte[] out) throws java.io.IOException {
        ex.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        } else {
            ex.close();
        }
    }

    private E2eSidecarClient client() {
        return new E2eSidecarClient() {
            @Override protected String baseUrl() { return baseUrl; }
            @Override protected String token() { return configuredToken; }
            @Override protected Duration describeTimeout() { return Duration.ofSeconds(5); }
            @Override protected Duration convertTimeout() { return Duration.ofSeconds(5); }
            @Override protected java.nio.file.Path convertRoot() { return java.nio.file.Path.of("/convert-root"); }
        };
    }

    @Test
    public void describeSendsTheStoredNameAndLabelWithTheTokenAndReadsTheSeries() {
        body = "{\"headerPatient\":\"empty\",\"headerLabel\":\"EIAMD005\",\"series\":[{\"seriesKey\":\"s3\",\"seriesModality\":\"OCT\","
                + "\"deviceModality\":\"IR\",\"scanPattern\":\"OCT ART Volume\",\"laterality\":\"OD\","
                + "\"acquiredAt\":\"2026-09-30T10:12:03\",\"frames\":97,\"width\":1024,\"height\":496,"
                + "\"octVolumeIndex\":0,\"somethingNew\":true},"
                + "{\"seriesKey\":\"s4\",\"seriesModality\":\"IR\",\"laterality\":null,\"octVolumeIndex\":null}]}";
        E2eSidecarClient.Description d = client().describe("3f2b8c1e-aa.e2e", "EIAMD005");

        assertEquals("3f2b8c1e-aa.e2e", request.get().path("storedName").asText());
        assertEquals("EIAMD005", request.get().path("expectedLabel").asText());
        assertEquals("tok", token.get());
        assertEquals("empty", d.headerPatient());
        assertEquals("EIAMD005", d.headerLabel());
        assertEquals(2, d.series().size());
        E2eSidecarClient.Series s = d.series().get(0);
        assertEquals("s3", s.seriesKey());
        assertEquals("OCT", s.seriesModality());
        assertEquals("OD", s.laterality());
        assertEquals(Integer.valueOf(97), s.frames());
        assertEquals(Integer.valueOf(0), s.octVolumeIndex());
        assertNull(d.series().get(1).octVolumeIndex());
        assertNull(d.series().get(1).laterality());
    }

    @Test
    public void describeWithoutAFolderLabelLeavesExpectedLabelOut() {
        body = "{\"headerPatient\":\"none\",\"headerLabel\":null,\"series\":[]}";
        E2eSidecarClient.Description d = client().describe("3f2b8c1e-aa.e2e", null);
        assertFalse(request.get().has("expectedLabel"));
        assertEquals("none", d.headerPatient());
        assertNull(d.headerLabel());
        client().describe("3f2b8c1e-aa.e2e", "  ");
        assertFalse(request.get().has("expectedLabel"));
    }

    @Test
    public void anErrorFromTheSidecarIs502WithoutItsBody() {
        status = 500;
        body = "{\"detail\":\"patient Mustermann\"}";
        try {
            client().describe("a.e2e", "X");
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
            assertFalse("a remote body is never echoed", e.getMessage().contains("Mustermann"));
            assertTrue(e.getMessage().contains("500"));
        }
    }

    @Test
    public void anUnreadableAnswerIs502() {
        body = "not json";
        try {
            client().describe("a.e2e", "X");
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
        }
        body = "{\"series\":[]}";
        try {
            client().describe("a.e2e", "X");
            fail("a missing headerPatient is not a usable answer");
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
        }
    }

    @Test
    public void anUnreachableOrUnconfiguredSidecarIs503() {
        baseUrl = "http://127.0.0.1:1/";
        try {
            client().describe("a.e2e", "X");
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(503, e.httpStatus());
        }
        baseUrl = "";
        assertFalse(client().isConfigured());
        try {
            client().describe("a.e2e", "X");
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(503, e.httpStatus());
        }
        baseUrl = "http://127.0.0.1:1/";
        configuredToken = "";
        assertFalse(client().isConfigured());
    }

    @Test
    public void onlyABareStoredNameIsEverSent() {
        for (String bad : List.of("../x.e2e", "a/b.e2e", "a.e2e.exe", "", "a b.e2e", "C:\\x.e2e", ".e2e")) {
            try {
                client().describe(bad, "X");
                fail(bad);
            } catch (E2eSidecarClient.SidecarException e) {
                assertEquals(bad, 502, e.httpStatus());
            }
            try {
                client().convert(bad, "X", List.of("s1"));
                fail(bad);
            } catch (E2eSidecarClient.SidecarException e) {
                assertEquals(bad, 502, e.httpStatus());
            }
        }
        assertNull("nothing reached the sidecar", request.get());
        assertNull("nothing reached the sidecar", convertRequest.get());
    }

    @Test
    public void convertSendsTheNameLabelAndKeysAndReadsTheFiles() {
        body = "{\"files\":[{\"seriesKey\":\"s3\",\"file\":\"s3.dcm\",\"sha256\":\"" + "ab".repeat(32)
                + "\",\"bytes\":1234,\"frames\":97,\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.4\","
                + "\"geometryFile\":\"s3.geometry.json\",\"somethingNew\":1},"
                + "{\"seriesKey\":\"s4\",\"file\":\"s4.dcm\",\"sha256\":\"" + "cd".repeat(32)
                + "\",\"bytes\":99,\"frames\":1,\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\"}]}";

        E2eSidecarClient.Conversion c = client().convert("3f2b8c1e-aa.e2e", "EIAMD005", List.of("s3", "s4"));

        assertEquals("3f2b8c1e-aa.e2e", convertRequest.get().path("storedName").asText());
        assertEquals("EIAMD005", convertRequest.get().path("label").asText());
        assertEquals("s3", convertRequest.get().path("seriesKeys").get(0).asText());
        assertEquals(2, convertRequest.get().path("seriesKeys").size());
        assertEquals("tok", token.get());
        assertEquals(java.nio.file.Path.of("/convert-root/3f2b8c1e-aa"), c.directory());
        assertEquals(2, c.files().size());
        E2eSidecarClient.ConvertedFile f = c.files().get(0);
        assertEquals("s3", f.seriesKey());
        assertEquals("s3.dcm", f.file());
        assertEquals(Long.valueOf(1234), f.bytes());
        assertEquals(Integer.valueOf(97), f.frames());
        assertEquals("s3.geometry.json", f.geometryFile());
        assertNull(c.files().get(1).geometryFile());
    }

    @Test
    public void convertWithoutALabelLeavesItOut() {
        body = "{\"files\":[{\"seriesKey\":\"s1\",\"file\":\"s1.dcm\",\"sha256\":\"x\",\"bytes\":1,\"frames\":1}]}";
        client().convert("a.e2e", null, List.of("s1"));
        assertFalse(convertRequest.get().has("label"));
    }

    @Test
    public void convertFailuresAreMappedLikeDescribe() {
        status = 400; // unknown series
        try {
            client().convert("a.e2e", "X", List.of("s1"));
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
            assertTrue(e.getMessage().contains("400"));
        }
        status = 200;
        body = "{\"files\":[]}";
        try {
            client().convert("a.e2e", "X", List.of("s1"));
            fail("a conversion without files is not a usable answer");
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
        }
        body = "nope";
        try {
            client().convert("a.e2e", "X", List.of("s1"));
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
        }
        try {
            client().convert("a.e2e", "X", List.of());
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
        }
        baseUrl = "http://127.0.0.1:1/";
        try {
            client().convert("a.e2e", "X", List.of("s1"));
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(503, e.httpStatus());
        }
    }
}
