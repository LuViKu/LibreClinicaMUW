/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-031 — the Remidio cloud "gateway" API, as far as the pull needs it.
 *
 * <p>Remidio's handheld (the FOP) uploads every capture to Remidio's cloud;
 * the gateway API is the read-only door integrators get to that cloud. This
 * class speaks it and nothing else — no storage, no binding, no policy. What
 * it knows about the API was verified against our own tenant on 2026-09-23 and
 * differs in places from the only public description of it (AIIMS Delhi's
 * integration); the differences are noted where they matter.
 *
 * <p><b>The chain.</b> {@code POST /api/user/loginUser} with the account's
 * e-mail and password gives a short-lived bearer; {@code GET
 * /api/gateway/getAuthToken} with that bearer gives a long-lived
 * {@code clientAuthToken}; every data call carries the client pair, the
 * {@code clientAuthToken} and the bearer. All three are cached here for the
 * life of the instance and re-minted once when a call answers 401.
 * {@code getAuthToken} invalidates whatever {@code clientAuthToken} was issued
 * before it, so re-minting on every poll would be self-defeating and a second
 * consumer on the same account would fight this one.
 *
 * <p><b>The client pair.</b> {@code clientName} must be the actor the token was
 * issued for — {@code PACS_GATEWAY} — not the e-mail address the vendor's mail
 * happened to mention. Any other name is an <em>unhandled HTTP 500</em> on
 * every endpoint, which looks like an outage and is not.
 *
 * <p><b>Nothing here is logged in the clear.</b> Tokens never appear in a log
 * line; signed download URLs carry their credential in the query string and
 * are logged by host only; an MRN is a subject label and stays out of the log
 * too.
 */
public class RemidioGatewayClient {

    private static final Logger LOG = LoggerFactory.getLogger(RemidioGatewayClient.class);

    public static final String KEY_ENABLED = "core.remidio.pull.enabled";
    public static final String KEY_BASE_URL = "core.remidio.baseUrl";
    public static final String KEY_CLIENT_NAME = "core.remidio.clientName";
    public static final String KEY_CLIENT_TOKEN = "core.remidio.clientIdentificationToken";
    public static final String KEY_EMAIL = "core.remidio.email";
    public static final String KEY_PASSWORD = "core.remidio.password";
    public static final String KEY_SITE_CUSTOM_ID = "core.remidio.siteCustomId";

    /** The gateway actor Remidio issues integration tokens for. */
    public static final String DEFAULT_CLIENT_NAME = "PACS_GATEWAY";

    /** The API takes DD-MM-YYYY in the path, not ISO. */
    static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);

    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** An exam listing for a wide window can run to several megabytes; give it longer. */
    static final Duration LISTING_TIMEOUT = Duration.ofSeconds(90);

    /* ------------------------------------------------------------------ */
    /* configuration                                                       */
    /* ------------------------------------------------------------------ */

    /** Everything the client needs, in one place, so a test can hand it in. */
    public record Settings(String baseUrl, String clientName, String clientToken,
                           String email, String password, String siteCustomId) {

        public Settings {
            baseUrl = strip(baseUrl);
            clientName = strip(clientName).isEmpty() ? DEFAULT_CLIENT_NAME : strip(clientName);
            clientToken = strip(clientToken);
            email = strip(email);
            password = password == null ? "" : password;
            siteCustomId = strip(siteCustomId);
        }

        /** True when every value a pull needs is present. */
        public boolean isConfigured() {
            return !baseUrl.isEmpty() && !clientToken.isEmpty() && !email.isEmpty()
                    && !password.isEmpty() && !siteCustomId.isEmpty();
        }

        /** The first missing key, for a log line that says what to fill in. */
        public String missing() {
            if (baseUrl.isEmpty()) return KEY_BASE_URL;
            if (clientToken.isEmpty()) return KEY_CLIENT_TOKEN;
            if (email.isEmpty()) return KEY_EMAIL;
            if (password.isEmpty()) return KEY_PASSWORD;
            if (siteCustomId.isEmpty()) return KEY_SITE_CUSTOM_ID;
            return null;
        }

        /** Reads the {@code core.remidio.*} keys from the runtime configuration. */
        public static Settings fromConfig() {
            return new Settings(cfg(KEY_BASE_URL), cfg(KEY_CLIENT_NAME), cfg(KEY_CLIENT_TOKEN),
                    cfg(KEY_EMAIL), cfg(KEY_PASSWORD), cfg(KEY_SITE_CUSTOM_ID));
        }

        private static String strip(String s) {
            return s == null ? "" : s.trim();
        }
    }

    /* ------------------------------------------------------------------ */
    /* what comes back                                                     */
    /* ------------------------------------------------------------------ */

    /** A screening site of the organisation. */
    public record Site(long siteId, String siteName, String siteDomain) {}

    /**
     * One capture. {@code laterality} is the API's own word ({@code RIGHT} /
     * {@code LEFT}); {@code variant} is {@code STANDARD} or {@code EDITED};
     * {@code path} is a signed download URL that expires an hour after the
     * listing that produced it.
     */
    public record Image(String id, String examId, Instant date, String laterality, String field,
                        Integer width, Integer height, String path, String thumbnailPath,
                        String deviceType, String group, String variant) {}

    /**
     * One exam: one patient, one sitting, its images. {@code mrn} is whatever
     * the photographer typed into the app's MRN field — by our convention the
     * study subject label. Name, date of birth and sex are present in the API
     * and deliberately not read.
     */
    public record Exam(String id, String localId, String examCustomId, Instant examDate,
                       List<String> deviceTypes, String examState, String mrn, List<Image> images) {

        /** The images worth ingesting: the untouched capture of every group. */
        public List<Image> standardImages() {
            List<Image> out = new ArrayList<>();
            for (Image i : images) {
                if ("STANDARD".equalsIgnoreCase(i.variant())) out.add(i);
            }
            return out;
        }
    }

    /** Why a call failed, coarsely — the caller decides whether to retry or give up. */
    public static final class RemidioException extends Exception {
        public enum Reason {
            /** A {@code core.remidio.*} key is missing. */
            UNCONFIGURED,
            /** No answer, or not an HTTP answer. */
            UNREACHABLE,
            /** 401 after a fresh login — credentials or client pair wrong. */
            UNAUTHORIZED,
            /** The gateway answered with an error envelope. */
            REMOTE,
            /** The answer was not the envelope this class understands. */
            MALFORMED
        }

        private final Reason reason;
        private final int httpStatus;
        private final String statusCode;

        public RemidioException(Reason reason, int httpStatus, String statusCode, String message) {
            super(message);
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.statusCode = statusCode;
        }

        public Reason reason() { return reason; }
        /** HTTP status, or 0 when there was none. */
        public int httpStatus() { return httpStatus; }
        /** The envelope's {@code status.statusCode}, or null. */
        public String statusCode() { return statusCode; }
    }

    /* ------------------------------------------------------------------ */
    /* the wire, abstracted so a test needs no network                     */
    /* ------------------------------------------------------------------ */

    /** One HTTP exchange with a small text body either way. */
    public interface Transport {
        Response send(String method, URI uri, Map<String, String> headers, String jsonBody,
                      Duration timeout) throws IOException, InterruptedException;
    }

    /** A streamed GET of an absolute URL, for the signed image downloads. */
    public interface Downloader {
        /** @return the body stream and its HTTP status; the caller closes the stream */
        Download open(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    public record Download(int status, InputStream body) {}

    private final Settings settings;
    private final Transport transport;
    private final Downloader downloader;
    private final ObjectMapper json = new ObjectMapper();

    private volatile String bearer;
    private volatile String clientAuthToken;

    /** Reads its settings from the runtime configuration and talks real HTTP. */
    public RemidioGatewayClient() {
        this(Settings.fromConfig());
    }

    public RemidioGatewayClient(Settings settings) {
        this(settings, defaultTransport(), defaultDownloader());
    }

    public RemidioGatewayClient(Settings settings, Transport transport, Downloader downloader) {
        this.settings = settings;
        this.transport = transport;
        this.downloader = downloader;
    }

    public Settings settings() {
        return settings;
    }

    /* ------------------------------------------------------------------ */
    /* calls                                                               */
    /* ------------------------------------------------------------------ */

    /** The organisation's sites — the cheapest call that proves the whole chain. */
    public List<Site> sites() throws RemidioException {
        JsonNode data = gateway("/api/gateway/getSites", TIMEOUT);
        List<Site> out = new ArrayList<>();
        for (JsonNode s : data) {
            out.add(new Site(s.path("siteId").asLong(0), text(s, "siteName"), text(s, "siteDomain")));
        }
        return out;
    }

    /**
     * Every exam of the configured site whose date falls in the window, both
     * ends inclusive, with signed download paths on the images.
     *
     * <p>The path wants the site's <em>custom</em> identifier (set in the
     * Remidio dashboard); the numeric site id is refused with 404. And the
     * gateway's end date is <em>exclusive</em> — verified 2026-09-24: an exam
     * captured at 06:51Z that day was absent from {@code …/24-09-2026/…} and
     * present in {@code …/25-09-2026/…} — so the wire gets the day after
     * {@code to}, and a caller asking for "today" gets today.
     */
    public List<Exam> examsBetween(LocalDate from, LocalDate to) throws RemidioException {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("window must be from <= to");
        }
        String path = "/api/gateway/getExamsByDate/" + PATH_DATE.format(from) + "/"
                + PATH_DATE.format(to.plusDays(1)) + "/" + settings.siteCustomId() + "?includeFilePaths=true";
        JsonNode data = gateway(path, LISTING_TIMEOUT);
        List<Exam> out = new ArrayList<>();
        for (JsonNode e : data) {
            Exam exam = parseExam(e);
            if (exam != null) out.add(exam);
        }
        return out;
    }

    /**
     * Opens a signed download URL. No client headers — the credential is in
     * the URL itself, and it points at object storage, not at the gateway.
     *
     * @return the body stream; the caller closes it
     */
    public InputStream download(String signedUrl) throws RemidioException {
        URI uri;
        try {
            uri = URI.create(signedUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("not https");
            }
        } catch (IllegalArgumentException bad) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, 0, null,
                    "the image download URL is not usable");
        }
        Download d;
        try {
            d = downloader.open(uri, LISTING_TIMEOUT);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Remidio image download from {} failed: {}", uri.getHost(), e.getClass().getSimpleName());
            throw new RemidioException(RemidioException.Reason.UNREACHABLE, 0, null,
                    "the image store did not answer");
        }
        if (d.status() / 100 != 2) {
            closeQuietly(d.body());
            LOG.warn("Remidio image download from {} answered HTTP {}", uri.getHost(), d.status());
            throw new RemidioException(RemidioException.Reason.REMOTE, d.status(), null,
                    "the image store refused the download (HTTP " + d.status() + ")"
                            + (d.status() == 403 ? " — the signed URL has probably expired" : ""));
        }
        return d.body();
    }

    /** Forget the cached tokens, so the next call logs in afresh. */
    public void reset() {
        bearer = null;
        clientAuthToken = null;
    }

    /* ------------------------------------------------------------------ */
    /* the chain                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * A gateway GET with the full header set, re-authenticating once on 401.
     *
     * @return the envelope's {@code data}
     */
    private JsonNode gateway(String path, Duration timeout) throws RemidioException {
        requireConfigured();
        ensureAuthenticated();
        Response r = exchange("GET", path, gatewayHeaders(), null, timeout);
        if (r.status() == 401) {
            LOG.info("Remidio gateway answered 401 on {} — re-authenticating once", pathOnly(path));
            reset();
            ensureAuthenticated();
            r = exchange("GET", path, gatewayHeaders(), null, timeout);
            if (r.status() == 401) {
                throw new RemidioException(RemidioException.Reason.UNAUTHORIZED, 401,
                        statusCodeOf(r), "the Remidio gateway refuses this client after a fresh login");
            }
        }
        return dataOf(r, path);
    }

    private void ensureAuthenticated() throws RemidioException {
        if (bearer == null) bearer = login();
        if (clientAuthToken == null) clientAuthToken = authToken();
    }

    private String login() throws RemidioException {
        ObjectNode body = json.createObjectNode();
        body.put("emailAddress", settings.email());
        body.put("password", settings.password());
        // The web dashboard sends this, empty; the gateway's login is the same
        // endpoint and is happier with the field present.
        body.put("deviceId", "");
        Response r = exchange("POST", "/api/user/loginUser", clientHeaders(), body.toString(), TIMEOUT);
        if (r.status() == 401 || r.status() == 404) {
            // 404 "User … was not found" is what a wrong e-mail gets.
            throw new RemidioException(RemidioException.Reason.UNAUTHORIZED, r.status(), statusCodeOf(r),
                    "the Remidio account login was refused (HTTP " + r.status() + ")");
        }
        String token = dataOf(r, "/api/user/loginUser").asText(null);
        if (token == null || token.isBlank()) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), statusCodeOf(r),
                    "the Remidio login answered without a token");
        }
        LOG.info("Remidio: logged in as the integration account");
        return token;
    }

    private String authToken() throws RemidioException {
        Map<String, String> h = clientHeaders();
        h.put("Authorization", "Bearer " + bearer);
        Response r = exchange("GET", "/api/gateway/getAuthToken", h, null, TIMEOUT);
        if (r.status() == 401) {
            throw new RemidioException(RemidioException.Reason.UNAUTHORIZED, 401, statusCodeOf(r),
                    "the Remidio gateway did not accept the login for this client pair");
        }
        String token = dataOf(r, "/api/gateway/getAuthToken").asText(null);
        if (token == null || token.isBlank()) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), statusCodeOf(r),
                    "the Remidio gateway answered getAuthToken without a token");
        }
        LOG.info("Remidio: gateway client auth token issued");
        return token;
    }

    private Map<String, String> clientHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("clientName", settings.clientName());
        h.put("clientIdentificationToken", settings.clientToken());
        h.put("Accept", "application/json");
        return h;
    }

    private Map<String, String> gatewayHeaders() {
        Map<String, String> h = clientHeaders();
        h.put("clientAuthToken", clientAuthToken);
        h.put("Authorization", "Bearer " + bearer);
        return h;
    }

    private Response exchange(String method, String path, Map<String, String> headers, String body,
                              Duration timeout) throws RemidioException {
        URI uri = URI.create(settings.baseUrl().replaceAll("/+$", "") + path);
        try {
            return transport.send(method, uri, headers, body, timeout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Remidio gateway unreachable on {}: {}", pathOnly(path), e.getClass().getSimpleName());
            throw new RemidioException(RemidioException.Reason.UNREACHABLE, 0, null,
                    "the Remidio gateway did not answer");
        } catch (IllegalArgumentException bad) {
            throw new RemidioException(RemidioException.Reason.UNCONFIGURED, 0, null,
                    "the Remidio base URL is not usable");
        }
    }

    /**
     * Unwraps the envelope: {@code {"status":{"statusCode","message"},"data",…}}.
     * A non-2xx answer is an error even when it parses; the message is the
     * gateway's own, which is specific ("The Site Custom ID provided cannot be
     * found for your organisation") where the HTTP status is not.
     */
    private JsonNode dataOf(Response r, String path) throws RemidioException {
        JsonNode root;
        try {
            root = r.body() == null || r.body().isBlank() ? null : json.readTree(r.body());
        } catch (IOException notJson) {
            root = null;
        }
        String code = root == null ? null : text(root.path("status"), "statusCode");
        String message = root == null ? null : text(root.path("status"), "message");
        if (r.status() / 100 != 2) {
            LOG.warn("Remidio gateway answered HTTP {} ({}) on {}", r.status(),
                    code == null ? "no envelope" : code, pathOnly(path));
            throw new RemidioException(RemidioException.Reason.REMOTE, r.status(), code,
                    message != null ? message : "the Remidio gateway answered HTTP " + r.status());
        }
        if (root == null || !root.has("data")) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), code,
                    "the Remidio gateway answered something that is not its envelope");
        }
        return root.get("data");
    }

    private void requireConfigured() throws RemidioException {
        if (!settings.isConfigured()) {
            throw new RemidioException(RemidioException.Reason.UNCONFIGURED, 0, null,
                    "the Remidio pull is not configured (" + settings.missing() + ")");
        }
    }

    /* ------------------------------------------------------------------ */
    /* parsing                                                             */
    /* ------------------------------------------------------------------ */

    /** Package-private so the test can feed it a recorded listing. */
    Exam parseExam(JsonNode e) {
        JsonNode ed = e.path("examDetails");
        String id = text(ed, "id");
        if (id == null) return null;
        List<String> devices = new ArrayList<>();
        for (JsonNode d : ed.path("deviceType")) devices.add(d.asText());
        List<Image> images = new ArrayList<>();
        JsonNode groups = e.path("images");
        if (groups.isObject()) {
            groups.fields().forEachRemaining(g -> {
                JsonNode variants = g.getValue();
                if (!variants.isObject()) return;
                variants.fields().forEachRemaining(v -> {
                    for (JsonNode i : v.getValue()) {
                        images.add(new Image(text(i, "id"), text(i, "examId"), instant(i.get("date")),
                                text(i, "laterality"), text(i, "field"),
                                intOrNull(i.get("width")), intOrNull(i.get("height")),
                                text(i, "path"), text(i, "thumbnailPath"), text(i, "deviceType"),
                                g.getKey(), v.getKey()));
                    }
                });
            });
        }
        return new Exam(id, text(ed, "localId"), text(ed, "examCustomId"), instant(ed.get("examDate")),
                Collections.unmodifiableList(devices), text(ed, "examState"),
                text(e.path("patientDetails"), "mrn"), Collections.unmodifiableList(images));
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(JsonNode v) {
        return v == null || !v.isNumber() ? null : v.asInt();
    }

    /** Epoch milliseconds (what the API sends) or an ISO instant; null otherwise. */
    static Instant instant(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return Instant.ofEpochMilli(v.asLong());
        try {
            return Instant.parse(v.asText());
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }

    private static String statusCodeOf(Response r) {
        try {
            JsonNode root = new ObjectMapper().readTree(r.body());
            return text(root.path("status"), "statusCode");
        } catch (Exception any) {
            return null;
        }
    }

    /** The path without its query string — which is where a signed credential would be. */
    private static String pathOnly(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do with a stream we are abandoning
        }
    }

    private static String cfg(String key) {
        try {
            String raw = CoreResources.getField(key);
            return raw == null ? "" : raw.trim();
        } catch (Exception noContext) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* the real wire                                                       */
    /* ------------------------------------------------------------------ */

    private static HttpClient newHttp() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static Transport defaultTransport() {
        HttpClient http = newHttp();
        return (method, uri, headers, body, timeout) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        };
    }

    static Downloader defaultDownloader() {
        HttpClient http = newHttp();
        return (uri, timeout) -> {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            return new Download(r.statusCode(), r.body());
        };
    }
}
