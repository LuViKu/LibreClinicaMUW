/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.util.List;
import java.util.Map;

/**
 * DR-022 — decoded ``RunEnvelope`` from the GPU sidecar's ``POST /run``.
 *
 * <p>Each {@link Artifact} carries the raw bytes the sidecar returned
 * (already base64-decoded) so the controller / storage service can write
 * them out without doing any encoding work itself.
 *
 * <p>Field names are camelCase here even though the wire shape is
 * snake_case; the {@link RemoteRetinalInferenceClient} translates at
 * parse time.
 *
 * <p>{@code geometry} + {@code e2eUuid} come from the app-VM /preprocess
 * sidecar's response headers (DR-022, Wave 1A). They are nullable when the
 * preprocess step was skipped or the sidecar didn't stamp the headers (eg
 * an older deploy); callers must null-check before reading.
 *
 * <p>2026-06-26 user-feedback round — {@code acquisitionDate} is the ISO
 * {@code YYYY-MM-DD} stamp pulled from the .e2e header by the preprocess
 * sidecar (same source as the {@link PreprocessResult#acquisitionDate}).
 * Threaded through here so the authenticated upload controller can
 * persist it into {@code retinal_inference_job.acquisition_date}
 * symmetrically with the public OCT-portal flow. Null when the preprocess
 * step was skipped, the .e2e device left the field blank, or the deploy
 * is older than the header.
 */
public record RemoteRunResult(String modelVersion,
                              double primaryMetricValue,
                              String primaryMetricUnit,
                              Map<String, Object> outputPayload,
                              double confidence,
                              List<Artifact> artifacts,
                              String task,
                              String laterality,
                              PixelGeometry geometry,
                              String e2eUuid,
                              String acquisitionDate) {

    /** Back-compat ctor for existing callers that don't carry geometry yet. */
    public RemoteRunResult(String modelVersion,
                           double primaryMetricValue,
                           String primaryMetricUnit,
                           Map<String, Object> outputPayload,
                           double confidence,
                           List<Artifact> artifacts,
                           String task,
                           String laterality) {
        this(modelVersion, primaryMetricValue, primaryMetricUnit, outputPayload,
                confidence, artifacts, task, laterality, null, null, null);
    }

    /**
     * Pre-2026-06-26 ctor that carries geometry + e2eUuid but not
     * acquisitionDate. Kept so {@link RemoteRetinalInferenceClient}'s
     * geometry-only callsite (no preprocess) doesn't need to thread an
     * extra null literal.
     */
    public RemoteRunResult(String modelVersion,
                           double primaryMetricValue,
                           String primaryMetricUnit,
                           Map<String, Object> outputPayload,
                           double confidence,
                           List<Artifact> artifacts,
                           String task,
                           String laterality,
                           PixelGeometry geometry,
                           String e2eUuid) {
        this(modelVersion, primaryMetricValue, primaryMetricUnit, outputPayload,
                confidence, artifacts, task, laterality, geometry, e2eUuid, null);
    }

    /**
     * A single runner-produced output (CSV / NPY / PNG) returned inline.
     *
     * @param name         basename inside the sidecar tempdir; receiver
     *                     persists at {@code <artifact-store>/<job-uuid>/<name>}.
     * @param mediaType    RFC 6838 media type from the wire.
     * @param content      decoded bytes ready to write out.
     */
    public record Artifact(String name, String mediaType, byte[] content) { }
}
