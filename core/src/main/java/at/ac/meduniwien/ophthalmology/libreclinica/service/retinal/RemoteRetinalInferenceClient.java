/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * DR-022 — long-poll client for the remote GPU sidecar's
 * {@code POST /run} endpoint.
 *
 * <p>When {@code core.retinalInference.remotePushUrl} is set the
 * institutional Tomcat reaches the sidecar over HTTP and blocks until it
 * returns the {@link RemoteRunResult} envelope. Same-network deployment
 * (the GPU host shares the institutional LAN), so a plain sync POST
 * with a long timeout is the right shape — no streaming, no callback.
 *
 * <p>If {@code core.retinalInference.preprocessUrl} is also set, the
 * {@code .e2e} is first converted to a PHI-redacted {@code bscan.dcm} by an
 * app-VM-side preprocess sidecar and only the DICOM is forwarded to the
 * (DICOM-only) cluster {@code /run} — the raw E2E never leaves the app VM
 * (DR-022). When it is blank, the {@code .e2e} is posted as-is.
 *
 * <p>The {@link RetinalInferenceClient#screenFast} client stays for the
 * SPA's fast-preview path and the single-host dev compose flow. This
 * client is opt-in: a blank {@code remotePushUrl} disables the remote
 * branch entirely and the existing local sidecar / DB-poll path runs
 * untouched.
 */
@Component
public class RemoteRetinalInferenceClient {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteRetinalInferenceClient.class);

    /** Default read+connect timeout when the property is unset. 60 minutes
     *  is generous — production GPU finishes a single scan in 5–30s but the
     *  Mac dev environment (amd64 emulation) takes 25+ minutes. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(60);

    /** True when the remote-push URL property is set. Callers branch on this
     *  before falling through to {@link RetinalInferenceClient#screenFast}. */
    public boolean isConfigured() {
        String url = remoteUrl();
        return url != null && !url.isBlank();
    }

    /**
     * POST the persisted E2E to {@code ${remotePushUrl}/run} and return the
     * decoded envelope. Returns {@code null} on any failure (connect refused,
     * timeout, non-2xx, malformed body); the caller is expected to revert the
     * job to {@code status='queued'} so the existing DB-poll fallback drains
     * it (or surface the failure to the operator).
     */
    public RemoteRunResult runRemote(long jobId,
                                     String task,
                                     String e2ePath,
                                     String laterality) {
        String url = remoteUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        String token = remoteToken();
        if (token == null || token.isBlank()) {
            LOG.warn("Remote /run requested but core.retinalInference.remotePushToken is unset");
            return null;
        }
        long timeoutMs = remoteTimeout().toMillis();

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(e2ePath));
        } catch (IOException e) {
            LOG.warn("Failed to read E2E at {} for job {}: {}", e2ePath, jobId, e.getMessage());
            return null;
        }

        String fileName = Path.of(e2ePath).getFileName().toString();

        RestTemplate rest = restTemplate(timeoutMs);

        // DR-022: convert .e2e -> bscan.dcm app-side when a preprocess service is
        // configured. The cluster ApptainerAdapter is DICOM-only and the
        // PHI-bearing .e2e must not leave the app VM, so a preprocess-only sidecar
        // co-located with Tomcat does the (PHI-redacting) conversion and we forward
        // only the bscan.dcm. When unset, post the .e2e as-is (the single-host dev
        // OptimaAdapter ingests the E2E itself).
        String prepUrl = preprocessUrl();
        if (prepUrl != null && !prepUrl.isBlank()) {
            byte[] dcm = preprocessToDicom(rest, prepUrl, jobId, fileName, bytes, laterality);
            if (dcm == null) {
                // Conversion failed — return null so the caller reverts the job and
                // the local fallback path drains it, rather than POSTing a raw .e2e
                // the DICOM-only cluster adapter would reject.
                return null;
            }
            bytes = dcm;
            fileName = "bscan.dcm";
        }

        final String partFileName = fileName;
        ByteArrayResource filePart = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return partFileName; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("task", task);
        body.add("laterality", laterality);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-MUW-Inference-Token", token);
        headers.set("Idempotency-Key", jobId + "-" + fileName);

        String endpoint = url.replaceAll("/+$", "") + "/run";

        try {
            HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, headers);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> resp = rest.postForEntity(endpoint, req, Map.class);
            if (resp == null || resp.getBody() == null) {
                LOG.warn("Remote /run returned empty body for job {} (task={})", jobId, task);
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) resp.getBody();
            return parseEnvelope(b);
        } catch (Exception e) {
            LOG.warn("Remote /run failed for job {} (task={}) at {}: {}",
                    jobId, task, endpoint, e.getMessage());
            return null;
        }
    }

    private static RestTemplate restTemplate(long timeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        rf.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        return new RestTemplate(rf);
    }

    /**
     * POST the {@code .e2e} to {@code ${preprocessUrl}/preprocess} and return the
     * PHI-redacted {@code bscan.dcm} bytes, or {@code null} on any failure (so the
     * caller reverts + falls back rather than shipping a raw E2E to the
     * DICOM-only cluster adapter).
     */
    private byte[] preprocessToDicom(RestTemplate rest,
                                     String prepUrl,
                                     long jobId,
                                     String e2eName,
                                     byte[] e2eBytes,
                                     String laterality) {
        String token = preprocessToken();
        if (token == null || token.isBlank()) {
            LOG.warn("Preprocess URL set but no token "
                    + "(core.retinalInference.preprocessToken / remotePushToken) for job {}", jobId);
            return null;
        }

        ByteArrayResource filePart = new ByteArrayResource(e2eBytes) {
            @Override public String getFilename() { return e2eName; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        if (laterality != null) {
            body.add("laterality", laterality);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-MUW-Inference-Token", token);

        String endpoint = prepUrl.replaceAll("/+$", "") + "/preprocess";
        try {
            HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, headers);
            ResponseEntity<byte[]> resp = rest.postForEntity(endpoint, req, byte[].class);
            if (resp == null || resp.getBody() == null || resp.getBody().length == 0) {
                LOG.warn("Preprocess returned empty body for job {} at {}", jobId, endpoint);
                return null;
            }
            return resp.getBody();
        } catch (Exception e) {
            LOG.warn("Preprocess /preprocess failed for job {} at {}: {}",
                    jobId, endpoint, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static RemoteRunResult parseEnvelope(Map<String, Object> b) {
        String modelVersion = stringOr(b.get("model_version"), "");
        double primary = doubleOr(b.get("primary_metric_value"), 0.0);
        String primaryUnit = stringOr(b.get("primary_metric_unit"), "");
        Map<String, Object> payload = b.get("output_payload") instanceof Map
                ? (Map<String, Object>) b.get("output_payload")
                : new HashMap<>();
        double confidence = doubleOr(b.get("confidence"), 0.0);
        String task = stringOr(b.get("task"), "");
        String laterality = stringOr(b.get("laterality"), "");

        List<RemoteRunResult.Artifact> artifacts = new ArrayList<>();
        Object rawArtifacts = b.get("artifacts");
        if (rawArtifacts instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String name = stringOr(m.get("name"), null);
                String mediaType = stringOr(m.get("media_type"), null);
                String content64 = stringOr(m.get("content_base64"), null);
                if (name == null || content64 == null) continue;
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(content64);
                } catch (IllegalArgumentException e) {
                    LOG.warn("Skipping artifact '{}' — invalid base64: {}", name, e.getMessage());
                    continue;
                }
                artifacts.add(new RemoteRunResult.Artifact(name, mediaType, content));
            }
        }

        return new RemoteRunResult(
                modelVersion,
                primary,
                primaryUnit,
                payload,
                confidence,
                artifacts,
                task,
                laterality
        );
    }

    // --- config readers (overridable for tests) ------------------------------

    /** Remote sidecar base URL. Tests override; production reads from
     *  {@code datainfo.properties}. */
    protected String remoteUrl() {
        return readField("core.retinalInference.remotePushUrl", "");
    }

    /** Shared secret for the {@code X-MUW-Inference-Token} header. */
    protected String remoteToken() {
        return readField("core.retinalInference.remotePushToken", "");
    }

    /** Base URL of the app-VM-side preprocess sidecar (DR-022). Blank disables
     *  app-side conversion — the raw {@code .e2e} is posted to {@code /run}. */
    protected String preprocessUrl() {
        return readField("core.retinalInference.preprocessUrl", "");
    }

    /** Token for the preprocess sidecar; falls back to {@link #remoteToken()}
     *  when {@code core.retinalInference.preprocessToken} is unset. */
    protected String preprocessToken() {
        String t = readField("core.retinalInference.preprocessToken", "");
        if (t == null || t.isBlank()) {
            return remoteToken();
        }
        return t;
    }

    /** Read + connect timeout for the remote POST. */
    protected Duration remoteTimeout() {
        String raw = readField("core.retinalInference.remotePushTimeoutSecs", null);
        if (raw == null || raw.isBlank()) return DEFAULT_TIMEOUT;
        try {
            long secs = Long.parseLong(raw.trim());
            if (secs <= 0) return DEFAULT_TIMEOUT;
            return Duration.ofSeconds(secs);
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT;
        }
    }

    private static String readField(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable in some test contexts.
        }
        return fallback;
    }

    // --- type coercion -------------------------------------------------------

    private static double doubleOr(Object v, double fallback) {
        if (v == null) return fallback;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String stringOr(Object v, String fallback) {
        if (v == null) return fallback;
        return v.toString();
    }
}