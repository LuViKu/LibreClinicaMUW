"""Supported inference tasks.

v1 enables ``task='ga'`` only. Adding a new task (fluid, layers, iAMD
classification) is decoder-only: extend the Literal + ``SUPPORTED_TASKS`` +
add a ``TASK_METADATA`` entry. The Postgres schema and the HTTP contract
already carry the discriminator.
"""

from __future__ import annotations

from typing import Any, Literal

# Extend the literal + the SUPPORTED_TASKS set when a new decoder ships.
TaskName = Literal["ga"]  # future: 'fluid', 'layers', 'iamd', ...

SUPPORTED_TASKS: set[TaskName] = {"ga"}

TASK_METADATA: dict[TaskName, dict[str, Any]] = {
    "ga": {
        "display_name": "Geographic atrophy (cRORA) segmentation",
        "output_kind": "segmentation",  # 'segmentation' | 'classification' | 'regression'
        "output_unit": "mm²",
        "reference_modality": "oct",  # 'oct' | 'slo' | 'oct+slo'
    },
}
