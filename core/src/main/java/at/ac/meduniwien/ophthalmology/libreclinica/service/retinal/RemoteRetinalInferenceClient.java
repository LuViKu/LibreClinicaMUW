/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json;

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
@SuppressWarnings("null")
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
        // DR-039 — the content decides, as on the sidecar: a DICOM Part-10
        // file is a DICOM OCT volume, anything else is handled as an .e2e.
        boolean dicom = isDicomPart10(bytes);

        RestTemplate rest = restTemplate(timeoutMs);

        // DR-022: convert .e2e -> bscan.dcm app-side when a preprocess service is
        // configured. The cluster ApptainerAdapter is DICOM-only and the
        // PHI-bearing .e2e must not leave the app VM, so a preprocess-only sidecar
        // co-located with Tomcat does the (PHI-redacting) conversion and we forward
        // only the bscan.dcm. When unset, post the .e2e as-is (the single-host dev
        // OptimaAdapter ingests the E2E itself).
        PreprocessResult prep = null;
        String runLaterality = laterality;
        int runScanIndex = scanIndex;
        String prepUrl = preprocessUrl();
        if (prepUrl != null && !prepUrl.isBlank()) {
            // DR-039 — the companion-directory key (RetinalArtifactKey): the
            // basename without .e2e for an .e2e, a path-derived UUID otherwise.
            String derivedUuid = RetinalArtifactKey.of(e2ePath);
            if (dicom) {
                // A DICOM is one volume, and the sidecar reads its eye from the
                // file. The item's eye goes along only when it names one; with
                // OU or none, and nothing in the file, the sidecar refuses with
                // laterality_missing rather than anyone guessing. No disk reuse:
                // the device headers decide below whether the task may run, and
                // only a /preprocess answer carries them.
                runScanIndex = 0;
                prep = preprocessToDicom(rest, prepUrl, jobId, fileName, bytes,
                        singleEye(laterality), derivedUuid, 0);
            } else {
                // 2026-06-19 — preprocess-dedup. The public OCT-portal commit
                // path eagerly calls /preprocess in its async pipeline, so the
                // bscan.dcm and geometry.json are usually on disk by now; a
                // second /preprocess of a 200 MB .e2e costs 30+ s and was seen
                // to die under load. Reuse them when present and intact.
                prep = tryReuseDiskDicom(derivedUuid, jobId, scanIndex);
                if (prep == null) {
                    prep = preprocessToDicom(rest, prepUrl, jobId, fileName, bytes, laterality,
                            derivedUuid, scanIndex);
                }
            }
            if (prep == null || prep.dcmBytes() == null) {
                // Conversion failed — return null so the caller reverts the job and
                // the local fallback path drains it, rather than POSTing a raw .e2e
                // the DICOM-only cluster adapter would reject.
                return null;
            }
            if (dicom) {
                // DR-039 — vendor gating. Nothing goes to the cluster for a task
                // whose model was not trained on this device; the sidecar says
                // which tasks are, and a sidecar that does not say is a no.
                ScanSource src = prep.source();
                if (src == null || src.deviceTasks() == null) {
                    throw new RetinalRunRefused("device_unknown",
                            "The preprocess service did not report this scan's device, so it cannot "
                                    + "be checked against the devices " + task + " is validated for. "
                                    + "The scan was not sent for analysis.", src);
                }
                if (!src.validatedFor(task)) {
                    LOG.info("Job {}: task {} is not validated for the device of this DICOM volume; "
                            + "not sent to the cluster", jobId, task);
                    throw new RetinalRunRefused("unsupported_device", src.unsupportedDeviceMessage(task), src);
                }
                if (src.laterality() != null) runLaterality = src.laterality();
            }
            bytes = prep.dcmBytes();
            fileName = "bscan.dcm";
        } else if (dicom) {
            // DR-039 — nothing identifying leaves the VM. Without the preprocess
            // sidecar a DICOM would go to /run as stored, identity and all.
            throw new RetinalRunRefused("preprocess_unavailable",
                    "DICOM OCT volumes are analysed only through the preprocess service, "
                            + "which is not configured on this server (core.retinalInference.preprocessUrl).",
                    null);
        }

        final String partFileName = fileName;
        ByteArrayResource filePart = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return partFileName; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("task", task);
        body.add("laterality", runLaterality);
        body.add("scan_index", Integer.toString(runScanIndex));

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
                // Thread the preprocess-derived geometry + e2e UUID + the .e2e
                // acquisition_date through; the /run envelope itself doesn't
                // carry pixel geometry (the runners read it off the DCM tags
                // directly), and acquisition_date lives only on the preprocess
                // response header (the GPU /run is task-only, not header-aware).
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
                        prep.e2eUuid(),
                        prep.acquisitionDate(),
                        prep.source()
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
            // DR-039 — a 422 with {"detail": {"error", "message"}} is a refusal
            // the operator can act on (unsupported_device, invalid_dicom, …):
            // it becomes the job's message instead of "returned null".
            RetinalRunRefused refused = refusalOf(httpErr.getStatusCode().value(), bodyText,
                    prep == null ? null : prep.source());
            if (refused != null) {
                LOG.warn("Remote /run refused job {} (task={}): {}", jobId, task, refused.code());
                throw refused;
            }
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

    /** DICOM Part-10: a 128-byte preamble, then "DICM". */
    static boolean isDicomPart10(byte[] bytes) {
        return bytes != null && bytes.length >= 132
                && bytes[128] == 'D' && bytes[129] == 'I' && bytes[130] == 'C' && bytes[131] == 'M';
    }

    /** OD or OS, upper-cased; null for OU, blank or anything else. */
    static String singleEye(String laterality) {
        if (laterality == null) return null;
        String l = laterality.trim().toUpperCase(java.util.Locale.ROOT);
        return "OD".equals(l) || "OS".equals(l) ? l : null;
    }

    private static final tools.jackson.databind.ObjectMapper JSON = Json.mapper();

    /**
     * A refusal out of a sidecar error body, or null when the body is not the
     * object form {@code {"detail": {"error": code, "message": text}}} of a
     * 422. Other errors stay outages (null from {@link #runRemote}).
     */
    static RetinalRunRefused refusalOf(int status, String body, ScanSource source) {
        if (status != 422 || body == null || body.isBlank()) return null;
        try {
            tools.jackson.databind.JsonNode detail = JSON.readTree(body).path("detail");
            if (!detail.isObject()) return null;
            String message = detail.path("message").asString("").trim();
            if (message.isEmpty()) return null;
            if (message.length() > 1000) message = message.substring(0, 1000) + "…";
            String code = detail.path("error").asString("refused");
            return new RetinalRunRefused(code, message, source);
        } catch (Exception notJson) {
            return null;
        }
    }

    private static RestTemplate restTemplate(long timeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        rf.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        return Json.restTemplate(rf);
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
        boolean dicom = isDicomPart10(e2eBytes);
        try {
            return preprocessToDicom(rest, prepUrl, jobId, e2eName, e2eBytes,
                    dicom ? singleEye(laterality) : laterality, e2eUuid, dicom ? 0 : scanIndex);
        } catch (RetinalRunRefused refused) {
            // Best-effort warm-up: the job's own dispatch preprocesses again
            // and records the refusal on the job.
            LOG.info("Eager preprocess refused for job {}: {}", jobId, refused.code());
            return null;
        }
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
            @SuppressWarnings("rawtypes")
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
     * 2026-06-19 — disk-side dedup probe. The async commit pipeline has
     * typically already written {@code bscan.dcm} and {@code geometry.json}
     * for this scan before {@link #runRemote} fires. When both are there,
     * read them instead of re-POSTing to the sidecar. Returns {@code null}
     * when they are not on disk or cannot be read, which falls back to the
     * HTTP path.
     *
     * <p>DR-039 — two defects fixed here. The geometry used to be dropped
     * (null), so every metric of a reused scan fell back to pixel units
     * although {@code geometry.json} sat next to the file; it is now read
     * from there, and a reuse without a readable geometry is no reuse. And
     * the probe looked in {@code scan-(i+1)/}, then {@code scan-1/}, while the
     * sidecar writes index 0 to the key's directory and index {@code i > 0}
     * to {@code scan-<i>/} ({@link RetinalArtifactKey#scanDir}): scan 0 of a
     * two-volume {@code .e2e} was analysed on scan 1's volume. Only the exact
     * directory is probed now; a miss costs a preprocess call, a wrong hit
     * costs a wrong result.
     */
    PreprocessResult tryReuseDiskDicom(String artifactKey, long jobId, int scanIndex) {
        String base = bscanStorePath();
        if (base == null || base.isBlank() || artifactKey == null || artifactKey.isBlank()) return null;
        Path dir = RetinalArtifactKey.scanDir(Path.of(base), artifactKey, scanIndex);
        Path dcmPath = dir.resolve("bscan.dcm");
        Path geomPath = dir.resolve("geometry.json");
        if (!Files.isRegularFile(dcmPath) || !Files.isRegularFile(geomPath)) return null;
        byte[] dcmBytes;
        PixelGeometry geometry;
        try {
            dcmBytes = Files.readAllBytes(dcmPath);
            geometry = PixelGeometry.fromGeometryJson(geomPath);
        } catch (IOException | RuntimeException ioEx) {
            LOG.warn("Found {} but could not read it for job {}: {}", dir, jobId, ioEx.getMessage());
            return null;
        }
        if (geometry == null) {
            LOG.warn("Stored geometry.json in {} has no usable bscan block; preprocessing job {} again",
                    dir, jobId);
            return null;
        }
        LOG.info("Reusing on-disk preprocess DICOM for job {} from {} ({} bytes)",
                jobId, dcmPath, dcmBytes.length);
        // No fresh /preprocess call, so no acquisition-date header: the first
        // preprocess already persisted it on the job row.
        return new PreprocessResult(dcmBytes, geometry, artifactKey, null);
    }

    /**
     * POST the {@code .e2e} to {@code ${preprocessUrl}/preprocess} and return the
     * PHI-redacted {@code bscan.dcm} bytes + parsed pixel geometry, or {@code null}
     * on any failure (so the caller reverts + falls back rather than shipping a
     * raw E2E to the DICOM-only cluster adapter).
     *
     * <p>{@code scanIndex} picks which volume from a multi-acquisition .e2e to
     * convert; forwarded to /preprocess as a {@code scan_index} form field.
     *
     * @throws RetinalRunRefused on a 422 with {@code detail.message} (DR-039)
     */
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
                    acquisitionDate != null && !acquisitionDate.isBlank() ? acquisitionDate : null,
                    ScanSource.from(resp.getHeaders()));
        } catch (org.springframework.web.client.HttpStatusCodeException httpErr) {
            // DR-039 — the sidecar refused the file (not_oct_volume,
            // laterality_missing, spacing_ambiguous, …); its message is what
            // the operator needs, and it never quotes values from the file.
            RetinalRunRefused refused = refusalOf(httpErr.getStatusCode().value(),
                    httpErr.getResponseBodyAsString(), null);
            if (refused != null) {
                LOG.warn("Preprocess refused the scan of job {}: {}", jobId, refused.code());
                throw refused;
            }
            LOG.warn("Preprocess /preprocess failed for job {} at {}: {}",
                    jobId, endpoint, httpErr.getMessage());
            return null;
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
                                   String e2eUuid, String acquisitionDate, ScanSource source) {

        /** Without {@code source}: the disk-reuse path and older sidecars. */
        public PreprocessResult(byte[] dcmBytes, PixelGeometry geometry,
                                String e2eUuid, String acquisitionDate) {
            this(dcmBytes, geometry, e2eUuid, acquisitionDate, null);
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

    /** Where the preprocess sidecar keeps its companions ({@code RETINAL_INFERENCE_BSCAN_STORE}). */
    protected String bscanStorePath() {
        return readField("core.retinalInference.bscanStorePath", "");
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