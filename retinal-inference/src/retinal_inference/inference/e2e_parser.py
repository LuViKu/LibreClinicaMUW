"""E2E binary → metadata.

Stub. The placeholder adapter reads the file but does not decode the
Heidelberg binary. The real adapter will use ``oct-converter`` / ``eyepy``
once those land in ``requirements.txt``.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class E2EMetadata:
    n_bscans: int
    bscan_width_px: int
    bscan_height_px: int
    pixel_scale_mm: float


def parse_e2e_metadata(e2e_path: Path) -> E2EMetadata:
    """Return placeholder metadata for an E2E file.

    Real implementation will use ``oct_converter.readers.E2E`` to read the
    Heidelberg header. For the placeholder period we return constants that
    match the MUW 41-eye / 97-B-scan cohort.
    """
    # Touch the file so callers get a FileNotFoundError early if the path is wrong.
    if not Path(e2e_path).is_file():
        raise FileNotFoundError(f"E2E file not found: {e2e_path}")
    return E2EMetadata(
        n_bscans=97,
        bscan_width_px=512,
        bscan_height_px=496,
        pixel_scale_mm=0.011,
    )
