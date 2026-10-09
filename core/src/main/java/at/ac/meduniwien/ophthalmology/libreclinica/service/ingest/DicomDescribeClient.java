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

    /**
     * What the sidecar says about a file — exam and device, never the patient.
     *
     * @param pixelSha256 DR-036 — SHA-256 of the decoded pixel array, the
     *                    file's picture without its tags; null when the
     *                    sidecar predates it or could not decode the pixels
     * @param numberOfFrames DR-039 — NumberOfFrames, null when absent or
     *                    the sidecar predates it
     * @param octVolume   DR-039 — the sidecar's verdict "OPT and more than
     *                    one frame", null when it predates it
     */
    public record Description(String sopInstanceUid, String sopClassUid, String studyInstanceUid,
                              String seriesInstanceUid, String modality, String laterality,
                              LocalDate studyDate, LocalDate acquisitionDate,
                              String manufacturer, String manufacturerModelName,
                              String previewPngPath, boolean identityRemoved, int changedTags,
                              String pixelSha256, Integer numberOfFrames, Boolean octVolume) {}

    /** Why a description could not be had, so the caller can pick a status code. */
    public static class DescribeException extends Exception {
        private static final long serialVersionUID = 1L;

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
        return describe(file, pseudonym, false);
    }

    /**
     * As {@link #describe(Path, String)}; {@code strict} asks the sidecar for
     * the de-identification-required behaviour: private tags dropped and the
     * extended attribute list (sex, size, weight, descriptions, device and
     * station) cleared as well.
     */
    public Description describe(Path file, String pseudonym, boolean strict) throws DescribeException {
        if (!isConfigured()) {
            throw new DescribeException(DescribeException.Reason.UNCONFIGURED,
                    "no DICOM describe sidecar is configured (" + KEY_URL + ")");
        }
        ObjectNode body = json.createObjectNode();
        body.put("path", file.toAbsolutePath().toString());
        if (strict) body.put("strict", true);
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
                n.path("changedTags").asInt(0),
                text(n, "pixelSha256"),
                n.hasNonNull("numberOfFrames") && n.get("numberOfFrames").canConvertToInt()
                        ? Integer.valueOf(n.get("numberOfFrames").asInt()) : null,
                n.hasNonNull("octVolume") && n.get("octVolume").isBoolean()
                        ? Boolean.valueOf(n.get("octVolume").asBoolean()) : null);
    }

    /**
     * DR-036 — the picture digest of a DICOM file already in the store, for
     * rows written before the digest existed. Nothing about the file is
     * changed: the sidecar decodes the pixels and answers with their SHA-256.
     *
     * @return the digest, or null when the sidecar could not decode the pixels
     */
    public String fingerprint(Path file) throws DescribeException {
        if (!isConfigured()) {
            throw new DescribeException(DescribeException.Reason.UNCONFIGURED,
                    "no DICOM describe sidecar is configured (" + KEY_URL + ")");
        }
        ObjectNode body = json.createObjectNode();
        body.put("path", file.toAbsolutePath().toString());
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(fingerprintUrl()))
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
            LOG.warn("DICOM fingerprint sidecar unreachable: {}", e.getClass().getSimpleName());
            throw new DescribeException(DescribeException.Reason.UNREACHABLE,
                    "the DICOM describe sidecar did not answer");
        }
        int status = response.statusCode();
        if (status == 422) {
            throw new DescribeException(DescribeException.Reason.NOT_DICOM, "not a DICOM file");
        }
        if (status / 100 != 2) {
            LOG.warn("DICOM fingerprint sidecar answered HTTP {}", status);
            throw new DescribeException(DescribeException.Reason.REJECTED,
                    "the DICOM describe sidecar refused (HTTP " + status + ")");
        }
        try {
            return text(json.readTree(response.body()), "pixelSha256");
        } catch (IOException | RuntimeException malformed) {
            throw new DescribeException(DescribeException.Reason.REJECTED,
                    "the DICOM describe sidecar answered malformed JSON");
        }
    }

    /**
     * The fingerprint endpoint sits beside the describe one on the same
     * server: {@code …/describe} becomes {@code …/fingerprint}, and a URL
     * that does not end in {@code /describe} gets {@code /fingerprint}
     * appended.
     */
    String fingerprintUrl() {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (base.endsWith("/describe")) {
            return base.substring(0, base.length() - "/describe".length()) + "/fingerprint";
        }
        return base + "/fingerprint";
    }

    /**
     * What {@link #verify} found: {@code violations} are DICOM keywords or
     * rule names, never values.
     */
    public record Verification(boolean ok, java.util.List<String> violations) {}

    /**
     * Layer 2/3 — ask the sidecar whether the file at {@code file} is already
     * de-identified, WITHOUT touching it. The patient name and id must be
     * empty or equal {@code pseudonym}; see {@code dicom_scp/verify.py}.
     */
    public Verification verify(Path file, String pseudonym) throws DescribeException {
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
            request = HttpRequest.newBuilder(URI.create(siblingUrl("verify")))
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
            LOG.warn("DICOM verify sidecar unreachable: {}", e.getClass().getSimpleName());
            throw new DescribeException(DescribeException.Reason.UNREACHABLE,
                    "the DICOM describe sidecar did not answer");
        }
        int status = response.statusCode();
        if (status == 422) {
            throw new DescribeException(DescribeException.Reason.NOT_DICOM, "not a DICOM file");
        }
        if (status / 100 != 2) {
            LOG.warn("DICOM verify sidecar answered HTTP {}", status);
            throw new DescribeException(DescribeException.Reason.REJECTED,
                    "the DICOM describe sidecar refused (HTTP " + status + ")");
        }
        try {
            JsonNode n = json.readTree(response.body());
            java.util.List<String> violations = new java.util.ArrayList<>();
            for (JsonNode v : n.path("violations")) violations.add(v.asText());
            // Fail-closed: only an explicit ok=true with no violations passes.
            boolean ok = n.path("ok").asBoolean(false) && violations.isEmpty();
            return new Verification(ok, violations);
        } catch (IOException | RuntimeException malformed) {
            throw new DescribeException(DescribeException.Reason.REJECTED,
                    "the DICOM describe sidecar answered malformed JSON");
        }
    }

    /** A sidecar route beside the describe one: {@code …/describe} becomes {@code …/<name>}. */
    String siblingUrl(String name) {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (base.endsWith("/describe")) {
            return base.substring(0, base.length() - "/describe".length()) + "/" + name;
        }
        return base + "/" + name;
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
