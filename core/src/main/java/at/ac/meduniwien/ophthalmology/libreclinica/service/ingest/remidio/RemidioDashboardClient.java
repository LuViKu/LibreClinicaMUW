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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.RemidioException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Response;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.Transport;

/**
 * DR-031 — the Remidio web dashboard's own API, for the two things the
 * gateway API cannot do: create a patient, and create an exam on one.
 *
 * <p>This is the contract between Remidio's web app and its backend, not an
 * API they hand to integrators. It was read off the dashboard on 2026-09-23
 * (its JavaScript bundle names every path; a recorded session showed the
 * bodies) and verified from a script the same day: {@code createPatient}
 * takes plain JSON — the {@code checksum} field the dashboard sends is an
 * empty string — and answers with the new patient's numeric id. It can change
 * under us without notice, which is why everything here is behind its own
 * flag and the pull (gateway API) never depends on it.
 *
 * <p>Identity is the dashboard's: the same account as the pull, but the
 * dashboard's client pair ({@code WEB_DASHBOARD} + its public client token,
 * the one every browser session sends) and a {@code tokentype: rem} header.
 * The gateway pair is refused on these paths and vice versa.
 *
 * <p>Nothing here can delete: the dashboard has no such endpoint (its bundle
 * lists {@code createPatient}, {@code editPatient} and the reads, nothing
 * else), so a patient created is a patient kept. The caller checks
 * {@link #findPatientId} first and creates only for what it is sure about.
 */
public class RemidioDashboardClient {

    private static final Logger LOG = LoggerFactory.getLogger(RemidioDashboardClient.class);

    public static final String KEY_ENABLED = "core.remidio.patientSync.enabled";
    public static final String KEY_CLIENT_NAME = "core.remidio.dashboard.clientName";
    public static final String KEY_CLIENT_TOKEN = "core.remidio.dashboard.clientIdentificationToken";
    /** The site's numeric id (not the custom identifier the gateway wants). */
    public static final String KEY_SITE_ID = "core.remidio.siteId";

    public static final String DEFAULT_CLIENT_NAME = "WEB_DASHBOARD";
    static final String DEVICE_TYPE = "FOP";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Everything the client needs; base URL and account are shared with the gateway settings. */
    public record Settings(String baseUrl, String clientName, String clientToken,
                           String email, String password, long siteId) {

        public Settings {
            baseUrl = strip(baseUrl);
            clientName = strip(clientName).isEmpty() ? DEFAULT_CLIENT_NAME : strip(clientName);
            clientToken = strip(clientToken);
            email = strip(email);
            password = password == null ? "" : password;
        }

        public boolean isConfigured() {
            return !baseUrl.isEmpty() && !clientToken.isEmpty() && !email.isEmpty()
                    && !password.isEmpty() && siteId > 0;
        }

        public String missing() {
            if (baseUrl.isEmpty()) return RemidioGatewayClient.KEY_BASE_URL;
            if (clientToken.isEmpty()) return KEY_CLIENT_TOKEN;
            if (email.isEmpty()) return RemidioGatewayClient.KEY_EMAIL;
            if (password.isEmpty()) return RemidioGatewayClient.KEY_PASSWORD;
            if (siteId <= 0) return KEY_SITE_ID;
            return null;
        }

        public static Settings fromConfig() {
            long site;
            try {
                site = Long.parseLong(cfg(KEY_SITE_ID));
            } catch (NumberFormatException notANumber) {
                site = 0;
            }
            return new Settings(cfg(RemidioGatewayClient.KEY_BASE_URL), cfg(KEY_CLIENT_NAME),
                    cfg(KEY_CLIENT_TOKEN), cfg(RemidioGatewayClient.KEY_EMAIL),
                    cfg(RemidioGatewayClient.KEY_PASSWORD), site);
        }

        private static String strip(String s) {
            return s == null ? "" : s.trim();
        }
    }

    private final Settings settings;
    private final Transport transport;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String bearer;

    public RemidioDashboardClient() {
        this(Settings.fromConfig());
    }

    public RemidioDashboardClient(Settings settings) {
        this(settings, RemidioGatewayClient.defaultTransport());
    }

    public RemidioDashboardClient(Settings settings, Transport transport) {
        this.settings = settings;
        this.transport = transport;
    }

    public Settings settings() {
        return settings;
    }

    /* ------------------------------------------------------------------ */
    /* calls                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * The sites this account can see, as the dashboard's own site list.
     *
     * <p>Exists so a misconfigured {@code core.remidio.siteId} can be caught
     * at the first pass instead of failing identically on every subject
     * forever. Deliberately the dashboard's own endpoint rather than the
     * gateway's: this client never calls {@code getAuthToken}, so checking
     * cannot invalidate a {@code clientAuthToken} the pull is holding.
     */
    public List<RemidioGatewayClient.Site> sites() throws RemidioException {
        JsonNode data = dataOf(authenticated("GET", "/api/org/getSites", null), "/api/org/getSites");
        List<RemidioGatewayClient.Site> out = new ArrayList<>();
        for (JsonNode s : data) {
            out.add(new RemidioGatewayClient.Site(
                    s.path("siteId").asLong(0), text(s, "siteName"), text(s, "siteDomain")));
        }
        return out;
    }

    /**
     * Whether the configured {@link Settings#siteId()} is one this account can
     * write to.
     *
     * @return empty when it is; otherwise the ids that *are* available, so the
     *         caller can name them in one actionable line
     */
    public Optional<List<Long>> siteMismatch() throws RemidioException {
        List<RemidioGatewayClient.Site> sites = sites();
        for (RemidioGatewayClient.Site s : sites) {
            if (s.siteId() == settings.siteId()) return Optional.empty();
        }
        List<Long> ids = new ArrayList<>(sites.size());
        for (RemidioGatewayClient.Site s : sites) ids.add(s.siteId());
        return Optional.of(ids);
    }

    /**
     * The Remidio patient id behind an MRN at our site, or empty when there
     * is none. The dashboard answers a missing MRN with 404 {@code NOT_FOUND},
     * which here is an answer, not an error.
     */
    public Optional<Long> findPatientId(String mrn) throws RemidioException {
        String path = "/api/patient/getPatientWithExams/" + encode(mrn)
                + "?siteId=" + settings.siteId() + "&deviceType=" + DEVICE_TYPE;
        Response r = authenticated("GET", path, null);
        if (r.status() == 404) return Optional.empty();
        JsonNode data = dataOf(r, path);
        JsonNode id = data.path("patient").path("id");
        if (!id.isNumber()) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), null,
                    "the patient lookup answered without a patient id");
        }
        return Optional.of(id.asLong());
    }

    /**
     * Creates a patient at our site and returns its Remidio id.
     *
     * @param mrn      the study subject label — the one thing about the patient that is real
     * @param gender   {@code MALE} or {@code FEMALE}; the dashboard offers nothing else
     * @param dobEpochMs a placeholder, by convention the epoch (1970-01-01)
     */
    public long createPatient(String mrn, String firstName, String lastName, long dobEpochMs, String gender)
            throws RemidioException {
        ObjectNode body = json.createObjectNode();
        body.put("mrn", mrn);
        body.put("checksum", "");
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("dateOfBirth", dobEpochMs);
        body.put("gender", gender);
        body.put("siteId", settings.siteId());
        body.put("phoneNo", "");
        Response r = authenticated("POST", "/api/patient/createPatient", body.toString());
        JsonNode data = dataOf(r, "/api/patient/createPatient");
        if (!data.isNumber()) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), null,
                    "createPatient answered without a patient id");
        }
        LOG.info("Remidio: patient created for a subject label (id {})", data.asLong());
        return data.asLong();
    }

    /**
     * Creates an exam on a patient and returns the exam's Remidio id.
     *
     * @param examCustomId what the exam is called in the app — our visit accession
     * @param examDateMs   when the visit is scheduled, epoch milliseconds
     * @param localId      a stable id of ours, so a retry can be recognised in a listing
     */
    public long createExam(long patientId, String examCustomId, long examDateMs, String localId)
            throws RemidioException {
        ObjectNode body = json.createObjectNode();
        body.put("examLocalId", localId);
        body.put("examDate", examDateMs);
        body.putArray("deviceType").add(DEVICE_TYPE);
        body.put("patientId", patientId);
        body.put("examCustomId", examCustomId);
        ObjectNode provider = body.putObject("orderingProvider");
        provider.put("firstName", "LibreClinica");
        provider.put("lastName", "");
        provider.put("email", "");
        Response r = authenticated("POST", "/api/exam/createExam", body.toString());
        JsonNode id = dataOf(r, "/api/exam/createExam").path("examDetails").path("id");
        if (!id.isNumber()) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), null,
                    "createExam answered without an exam id");
        }
        LOG.info("Remidio: exam {} created for {}", id.asLong(), examCustomId);
        return id.asLong();
    }

    public void reset() {
        bearer = null;
    }

    /* ------------------------------------------------------------------ */
    /* auth                                                                */
    /* ------------------------------------------------------------------ */

    /** A call with the login bearer, logging in first if needed and once more on 401. */
    private Response authenticated(String method, String path, String body) throws RemidioException {
        if (!settings.isConfigured()) {
            throw new RemidioException(RemidioException.Reason.UNCONFIGURED, 0, null,
                    "the Remidio patient sync is not configured (" + settings.missing() + ")");
        }
        if (bearer == null) bearer = login();
        Response r = exchange(method, path, headers(), body);
        if (r.status() == 401) {
            LOG.info("Remidio dashboard answered 401 on {} — logging in again once", pathOnly(path));
            bearer = login();
            r = exchange(method, path, headers(), body);
            if (r.status() == 401) {
                throw new RemidioException(RemidioException.Reason.UNAUTHORIZED, 401, null,
                        "the Remidio dashboard refuses this account after a fresh login");
            }
        }
        return r;
    }

    private String login() throws RemidioException {
        ObjectNode body = json.createObjectNode();
        body.put("emailAddress", settings.email());
        body.put("password", settings.password());
        body.put("deviceId", "");
        Map<String, String> h = clientHeaders();
        Response r = exchange("POST", "/api/user/loginUser", h, body.toString());
        if (r.status() == 401 || r.status() == 404) {
            throw new RemidioException(RemidioException.Reason.UNAUTHORIZED, r.status(), null,
                    "the Remidio account login was refused (HTTP " + r.status() + ")");
        }
        String token = dataOf(r, "/api/user/loginUser").asText(null);
        if (token == null || token.isBlank()) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), null,
                    "the Remidio login answered without a token");
        }
        return token;
    }

    private Map<String, String> clientHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("clientName", settings.clientName());
        h.put("clientIdentificationToken", settings.clientToken());
        h.put("Accept", "application/json");
        return h;
    }

    private Map<String, String> headers() {
        Map<String, String> h = clientHeaders();
        h.put("Authorization", "Bearer " + bearer);
        h.put("tokentype", "rem");
        return h;
    }

    private Response exchange(String method, String path, Map<String, String> headers, String body)
            throws RemidioException {
        URI uri = URI.create(settings.baseUrl().replaceAll("/+$", "") + path);
        try {
            return transport.send(method, uri, headers, body, TIMEOUT);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Remidio dashboard unreachable on {}: {}", pathOnly(path), e.getClass().getSimpleName());
            throw new RemidioException(RemidioException.Reason.UNREACHABLE, 0, null,
                    "the Remidio dashboard did not answer");
        }
    }

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
            LOG.warn("Remidio dashboard answered HTTP {} ({}) on {}", r.status(),
                    code == null ? "no envelope" : code, pathOnly(path));
            throw new RemidioException(RemidioException.Reason.REMOTE, r.status(), code,
                    message != null ? message : "the Remidio dashboard answered HTTP " + r.status());
        }
        if (root == null || !root.has("data")) {
            throw new RemidioException(RemidioException.Reason.MALFORMED, r.status(), code,
                    "the Remidio dashboard answered something that is not its envelope");
        }
        return root.get("data");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String pathOnly(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String cfg(String key) {
        try {
            String raw = CoreResources.getField(key);
            return raw == null ? "" : raw.trim();
        } catch (Exception noContext) {
            return "";
        }
    }
}
