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
     * Back-compat overload — defaults {@code scanIndex} to 0. Callers should
     * prefer {@link #runRemote(long, String, String, String, int)} so the
     * portal's multi-acquisition .e2e routing reaches the sidecar.
     */
    public RemoteRunResult runRemote(long jobId,
                                     String task,
                                     String e2ePath,
                                     String laterality) {
        return runRemote(jobId, task, e2ePath, laterality, 0);
    }

    /**
     * POST the persisted E2E to {@code ${remotePushUrl}/run} and return the
     * decoded envelope. Returns {@code null} on any failure (connect refused,
     * timeout, non-2xx, malformed body); the caller is expected to revert the
     * job to {@code status='queued'} so the existing DB-poll fallback drains
     * it (or surface the failure to the operator).
     *
     * <p>{@code scanIndex} selects which volume from a multi-acquisition .e2e
     * the sidecar should ingest (default 0). Forwarded to both /preprocess and
     * /run as a {@code scan_index} multipart form field.
     */
    public RemoteRunResult runRemote(long jobId,
                                     String task,
                                     String e2ePath,
                                     String laterality,
                                     int scanIndex) {
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
        PreprocessResult prep = null;
        String prepUrl = preprocessUrl();
        if (prepUrl != null && !prepUrl.isBlank()) {
            // Derive the e2e UUID from the file basename (strip the .e2e suffix);
            // the uploads service names files {uuid}.e2e so this stays stable
            // across re-uploads of the same scan.
            String derivedUuid = stripE2eSuffix(fileName);
            // 2026-06-19 — preprocess-dedup. The public OCT-portal commit
            // path now eagerly calls /preprocess in its async pipeline
            // (PublicOctUploadController.runPostCommitPipeline), so by
            // the time {@link RetinalInferenceApiController#handleRemote}
            // invokes runRemote the {@code bscan.dcm} (and companion
            // {@code geometry.json}) are already on disk under
            // {@code bscanStorePath/<e2eUuid>/scan-{1..N}/}. A second
            // {@code /preprocess} HTTP call against the same sidecar
            // wastes 30+ s of CPU, doubles RAM pressure on the
            // 10 GiB-limited container, and the 2026-06-19 smoke
            // observed those repeat calls reliably dying with
            // "Unexpected end of file from server" (sidecar closes the
            // connection mid-response under sustained load). Try the
            // disk artifacts first — if present + intact, skip the
            // HTTP call entirely.
            prep = tryReuseDiskDicom(derivedUuid, jobId, scanIndex);
            if (prep == null) {
                prep = preprocessToDicom(rest, prepUrl, jobId, fileName, bytes, laterality,
                        derivedUuid, scanIndex);
            }
            if (prep == null || prep.dcmBytes() == null) {
                // Conversion failed — return null so the caller reverts the job and
                // the local fallback path drains it, rather than POSTing a raw .e2e
                // the DICOM-only cluster adapter would reject.
                return null;
            }
            bytes = prep.dcmBytes();
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
        body.add("scan_index", Integer.toString(scanIndex));

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
            RemoteRunResult parsed = parseEnvelope(b);
            if (prep != null) {
                // Thread the preprocess-derived geometry + e2e UUID through; the
                // /run envelope itself doesn't carry pixel geometry (the runners
                // read it off the DCM tags directly).
                parsed = new RemoteRunResult(
                        parsed.modelVersion(),
                        parsed.primaryMetricValue(),
                        parsed.primaryMetricUnit(),
                        parsed.outputPayload(),
                        parsed.confidence(),
                        parsed.artifacts(),
                        parsed.task(),
                        parsed.laterality(),
                        prep.geometry(),
                        prep.e2eUuid()
                );
            }
            return parsed;
        } catch (org.springframework.web.client.HttpStatusCodeException httpErr) {
            // 2026-06-24 — capture the cluster's response body alongside
            // the status. Spring's HttpStatusCodeException stops at
            // `e.getMessage()` returning the bare "500 Internal Server
            // Error" line; the real diagnostic text (e.g. a Python
            // stack trace from the sidecar's uvicorn) lives in
            // `getResponseBodyAsString()` and was being silently
            // dropped. Trim to 4 KiB so a runaway HTML 500 page can't
            // fill the log; truncated body is still vastly more useful
            // than the status line alone.
            String bodyText = httpErr.getResponseBodyAsString();
            if (bodyText != null && bodyText.length() > 4096) {
                bodyText = bodyText.substring(0, 4096) + "…[truncated]";
            }
            LOG.warn("Remote /run failed for job {} (task={}) at {}: {} body=<<{}>>",
                    jobId, task, endpoint, httpErr.getMessage(),
                    bodyText == null ? "" : bodyText);
            return null;
        } catch (Exception e) {
            LOG.warn("Remote /run failed for job {} (task={}) at {}: {}",
                    jobId, task, endpoint, e.getMessage());
            return null;
        }
    }

    private static String stripE2eSuffix(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        String n = fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.substring(dot).equalsIgnoreCase(".e2e")) {
            return n.substring(0, dot);
        }
        return n;
    }

    private static RestTemplate restTemplate(long timeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        rf.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        return new RestTemplate(rf);
    }

    /**
     * 2026-06-18 — public-facing wrapper around {@link #preprocessToDicom}.
     * Lets the public OCT-upload portal kick off the preprocess sidecar
     * directly after persisting an {@code .e2e}, so the {@code bscan.dcm}
     * /{@code fundus.png}/{@code geometry.json} companion files land on
     * disk REGARDLESS of whether the inference pipeline runs (or runs
     * successfully). Without this entry, parked uploads + queued uploads
     * leave the operator unable to browse the just-uploaded scan at all.
     *
     * <p>{@code null} on any failure (no preprocess URL configured, no
     * token, sidecar HTTP error) so the caller can log + continue
     * without breaking the upload UX.
     */
    public PreprocessResult preprocessUpload(long jobId,
                                              String e2eName,
                                              byte[] e2eBytes,
                                              String laterality,
                                              String e2eUuid,
                                              int scanIndex) {
        String prepUrl = preprocessUrl();
        if (prepUrl == null || prepUrl.isBlank()) {
            return null;
        }
        RestTemplate rest = restTemplate(remoteTimeout().toMillis());
        return preprocessToDicom(rest, prepUrl, jobId, e2eName, e2eBytes, laterality,
                e2eUuid, scanIndex);
    }

    /**
     * 2026-06-22 — post-inference derivation. The cluster /run only ships
     * the raw segmentation (e.g. fluidseg.npz) — projection PNGs +
     * per-slice overlays are computed app-VM-side from the persisted
     * npz so the cluster image stays minimal + the local artifact
     * store is the single source of truth for what the SPA renders.
     *
     * <p>POSTs {@code {"job_dir": "...", "task": "..."}} to the local
     * sidecar's {@code /derive} endpoint. The sidecar reads the npz
     * back from the bind-mounted artifact dir + writes the derived
     * PNGs alongside. Idempotent server-side.
     *
     * <p>The caller treats failures as soft — the result row still
     * persists with just the raw segmentation, and
     * {@code backfill_projections.py} provides the same derivation
     * on-demand for jobs that landed before this chain was wired.
     */
    public void derive(Path jobDir, String task) {
        String prepUrl = preprocessUrl();
        if (prepUrl == null || prepUrl.isBlank()) {
            LOG.debug("derive skipped — preprocessUrl unset (dev compose without sidecar)");
            return;
        }
        String endpoint = prepUrl.trim().replaceAll("/$", "") + "/derive";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = preprocessToken();
        if (token != null && !token.isBlank()) {
            headers.set("X-Auth-Token", token);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("job_dir", jobDir.toString());
        body.put("task", task);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        RestTemplate rest = restTemplate(remoteTimeout().toMillis());
        try {
            ResponseEntity<Map> resp = rest.postForEntity(endpoint, req, Map.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                Map<?, ?> rb = resp.getBody();
                Object written = rb == null ? null : rb.get("written");
                Object skipped = rb == null ? null : rb.get("skipped");
                LOG.info("Local /derive ok for {} (task={}) — written={}, skipped={}",
                        jobDir, task, written, skipped);
            } else {
                LOG.warn("Local /derive returned non-2xx for {} (task={}): {}",
                        jobDir, task, resp.getStatusCode());
            }
        } catch (Exception e) {
            LOG.warn("Local /derive failed for {} (task={}): {}", jobDir, task, e.getMessage());
        }
    }

    /**
     * POST the {@code .e2e} to {@code ${preprocessUrl}/preprocess} and return the
     * PHI-redacted {@code bscan.dcm} bytes + parsed pixel geometry, or {@code null}
     * on any failure (so the caller reverts + falls back rather than shipping a
     * raw E2E to the DICOM-only cluster adapter).
     *
     * <p>{@code scanIndex} picks which volume from a multi-acquisition .e2e to
     * convert; forwarded to /preprocess as a {@code scan_index} form field.
     */
    /**
     * 2026-06-19 — disk-side dedup probe. The async commit pipeline
     * has typically already populated
     * {@code bscanStorePath/<e2eUuid>/scan-{N}/bscan.dcm} (+
     * {@code geometry.json}) before {@link #runRemote} fires. When
     * present, read those bytes + reconstruct the
     * {@link PreprocessResult} envelope instead of re-POSTing to the
     * sidecar. Returns {@code null} when the artifacts aren't on
     * disk, or when reading them fails — falls back to the normal
     * HTTP path.
     *
     * <p>Tries {@code scan-{scanIndex+1}/} first, then {@code scan-1/}
     * (the sidecar's observed default when scan_index is ignored),
     * then root. Mirrors the resolver-side fallback ladder in
     * {@link RetinalArtifactStorageService}.
     */
    private PreprocessResult tryReuseDiskDicom(String e2eUuid, long jobId, int scanIndex) {
        String base = readField("core.retinalInference.bscanStorePath", "");
        if (base == null || base.isBlank()) return null;
        // Candidate paths in priority order — matches the resolver's
        // fallback ladder. Empty subdir = root layout.
        String[] subdirs = (scanIndex > 0)
                ? new String[] { "scan-" + (scanIndex + 1), "scan-1", "" }
                : new String[] { "scan-1", "" };
        for (String sub : subdirs) {
            java.nio.file.Path dcmPath = sub.isEmpty()
                    ? java.nio.file.Path.of(base, e2eUuid, "bscan.dcm")
                    : java.nio.file.Path.of(base, e2eUuid, sub, "bscan.dcm");
            if (!java.nio.file.Files.exists(dcmPath)) continue;
            byte[] dcmBytes;
            try {
                dcmBytes = java.nio.file.Files.readAllBytes(dcmPath);
            } catch (java.io.IOException ioEx) {
                LOG.warn("Found {} but read failed for job {}: {}", dcmPath, jobId, ioEx.getMessage());
                continue;
            }
            // Geometry stays null — PixelGeometry.from(...) only knows
            // how to parse from HTTP headers (the sidecar's response
            // shape). The runners read the actual pixel scale from
            // the DICOM tags directly, so a null PreprocessResult.geometry()
            // doesn't block the segmentation pipeline; the metric
            // computer also degrades gracefully when geometry is null.
            LOG.info("Reusing on-disk preprocess DICOM for job {} from {} ({} bytes)",
                    jobId, dcmPath, dcmBytes.length);
            // Disk-reuse path — no fresh /preprocess call, so no
            // acquisition_date header to capture. The first preprocess
            // already persisted it on the job row via the live header
            // path; later jobs sharing the same e2eUuid inherit the
            // date via the same update.
            return new PreprocessResult(dcmBytes, null, e2eUuid, null);
        }
        return null;
    }

    private PreprocessResult preprocessToDicom(RestTemplate rest,
                                               String prepUrl,
                                               long jobId,
                                               String e2eName,
                                               byte[] e2eBytes,
                                               String laterality,
                                               String e2eUuid,
                                               int scanIndex) {
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
        if (e2eUuid != null && !e2eUuid.isBlank()) {
            body.add("e2e_uuid", e2eUuid);
        }
        body.add("scan_index", Integer.toString(scanIndex));

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
            PixelGeometry geom = null;
            try {
                geom = PixelGeometry.from(resp.getHeaders());
            } catch (IllegalStateException missingHeaders) {
                // Soft-fail: an older preprocess deploy may not yet stamp the 7
                // headers. The DCM round-trip still works; geometry-dependent
                // features (metric computer, viewer overlays) skip those steps.
                LOG.warn("Preprocess didn't stamp geometry headers for job {} ({}); "
                        + "continuing without geometry", jobId, missingHeaders.getMessage());
            }
            String echoedUuid = resp.getHeaders().getFirst(PixelGeometry.HEADER_E2E_UUID);
            String resolvedUuid = (echoedUuid == null || echoedUuid.isBlank()) ? e2eUuid : echoedUuid;
            String acquisitionDate = resp.getHeaders().getFirst(PixelGeometry.HEADER_ACQUISITION_DATE);
            return new PreprocessResult(resp.getBody(), geom, resolvedUuid,
                    acquisitionDate != null && !acquisitionDate.isBlank() ? acquisitionDate : null);
        } catch (Exception e) {
            LOG.warn("Preprocess /preprocess failed for job {} at {}: {}",
                    jobId, endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * DR-022 carrier — bscan.dcm bytes + geometry parsed off the response headers.
     *
     * <p>{@code acquisitionDate} is the optional ISO {@code YYYY-MM-DD}
     * stamp pulled out of the .e2e header by the preprocess sidecar
     * (2026-06-23 user-feedback round). Null when the device left the
     * field blank or the preprocess deploy is older than this header.
     */
    public record PreprocessResult(byte[] dcmBytes, PixelGeometry geometry,
                                   String e2eUuid, String acquisitionDate) { }

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