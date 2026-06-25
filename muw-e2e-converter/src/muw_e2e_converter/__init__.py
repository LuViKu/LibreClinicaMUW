"""Heidelberg .e2e → bscan.dcm + SLO companion conversion (DR-024 single ingestion seam).

This package is installed in the LOCAL app-VM ``retinal-preprocess``
container only. The CLUSTER inference sidecar on cn5 deliberately does
NOT install it — the cluster's ``ApptainerAdapter.full_volume`` rejects
``.e2e`` inputs and expects a pre-converted ``bscan.dcm`` from the local
preprocess service.

See ``docs/development/modernization/decision-record.md`` § DR-024 for
the split rationale and the 2026-06-24 IOWA-debug session that motivated
the refactor.
"""

from __future__ import annotations

from .e2e_parser import (
    BscanVolume,
    E2EMetadata,
    heidelberg_pos_to_mm,
    parse_e2e_metadata,
    prepare_bscan_dcm,
    read_e2e_volume,
    write_bscan_dcm,
)
from .fundus_extract import build_geometry, extract_fundus_png
from .phi import DEIDENTIFICATION_METHOD, redact_dicom

__all__ = [
    "BscanVolume",
    "DEIDENTIFICATION_METHOD",
    "E2EMetadata",
    "build_geometry",
    "extract_fundus_png",
    "heidelberg_pos_to_mm",
    "parse_e2e_metadata",
    "prepare_bscan_dcm",
    "read_e2e_volume",
    "redact_dicom",
    "write_bscan_dcm",
]
