"""Generate the synthetic SD-RetinaNet fixture and its reference metrics.

Run with the namd_switcher_study virtualenv (it has layerlib, lesionlib, scipy and
the namd_switcher package, which is the reference implementation):

    E:\\namd_switcher_study\\.venv\\Scripts\\python.exe generate_fixture.py

Writes sdretinanet.zip (layers/NNN.yml + lesions/NNN.png, written by the real
layerlib/lesionlib writers) and expected.json (namd_switcher's fovea detection and
quantification on the same data). SdRetinaNetMetricTest checks the Java port
against expected.json.
"""
import io
import json
import zipfile
from pathlib import Path

import numpy as np
from layerlib import Layer, LayerSet
from lesionlib.lesion_packing import save_lesion_dicts
from namd_switcher.biomarkers import quantify
from namd_switcher.fovea import detect_fovea
from namd_switcher.geometry import ScanGeometry
from namd_switcher.segmentation import LAYER_NAMES, LESION_NAMES, Segmentation
from scipy import ndimage

HERE = Path(__file__).parent
N_B, N_A, N_ROWS = 33, 64, 80
AXIAL, LATERAL, SLICE = 0.006, 0.095, 0.19   # 0.1 lateral trips a float edge case in namd_switcher.fovea
PIT_B, PIT_A = 18, 37          # off-centre pit so the fovea is not the scan centre

rng = np.random.default_rng(7)
yy, xx = np.meshgrid(np.arange(N_B) * SLICE, (np.arange(N_A) + 0.5) * LATERAL, indexing="ij")
r_mm = np.hypot(yy - PIT_B * SLICE, xx - (PIT_A + 0.5) * LATERAL)
pit = 0.9 * np.exp(-(r_mm / 0.6) ** 2)           # fraction of the inner retina missing at the pit

# boundary rows, top to bottom; the inner layers sink onto INL-OPL at the pit
base = np.array([10, 14, 18, 22, 26, 34, 44, 47, 52, 55, 56, 72], dtype=float)
bound = np.repeat(np.repeat(base[None, :, None], N_B, 0), N_A, 2)
for i in range(4):                                # ILM .. IPL-INL
    bound[:, i, :] += pit * (base[4] - base[i])
bound += rng.integers(-1, 2, size=bound.shape)
bound = np.sort(np.round(bound), axis=1)
conf = np.ones_like(bound, dtype=np.uint8)
conf[:, :, :2] = 0                                # invalid A-scans at the left edge
conf[3, 10, 20:25] = 0                            # a gap in BM

masks = np.zeros((N_B, len(LESION_NAMES), N_ROWS, N_A), dtype=np.uint8)
def put(name, b0, b1, r0, r1, a0, a1):
    masks[b0:b1, LESION_NAMES.index(name), r0:r1, a0:a1] = 1
put("Cyst", 16, 20, 28, 31, 33, 40)
put("SRF", 17, 22, 48, 51, 30, 45)
put("PED", 10, 14, 55, 58, 12, 20)
put("SHRM", 25, 26, 49, 52, 50, 54)
put("Pseudodrusen", 5, 7, 50, 52, 5, 9)
for b, r, a in [(18, 30, 36), (18, 30, 38), (20, 40, 10), (2, 20, 60)]:   # HRF dots, one on a cyst
    masks[b, LESION_NAMES.index("HRF"), r, a] = 1
main_names = LESION_NAMES[:-1]

buf = io.BytesIO()
with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
    for b in range(N_B):
        ls = LayerSet()
        ls.info.update({"width": N_A, "height": N_ROWS, "idx": b})
        for li, name in enumerate(LAYER_NAMES):
            ls.layers[name] = Layer(name, bound[b, li].astype(int).tolist(), conf[b, li].tolist(), None)
        z.writestr(f"layers/{b:03d}.yml", ls.to_string())
        png = HERE / "_tmp.png"
        save_lesion_dicts(png, {n: masks[b, LESION_NAMES.index(n)] for n in main_names},
                          {"HRF": masks[b, LESION_NAMES.index("HRF")]})
        z.writestr(f"lesions/{b:03d}.png", png.read_bytes())
        png.unlink()
(HERE / "sdretinanet.zip").write_bytes(buf.getvalue())

# reference metrics, computed the way namd_switcher does
boundaries = bound.astype(float)
boundaries[conf == 0] = np.nan
heights = masks.sum(axis=2).astype(float)
foci = []
for b in range(N_B):
    lab, n = ndimage.label(masks[b, LESION_NAMES.index("HRF")])
    foci += [(b, c[1]) for c in ndimage.center_of_mass(lab > 0, lab, range(1, n + 1))]
seg = Segmentation(boundaries, heights, np.array(foci, dtype=float).reshape(-1, 2))
geom = ScanGeometry(laterality="OD", n_bscans=N_B, n_ascans=N_A, n_rows=N_ROWS,
                    lateral_mm=LATERAL, axial_mm=AXIAL, bscan_spacing_mm=SLICE,
                    x_mm=(np.arange(N_A) + 0.5) * LATERAL, y_mm=np.arange(N_B) * SLICE, protocol="test")
fovea = detect_fovea(boundaries, geom)
# namd_switcher.pipeline: a doubtful pit falls back to the scan centre
if fovea["fovea_needs_review"]:
    center_xy, source = geom.center_mm, "scan_center_fallback"
else:
    center_xy, source = (fovea["fovea_x_mm"], fovea["fovea_y_mm"]), "detected"
rows = quantify(seg, geom, center_xy)
keep = {"C1", "C3", "C6", "R1_3", "R3_6"}
expected = {
    "fovea": fovea,
    "grid_center": {"x_mm": center_xy[0], "y_mm": center_xy[1], "source": source},
    "regions": [r for r in rows if r["region"] in keep],
    "total_voxels": {n: int(masks[:, i].sum()) for i, n in enumerate(LESION_NAMES)},
}
(HERE / "expected.json").write_text(json.dumps(expected, indent=1, default=float))
print("fovea", fovea)
