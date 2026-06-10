/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * Phase E.7 — synchronous client for the retinal-inference sidecar's
 * {@code POST /screen} endpoint.
 *
 * <p>Convention mirrors {@link at.ac.meduniwien.ophthalmology.libreclinica.web.pform.EnketoAPI}:
 * a thin {@link RestTemplate} wrapper with a hand-rolled JSON body and
 * an SLF4J logger. The connect + read timeout is fixed at 8 seconds via
 * {@link SimpleClientHttpRequestFactory}; on timeout the caller
 * ({@code RetinalInferenceApiController}) leaves the
 * {@code retinal_inference_job} row at {@code status='queued'} and the
 * background worker picks it up.
 *
 * <p>Base URL is read from the {@code core.retinalInference.baseUrl}
 * property at every call so a compose-time env override takes effect
 * without an app restart. The default
 * {@code http://retinal-inference:8000} resolves via the compose
 * project network.
 */
@Component
public class RetinalInferenceClient {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalInferenceClient.class);

    /** 8-second connect + read budget for the sync screen. */
    public static final Duration FAST_SCREEN_TIMEOUT = Duration.ofSeconds(8);

    /** Default base URL when {@code core.retinalInference.baseUrl} is unset. */
    public static final String DEFAULT_BASE_URL = "http://retinal-inference:8000";

    /**
     * Result of a successful {@code /screen} call. Field names match the
     * sidecar's {@code ScreenResponse} Pydantic model (snake_case on the
     * wire, mapped to camelCase here).
     *
     * @param approxAreaMm2     deterministic placeholder estimate in mm²
     * @param confidence        0.0–1.0 confidence in the screen-pass result
     * @param modelVersion      per-(encoder, task, decoder) pin,
     *                          e.g. {@code "placeholder-v1"}
     * @param foveaBscanIndex   the B-scan index the model picked as
     *                          fovea-centred; used by the SPA's preview
     *                          before the full-volume mask arrives
     */
    public record ScreenResult(double approxAreaMm2,
                               double confidence,
                               String modelVersion,
                               int foveaBscanIndex) { }

    /**
     * Invoke the sidecar's {@code POST /screen} synchronously.
     *
     * <p>Returns {@code null} on any failure (connect refused, timeout,
     * non-2xx, malformed body) — the caller decides whether to surface
     * this as 202 (queue for async retry) or 5xx (hard fail).
     */
    public ScreenResult screenFast(long jobId, String task, String e2ePath, String laterality) {
        String url = baseUrl() + "/screen";
        try {
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout((int) FAST_SCREEN_TIMEOUT.toMillis());
            rf.setReadTimeout((int) FAST_SCREEN_TIMEOUT.toMillis());
            RestTemplate rest = new RestTemplate(rf);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("job_id", jobId);
            body.put("task", task);
            body.put("e2e_path", e2ePath);
            body.put("laterality", laterality);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = rest.postForEntity(url, request, Map.class);
            if (response == null || response.getBody() == null) {
                LOG.warn("Sidecar /screen returned empty body for job {} (task={})", jobId, task);
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) response.getBody();

            double approx = toDouble(b.get("approx_area_mm2"));
            double confidence = toDouble(b.get("confidence"));
            String modelVersion = toString(b.get("model_version"));
            int foveaIdx = toInt(b.get("foveal_bscan_index"));

            return new ScreenResult(approx, confidence, modelVersion, foveaIdx);
        } catch (Exception e) {
            LOG.warn("Sidecar /screen failed for job {} (task={}) at {}: {}",
                    jobId, task, url, e.getMessage());
            return null;
        }
    }

    private static String baseUrl() {
        try {
            String raw = CoreResources.getField("core.retinalInference.baseUrl");
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable in some test contexts -- fall back.
        }
        return DEFAULT_BASE_URL;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String toString(Object v) {
        return v == null ? "" : v.toString();
    }
}
