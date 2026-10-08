"""Voxel spacing of an Ophthalmic Tomography (OPT) DICOM volume, in mm.

Shared by the app-VM ``/preprocess`` DICOM branch (which normalises a
vendor OCT export into the ``bscan.dcm`` the cluster expects) and the
cluster's ``ApptainerAdapter._spacing_mm`` (defence in depth). Depends on
pydicom only, so the cluster posture can import it without the converter.

What the DICOM standard says, and what this module relies on
-------------------------------------------------------------

* The OPT IOD (PS3.3 A.52) is an enhanced multi-frame IOD: the Pixel Measures
  functional group is mandatory, and the vendor may put it in the Shared or
  the Per-frame Functional Groups Sequence. Our own synthesised ``bscan.dcm``
  (and older, non-enhanced exports) carry the same attributes at the top level
  instead. All three places are read, top level first.
* Pixel Spacing (0028,0030) is "adjacent row spacing \\ adjacent column
  spacing in mm". An OPT frame is one B-scan whose rows run along depth, so
  the first value is the axial (depth) spacing and the second the lateral
  (along-scan, A-scan to A-scan) spacing. Real Spectralis exports confirm the
  order (~0.0039 axial, ~0.011 lateral).
* Spacing Between Slices (0018,0088) in mm is the B-scan to B-scan distance.
  It is read from the top level and from Pixel Measures.
* Slice Thickness (0018,0050) is NOT used: in an OPT object it is the
  nominal thickness of the beam/section, not the distance between B-scans.
  Reading it as a spacing would silently mis-scale every volume metric.
* Plane Position (Patient) Image Position (Patient) (0020,0032) is in mm in
  the patient coordinate system and is required in an OPT object when no
  ophthalmic photography reference image is available. The slice spacing is
  derived from it as the first-to-last frame distance / (n - 1), and only when
  the consecutive steps are uniform (PS3.3 A.52.4.3 calls these values
  nominal, which is good enough for a mean spacing, not for irregular grids).
* Ophthalmic Frame Location (0022,0031) Reference Coordinates (0022,0032) are
  row/column pairs in PIXELS of the referenced (SLO / fundus) image, not mm.
  Converting them needs that image's own Pixel Spacing, which lives in a
  separate SOP instance, so they are deliberately not a spacing source here.

When a spacing cannot be determined, :class:`SpacingUnavailable` is raised:
metrics in mm depend on it, so it is never guessed.
"""

from __future__ import annotations

import math
from typing import Any

# A spacing outside this range is not an OCT voxel (mm); refuse rather than
# carry a unit error (e.g. µm written as mm) into every metric.
_MIN_MM = 1e-5
_MAX_MM = 10.0
# Per-frame values must agree to this relative tolerance.
_REL_TOL = 1e-3
# Consecutive plane-position steps may deviate this much from their mean.
_POSITION_STEP_TOL = 0.25


class SpacingUnavailable(ValueError):
    """The volume's spacing cannot be determined from the DICOM header."""


def _first_item(ds: Any, keyword: str) -> Any | None:
    seq = getattr(ds, keyword, None)
    if seq is None:
        return None
    try:
        return seq[0] if len(seq) > 0 else None
    except (TypeError, IndexError):
        return None


def _pixel_measures(group: Any) -> Any | None:
    return _first_item(group, "PixelMeasuresSequence") if group is not None else None


def _per_frame_groups(ds: Any) -> list[Any]:
    seq = getattr(ds, "PerFrameFunctionalGroupsSequence", None)
    return list(seq) if seq is not None else []


def _positive_mm(value: Any, what: str) -> float:
    try:
        f = float(value)
    except (TypeError, ValueError) as e:
        raise SpacingUnavailable(f"{what} is not a number ({value!r})") from e
    if not math.isfinite(f) or not (_MIN_MM <= f <= _MAX_MM):
        raise SpacingUnavailable(
            f"{what} = {f!r} mm is outside the plausible OCT range "
            f"[{_MIN_MM}, {_MAX_MM}] mm"
        )
    return f


def _pair(value: Any, what: str) -> tuple[float, float]:
    try:
        a, b = value[0], value[1]
    except (TypeError, IndexError) as e:
        raise SpacingUnavailable(f"{what} does not hold two values") from e
    return _positive_mm(a, f"{what}[0] (axial)"), _positive_mm(b, f"{what}[1] (lateral)")


def _agree(values: list[float]) -> bool:
    ref = values[0]
    return all(abs(v - ref) <= _REL_TOL * max(abs(ref), _MIN_MM) for v in values)


def frame_count(ds: Any) -> int:
    """NumberOfFrames as an int (1 when absent); raises ValueError when malformed."""
    raw = getattr(ds, "NumberOfFrames", None)
    if raw is None or str(raw).strip() == "":
        return 1
    return int(float(str(raw)))


def pixel_spacing_mm(ds: Any) -> tuple[float, float, str]:
    """(axial, lateral, source) — Pixel Spacing from the first place that has it."""
    if getattr(ds, "PixelSpacing", None) is not None:
        axial, lateral = _pair(ds.PixelSpacing, "PixelSpacing")
        return axial, lateral, "top-level PixelSpacing"

    shared = _pixel_measures(_first_item(ds, "SharedFunctionalGroupsSequence"))
    if shared is not None and getattr(shared, "PixelSpacing", None) is not None:
        axial, lateral = _pair(shared.PixelSpacing, "shared PixelMeasures PixelSpacing")
        return axial, lateral, "SharedFunctionalGroups PixelMeasures PixelSpacing"

    per_frame = [_pixel_measures(g) for g in _per_frame_groups(ds)]
    pairs = [
        _pair(pm.PixelSpacing, "per-frame PixelMeasures PixelSpacing")
        for pm in per_frame
        if pm is not None and getattr(pm, "PixelSpacing", None) is not None
    ]
    if pairs:
        if len(pairs) != len(per_frame):
            raise SpacingUnavailable("PixelSpacing is present on some frames only")
        if not (_agree([p[0] for p in pairs]) and _agree([p[1] for p in pairs])):
            raise SpacingUnavailable(
                "PixelSpacing differs between frames; a volume with varying "
                "pixel spacing is not supported"
            )
        return pairs[0][0], pairs[0][1], "PerFrameFunctionalGroups PixelMeasures PixelSpacing"

    raise SpacingUnavailable(
        "no PixelSpacing at the top level or in the Pixel Measures functional group"
    )


def _positions(ds: Any) -> list[tuple[float, float, float]] | None:
    out: list[tuple[float, float, float]] = []
    for g in _per_frame_groups(ds):
        pp = _first_item(g, "PlanePositionSequence")
        ipp = getattr(pp, "ImagePositionPatient", None) if pp is not None else None
        if ipp is None:
            return None
        try:
            x, y, z = (float(ipp[0]), float(ipp[1]), float(ipp[2]))
        except (TypeError, ValueError, IndexError):
            return None
        out.append((x, y, z))
    return out or None


def slice_spacing_mm(ds: Any, n_frames: int | None = None) -> tuple[float, str]:
    """(slice, source) — the B-scan to B-scan distance in mm."""
    if getattr(ds, "SpacingBetweenSlices", None) is not None:
        return _positive_mm(ds.SpacingBetweenSlices, "SpacingBetweenSlices"), (
            "top-level SpacingBetweenSlices"
        )

    shared = _pixel_measures(_first_item(ds, "SharedFunctionalGroupsSequence"))
    if shared is not None and getattr(shared, "SpacingBetweenSlices", None) is not None:
        return _positive_mm(
            shared.SpacingBetweenSlices, "shared PixelMeasures SpacingBetweenSlices"
        ), "SharedFunctionalGroups PixelMeasures SpacingBetweenSlices"

    per_frame = [_pixel_measures(g) for g in _per_frame_groups(ds)]
    values = [
        _positive_mm(pm.SpacingBetweenSlices, "per-frame PixelMeasures SpacingBetweenSlices")
        for pm in per_frame
        if pm is not None and getattr(pm, "SpacingBetweenSlices", None) is not None
    ]
    if values:
        if len(values) != len(per_frame) or not _agree(values):
            raise SpacingUnavailable("SpacingBetweenSlices differs between frames")
        return values[0], "PerFrameFunctionalGroups PixelMeasures SpacingBetweenSlices"

    n = n_frames if n_frames is not None else frame_count(ds)
    positions = _positions(ds)
    if positions is not None and n > 1 and len(positions) == n:
        steps = [math.dist(positions[i], positions[i + 1]) for i in range(n - 1)]
        mean = math.dist(positions[0], positions[-1]) / (n - 1)
        step_mean = sum(steps) / len(steps)
        if mean > 0 and step_mean > 0 and all(
            abs(s - step_mean) <= _POSITION_STEP_TOL * step_mean for s in steps
        ) and abs(mean - step_mean) <= _POSITION_STEP_TOL * step_mean:
            return _positive_mm(mean, "slice spacing derived from ImagePositionPatient"), (
                "derived from PerFrameFunctionalGroups PlanePosition ImagePositionPatient"
            )
        raise SpacingUnavailable(
            "per-frame ImagePositionPatient values do not describe a uniform "
            "B-scan stack (identical or irregular positions)"
        )

    raise SpacingUnavailable(
        "no SpacingBetweenSlices (top level or Pixel Measures) and no per-frame "
        "ImagePositionPatient to derive it from. Slice Thickness and the "
        "Ophthalmic Frame Location reference coordinates (pixels of the SLO) "
        "are not usable as a B-scan spacing"
    )


def volume_spacing_mm(ds: Any) -> tuple[float, float, float, dict[str, str]]:
    """(axial, lateral, slice, sources) — raises SpacingUnavailable."""
    axial, lateral, px_src = pixel_spacing_mm(ds)
    slice_mm, sl_src = slice_spacing_mm(ds)
    return axial, lateral, slice_mm, {"pixel": px_src, "slice": sl_src}
