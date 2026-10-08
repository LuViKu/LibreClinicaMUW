"""--output_formatter for retinanet-spectralis_main.sif: write the native files.

main.py loads this file and calls ``format_output(**kwargs)`` once per input
volume with (shapes from main.py's process_volume / save_default_outputs):

    layer_positions      (B, 12, W) int, depth row per A-scan
    std_deviations       (B, 12, W) float, rounded to 0.01
    final_lesions        (B, 7, H, W) float, per-class probability
    final_layers         raw layer maps (unused here)
    thresholded_lesions  (B, 7, H, W) uint8, final_lesions > --threshold
    original_path        the input file
    output_folder        main.py's output folder

and writes ``layers/NNN.yml`` + ``lesions/NNN.png`` into ``output_folder``.

Two conversions:

- **Validity**: a boundary A-scan is valid where its standard deviation is
  below 10, the rule main.py's own save_default_outputs applies.
- **Lesions**: the model's channel order is alphabetical (MODEL_LESIONS). The
  lesionlib file keeps the six main classes mutually exclusive and HRF as an
  overlay, in the order the SWITCHER segmentations use. A pixel above the
  threshold in more than one main class goes to the class with the highest
  probability; HRF is kept wherever it is above the threshold.

The ApptainerAdapter mounts this file at /opt/ri/sdretinanet_formatter.py and
the writer module beside it at /opt/ri/sdretinanet_native.py.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sdretinanet_native as native  # noqa: E402  (mounted beside this file)

# main.py's channel order (save_default_outputs)
MODEL_LESIONS = ["Cyst", "HRF", "ORT", "PED", "Pseudodrusen", "SHRM", "SRF"]
# lesionlib order of the SWITCHER segmentations: main, then overlay
MAIN_LESIONS = ["Cyst", "SRF", "PED", "SHRM", "Pseudodrusen", "ORT"]
OVERLAY_LESIONS = ["HRF"]
LAYER_NAMES = ["ILM", "RNFL-GCL", "GCL-IPL", "IPL-INL", "INL-OPL", "OPL-HFL",
               "OB_ELM", "BMEIS", "IB_RPE", "OB_RPE", "BM", "HL-S"]
MAX_VALID_STD = 10


def exclusive_main(thresholded: np.ndarray, probabilities: np.ndarray) -> dict[str, np.ndarray]:
    """Main-lesion masks (B, H, W) with overlaps given to the most probable class."""
    idx = [MODEL_LESIONS.index(n) for n in MAIN_LESIONS]
    n_b, _, h, w = thresholded.shape
    out = {n: np.zeros((n_b, h, w), dtype=np.uint8) for n in MAIN_LESIONS}
    for b in range(n_b):  # one B-scan at a time: the probability volume is large
        on = thresholded[b, idx] > 0
        p = np.where(on, probabilities[b, idx].astype(np.float32), -np.inf)
        winner = np.argmax(p, axis=0)
        any_on = on.any(axis=0)
        for i, name in enumerate(MAIN_LESIONS):
            out[name][b] = (any_on & (winner == i)).astype(np.uint8)
    return out


def format_output(*, layer_positions, std_deviations, final_lesions, thresholded_lesions,
                  output_folder, **_unused) -> None:
    layer_positions = np.asarray(layer_positions)
    thresholded_lesions = np.asarray(thresholded_lesions)
    if layer_positions.ndim != 3 or layer_positions.shape[1] != len(LAYER_NAMES):
        raise ValueError(f"layer_positions shape {layer_positions.shape}, expected (B, 12, W)")
    if thresholded_lesions.ndim != 4 or thresholded_lesions.shape[1] != len(MODEL_LESIONS):
        raise ValueError(f"thresholded_lesions shape {thresholded_lesions.shape}, expected (B, 7, H, W)")
    confidence = (np.asarray(std_deviations) < MAX_VALID_STD).astype(np.uint8)
    main = exclusive_main(thresholded_lesions, np.asarray(final_lesions))
    overlay = {n: thresholded_lesions[:, MODEL_LESIONS.index(n)].astype(np.uint8)
               for n in OVERLAY_LESIONS}
    native.write_volume(Path(output_folder), layer_positions, confidence, LAYER_NAMES, main, overlay)
