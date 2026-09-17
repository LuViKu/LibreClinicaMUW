"""Persist the received Part-10 object + a best-effort preview PNG to the shared
ingest store. Paths (not blobs) are what the app records — mirroring the retinal
artifact store."""
from __future__ import annotations

import logging
from pathlib import Path

from pydicom.dataset import Dataset, FileMetaDataset

LOG = logging.getLogger("dicom_scp.store")


def _safe_seg(uid: str) -> str:
    """UIDs are dotted digits; keep only filesystem-safe chars as a defence."""
    cleaned = "".join(c for c in uid if c.isalnum() or c in "._-")
    return cleaned or "unknown"


def persist(ds: Dataset, file_meta: FileMetaDataset, store_path: str,
            study_date_iso: str | None) -> tuple[str, str | None]:
    """Write <store>/<yyyy-mm-dd|undated>/<sopuid>.dcm (+ .png).

    Returns (dcm_path, png_path|None). Raises only if the DICOM itself can't be
    written (→ the SCP refuses the C-STORE); a failed preview is swallowed.
    """
    sub = study_date_iso or "undated"
    sop = _safe_seg(str(getattr(ds, "SOPInstanceUID", "") or "unknown"))
    base = Path(store_path) / sub
    base.mkdir(parents=True, exist_ok=True)
    dcm_path = base / f"{sop}.dcm"

    ds.file_meta = file_meta
    # pydicom 3.x: enforce_file_format=True writes a proper Part-10 (preamble +
    # file meta) using ds.file_meta's transfer syntax.
    ds.save_as(str(dcm_path), enforce_file_format=True)

    png_path = _render_preview(ds, base / f"{sop}.png")
    return str(dcm_path), (str(png_path) if png_path else None)


def _render_preview(ds: Dataset, out: Path) -> Path | None:
    """Render an 8-bit RGB/greyscale thumbnail. Best-effort — a compressed
    transfer syntax without a decoder (no pylibjpeg) just yields no preview; the
    DICOM is still stored and enqueued."""
    try:
        import numpy as np
        from PIL import Image

        arr = ds.pixel_array
        if arr.ndim == 2:  # monochrome
            a = arr.astype("float32")
            lo, hi = float(a.min()), float(a.max())
            a = (a - lo) / (hi - lo) * 255.0 if hi > lo else a * 0.0
            img = Image.fromarray(a.astype("uint8"), mode="L")
        else:  # colour — pydicom returns RGB for most photometric interpretations
            a = arr[..., :3]
            if a.dtype != np.uint8:
                peak = float(a.max()) or 1.0
                a = (a.astype("float32") / peak * 255.0).astype("uint8")
            img = Image.fromarray(a, mode="RGB")
        img.thumbnail((1024, 1024))
        img.save(str(out), format="PNG")
        return out
    except Exception as exc:  # noqa: BLE001 — preview is optional, never fail ingest
        LOG.warning("preview render skipped (%s) — storing DICOM only", type(exc).__name__)
        return None
