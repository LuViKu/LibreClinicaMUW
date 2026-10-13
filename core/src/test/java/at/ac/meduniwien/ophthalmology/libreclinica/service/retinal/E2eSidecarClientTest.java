/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertArrayEquals;
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
 * /e2e/series-image: what it sends, what it makes of the answer, and which
 * status each kind of failure maps to.
 */
public class E2eSidecarClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
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
        server.createContext("/e2e/series-image", ex -> {
            query.set(ex.getRequestURI().getRawQuery());
            token.set(ex.getRequestHeaders().getFirst("X-MUW-Inference-Token"));
            reply(ex, status, status == 200 ? PNG : new byte[0]);
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
            @Override protected Duration imageTimeout() { return Duration.ofSeconds(5); }
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
                client().seriesImage(bad, "s1", 0);
                fail(bad);
            } catch (E2eSidecarClient.SidecarException e) {
                assertEquals(bad, 502, e.httpStatus());
            }
        }
        assertNull("nothing reached the sidecar", request.get());
    }

    @Test
    public void seriesImageReturnsThePngAndEncodesItsQuery() {
        byte[] png = client().seriesImage("3f2b-aa.e2e", "s 3&x", 2);
        assertArrayEquals(PNG, png);
        assertEquals("tok", token.get());
        String q = query.get();
        assertTrue(q, q.contains("storedName=3f2b-aa.e2e"));
        assertTrue(q, q.contains("seriesKey=s%203%26x"));
        assertTrue(q, q.contains("frame=2"));
    }

    @Test
    public void anUnknownSeriesIsNullAndAFailureIs502() {
        status = 404;
        assertNull(client().seriesImage("a.e2e", "nope", 0));
        status = 500;
        try {
            client().seriesImage("a.e2e", "s1", 0);
            fail();
        } catch (E2eSidecarClient.SidecarException e) {
            assertEquals(502, e.httpStatus());
        }
    }
}
