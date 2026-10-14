/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-041 — the app's client for the converter sidecar's E2E endpoints
 * ({@code POST /e2e/describe}, {@code POST /e2e/convert}), which sit on
 * the service that serves {@code /preprocess} and take the same token.
 * (The sidecar also has {@code GET /e2e/series-image}; the app does not use it
 * since the browser renders the previews and a series item is a DICOM.)
 *
 * <p>The file is not sent: the sidecar reads it from the E2E upload directory
 * it mounts, so a request names the stored file by its bare name only. A
 * conversion writes its DICOMs below {@link #convertRoot()}, which the app and
 * the sidecar both mount read-write. The only
 * identifying-looking value the sidecar returns is {@code headerLabel}, the study
 * label in the E2E patient chunk's surname slot (never the first-name or
 * patient-id slot, a name or a birth date). It is a candidate subject label, and
 * the caller must not store or log it unless it resolves to a subject.
 *
 * <p>Failures are {@link SidecarException}s with a message fit to show an
 * operator and the HTTP status the API should answer: 503 when the sidecar is
 * not configured or cannot be reached, 502 when it answered with an error or
 * with something unreadable.
 */
@Component
public class E2eSidecarClient {

    private static final Logger LOG = LoggerFactory.getLogger(E2eSidecarClient.class);

    /** A describe parses a whole multimodal file (up to 2 GiB): minutes, not seconds. */
    public static final Duration DEFAULT_DESCRIBE_TIMEOUT = Duration.ofMinutes(10);
    /** Converting a whole export decodes every kept series: a few minutes for 600 MB. */
    public static final Duration DEFAULT_CONVERT_TIMEOUT = Duration.ofMinutes(15);
    public static final String DEFAULT_CONVERT_PATH = "/var/lib/libreclinica/e2e-convert";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The sidecar accepts exactly this shape (the app stores {@code <uuid>.e2e}). */
    static final Pattern STORED_NAME = Pattern.compile("^[A-Za-z0-9-]+\\.e2e$");

    /** One series of a file. Fields the sidecar leaves out read as null. */
    public record Series(String seriesKey, String seriesModality, String deviceModality, String scanPattern,
                         String laterality, String acquiredAt, Integer frames, Integer width, Integer height,
                         Integer octVolumeIndex) {}

    /**
     * {@code headerPatient} is {@code none} (no expected label was sent), {@code empty},
     * {@code matches} or {@code differs}, comparing the expected label with the surname slot.
     * {@code headerLabel} is that surname when it is a plain label token, else null.
     */
    public record Description(String headerPatient, String headerLabel, List<Series> series) {
        public Description {
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    /**
     * One converted series: a DICOM {@code file} (bare name) below the conversion directory, its
     * digest and size, and, for an OCT volume, the {@code geometryFile} companion.
     */
    public record ConvertedFile(String seriesKey, String file, String sha256, Long bytes, Integer frames,
                                String sopClassUid, String geometryFile) {}

    /** The files of one conversion, and the directory they are in. */
    public record Conversion(Path directory, List<ConvertedFile> files) {}

    /** A sidecar call that did not give a usable answer. */
    public static class SidecarException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int httpStatus;

        public SidecarException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        /** The status the API should answer with: 502 or 503. */
        public int httpStatus() {
            return httpStatus;
        }
    }

    /** True when a base URL and a token are set. */
    public boolean isConfigured() {
        return !blank(baseUrl()) && !blank(token());
    }

    /**
     * @param storedName    the bare name of a file in the E2E upload directory
     * @param expectedLabel the subject label (folder name) the header surname is compared with;
     *                      null or blank when the file has no folder (the field is then left out)
     */
    public Description describe(String storedName, String expectedLabel) {
        requireConfigured();
        requireStoredName(storedName);
        String endpoint = baseUrl().replaceAll("/+$", "") + "/e2e/describe";
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storedName", storedName);
        if (!blank(expectedLabel)) body.put("expectedLabel", expectedLabel);
        try {
            ResponseEntity<String> resp = rest(describeTimeout()).exchange(
                    URI.create(endpoint), HttpMethod.POST,
                    new HttpEntity<>(Json.mapper().writeValueAsString(body), headers), String.class);
            String text = resp.getBody();
            if (text == null || text.isBlank()) {
                throw new SidecarException(502, "The image converter returned an empty answer for the E2E file.");
            }
            Description d = Json.mapper().readValue(text, Description.class);
            if (d == null || d.headerPatient() == null) {
                throw new SidecarException(502, "The image converter's answer for the E2E file was incomplete.");
            }
            return d;
        } catch (SidecarException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            // Status only: the body is remote content and may quote the file.
            LOG.warn("E2E describe refused with HTTP {}", e.getStatusCode().value());
            throw new SidecarException(502, "The image converter could not read the E2E file (HTTP "
                    + e.getStatusCode().value() + ").");
        } catch (ResourceAccessException e) {
            LOG.warn("E2E describe: converter unreachable or timed out: {}", e.getClass().getSimpleName());
            throw new SidecarException(503,
                    "The image converter is not reachable or took too long to read the E2E file.");
        } catch (RuntimeException e) {
            LOG.warn("E2E describe: unreadable answer: {}", e.getClass().getSimpleName());
            throw new SidecarException(502, "The image converter's answer for the E2E file could not be read.");
        }
    }

    /**
     * Converts the given series of a stored file, in one pass, into one DICOM each.
     *
     * @param storedName the bare name of a file in the E2E upload directory
     * @param label      the only identity written into the DICOMs (PatientID and PatientName); null or
     *                   blank gives blank ones
     * @param seriesKeys the series to keep
     * @return the directory the sidecar wrote to ({@code <convertRoot>/<name without .e2e>}) and the
     *         files in it; the caller imports them and removes the directory
     */
    public Conversion convert(String storedName, String label, List<String> seriesKeys) {
        requireConfigured();
        requireStoredName(storedName);
        if (seriesKeys == null || seriesKeys.isEmpty()) {
            throw new SidecarException(502, "No series to convert.");
        }
        String endpoint = baseUrl().replaceAll("/+$", "") + "/e2e/convert";
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storedName", storedName);
        if (!blank(label)) body.put("label", label);
        body.put("seriesKeys", seriesKeys);
        Path dir = convertRoot().resolve(storedName.substring(0, storedName.length() - ".e2e".length()));
        try {
            ResponseEntity<String> resp = rest(convertTimeout()).exchange(
                    URI.create(endpoint), HttpMethod.POST,
                    new HttpEntity<>(Json.mapper().writeValueAsString(body), headers), String.class);
            String text = resp.getBody();
            if (text == null || text.isBlank()) {
                throw new SidecarException(502, "The image converter returned an empty answer for the conversion.");
            }
            tools.jackson.databind.JsonNode root = Json.mapper().readTree(text);
            List<ConvertedFile> files = new java.util.ArrayList<>();
            for (tools.jackson.databind.JsonNode n : root.path("files")) {
                files.add(Json.mapper().treeToValue(n, ConvertedFile.class));
            }
            if (files.isEmpty()) {
                throw new SidecarException(502, "The image converter's conversion answer listed no files.");
            }
            return new Conversion(dir, List.copyOf(files));
        } catch (SidecarException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            // Status only: the body is remote content and may quote the file.
            LOG.warn("E2E convert refused with HTTP {}", e.getStatusCode().value());
            throw new SidecarException(502, "The image converter could not convert the E2E file (HTTP "
                    + e.getStatusCode().value() + ").");
        } catch (ResourceAccessException e) {
            LOG.warn("E2E convert: converter unreachable or timed out: {}", e.getClass().getSimpleName());
            throw new SidecarException(503,
                    "The image converter is not reachable or took too long to convert the E2E file.");
        } catch (RuntimeException e) {
            LOG.warn("E2E convert: unreadable answer: {}", e.getClass().getSimpleName());
            throw new SidecarException(502, "The image converter's conversion answer could not be read.");
        }
    }

    /* ---- internals ---- */

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new SidecarException(503,
                    "The image converter is not configured (core.retinalInference.preprocessUrl / preprocessToken).");
        }
    }

    private static void requireStoredName(String storedName) {
        if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
            throw new SidecarException(502, "The stored E2E file name is not valid.");
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-MUW-Inference-Token", token());
        return h;
    }

    private static RestTemplate rest(Duration readTimeout) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        rf.setReadTimeout((int) Math.min(readTimeout.toMillis(), Integer.MAX_VALUE));
        return Json.restTemplate(rf);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /* ---- config (overridable for tests) ---- */

    protected String baseUrl() {
        return read("core.retinalInference.preprocessUrl", "");
    }

    protected String token() {
        String t = read("core.retinalInference.preprocessToken", "");
        return blank(t) ? read("core.retinalInference.remotePushToken", "") : t;
    }

    protected Duration describeTimeout() {
        return secs("core.retinalInference.e2eDescribeTimeoutSecs", DEFAULT_DESCRIBE_TIMEOUT);
    }

    protected Duration convertTimeout() {
        return secs("core.retinalInference.e2eConvertTimeoutSecs", DEFAULT_CONVERT_TIMEOUT);
    }

    /** {@code core.retinalInference.e2eConvertPath}: where the sidecar writes the converted series. */
    protected Path convertRoot() {
        return Path.of(read("core.retinalInference.e2eConvertPath", DEFAULT_CONVERT_PATH));
    }

    private static Duration secs(String key, Duration fallback) {
        String raw = read(key, null);
        if (blank(raw)) return fallback;
        try {
            long s = Long.parseLong(raw.trim());
            return s > 0 ? Duration.ofSeconds(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String read(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable in some test contexts.
        }
        return fallback;
    }
}
