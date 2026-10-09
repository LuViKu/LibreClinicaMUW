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
  (along-scan, A-scan to A-scan) spacing. Original Heyex / Spectralis exports
  are expected to follow this order (per the user).
* The stored order is NOT trusted blindly. A DICOM produced by a third-party
  .e2e -> DICOM converter (another MUW-internal platform) was observed with
  PixelSpacing = [lateral, axial] (0.005825 \\ 0.003872 on a 496 x 1024 x 97
  6 x 6 mm cube), and such files can be uploaded again. Read in standard
  order, every thickness would come out ~1.5x too large, silently. So the
  axial value is resolved physically (:func:`resolve_axial_lateral`): for a
  Heidelberg device the axial spacing is Spectralis' fixed ~3.87 µm digital
  depth sampling, so the value inside [0.0035, 0.0042] mm is the axial one.
  Exactly one value must fit (``standard`` or ``swapped``); none or both is
  refused (:class:`SpacingAmbiguous`). The physical rule handles both
  orders. A file written by this pipeline's own writer (marked by its
  DeidentificationMethod) is always [axial, lateral] and is taken as
  ``standard``. Other manufacturers keep the DICOM order as
  ``standard-assumed``; no task is validated for them anyway (vendor gating).
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


class SpacingAmbiguous(SpacingUnavailable):
    """PixelSpacing is present, but which value is axial cannot be decided."""


# Spectralis samples depth at a fixed ~3.87 µm per pixel; the window is wide
# enough for rounding, narrow enough to exclude the lateral spacing of the
# usual scan patterns (5.7-11.5 µm).
HEIDELBERG_AXIAL_MM = (0.0035, 0.0042)
# Spectralis B-scan depth: 496 rows x 3.87 µm = 1.92 mm (plausibility only).
HEIDELBERG_DEPTH_MM = (1.5, 2.5)
# Written by muw_e2e_converter.phi.redact_dicom on every bscan.dcm this
# pipeline produces; that writer stores [axial, lateral].
OWN_WRITER_MARKER = "LibreClinicaMUW-sidecar-v1"


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
    return _positive_mm(a, f"{what}[0]"), _positive_mm(b, f"{what}[1]")


def _agree(values: list[float]) -> bool:
    ref = values[0]
    return all(abs(v - ref) <= _REL_TOL * max(abs(ref), _MIN_MM) for v in values)


def frame_count(ds: Any) -> int:
    """NumberOfFrames as an int (1 when absent); raises ValueError when malformed."""
    raw = getattr(ds, "NumberOfFrames", None)
    if raw is None or str(raw).strip() == "":
        return 1
    return int(float(str(raw)))


def _is_heidelberg(manufacturer: str | None) -> bool:
    return "heidelberg" in (manufacturer or "").lower()


def resolve_axial_lateral(
    first: float, second: float, manufacturer: str | None, own_writer: bool = False
) -> tuple[float, float, str]:
    """(axial, lateral, order) from PixelSpacing as stored.

    ``order``: ``standard`` (stored [axial, lateral]), ``swapped`` (stored
    [lateral, axial]) or ``standard-assumed`` (no device rule; DICOM order
    taken). Raises :class:`SpacingAmbiguous` for a Heidelberg file whose
    values do not single out the axial one.
    """
    if own_writer:
        return first, second, "standard"
    if not _is_heidelberg(manufacturer):
        return first, second, "standard-assumed"
    lo, hi = HEIDELBERG_AXIAL_MM
    fits = [lo <= v <= hi for v in (first, second)]
    if fits == [True, False]:
        return first, second, "standard"
    if fits == [False, True]:
        return second, first, "swapped"
    which = "both values" if all(fits) else "neither value"
    raise SpacingAmbiguous(
        f"PixelSpacing = [{first:g}, {second:g}] mm: {which} matches the "
        f"Heidelberg Spectralis axial spacing (~0.00387 mm, accepted "
        f"{lo}-{hi} mm), so which value is axial and which lateral cannot be "
        "decided"
    )


def stored_pixel_spacing_mm(ds: Any) -> tuple[float, float, str]:
    """(first, second, source): Pixel Spacing as stored, from the first place that has it."""
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


def pixel_spacing_mm(ds: Any, trust_own_writer: bool = False) -> tuple[float, float, str, str]:
    """(axial, lateral, source, order): the stored pair resolved physically.

    ``trust_own_writer`` lets a file our own writer produced (its
    DeidentificationMethod marker) resolve as standard without the value rule.
    Only the cluster may pass it: it receives nothing but the normalised
    bscan.dcm the preprocess step wrote. The preprocess step reads uploaded
    files, and an upload carrying the marker must not switch the check off.
    """
    first, second, source = stored_pixel_spacing_mm(ds)
    manufacturer = str(getattr(ds, "Manufacturer", "") or "")
    own = trust_own_writer and (
        str(getattr(ds, "DeidentificationMethod", "") or "") == OWN_WRITER_MARKER)
    axial, lateral, order = resolve_axial_lateral(first, second, manufacturer, own)
    return axial, lateral, source, order


def plausibility(
    *, rows: int, cols: int, n_frames: int, axial: float, lateral: float,
    slice_mm: float, manufacturer: str | None,
) -> dict:
    """Physical extents + a warn-level depth check (never a refusal).

    ``depth_plausible`` is judged for Heidelberg only (B-scan depth expected
    in 1.5-2.5 mm); None for other vendors.
    """
    depth = rows * axial
    width = cols * lateral
    volume_depth = max(n_frames - 1, 0) * slice_mm
    warnings: list[str] = []
    plausible: bool | None = None
    if _is_heidelberg(manufacturer):
        lo, hi = HEIDELBERG_DEPTH_MM
        plausible = lo <= depth <= hi
        if not plausible:
            warnings.append(
                f"B-scan depth {depth:.3f} mm is outside the {lo}-{hi} mm expected "
                "for a Spectralis volume; check the axial spacing"
            )
    return {
        "depth_mm": depth,
        "width_mm": width,
        "volume_depth_mm": volume_depth,
        "depth_plausible": plausible,
        "warnings": warnings,
    }


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


def volume_spacing_mm(ds: Any, trust_own_writer: bool = False) -> tuple[float, float, float, dict[str, str]]:
    """(axial, lateral, slice, sources) — raises SpacingUnavailable.

    ``sources`` has ``pixel``, ``slice`` and the resolved ``order``.
    """
    axial, lateral, px_src, order = pixel_spacing_mm(ds, trust_own_writer)
    slice_mm, sl_src = slice_spacing_mm(ds)
    return axial, lateral, slice_mm, {"pixel": px_src, "slice": sl_src, "order": order}
