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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The describe call is the gate that keeps a hospital patient out of the
 * store, so its failure modes matter as much as its success.
 */
public class DicomDescribeClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/describe", ex -> {
            lastToken.set(ex.getRequestHeaders().getFirst(DicomDescribeClient.TOKEN_HEADER));
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/describe";
    }

    @Test
    public void aDescriptionCarriesTheExamAndTheDeviceAndSendsTheToken() throws Exception {
        answer = "{\"sopInstanceUid\":\"1.2.3\",\"sopClassUid\":\"1.2.840.10008.5.1.4.1.1.77.1.5.1\","
                + "\"studyInstanceUid\":\"1.2\",\"seriesInstanceUid\":\"1.2.9\",\"modality\":\"OP\","
                + "\"studyDate\":\"2026-09-18\",\"acquisitionDate\":\"2026-09-18\",\"laterality\":\"OD\","
                + "\"manufacturer\":\"Carl Zeiss Meditec\",\"manufacturerModelName\":\"CLARUS 700\","
                + "\"previewPngPath\":\"/store/x.png\",\"identityRemoved\":true,\"changedTags\":5}";
        DicomDescribeClient client = new DicomDescribeClient(url(), "secret");
        assertTrue(client.isConfigured());

        DicomDescribeClient.Description d = client.describe(Path.of("/store/x.dcm"), "HAE-001");

        assertEquals("1.2.3", d.sopInstanceUid());
        assertEquals("OP", d.modality());
        assertEquals("OD", d.laterality());
        assertEquals(LocalDate.of(2026, 9, 18), d.acquisitionDate());
        assertEquals("CLARUS 700", d.manufacturerModelName());
        assertEquals("/store/x.png", d.previewPngPath());
        assertTrue(d.identityRemoved());
        assertEquals(5, d.changedTags());
        assertEquals("secret", lastToken.get());
        assertTrue(lastBody.get().contains("\"path\":\"/store/x.dcm\""));
        assertTrue(lastBody.get().contains("\"pseudonym\":\"HAE-001\""));
    }

    @Test
    public void anUnfiledUploadSendsNoPseudonym() throws Exception {
        answer = "{\"sopInstanceUid\":\"1\"}";
        new DicomDescribeClient(url(), "secret").describe(Path.of("/store/y.dcm"), "  ");
        assertTrue(lastBody.get().contains("\"pseudonym\":null"));
    }

    @Test
    public void nullsAndBlanksInTheAnswerReadAsAbsent() throws Exception {
        answer = "{\"sopInstanceUid\":\"1\",\"laterality\":null,\"acquisitionDate\":\"\",\"studyDate\":\"garbage\"}";
        DicomDescribeClient.Description d =
                new DicomDescribeClient(url(), "secret").describe(Path.of("/store/z.dcm"), null);
        assertNull(d.laterality());
        assertNull(d.acquisitionDate());
        assertNull(d.studyDate());
        assertFalse(d.identityRemoved());
    }

    @Test
    public void notADicomFileIsItsOwnReason() {
        status = 422;
        answer = "{\"message\":\"not a DICOM file\"}";
        try {
            new DicomDescribeClient(url(), "secret").describe(Path.of("/store/j.dcm"), null);
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.NOT_DICOM, e.reason());
        }
    }

    @Test
    public void anyOtherRefusalIsRejected() {
        status = 403;
        answer = "{\"message\":\"path is not a file under the ingest store\"}";
        try {
            new DicomDescribeClient(url(), "secret").describe(Path.of("/elsewhere/j.dcm"), null);
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.REJECTED, e.reason());
        }
    }

    @Test
    public void noSidecarMeansNoDescription() {
        DicomDescribeClient unconfigured = new DicomDescribeClient("", "");
        assertFalse(unconfigured.isConfigured());
        try {
            unconfigured.describe(Path.of("/store/x.dcm"), null);
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.UNCONFIGURED, e.reason());
        }
        // A URL without a token is as good as no URL: the sidecar would refuse anyway.
        assertFalse(new DicomDescribeClient(url(), "").isConfigured());
    }

    @Test
    public void aSidecarThatDoesNotAnswerIsUnreachable() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        try {
            new DicomDescribeClient("http://127.0.0.1:" + port + "/describe", "secret")
                    .describe(Path.of("/store/x.dcm"), null);
            fail("expected a refusal");
        } catch (DicomDescribeClient.DescribeException e) {
            assertEquals(DicomDescribeClient.DescribeException.Reason.UNREACHABLE, e.reason());
        } finally {
            // @After stops it again; a stopped server tolerates that.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
    }
}
