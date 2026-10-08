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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The sidecar's verify route, as the app calls it: read-only, and fail-closed on anything but a clear yes. */
@SuppressWarnings("resource") // the request body belongs to the HttpExchange, which its handler closes
public class DicomVerifyClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"ok\":true,\"violations\":[]}";

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private DicomDescribeClient client() {
        return new DicomDescribeClient("http://127.0.0.1:" + server.getAddress().getPort() + "/describe", "secret");
    }

    @Test
    public void aCleanFileIsOkAndTheRouteIsVerifyBesideDescribe() throws Exception {
        DicomDescribeClient.Verification v = client().verify(Path.of("/store/x.dcm"), "HAE-001");
        assertTrue(v.ok());
        assertEquals("/verify", lastPath.get());
        assertTrue(lastBody.get().contains("\"pseudonym\":\"HAE-001\""));
    }

    @Test
    public void violationsComeBackAsNames() throws Exception {
        answer = "{\"ok\":false,\"violations\":[\"PatientName\",\"PrivateTags\"]}";
        DicomDescribeClient.Verification v = client().verify(Path.of("/store/x.dcm"), null);
        assertFalse(v.ok());
        assertEquals(List.of("PatientName", "PrivateTags"), v.violations());
        assertTrue(lastBody.get().contains("\"pseudonym\":null"));
    }

    @Test
    public void onlyAnExplicitOkWithNoViolationsPasses() throws Exception {
        answer = "{}";
        assertFalse(client().verify(Path.of("/store/x.dcm"), "L").ok());
        answer = "{\"ok\":true,\"violations\":[\"PatientID\"]}";
        assertFalse(client().verify(Path.of("/store/x.dcm"), "L").ok());
        answer = "{\"ok\":false}";
        assertFalse(client().verify(Path.of("/store/x.dcm"), "L").ok());
    }

    @Test
    public void notDicomIsItsOwnReason() throws Exception {
        status = 422;
        answer = "{\"message\":\"not a DICOM file\"}";
        try {
            client().verify(Path.of("/store/x.dcm"), "L");
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.NOT_DICOM, e.reason());
        }
    }

    @Test
    public void aSidecarErrorOrGarbageIsAFailureNotAPass() {
        status = 500;
        try {
            client().verify(Path.of("/store/x.dcm"), "L");
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.REJECTED, e.reason());
        }
        status = 200;
        answer = "not json";
        try {
            client().verify(Path.of("/store/x.dcm"), "L");
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.REJECTED, e.reason());
        }
    }

    @Test
    public void anUnconfiguredClientCannotVerify() {
        try {
            new DicomDescribeClient("", "").verify(Path.of("/store/x.dcm"), "L");
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.UNCONFIGURED, e.reason());
        }
    }

    @Test
    public void strictDescribeAsksTheSidecarToBeStrict() throws Exception {
        answer = "{\"sopInstanceUid\":\"1\"}";
        client().describe(Path.of("/store/x.dcm"), "HAE-001", true);
        assertTrue(lastBody.get().contains("\"strict\":true"));
        client().describe(Path.of("/store/x.dcm"), "HAE-001");
        assertFalse(lastBody.get().contains("strict"));
    }
}
