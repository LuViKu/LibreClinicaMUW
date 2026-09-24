/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Exam;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Image;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.RemidioException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Response;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Settings;

/**
 * The gateway client against a scripted wire: the auth chain, the headers
 * each call carries, the single re-login on 401, the error envelope, and the
 * shape of a listing as our tenant returned it on 2026-09-23 (identifiers
 * replaced, structure kept).
 */
public class RemidioGatewayClientTest {

    private static final Settings SETTINGS = new Settings(
            "https://remidio.example/", "", "client-jwt", "bot@example.org", "pw", "muw_vienna");

    /** One recorded call. */
    record Call(String method, String path, Map<String, String> headers, String body) {}

    /** Answers in the order given; records what it was asked. */
    static final class ScriptedTransport implements RemidioGatewayClient.Transport {
        final Deque<Response> answers = new ArrayDeque<>();
        final List<Call> calls = new ArrayList<>();

        ScriptedTransport answer(int status, String body) {
            answers.add(new Response(status, body));
            return this;
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String jsonBody,
                             Duration timeout) {
            calls.add(new Call(method, uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery()),
                    Map.copyOf(headers), jsonBody));
            if (answers.isEmpty()) throw new IllegalStateException("unexpected call " + method + " " + uri);
            return answers.poll();
        }
    }

    private static final RemidioGatewayClient.Downloader NO_DOWNLOADS = (uri, timeout) -> {
        throw new IllegalStateException("no download expected");
    };

    private static String ok(String dataJson) {
        return "{\"status\":{\"statusCode\":\"OK\",\"message\":\"HTTP Status - OK\"},\"data\":" + dataJson
                + ",\"paging\":null}";
    }

    private static String error(String code, String message) {
        return "{\"status\":{\"statusCode\":\"" + code + "\",\"message\":\"" + message
                + "\"},\"data\":\"ResourceNotFoundException\",\"paging\":null}";
    }

    private static ScriptedTransport authenticating() {
        return new ScriptedTransport()
                .answer(200, ok("\"bearer-1\""))
                .answer(200, ok("\"cat-1\""));
    }

    @Test
    public void chainLogsInThenMintsTheClientAuthTokenThenCarriesEverything() throws Exception {
        ScriptedTransport wire = authenticating().answer(200, ok(
                "[{\"siteId\":5898310359449600,\"siteName\":\"Vienna\",\"siteDomain\":\"meduniwien.ac.at\"}]"));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        List<RemidioGatewayClient.Site> sites = client.sites();

        assertEquals(1, sites.size());
        assertEquals(5898310359449600L, sites.get(0).siteId());
        assertEquals("Vienna", sites.get(0).siteName());

        assertEquals(3, wire.calls.size());
        Call login = wire.calls.get(0);
        assertEquals("POST", login.method());
        assertEquals("/api/user/loginUser", login.path());
        assertEquals("blank client name falls back to the gateway actor",
                "PACS_GATEWAY", login.headers().get("clientName"));
        assertEquals("client-jwt", login.headers().get("clientIdentificationToken"));
        assertFalse(login.headers().containsKey("Authorization"));
        assertTrue(login.body().contains("\"emailAddress\":\"bot@example.org\""));
        assertTrue("the login carries the dashboard's empty deviceId",
                login.body().contains("\"deviceId\":\"\""));

        Call auth = wire.calls.get(1);
        assertEquals("GET", auth.method());
        assertEquals("/api/gateway/getAuthToken", auth.path());
        assertEquals("Bearer bearer-1", auth.headers().get("Authorization"));
        assertFalse(auth.headers().containsKey("clientAuthToken"));

        Call data = wire.calls.get(2);
        assertEquals("/api/gateway/getSites", data.path());
        assertEquals("cat-1", data.headers().get("clientAuthToken"));
        assertEquals("Bearer bearer-1", data.headers().get("Authorization"));
        assertEquals("PACS_GATEWAY", data.headers().get("clientName"));
    }

    @Test
    public void tokensAreCachedAcrossCalls() throws Exception {
        ScriptedTransport wire = authenticating()
                .answer(200, ok("[]"))
                .answer(200, ok("[]"));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        client.sites();
        client.sites();

        assertEquals("one login, one getAuthToken, two data calls", 4, wire.calls.size());
    }

    @Test
    public void a401OnADataCallReauthenticatesExactlyOnce() throws Exception {
        ScriptedTransport wire = authenticating()
                .answer(401, error("NOT_AUTHORIZED", "The Authentication Token supplied was invalid"))
                .answer(200, ok("\"bearer-2\""))
                .answer(200, ok("\"cat-2\""))
                .answer(200, ok("[]"));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        client.sites();

        assertEquals(6, wire.calls.size());
        assertEquals("cat-2", wire.calls.get(5).headers().get("clientAuthToken"));
        assertEquals("Bearer bearer-2", wire.calls.get(5).headers().get("Authorization"));
    }

    @Test
    public void a401AfterAFreshLoginIsUnauthorizedNotALoop() {
        ScriptedTransport wire = authenticating()
                .answer(401, error("NOT_AUTHORIZED", "x"))
                .answer(200, ok("\"bearer-2\""))
                .answer(200, ok("\"cat-2\""))
                .answer(401, error("NOT_AUTHORIZED", "Only an authorised Remidio gateway is allowed"));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        RemidioException e = assertThrows(RemidioException.class, client::sites);

        assertEquals(RemidioException.Reason.UNAUTHORIZED, e.reason());
        assertEquals("no third attempt", 6, wire.calls.size());
    }

    @Test
    public void aRefusedLoginIsUnauthorized() {
        ScriptedTransport wire = new ScriptedTransport()
                .answer(404, error("NOT_FOUND", "User bot@example.org was not found"));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        RemidioException e = assertThrows(RemidioException.class, client::sites);

        assertEquals(RemidioException.Reason.UNAUTHORIZED, e.reason());
        assertEquals(404, e.httpStatus());
        assertEquals("NOT_FOUND", e.statusCode());
    }

    @Test
    public void theEnvelopesOwnMessageSurvivesARemoteError() {
        ScriptedTransport wire = authenticating()
                .answer(404, error("NOT_FOUND", "The Site Custom ID provided cannot be found for your organisation"));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        RemidioException e = assertThrows(RemidioException.class,
                () -> client.examsBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 23)));

        assertEquals(RemidioException.Reason.REMOTE, e.reason());
        assertEquals("NOT_FOUND", e.statusCode());
        assertTrue(e.getMessage().contains("Site Custom ID"));
        assertEquals("DD-MM-YYYY, the custom site id, file paths requested — and the gateway's end date is"
                        + " exclusive, so an inclusive 'to' of the 23rd goes out as the 24th",
                "/api/gateway/getExamsByDate/01-09-2026/24-09-2026/muw_vienna?includeFilePaths=true",
                wire.calls.get(2).path());
    }

    @Test
    public void anUnhandled500WithoutAnEnvelopeIsStillARemoteError() {
        ScriptedTransport wire = new ScriptedTransport()
                .answer(500, "Something went wrong! Please try again later");
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        RemidioException e = assertThrows(RemidioException.class, client::sites);

        assertEquals(RemidioException.Reason.REMOTE, e.reason());
        assertEquals(500, e.httpStatus());
        assertNull(e.statusCode());
    }

    @Test
    public void unconfiguredNeverTouchesTheWire() {
        ScriptedTransport wire = new ScriptedTransport();
        Settings blank = new Settings("https://remidio.example", "", "", "", "", "");
        RemidioGatewayClient client = new RemidioGatewayClient(blank, wire, NO_DOWNLOADS);

        RemidioException e = assertThrows(RemidioException.class, client::sites);

        assertEquals(RemidioException.Reason.UNCONFIGURED, e.reason());
        assertTrue(e.getMessage().contains(RemidioGatewayClient.KEY_CLIENT_TOKEN));
        assertTrue(wire.calls.isEmpty());
    }

    @Test
    public void aListingParsesIntoExamsWithStandardAndEditedImages() throws Exception {
        String listing = "[{"
                + "\"patientDetails\":{\"id\":\"p1\",\"firstName\":\"X\",\"lastName\":\"Y\",\"dateOfBirth\":0,"
                + "  \"gender\":\"OTHER\",\"mrn\":\"HAE-002\",\"siteId\":5898310359449600},"
                + "\"examDetails\":{\"id\":\"ex-1\",\"localId\":\"l-1\",\"examCustomId\":null,"
                + "  \"examDate\":1790150400000,\"reportDate\":null,\"deviceType\":[\"FOP\"],\"examState\":\"ACTIVE\"},"
                + "\"images\":{"
                + "  \"fopImages\":{"
                + "    \"STANDARD\":["
                + "      {\"id\":\"im-1\",\"examId\":\"ex-1\",\"date\":1790150500000,\"laterality\":\"RIGHT\","
                + "       \"field\":\"MACULA\",\"width\":2448,\"height\":2448,\"deviceType\":\"FOP\","
                + "       \"path\":\"https://storage.googleapis.com/b/im-1.jpg?X-Goog-Signature=s1\","
                + "       \"thumbnailPath\":\"https://storage.googleapis.com/b/im-1_t.jpg?X-Goog-Signature=t1\"},"
                + "      {\"id\":\"im-2\",\"examId\":\"ex-1\",\"date\":1790150600000,\"laterality\":\"LEFT\","
                + "       \"field\":\"MACULA\",\"width\":2448,\"height\":2448,\"deviceType\":\"FOP\","
                + "       \"path\":\"https://storage.googleapis.com/b/im-2.jpg?X-Goog-Signature=s2\"}],"
                + "    \"EDITED\":["
                + "      {\"id\":\"im-3\",\"examId\":\"ex-1\",\"laterality\":\"LEFT\",\"isCropped\":true,"
                + "       \"path\":\"https://storage.googleapis.com/b/im-3.jpg?X-Goog-Signature=s3\"}]},"
                + "  \"aimImages\":{},\"pristineImages\":{}"
                + "}},"
                + "{\"examDetails\":{},\"images\":{}}"   // an entry without an id is dropped, not fatal
                + "]";
        ScriptedTransport wire = authenticating().answer(200, ok(listing));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, wire, NO_DOWNLOADS);

        List<Exam> exams = client.examsBetween(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23));

        assertEquals(1, exams.size());
        Exam ex = exams.get(0);
        assertEquals("ex-1", ex.id());
        assertEquals("HAE-002", ex.mrn());
        assertEquals(List.of("FOP"), ex.deviceTypes());
        assertEquals(Instant.ofEpochMilli(1790150400000L), ex.examDate());
        assertEquals(3, ex.images().size());
        assertEquals("EDITED variants are not ingested", 2, ex.standardImages().size());
        Image first = ex.standardImages().get(0);
        assertEquals("im-1", first.id());
        assertEquals("RIGHT", first.laterality());
        assertEquals("fopImages", first.group());
        assertEquals("STANDARD", first.variant());
        assertEquals(2448, first.width().intValue());
        assertNotNull(first.path());
        assertNull(ex.standardImages().get(1).thumbnailPath());
    }

    @Test
    public void downloadOpensTheSignedUrlWithoutGatewayHeadersAndRefusesNonHttps() throws Exception {
        List<URI> opened = new ArrayList<>();
        RemidioGatewayClient.Downloader dl = (uri, timeout) -> {
            opened.add(uri);
            return new RemidioGatewayClient.Download(200, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        };
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, new ScriptedTransport(), dl);

        try (InputStream in = client.download("https://storage.googleapis.com/b/im-1.jpg?X-Goog-Signature=s1")) {
            assertEquals(3, in.readAllBytes().length);
        }
        assertEquals(1, opened.size());
        assertEquals("storage.googleapis.com", opened.get(0).getHost());

        RemidioException e = assertThrows(RemidioException.class,
                () -> client.download("http://storage.googleapis.com/b/im-1.jpg"));
        assertEquals(RemidioException.Reason.MALFORMED, e.reason());
    }

    @Test
    public void anExpiredSignedUrlIsReportedAsSuch() {
        RemidioGatewayClient.Downloader dl = (uri, timeout) ->
                new RemidioGatewayClient.Download(403, new ByteArrayInputStream(new byte[0]));
        RemidioGatewayClient client = new RemidioGatewayClient(SETTINGS, new ScriptedTransport(), dl);

        RemidioException e = assertThrows(RemidioException.class,
                () -> client.download("https://storage.googleapis.com/b/im-1.jpg?X-Goog-Signature=old"));

        assertEquals(RemidioException.Reason.REMOTE, e.reason());
        assertEquals(403, e.httpStatus());
        assertTrue(e.getMessage().contains("expired"));
    }

    @Test
    public void instantsComeAsEpochMillisOrIso() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertEquals(Instant.ofEpochMilli(1790150400000L),
                RemidioGatewayClient.instant(m.readTree("1790150400000")));
        assertEquals(Instant.parse("2026-09-23T08:00:00Z"),
                RemidioGatewayClient.instant(m.readTree("\"2026-09-23T08:00:00Z\"")));
        assertNull(RemidioGatewayClient.instant(m.readTree("\"yesterday\"")));
        assertNull(RemidioGatewayClient.instant(null));
    }
}
