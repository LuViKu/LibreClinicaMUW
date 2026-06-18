# Model runners

Each OPTIMA/RetInsight model runs in its **own container** because their
runtimes are mutually incompatible (RetInsight py3.7 wheels, ONL torch, PR
torch‑1.0, GA Singularity). The sidecar's `OptimaAdapter` stays thin and
dispatches each task to the runner whose URL is configured
(`RETINAL_INFERENCE_RUNNER_<TASK>_URL`, e.g. `..._FLUID_URL` / `..._ONL_URL` /
`..._PR_URL` / `..._GA_URL`).

## Runner HTTP contract

Each runner is a small FastAPI service exposing:

### `GET /health`
```json
{ "status": "ok", "task": "fluid", "model_version": "retinsight-fluid-1.3.0" }
```

### `POST /infer`
Request (sent by the sidecar's `OptimaAdapter`):
```json
{
  "task": "fluid",
  "bscan_dcm_path": "/data/segmentation-output/<job>-fluid/bscan.dcm",
  "laterality": "OD",
  "output_dir": "/data/segmentation-output/<job>-fluid"
}
```
Response (mapped onto `FullVolumeResult` → `retinal_inference_result`):
```json
{
  "primary_metric_value": null,
  "primary_metric_unit": null,
  "output_payload": { "...artifact basenames / file refs..." : "..." },
  "en_face_mask_path": "/data/.../en_face.png",
  "bscan_masks_dir": "/data/.../masks",
  "pixel_scale_mm": 0.00387,
  "model_version": "retinsight-fluid-1.3.0",
  "confidence": 0.9
}
```

The server returns **raw segmentation artifacts only** — the Java backend
computes every clinical metric (fluid mm³, ONL/PR µm, GA mm²). So
`primary_metric_value` / `primary_metric_unit` are `null`; the `output_payload`
carries the produced file references (CSV / NPZ basenames) and the artifacts ride
back inline via the `/run` envelope.

Conventions:
- Input `bscan.dcm` is produced by the sidecar's shared E2E ingestion
  (`prepare_bscan_dcm`) — a multi-frame Heidelberg-flavoured DICOM with
  `PixelSpacing` (axial, lateral) + `SpacingBetweenSlices`.
- The runner reads/writes only under `output_dir` on the shared
  `segmentation-output` volume.
- **Weights are never baked into git.** Provide them at build time (copy into
  the image) or mount at runtime; see each runner's README.
- Runners are **CPU-capable** where the model allows (fluid/onl/pr). GA is
  GPU-gated.
