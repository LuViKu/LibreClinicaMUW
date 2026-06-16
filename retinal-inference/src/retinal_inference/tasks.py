"""Supported inference tasks.

Each task is backed by one OPTIMA/RetInsight model behind the ``OptimaAdapter``
(which dispatches to a per-task model-runner). Adding a task is registry-only:
extend the Literal + ``SUPPORTED_TASKS`` + add a ``TASK_METADATA`` entry. The
Postgres schema (``output_payload`` JSONB + ``primary_metric_value/unit``) and
the HTTP contract already carry the discriminator and a generic result shape.
"""

from __future__ import annotations

from typing import Any, Literal

# Extend the literal + the SUPPORTED_TASKS set when a new model-runner ships.
#   ga    — RPE-loss → geographic-atrophy area              (sese_ga, RPEL channel)  [GPU-gated]
#   fluid — IRF + SRF + PED fluid segmentation              (sese_retinsight_fluid)
#   onl   — outer nuclear layer thickness                   (sese_onl)
#   bmeis — photoreceptor (BMEIS) boundary                  (sese_pr)
TaskName = Literal["ga", "fluid", "onl", "bmeis"]

# All tasks the platform knows about (and that the PlaceholderAdapter can mock).
# Whether a task can actually run in a given deployment is decided by the active
# adapter: the OptimaAdapter only ``supports()`` a task that has a configured,
# enabled model-runner — that is the gate that keeps ``ga`` off until the IOWA
# layer segmenter + a GPU host are available (see the project plan).
SUPPORTED_TASKS: set[TaskName] = {"ga", "fluid", "onl", "bmeis"}

TASK_METADATA: dict[TaskName, dict[str, Any]] = {
    "ga": {
        "display_name": "Geographic atrophy (cRORA) segmentation",
        "output_kind": "segmentation",  # 'segmentation' | 'classification' | 'regression' | 'layer'
        "output_unit": "mm²",
        "reference_modality": "oct",  # 'oct' | 'slo' | 'oct+slo'
        "primary_metric": "total_area_mm2",
    },
    "fluid": {
        "display_name": "Retinal fluid segmentation (IRF / SRF / PED)",
        "output_kind": "segmentation",
        "output_unit": "mm³",
        "reference_modality": "oct",
        "primary_metric": "total_fluid_volume_mm3",
    },
    "onl": {
        "display_name": "Outer nuclear layer thickness",
        "output_kind": "layer",
        "output_unit": "µm",
        "reference_modality": "oct",
        "primary_metric": "mean_onl_thickness_um",
    },
    "bmeis": {
        "display_name": "Photoreceptor boundary (BMEIS)",
        "output_kind": "layer",
        "output_unit": "µm",
        "reference_modality": "oct",
        "primary_metric": "mean_bmeis_depth_um",
    },
}
