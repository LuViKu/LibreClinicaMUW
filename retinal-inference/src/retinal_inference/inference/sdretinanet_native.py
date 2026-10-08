"""SD-RetinaNet output in the OPTIMA group's native formats (layerlib + lesionlib).

The eCRF stores this model's output exactly as the SWITCHER study and iamd-ws
read it, one pair of files per B-scan:

    layers/NNN.yml    layerlib line format: info (width, height, idx) + per layer
                      ``values`` (0-based depth row) and ``confid`` (0 = invalid)
    lesions/NNN.png   lesionlib packing: 8-bit indexed PNG whose pixel *index* is
                      main-lesion id (low bits, mutually exclusive) | overlay bits
                      (bit 7 - k for overlay k), plus a zTXt chunk ``Lesions`` with
                      {"v","bc","mc","oc","mn","on"}

and the sidecar ships them as one ``sdretinanet.zip`` artifact (the collector
flattens basenames, which would collide across the two folders).

This module depends on numpy and the standard library only, so it also works as
a file bind-mounted into the model container. The PNG is written with zlib, not
Pillow: the cluster env has no Pillow and the GPU nodes are glibc 2.17.
"""

from __future__ import annotations

import json
import re
import struct
import zipfile
import zlib
from pathlib import Path

import numpy as np

ARCHIVE = "sdretinanet.zip"
METADATA_KEY = "Lesions"
MODULE_VERSION = "1"
DEFAULT_MAIN_BITS = 6

# lesionlib.lesion_packing palette, so the PNGs look the same in any viewer.
_FIXED = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0), (0, 255, 255), (255, 0, 255),
          (255, 128, 0), (128, 0, 255), (255, 0, 128), (0, 128, 255), (128, 255, 0),
          (255, 128, 128), (128, 128, 255), (0, 128, 128), (128, 0, 0), (0, 100, 0)]
_TINTS = [(255, 255, 255), (0, 255, 255), (255, 0, 255), (255, 255, 0), (0, 0, 255),
          (0, 255, 0), (255, 0, 0)]


def layer_yml(values: np.ndarray, confidence: np.ndarray, names: list[str],
              width: int, height: int, idx: int) -> str:
    """One B-scan's layer file. ``values``/``confidence`` are (n_layers, width)."""
    lines = ["info:", f"  width: {width}", f"  height: {height}", f"  idx: {idx}", "", "layers:"]
    for li, name in enumerate(names):
        v = np.asarray(values[li])
        c = np.asarray(confidence[li]).astype(int)
        if v.shape != (width,) or c.shape != (width,):
            raise ValueError(f"layer {name}: expected {width} values, got {v.shape} / {c.shape}")
        if np.any(c < 0) or np.any(c > 255):
            raise ValueError(f"layer {name}: confidence outside 0..255")
        vals = np.where(np.isfinite(v), v, 0)
        if np.all(vals == np.round(vals)):
            vtxt = ",".join(str(int(x)) for x in vals)
        else:
            vtxt = ",".join(repr(float(x)) for x in vals)
        lines.append(f"  - {name}:")
        lines.append(f"    - values: {vtxt}")
        lines.append(f"    - confid: {','.join(str(int(x)) for x in c)}")
    return "\n".join(lines) + "\n"


def pack_lesions(main: list[np.ndarray], overlay: list[np.ndarray],
                 main_bits: int = DEFAULT_MAIN_BITS) -> np.ndarray:
    """lesionlib bit packing of (H, W) masks into one uint8 image."""
    if not 1 <= main_bits <= 7:
        raise ValueError("main_bits must be 1..7")
    if len(main) > (1 << main_bits) - 1 or len(overlay) > 8 - main_bits:
        raise ValueError("too many lesion classes for the bit split")
    shape = (main or overlay)[0].shape
    packed = np.zeros(shape, dtype=np.uint8)
    seen = np.zeros(shape, dtype=bool)
    for i, m in enumerate(main):
        m = np.asarray(m) > 0
        if np.any(seen & m):
            raise ValueError("main lesions must be mutually exclusive")
        seen |= m
        packed[m] = i + 1
    for k, m in enumerate(overlay):
        packed[np.asarray(m) > 0] |= np.uint8(1 << (7 - k))
    return packed


def _palette(main_bits: int, max_index: int) -> bytes:
    out = bytearray()
    overlay_bits = 8 - main_bits
    for i in range(max_index + 1):
        if i == 0:
            out += bytes((0, 0, 0))
            continue
        main_id = i & ((1 << main_bits) - 1)
        flags = i >> main_bits
        if main_id == 0:
            base = np.array([128, 128, 128])
        elif main_id <= 16:
            base = np.array(_FIXED[main_id - 1])
        else:
            base = np.random.default_rng(main_id * 100).integers(50, 255, size=3)
        tints = [_TINTS[k % len(_TINTS)] for k in range(overlay_bits)
                 if (flags >> (overlay_bits - 1 - k)) & 1]
        color = (base.astype(int) + np.mean(tints, axis=0).astype(int)) // 2 if tints else base
        out += bytes(int(x) for x in color)
    return bytes(out)


def _chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def lesion_png(packed: np.ndarray, main_names: list[str], overlay_names: list[str],
               main_bits: int = DEFAULT_MAIN_BITS) -> bytes:
    """An 8-bit indexed PNG of ``packed`` with lesionlib's ``Lesions`` metadata."""
    packed = np.ascontiguousarray(packed, dtype=np.uint8)
    h, w = packed.shape
    meta = {"v": MODULE_VERSION, "bc": main_bits, "mc": len(main_names), "oc": len(overlay_names)}
    if main_names:
        meta["mn"] = list(main_names)
    if overlay_names:
        meta["on"] = list(overlay_names)
    text = json.dumps(meta, separators=(",", ":")).encode("latin-1")
    raw = b"".join(b"\x00" + packed[y].tobytes() for y in range(h))
    return b"".join([
        b"\x89PNG\r\n\x1a\n",
        _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 3, 0, 0, 0)),
        _chunk(b"PLTE", _palette(main_bits, int(packed.max()))),
        _chunk(b"zTXt", METADATA_KEY.encode("latin-1") + b"\x00\x00" + zlib.compress(text, 9)),
        _chunk(b"IDAT", zlib.compress(raw, 9)),
        _chunk(b"IEND", b""),
    ])


def write_volume(out_dir: Path, boundaries: np.ndarray, confidence: np.ndarray,
                 layer_names: list[str], main: dict[str, np.ndarray],
                 overlay: dict[str, np.ndarray], main_bits: int = DEFAULT_MAIN_BITS) -> int:
    """Write ``layers/`` and ``lesions/`` for a volume.

    ``boundaries``/``confidence`` are (n_bscans, n_layers, width); each lesion
    mask is (n_bscans, height, width). Returns the number of B-scans written.
    """
    boundaries = np.asarray(boundaries)
    n_b, n_l, width = boundaries.shape
    if n_l != len(layer_names):
        raise ValueError(f"{n_l} boundary rows for {len(layer_names)} layer names")
    masks = list(main.values()) + list(overlay.values())
    if not masks:
        raise ValueError("no lesion masks")
    height = np.asarray(masks[0]).shape[1]
    for m in masks:
        if np.asarray(m).shape != (n_b, height, width):
            raise ValueError(f"lesion mask shape {np.asarray(m).shape} != {(n_b, height, width)}")
    (out_dir / "layers").mkdir(parents=True, exist_ok=True)
    (out_dir / "lesions").mkdir(parents=True, exist_ok=True)
    for b in range(n_b):
        (out_dir / "layers" / f"{b:03d}.yml").write_text(
            layer_yml(boundaries[b], confidence[b], layer_names, width, height, b), encoding="utf-8")
        packed = pack_lesions([np.asarray(m)[b] for m in main.values()],
                              [np.asarray(m)[b] for m in overlay.values()], main_bits)
        (out_dir / "lesions" / f"{b:03d}.png").write_bytes(
            lesion_png(packed, list(main), list(overlay), main_bits))
    return n_b


_NUMBERED = re.compile(r"^(\d+)\.(yml|png)$")


def pack_archive(src_dir: Path, archive: Path, expected_bscans: int | None = None) -> int:
    """Zip ``src_dir``'s ``layers/NNN.yml`` + ``lesions/NNN.png`` into ``archive``.

    Refuses incomplete output: both folders must hold the same 0..n-1 numbering,
    and n must equal ``expected_bscans`` when given (the DICOM's frame count).
    ``scan_stats.json`` rides along when present. Returns n.
    """
    found: dict[str, set[int]] = {}
    for sub, ext in (("layers", "yml"), ("lesions", "png")):
        d = src_dir / sub
        nums = set()
        if d.is_dir():
            for f in d.iterdir():
                m = _NUMBERED.match(f.name)
                if m and m.group(2) == ext:
                    nums.add(int(m.group(1)))
        found[sub] = nums
    n = len(found["layers"])
    if n == 0:
        raise RuntimeError(f"SD-RetinaNet wrote no layers/NNN.yml in {src_dir}")
    if found["layers"] != set(range(n)) or found["lesions"] != found["layers"]:
        raise RuntimeError(f"incomplete SD-RetinaNet output in {src_dir}: "
                           f"{n} layer / {len(found['lesions'])} lesion files")
    if expected_bscans is not None and n != expected_bscans:
        raise RuntimeError(f"SD-RetinaNet wrote {n} B-scans, the DICOM has {expected_bscans}")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for b in range(n):
            z.write(src_dir / "layers" / f"{b:03d}.yml", f"layers/{b:03d}.yml")
            z.write(src_dir / "lesions" / f"{b:03d}.png", f"lesions/{b:03d}.png")
        stats = src_dir / "scan_stats.json"
        if stats.is_file():
            z.write(stats, "scan_stats.json")
    return n
