"""Pydantic models for the stateless ``POST /run`` endpoint (DR-022).

Java side decodes the envelope as JSON and base64-decodes each artifact into a
``byte[]`` for local persistence; the sidecar deletes the source tempdir before
returning so nothing about the scan volume persists on the GPU host.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from retinal_inference.tasks import TaskName


class Artifact(BaseModel):
    """A single runner-produced output (CSV / PNG / NPY) sent inline.

    The name is the file's basename inside the sidecar's tempdir; the receiver
    persists it under ``<artifact-store>/<job-uuid>/<name>`` so the result row's
    ``bscan_masks_dir`` points to the parent directory the operator can browse.
    """

    name: str
    media_type: str = Field(..., description="RFC 6838 media type, e.g. text/csv.")
    content_base64: str


class RunEnvelope(BaseModel):
    """Successful ``POST /run`` body. One-shot — no streaming, no heartbeats.

    Fields mirror the existing ``FullVolumeResult`` so the Java side can carry
    the same DB columns it already writes for the local DB-poll path; the
    additional ``artifacts`` array carries the binary outputs that used to live
    on the shared segmentation-output volume.

    The server emits raw artifacts only and the Java backend computes the
    clinical metric, so ``primary_metric_value``/``primary_metric_unit`` are
    optional (``None`` for the real-model tasks).
    """

    model_version: str
    primary_metric_value: float | None = None
    primary_metric_unit: str | None = None
    output_payload: dict[str, Any] = Field(default_factory=dict)
    confidence: float = 0.85
    artifacts: list[Artifact] = Field(default_factory=list)
    task: TaskName
    laterality: str