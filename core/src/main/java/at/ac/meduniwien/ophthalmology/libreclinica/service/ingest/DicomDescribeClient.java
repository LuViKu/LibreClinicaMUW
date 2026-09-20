/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-029 — ask the DICOM sidecar what an uploaded file is, and have it
 * pseudonymised on the way.
 *
 * <p>The application has no DICOM parser and is not getting one for this: the
 * sidecar that already receives C-STOREs carries pydicom, renders the same
 * preview, and extracts the same tags. An uploaded file is written to the
 * volume both containers share, then described by path. The sidecar rewrites
 * the file's patient identity before it answers, so a Clarus export that
 * carried the hospital's patient never reaches a database row or an export
 * bundle with it — see {@code dicom_scp/deidentify.py} for what goes and what
 * stays.
 *
 * <p>Fails loudly by design. When the sidecar is unconfigured or unreachable
 * the upload is refused, not stored as-is: the whole point of the call is
 * that the file is clean before it is kept.
 */
public class DicomDescribeClient {

    private static final Logger LOG = LoggerFactory.getLogger(DicomDescribeClient.class);

    public static final String KEY_URL = "core.dicom.describe.url";
    /** The same shared secret the sidecar's ingest hand-off uses. */
    public static final String KEY_TOKEN = "core.dicom.ingest.token";
    public static final String TOKEN_HEADER = "X-MUW-Dicom-Token";

    /** A widefield export is tens of megabytes; decoding it for a preview takes a moment. */
    public static final Duration TIMEOUT = Duration.ofSeconds(90);

    /** What the sidecar says about a file — exam and device, never the patient. */
    public record Description(String sopInstanceUid, String sopClassUid, String studyInstanceUid,
                              String seriesInstanceUid, String modality, String laterality,
                              LocalDate studyDate, LocalDate acquisitionDate,
                              String manufacturer, String manufacturerModelName,
                              String previewPngPath, boolean identityRemoved, int changedTags) {}

    /** Why a description could not be had, so the caller can pick a status code. */
    public static class DescribeException extends Exception {
        public enum Reason {
            /** No sidecar URL or token is configured. */
            UNCONFIGURED,
            /** The sidecar did not answer. */
            UNREACHABLE,
            /** The sidecar read the file and it is not DICOM. */
            NOT_DICOM,
            /** The sidecar refused for another reason (path, token, internal error). */
            REJECTED
        }

        private final Reason reason;

        public DescribeException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final String url;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /** Reads {@link #KEY_URL} and {@link #KEY_TOKEN} from the runtime configuration. */
    public DicomDescribeClient() {
        this(cfg(KEY_URL), cfg(KEY_TOKEN));
    }

    public DicomDescribeClient(String url, String token) {
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** True when a sidecar is configured — the only case in which a DICOM upload can be accepted. */
    public boolean isConfigured() {
        return !url.isEmpty() && !token.isEmpty();
    }

    /**
     * Describe (and pseudonymise) the file at {@code file}.
     *
     * @param pseudonym the subject label to write into the file's patient
     *                  identity, or null to blank it — an upload nobody has
     *                  filed yet carries no name at all
     */
    public Description describe(Path file, String pseudonym) throws DescribeException {
        if (!isConfigured()) {
            throw new DescribeException(DescribeException.Reason.UNCONFIGURED,
                    "no DICOM describe sidecar is configured (" + KEY_URL + ")");
        }
        ObjectNode body = json.createObjectNode();
        body.put("path", file.toAbsolutePath().toString());
        if (pseudonym != null && !pseudonym.isBlank()) {
            body.put("pseudonym", pseudonym.trim());
        } else {
            body.putNull("pseudonym");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(TOKEN_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
        } catch (IllegalArgumentException | IOException bad) {
            throw new DescribeException(DescribeException.Reason.UNCONFIGURED,
                    "the DICOM describe URL is not usable: " + bad.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // The message can name the host, never the file — the path stays out of it.
            LOG.warn("DICOM describe sidecar unreachable: {}", e.getClass().getSimpleName());
            throw new DescribeException(DescribeException.Reason.UNREACHABLE,
                    "the DICOM describe sidecar did not answer");
        }
        int status = response.statusCode();
        if (status == 422) {
            throw new DescribeException(DescribeException.Reason.NOT_DICOM, "not a DICOM file");
        }
        if (status / 100 != 2) {
            LOG.warn("DICOM describe sidecar answered HTTP {}", status);
            throw new DescribeException(DescribeException.Reason.REJECTED,
                    "the DICOM describe sidecar refused (HTTP " + status + ")");
        }
        try {
            return parse(json.readTree(response.body()));
        } catch (IOException | RuntimeException malformed) {
            LOG.warn("DICOM describe sidecar answered something that is not a description: {}",
                    malformed.getClass().getSimpleName());
            throw new DescribeException(DescribeException.Reason.REJECTED,
                    "the DICOM describe sidecar answered malformed JSON");
        }
    }

    private static Description parse(JsonNode n) {
        return new Description(
                text(n, "sopInstanceUid"), text(n, "sopClassUid"),
                text(n, "studyInstanceUid"), text(n, "seriesInstanceUid"),
                text(n, "modality"), text(n, "laterality"),
                date(text(n, "studyDate")), date(text(n, "acquisitionDate")),
                text(n, "manufacturer"), text(n, "manufacturerModelName"),
                text(n, "previewPngPath"),
                n.path("identityRemoved").asBoolean(false),
                n.path("changedTags").asInt(0));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(String iso) {
        if (iso == null) return null;
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException bad) {
            return null;
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
}
