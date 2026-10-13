"""convert_series on SYNTHETIC .E2E files (see convert_synth.py): one DICOM per series.

No real export is used. The OCT parity test compares with ``read_e2e_volume`` (oct-converter's
``read_oct_volume``), the path the analysis has used so far.
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path

import numpy as np
import pydicom
import pytest
from convert_synth import DECOY_FIRST_NAME, DECOY_PATIENT_ID, Item, Ser, build, pixels
from oct_converter.readers import E2E

from muw_e2e_converter import convert_series, describe_e2e, read_e2e_volume
from muw_e2e_converter import convert as convert_mod
from muw_e2e_converter import series as series_mod

T = datetime
LABEL = "EIAMD005"


def _bscans(n: int, h: int = 12, w: int = 20, seed: int = 100) -> list[Item]:
    return [Item(1, 2 * i, h, w, seed + i) for i in range(n)]


def _series() -> list[Ser]:
    return [
        # OD OCT volume with IR localizer
        Ser((1, 1, 1), "OCT ART Volume", "IR", "R", T(2026, 9, 30, 10, 0, 0),
            _bscans(5) + [Item(0, 0, 16, 16, 1)], geometry=True),
        # OD OCT line scan
        Ser((1, 1, 2), "OCT B-Scan", "IR", "R", T(2026, 9, 30, 10, 5, 0),
            [Item(1, 0, 12, 20, 200), Item(0, 0, 16, 16, 2)], geometry=True),
        # OS qAF time sequence, 3 frames
        Ser((1, 1, 3), "Time Sequence", "qAF", "L", T(2026, 9, 30, 10, 20, 0),
            [Item(0, i, 16, 24, 300 + i) for i in range(3)]),
        # OD ICGA single image
        Ser((1, 1, 4), "Images", "ICGA", "R", T(2026, 9, 30, 11, 0, 0), [Item(0, 0, 18, 18, 400)]),
        # no eye, no time
        Ser((1, 1, 5), "Images", "XYZ", None, None, [Item(0, 0, 10, 10, 500)]),
        # OS OCT volume
        Ser((1, 1, 6), "OCT ART Volume", "IR", "L", T(2026, 9, 30, 10, 30, 0),
            _bscans(4, seed=600) + [Item(0, 0, 16, 16, 3)], geometry=True),
    ]


@pytest.fixture
def e2e(tmp_path: Path) -> Path:
    return build(tmp_path / "a.e2e", _series())


def _keys(path: Path) -> dict[str, dict]:
    return {s["seriesKey"]: s for s in describe_e2e(path)["series"]}


def _by_ids(path: Path) -> dict[str, str]:
    """pattern/time-agnostic lookup: key of the series with these frames+laterality"""
    out = {}
    for k, s in _keys(path).items():
        out[(s["seriesModality"], s["laterality"], s["frames"])] = k
    return out


def test_ufloat16_lut_matches_the_reference_for_every_value(e2e: Path) -> None:
    reference = E2E(str(e2e))
    lut = convert_mod._ufloat16_lut()
    expected = np.array([reference.uint16_to_ufloat16(i) for i in range(65536)])
    assert np.array_equal(lut, expected)


def test_oct_pixels_equal_read_e2e_volume(e2e: Path, tmp_path: Path) -> None:
    desc = describe_e2e(e2e)
    out = tmp_path / "out"
    octs = [s for s in desc["series"] if s["seriesModality"] == "OCT"]
    assert len(octs) == 2
    convert_series(e2e, LABEL, [s["seriesKey"] for s in octs], out)
    for s in octs:
        ds = pydicom.dcmread(str(out / f"{s['seriesKey']}.dcm"))
        got = np.frombuffer(ds.PixelData, dtype=np.uint16).reshape(
            int(ds.NumberOfFrames), ds.Rows, ds.Columns)
        today = read_e2e_volume(e2e, scan_index=s["octVolumeIndex"]).volume_u8
        assert today.dtype == np.uint16
        assert np.array_equal(got, today)


def test_oct_line_scan_is_one_frame_with_the_same_writer(e2e: Path, tmp_path: Path) -> None:
    key = _by_ids(e2e)[("OCT_LINE", "OD", 1)]
    [res] = convert_series(e2e, LABEL, [key], tmp_path / "o")
    ds = pydicom.dcmread(str(tmp_path / "o" / res["file"]))
    assert ds.SOPClassUID == "1.2.840.10008.5.1.4.1.1.77.1.5.4"
    assert int(ds.NumberOfFrames) == 1 and ds.BitsAllocated == 16
    assert res["frames"] == 1 and "geometryFile" not in res


def test_oct_volume_metadata_geometry_and_companion(e2e: Path, tmp_path: Path) -> None:
    key = _by_ids(e2e)[("OCT", "OS", 4)]
    [res] = convert_series(e2e, LABEL, [key], tmp_path / "o")
    ds = pydicom.dcmread(str(tmp_path / "o" / res["file"]))
    assert (ds.Laterality, ds.ImageLaterality) == ("OS", "L")
    assert (ds.AcquisitionDate, ds.AcquisitionTime) == ("20260930", "103000")
    assert ds.Manufacturer == "Heidelberg Retina Angiograph"
    assert ds.ManufacturerModelName == "SPECTRALISHX202"
    assert (ds.Rows, ds.Columns, int(ds.NumberOfFrames)) == (12, 20, 4)
    assert ds.PixelSpacing[0] == pytest.approx(0.00387, rel=1e-3)
    assert ds.SOPClassUID == res["sopClassUid"] == convert_mod.OPT_SOP_CLASS
    geo = json.loads((tmp_path / "o" / res["geometryFile"]).read_text())
    assert geo["bscan"]["dim_z_bscans"] == 4 and geo["bscan"]["dim_x_ascans"] == 20
    assert geo["fundus"]["width_px"] == 16 and geo["source_format"] == "e2e"
    assert len(geo["bscan_positions_fundus_px"]) == 4


def test_enface_is_ophthalmic_photography_8bit_multiframe(e2e: Path, tmp_path: Path) -> None:
    key = _by_ids(e2e)[("QAF", "OS", 3)]
    [res] = convert_series(e2e, LABEL, [key], tmp_path / "o")
    ds = pydicom.dcmread(str(tmp_path / "o" / res["file"]))
    assert ds.SOPClassUID == res["sopClassUid"] == convert_mod.OP_8BIT_SOP_CLASS
    assert ds.Modality == "OP" and ds.PhotometricInterpretation == "MONOCHROME2"
    assert ds.file_meta.TransferSyntaxUID == "1.2.840.10008.1.2.1"  # explicit VR LE, uncompressed
    assert (ds.BitsAllocated, ds.BitsStored, ds.HighBit, ds.SamplesPerPixel) == (8, 8, 7, 1)
    assert (int(ds.NumberOfFrames), ds.Rows, ds.Columns) == (3, 16, 24)
    assert ds.Laterality == "OS" and ds.ImageLaterality == "L"
    assert (ds.AcquisitionDate, ds.AcquisitionTime) == ("20260930", "102000")
    assert ds.Manufacturer == "Heidelberg Retina Angiograph"
    assert ds.SeriesDescription == "Time Sequence qAF"
    got = np.frombuffer(ds.PixelData, dtype=np.uint8).reshape(3, 16, 24)
    for i in range(3):
        assert np.array_equal(got[i], pixels(Item(0, i, 16, 24, 300 + i)))
    assert res["frames"] == 3 and res["bytes"] == (tmp_path / "o" / res["file"]).stat().st_size
    assert res["sha256"] == hashlib.sha256((tmp_path / "o" / res["file"]).read_bytes()).hexdigest()


def test_series_without_eye_and_time_has_no_laterality_or_date(e2e: Path, tmp_path: Path) -> None:
    key = next(k for k, s in _keys(e2e).items() if s["seriesModality"] == "OTHER")
    [res] = convert_series(e2e, LABEL, [key], tmp_path / "o")
    ds = pydicom.dcmread(str(tmp_path / "o" / res["file"]))
    assert "Laterality" not in ds and "ImageLaterality" not in ds
    assert "AcquisitionDate" not in ds and ds.StudyDate == ""
    assert "NumberOfFrames" not in ds  # single image


def _all_strings(ds: pydicom.Dataset) -> list[str]:
    out: list[str] = []
    for el in ds.iterall():
        if el.VR == "SQ":
            continue
        v = el.value
        if isinstance(v, bytes):
            if el.tag != (0x7FE0, 0x0010):
                out.append(v.decode("latin-1"))
        elif isinstance(v, (list, pydicom.multival.MultiValue)):
            out.extend(str(x) for x in v)
        else:
            out.append(str(v))
    return out


def test_no_identifying_data_beyond_the_label(e2e: Path, tmp_path: Path) -> None:
    keys = list(_keys(e2e))
    convert_series(e2e, LABEL, keys, tmp_path / "o")
    for f in sorted((tmp_path / "o").glob("*.dcm")):
        raw = f.read_bytes()
        for decoy in (DECOY_FIRST_NAME, DECOY_PATIENT_ID):
            assert decoy.encode() not in raw
        ds = pydicom.dcmread(str(f))
        assert str(ds.PatientID) == LABEL and str(ds.PatientName) == LABEL
        for kw in ("PatientBirthDate", "PatientSex", "PatientAge", "OtherPatientIDs",
                   "InstitutionName", "OperatorsName", "ReferringPhysicianName"):
            assert str(getattr(ds, kw, "")) == "", kw
        # the only occurrences of anything label-like are the two patient attributes
        assert [s for s in _all_strings(ds) if LABEL in s] == [LABEL, LABEL]


def test_label_is_cleaned_like_the_dicom_sidecar() -> None:
    assert convert_mod.clean_label("  EIAMD^005=x\\y ") == "EIAMD005xy"
    assert convert_mod.clean_label(None) == ""
    assert len(convert_mod.clean_label("x" * 200)) == 64


def test_blank_label_gives_blank_patient_identity(e2e: Path, tmp_path: Path) -> None:
    key = next(iter(_keys(e2e)))
    [res] = convert_series(e2e, None, [key], tmp_path / "o")
    ds = pydicom.dcmread(str(tmp_path / "o" / res["file"]))
    assert str(ds.PatientID) == "" and str(ds.PatientName) == ""


def test_reconverting_is_byte_identical_and_uids_depend_on_file_and_series(
        e2e: Path, tmp_path: Path) -> None:
    keys = list(_keys(e2e))
    a = convert_series(e2e, LABEL, keys, tmp_path / "a")
    b = convert_series(e2e, LABEL, keys, tmp_path / "b")
    assert [r["sha256"] for r in a] == [r["sha256"] for r in b]
    uids = {}
    for r in a:
        ds = pydicom.dcmread(str(tmp_path / "a" / r["file"]), stop_before_pixels=True)
        uids[r["seriesKey"]] = (str(ds.StudyInstanceUID), str(ds.SeriesInstanceUID),
                                str(ds.SOPInstanceUID))
    assert len({u[1] for u in uids.values()}) == len(keys)  # a series UID per series
    assert len({u[2] for u in uids.values()}) == len(keys)
    assert len({u[0] for u in uids.values()}) == 1  # one study per export
    # another export (different bytes) gets other UIDs
    other = build(tmp_path / "b.e2e", _series(), surname="EIAMD006")
    c = convert_series(other, LABEL, [keys[0]], tmp_path / "c")
    ds = pydicom.dcmread(str(tmp_path / "c" / c[0]["file"]), stop_before_pixels=True)
    assert str(ds.SeriesInstanceUID) != uids[keys[0]][1]
    assert str(ds.StudyInstanceUID) != uids[keys[0]][0]


def test_unknown_key_writes_nothing(e2e: Path, tmp_path: Path) -> None:
    out = tmp_path / "o"
    with pytest.raises(KeyError):
        convert_series(e2e, LABEL, ["s0", "s999"], out)
    assert not out.exists()


@pytest.mark.parametrize("keys", [[], ["../x"], ["s1; rm"], [""], ["S1"]])
def test_bad_key_list_is_a_value_error(e2e: Path, tmp_path: Path, keys: list[str]) -> None:
    with pytest.raises(ValueError):
        convert_series(e2e, LABEL, keys, tmp_path / "o")


def test_one_pass_no_read_oct_volume_and_one_index(e2e: Path, tmp_path: Path, monkeypatch) -> None:
    def boom(self, *a, **k):  # the all-in-memory reader must never be used by the split
        raise AssertionError("read_oct_volume called")

    scans: list[int] = []
    real_scan = series_mod._scan

    def counting(path):
        scans.append(1)
        return real_scan(path)

    monkeypatch.setattr(E2E, "read_oct_volume", boom)
    monkeypatch.setattr(series_mod, "_scan", counting)
    keys = [s["seriesKey"] for s in describe_e2e(e2e)["series"]]  # builds the index (1 scan)
    convert_series(e2e, LABEL, keys, tmp_path / "o")  # reuses it
    assert len(scans) == 1


def test_each_series_is_decoded_once_and_released(e2e: Path, tmp_path: Path, monkeypatch) -> None:
    decoded: list[str] = []
    real_oct, real_en = convert_mod.decode_oct_u16, convert_mod._decode_enface

    def oct_spy(idx, images):
        decoded.append("oct")
        return real_oct(idx, images)

    def en_spy(idx, images):
        decoded.append("en")
        return real_en(idx, images)

    monkeypatch.setattr(convert_mod, "decode_oct_u16", oct_spy)
    monkeypatch.setattr(convert_mod, "_decode_enface", en_spy)
    keys = [s["seriesKey"] for s in describe_e2e(e2e)["series"]]
    convert_series(e2e, LABEL, keys, tmp_path / "o")
    assert sorted(decoded) == ["en"] * 3 + ["oct"] * 3  # one decode per series, none for the rest


def test_only_requested_series_are_decoded_and_written(e2e: Path, tmp_path: Path) -> None:
    keys = [s["seriesKey"] for s in describe_e2e(e2e)["series"]]
    res = convert_series(e2e, LABEL, [keys[0], keys[0], keys[2]], tmp_path / "o")
    assert [r["seriesKey"] for r in res] == [keys[0], keys[2]]
    assert sorted(p.name for p in (tmp_path / "o").glob("*.dcm")) == sorted(
        f"{k}.dcm" for k in (keys[0], keys[2]))


def test_corrupt_pixel_payload_is_reported(e2e: Path, tmp_path: Path, monkeypatch) -> None:
    key = _by_ids(e2e)[("ICGA", "OD", 1)]
    monkeypatch.setattr(convert_mod, "_verify",
                        lambda *a, **k: (_ for _ in ()).throw(RuntimeError("mismatch")))
    with pytest.raises(RuntimeError):
        convert_series(e2e, LABEL, [key], tmp_path / "o")


def test_truncated_file_series_is_not_offered(tmp_path: Path) -> None:
    path = build(tmp_path / "t.e2e", _series())
    data = path.read_bytes()
    path.write_bytes(data[: len(data) - 200])  # cut into the last chunks
    # describe drops series whose payload runs past the end; convert only knows described series
    keys = [s["seriesKey"] for s in describe_e2e(path)["series"]]
    out = convert_series(path, LABEL, keys, tmp_path / "o")
    assert len(out) == len(keys)
