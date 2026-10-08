"""sdretinanet: native-format writer + ApptainerAdapter handler (no cluster needed).

The writer is checked against lesionlib's documented packing; the round trip
through the real layerlib/lesionlib readers was verified separately in the
namd_switcher_study env, which has them (this env does not).
"""

from __future__ import annotations

import io
import json
import types
import zipfile
import zlib
from pathlib import Path

import numpy as np
import pytest

from retinal_inference import config as _config
from retinal_inference.inference import apptainer as ap
from retinal_inference.inference import sdretinanet_native as native
from retinal_inference.inference.artifact_collector import collect_artifacts

LAYERS = ["ILM", "RNFL-GCL", "GCL-IPL", "IPL-INL", "INL-OPL", "OPL-HFL",
          "OB_ELM", "BMEIS", "IB_RPE", "OB_RPE", "BM", "HL-S"]
MAIN = ["Cyst", "SRF", "PED", "SHRM", "Pseudodrusen", "ORT"]


def _volume(n_b=3, h=20, w=16):
    bound = np.tile(np.arange(12, dtype=float)[None, :, None] + 2, (n_b, 1, w))
    conf = np.ones_like(bound, dtype=int)
    conf[:, :, 0] = 0
    main = {k: np.zeros((n_b, h, w), np.uint8) for k in MAIN}
    main["Cyst"][1, 4:6, 3:7] = 1
    main["SRF"][n_b - 1, 10:12, 8:10] = 1
    hrf = {"HRF": np.zeros((n_b, h, w), np.uint8)}
    hrf["HRF"][1, 5, 4] = 1
    return bound, conf, main, hrf


def _png_chunks(data: bytes) -> dict[bytes, bytes]:
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, out = 8, {}
    while pos < len(data):
        n = int.from_bytes(data[pos:pos + 4], "big")
        kind = data[pos + 4:pos + 8]
        out[kind] = out.get(kind, b"") + data[pos + 8:pos + 8 + n]
        pos += 12 + n
    return out


def test_lesion_png_packs_main_ids_and_overlay_bits(tmp_path) -> None:
    bound, conf, main, hrf = _volume()
    native.write_volume(tmp_path, bound, conf, LAYERS, main, hrf)
    chunks = _png_chunks((tmp_path / "lesions" / "001.png").read_bytes())
    w, h, depth, color = int.from_bytes(chunks[b"IHDR"][:4], "big"), \
        int.from_bytes(chunks[b"IHDR"][4:8], "big"), chunks[b"IHDR"][8], chunks[b"IHDR"][9]
    assert (w, h, depth, color) == (16, 20, 8, 3)  # 8-bit indexed
    key, rest = chunks[b"zTXt"].split(b"\x00", 1)
    assert key == b"Lesions"
    meta = json.loads(zlib.decompress(rest[1:]))
    assert meta == {"v": "1", "bc": 6, "mc": 6, "oc": 1, "mn": MAIN, "on": ["HRF"]}
    raw = zlib.decompress(chunks[b"IDAT"])
    rows = [raw[y * (w + 1) + 1:(y + 1) * (w + 1)] for y in range(h)]
    assert rows[5][4] == 0x81  # cyst (id 1) + HRF (bit 7)
    assert rows[4][3] == 1
    assert rows[0][0] == 0


def test_layer_yml_marks_invalid_as_confidence_zero(tmp_path) -> None:
    bound, conf, main, hrf = _volume()
    native.write_volume(tmp_path, bound, conf, LAYERS, main, hrf)
    text = (tmp_path / "layers" / "000.yml").read_text()
    assert text.startswith("info:\n  width: 16\n  height: 20\n  idx: 0\n")
    assert "  - ILM:\n    - values: 2,2," in text
    assert "    - confid: 0,1,1," in text
    assert text.count("  - ") == 12 * 3


def test_main_lesions_must_not_overlap() -> None:
    a = np.ones((2, 2), np.uint8)
    with pytest.raises(ValueError):
        native.pack_lesions([a, a], [])


def test_pack_archive_refuses_partial_output(tmp_path) -> None:
    bound, conf, main, hrf = _volume()
    native.write_volume(tmp_path, bound, conf, LAYERS, main, hrf)
    (tmp_path / "lesions" / "002.png").unlink()
    with pytest.raises(RuntimeError, match="incomplete"):
        native.pack_archive(tmp_path, tmp_path / "x.zip")
    with pytest.raises(RuntimeError, match="no layers"):
        native.pack_archive(tmp_path / "empty", tmp_path / "y.zip")


def _configure(monkeypatch, formatter: str | None = "/ri/sdretinanet_formatter.py") -> None:
    for task in ("fluid", "onl", "pr", "ga"):
        monkeypatch.setattr(_config.settings, f"{task}_sif", None, raising=False)
    monkeypatch.setattr(_config.settings, "sdretinanet_sif", "/sif/retinanet.sif", raising=False)
    monkeypatch.setattr(_config.settings, "sdretinanet_formatter", formatter, raising=False)
    monkeypatch.setattr(ap, "_spacing_mm", lambda p: (0.0039, 0.0116, 0.12))
    import pydicom
    monkeypatch.setattr(pydicom, "dcmread", lambda *a, **k: types.SimpleNamespace(NumberOfFrames=3))


def _dcm_dir(tmp_path: Path) -> Path:
    d = tmp_path / "in"
    d.mkdir()
    (d / "bscan.dcm").write_bytes(b"FAKE")
    return d


def test_handler_runs_the_sif_and_ships_one_archive(monkeypatch, tmp_path) -> None:
    _configure(monkeypatch)
    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = Path(cmd[cmd.index("--bind") + 1].split(",")[1].rsplit(":", 1)[0])
        bound, conf, main, hrf = _volume()
        native.write_volume(out, bound, conf, LAYERS, main, hrf)
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)
    adapter = ap.ApptainerAdapter()
    assert adapter.supports("sdretinanet")
    work = tmp_path / "work"
    res = adapter.full_volume("sdretinanet", _dcm_dir(tmp_path), "OD", out_dir_override=work)

    cmd = captured["cmd"]
    assert cmd[1] == "run" and "/sif/retinanet.sif" in cmd
    # main.py reads fold_0.json + aot_models_spectralis/ from the working dir
    assert cmd[cmd.index("--pwd") + 1] == "/app"
    assert cmd.index("--pwd") < cmd.index("/sif/retinanet.sif")
    assert cmd[cmd.index("/sif/retinanet.sif") + 1:][:2] == ["/in/bscan.dcm", "/out"]
    assert "--output_formatter" in cmd
    binds = cmd[cmd.index("--bind") + 1]
    assert "/ri/sdretinanet_formatter.py:/opt/ri/sdretinanet_formatter.py:ro" in binds
    assert "sdretinanet_native.py:/opt/ri/sdretinanet_native.py:ro" in binds
    assert res.artifact_names == ["sdretinanet.zip"]
    assert res.output_payload == {"segmentation_file": "sdretinanet.zip", "n_bscans": 3}

    arts = collect_artifacts(work, only=res.artifact_names)
    assert [a.name for a in arts] == ["sdretinanet.zip"]
    assert arts[0].media_type == "application/zip"
    import base64
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(arts[0].content_base64))) as z:
        names = sorted(z.namelist())
    assert names == sorted([f"layers/{b:03d}.yml" for b in range(3)]
                           + [f"lesions/{b:03d}.png" for b in range(3)])


def test_handler_fails_closed_without_native_output(monkeypatch, tmp_path) -> None:
    # e.g. no formatter configured: main.py writes only its csv/npy defaults
    _configure(monkeypatch, formatter=None)
    monkeypatch.setattr(ap, "_exec", lambda cmd, env=None: "")
    with pytest.raises(RuntimeError, match="no layers"):
        ap.ApptainerAdapter().full_volume("sdretinanet", _dcm_dir(tmp_path), "OD",
                                          out_dir_override=tmp_path / "work")


def test_handler_rejects_a_bscan_count_mismatch(monkeypatch, tmp_path) -> None:
    _configure(monkeypatch)

    def fake_exec(cmd, env=None):
        out = Path(cmd[cmd.index("--bind") + 1].split(",")[1].rsplit(":", 1)[0])
        bound, conf, main, hrf = _volume(n_b=2)
        native.write_volume(out, bound, conf, LAYERS, main, hrf)
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)
    with pytest.raises(RuntimeError, match="the DICOM has 3"):
        ap.ApptainerAdapter().full_volume("sdretinanet", _dcm_dir(tmp_path), "OD",
                                          out_dir_override=tmp_path / "work")


def test_slurm_mode_wraps_sdretinanet_in_srun(monkeypatch, tmp_path) -> None:
    _configure(monkeypatch)
    monkeypatch.setattr(_config.settings, "apptainer_use_slurm", True, raising=False)
    monkeypatch.setattr(_config.settings, "apptainer_slurm_account", "optima", raising=False)
    captured: dict = {}

    def fake_exec(cmd, env=None):
        captured["cmd"] = cmd
        out = Path(cmd[cmd.index("--bind") + 1].split(",")[1].rsplit(":", 1)[0])
        bound, conf, main, hrf = _volume()
        native.write_volume(out, bound, conf, LAYERS, main, hrf)
        return ""

    monkeypatch.setattr(ap, "_exec", fake_exec)
    ap.ApptainerAdapter().full_volume("sdretinanet", _dcm_dir(tmp_path), "OD",
                                      out_dir_override=tmp_path / "work")
    cmd = captured["cmd"]
    assert cmd[0] == "srun"
    assert "--job-name=ri-sdretinanet" in cmd
    assert any(c.startswith("--gres=") for c in cmd)


# --- the --output_formatter, loaded the way main.py loads it ------------------

RUNNER = Path(__file__).resolve().parents[1] / "runners" / "sdretinanet" / "format_output.py"
MODEL_ORDER = ["Cyst", "HRF", "ORT", "PED", "Pseudodrusen", "SHRM", "SRF"]  # main.py's channels


def _load_formatter(tmp_path: Path):
    """Mount layout of the ApptainerAdapter: formatter + writer side by side."""
    import importlib.util
    import shutil

    opt = tmp_path / "opt_ri"
    opt.mkdir()
    shutil.copy(RUNNER, opt / "sdretinanet_formatter.py")
    shutil.copy(Path(native.__file__), opt / "sdretinanet_native.py")
    spec = importlib.util.spec_from_file_location("custom_formatter_module",
                                                  opt / "sdretinanet_formatter.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.format_output


def _model_outputs(n_b=2, h=12, w=10):
    lp = np.tile(np.arange(12)[None, :, None] * 2 + 1, (n_b, 1, w))
    std = np.full((n_b, 12, w), 1.5)
    std[0, 3, 4] = 12.0                            # one unreliable A-scan
    prob = np.zeros((n_b, 7, h, w), np.float32)
    c = MODEL_ORDER.index
    prob[1, c("Cyst"), 2:4, 2:4] = 0.9
    prob[1, c("SRF"), 3:5, 3:5] = 0.7              # overlaps the cyst at (3, 3)
    prob[1, c("HRF"), 3, 3] = 0.8                  # HRF on the overlap
    prob[0, c("PED"), 8, 1] = 0.6
    thr = (prob > 0.5).astype(np.uint8)
    return dict(layer_positions=lp, std_deviations=std, final_lesions=prob,
                final_layers=None, thresholded_lesions=thr, original_path=Path("bscan.dcm"))


def test_formatter_writes_switcher_order_and_resolves_overlaps(tmp_path) -> None:
    out = tmp_path / "out"
    _load_formatter(tmp_path)(output_folder=out, **_model_outputs())
    assert native.pack_archive(out, tmp_path / "x.zip", expected_bscans=2) == 2

    chunks = _png_chunks((out / "lesions" / "001.png").read_bytes())
    meta = json.loads(zlib.decompress(chunks[b"zTXt"].split(b"\x00", 1)[1][1:]))
    assert meta["mn"] == MAIN and meta["on"] == ["HRF"]
    raw = zlib.decompress(chunks[b"IDAT"])
    px = lambda y, x: raw[y * 11 + 1 + x]  # noqa: E731  (w=10, one filter byte per row)
    assert px(2, 2) == MAIN.index("Cyst") + 1
    assert px(4, 4) == MAIN.index("SRF") + 1
    assert px(3, 3) == (MAIN.index("Cyst") + 1) | 0x80  # cyst wins (0.9 > 0.7), HRF kept

    yml = (out / "layers" / "000.yml").read_text()
    confid = [ln for ln in yml.splitlines() if "confid" in ln][3]  # IPL-INL
    assert confid.split(":", 1)[1].strip().split(",")[4] == "0"  # std 12 >= 10


def test_formatter_rejects_unexpected_shapes(tmp_path) -> None:
    bad = _model_outputs()
    bad["thresholded_lesions"] = bad["thresholded_lesions"][:, :6]
    with pytest.raises(ValueError, match="expected"):
        _load_formatter(tmp_path)(output_folder=tmp_path / "out", **bad)
