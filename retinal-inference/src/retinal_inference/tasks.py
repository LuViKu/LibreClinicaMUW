"""Supported inference tasks.

Each task is backed by one OPTIMA/RetInsight model behind the ``OptimaAdapter``
(which dispatches to a per-task model-runner). Adding a task is registry-only:
extend the Literal + ``SUPPORTED_TASKS`` + add a ``TASK_METADATA`` entry. The
Postgres schema (``output_payload`` JSONB + ``primary_metric_value/unit``) and
the HTTP contract already carry the discriminator and a generic result shape.

The inference server returns *raw segmentation artifacts only* — the Java
backend computes every clinical metric (fluid mm³, ONL/PR µm, GA mm²). So
``primary_metric`` is ``None`` for every task here: the server no longer emits
a server-side metric, and ``primary_metric_value/unit`` on the result models is
optional.
"""

from __future__ import annotations

from typing import Any, Literal

# Extend the literal + the SUPPORTED_TASKS set when a new model-runner ships.
#   ga    — RPE-loss → geographic-atrophy segmentation      (sese_ga, RPEL channel)  [GPU-gated]
#   fluid — IRF + SRF + PED fluid segmentation              (sese_retinsight_fluid)
#   onl   — outer nuclear layer segmentation (OPL-HFL/BMEIS)(sese_onl)
#   pr    — photoreceptor layer segmentation (BMEIS/OB-OPR) (sese_pr)
TaskName = Literal["ga", "fluid", "onl", "pr"]

# All tasks the platform knows about (and that the PlaceholderAdapter can mock).
# Whether a task can actually run in a given deployment is decided by the active
# adapter: the OptimaAdapter only ``supports()`` a task that has a configured,
# enabled model-runner — that is the gate that keeps ``ga`` off until the IOWA
# layer segmenter + a GPU host are available (see the project plan).
SUPPORTED_TASKS: set[TaskName] = {"ga", "fluid", "onl", "pr"}

# The server emits only raw artifacts; ``primary_metric`` is None for every task
# because the Java backend computes the clinical metric from those artifacts.
TASK_METADATA: dict[TaskName, dict[str, Any]] = {
    "ga": {
        "display_name": "Geographic atrophy (cRORA) segmentation",
        "output_kind": "segmentation",  # 'segmentation' | 'classification' | 'regression' | 'layer'
        "reference_modality": "oct",  # 'oct' | 'slo' | 'oct+slo'
        "primary_metric": None,  # Java computes the GA area (mm²) from the RPEL artifact
    },
    "fluid": {
        "display_name": "Retinal fluid segmentation (IRF / SRF / PED)",
        "output_kind": "segmentation",
        "reference_modality": "oct",
        "primary_metric": None,  # Java computes the fluid volumes (mm³) from the masks
    },
    "onl": {
        "display_name": "Outer nuclear layer segmentation (OPL-HFL / BMEIS boundaries)",
        "output_kind": "layer",
        "reference_modality": "oct",
        "primary_metric": None,  # Java computes the ONL thickness (µm) from the surface CSVs
    },
    "pr": {
        "display_name": "Photoreceptor (PR) layer segmentation (BMEIS / OB-OPR boundaries)",
        "output_kind": "layer",
        "reference_modality": "oct",
        "primary_metric": None,  # Java computes the PR depth (µm) from the surface CSVs
    },
}
