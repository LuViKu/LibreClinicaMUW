# Heidelberg `.E2E` exports: format notes

This page collects what we have verified about Spectralis/Heyex `.E2E` files and how our code reads them, so it is not re-discovered each time. Unless a line says otherwise, everything here was checked on real MUW exports (22 files of the iAMD/drusen study, 2026-10-13), read-only, with no identifying values printed.

Related pages:

- [e2e-multimodal-split.md](e2e-multimodal-split.md) and DR-041: how exports are uploaded and split per series.
- [browser-deidentification.md](browser-deidentification.md): how the browser rewrites the patient chunk.
- `muw-e2e-converter/`: the converter code.

## Container layout

- **Header.** A file starts with the magic `CMDb`. Variants seen in the wild are `MDbDir` and `E2EMultipleVolumeFile`, and `FileKindSniffer` accepts all three.
- **Directories and chunks.** The file holds a chain of directories that list chunks. Each chunk starts with a 60-byte header that begins with `MDbData`. The fields that matter are at these offsets from the start of the header:
  - 32: `patient_db_id`
  - 36: `study_id`
  - 40: `series_id`
  - 44: `slice_id`
  - 48: `ind` (u16)
  - 52: `type` (u32)

  The payload follows at offset 60. The layout matches `oct_converter.readers.binary_structs.e2e_binary.chunk_structure`.
- **Series identity.** A series is identified by the triple `(patient_db_id, study_id, series_id)`. `patient_db_id` is an internal Heyex database key. It is not a name, a hospital ID or a date.

## Chunk types we use

| Type | Content | Used for |
|---|---|---|
| 9 | Patient record, 127 bytes (see below) | subject label; de-identification |
| 3, 11 | Laterality | eye per series (3 first, 11 as fallback) |
| 5 | FILETIME per image | acquisition time (local, naive) |
| 10004 | Acquisition time per volume | acquisition date, fallback |
| 10 | File-wide OLE date | date fallback for en-face-only files |
| 9006 | Scan pattern text, e.g. `OCT ART Volume`, `OCT B-Scan`, `Images`, `Time Sequence` | series kind |
| 9007 | Device modality text: `IR`, `BAF`, `BR`, `IRAF`, `qAF`, `ICGA`, `FA` | series kind |
| `0x40000000` | Image data. `ind 0`: en-face image, **8-bit**. `ind 1`: OCT B-scan, **16-bit** | pixels |

## The patient chunk (type 9) and where the study label is

| Offset | Length | Field |
|---|---|---|
| 0 | 31 | first name |
| 31 | 51 | surname |
| 82 | 15 | title |
| 97 | 4 (u32) | birth date |
| 101 | 1 | sex |
| 102 | 25 | patient ID |

**Observed in the iAMD exports** (value shapes only: L = letter, D = digit):

- **Surname.** Holds the study label (`EIAMD005`, shape `LLLLLDDD`) in every file, and equals the patient's folder name in 20 of 22. One of the other two sits in another subject's folder; the other is in an example folder.
- **First name.** Holds a 2-letter + 4–5-digit ID in 13 files. That looks like Heyex's own patient ID, possibly a clinic number, so treat it as identifying. In 2 files it has a different short ID shape, and in 6 it repeats the study label.
- **Patient ID slot.** Usually empty. When filled, it repeats the first name.
- **`oct-converter`.** Its `patient_id` attribute reads only the patient-ID slot, so it is empty or misleading for most of these files.

**Rule (maintainer, 2026-10-13):**

- The subject is the study label in the **surname** slot.
- The first-name and patient-ID slots are never used as a subject and are never sent or logged.
- Where the export sits in a patient folder, the folder name must agree with the label; otherwise the file is parked for a person to decide.
- The existing per-volume parsers (`e2eParser.ts`, the Export Watcher) read patient ID first, then surname, then first name. On these exports that order picks the wrong value.

**File names are not identifiers.** Heyex names exports `<first letters of the name><counter><letter>` (`EIAMD01D.E2E`, `TEST_02D.E2E`). The same name occurs for different patients.

## What one export contains

A full session for both eyes. Series counts over the 22 files:

| Device modality | Scan pattern | Series |
|---|---|---|
| ICGA | Images | 202 |
| qAF | Time Sequence | 79 |
| IR | OCT ART Volume | 73 |
| IR | OCT B-Scan | 68 |
| BAF | Images | 38 |
| BR | Images | 37 |
| IR | Images | 36 |
| IRAF | Images | 26 |
| FA | Images | 17 |
| qAF | Images | 6 |
| ICGA | Time Sequence | 3 |
| IRAF | Time Sequence | 1 |

Things to know about these series:

- **OCT series are listed under `IR`.** Their device modality is that of the localizer, so the scan pattern is what identifies an OCT series.
- **Typical OCT volumes** are 97 B-scans of 496 × 1024.
- **En-face images** are 1536² or 768².
- **"Images" series** often hold 2–5 images, not one.
- **Some files hold no OCT at all**, only ICGA or IRAF.
- **File sizes** range from 10 to 640 MB.

## Pixel depth and contrast (8-bit vs 16-bit)

- **En-face images** are stored as 8-bit (`ind 0`). Reading them as `uint8` loses nothing.
- **OCT B-scans** are stored as 16-bit values in Heidelberg's unsigned float format, UFloat16 (`ind 1`), holding linear reflectivity.
  - `oct-converter` 0.7.0 decodes them through a lookup table (`uint16_to_ufloat16`) to float.
  - It then applies `vol_intensity_transform`: `log(x + 2.44e-4)`, shifted and scaled by fixed constants into 0–1, then clipped. That is a log compression, **not** the display curve Heyex uses.
- **What our code does with it.**
  - `read_e2e_volume` converts that 0–1 volume to **uint16**, not uint8. This has been the case since 2026-06-24: the IOWA layer segmentation crashed on 8-bit data, and 16-bit keeps the full range.
  - The analysis path therefore keeps 16-bit precision. Its contrast is still oct-converter's log curve.
  - Previews (`render_series_png`, the SLO companion) are 8-bit, scaled from the same log curve.
- **Consequence.** OCT images decoded with `oct-converter` look flatter (lower contrast) than in Heyex, as a colleague reported. Two questions are still open:
  - Should previews use a Heyex-like display curve?
  - What intensity scaling do the analysis models expect: log as now, or the Heidelberg DICOM export's?

  Both deserve a check against a Heyex DICOM export of the same scan before anyone changes a model input.

## Practical notes

- **Use the chunk directory, not the decoders.** `describe_e2e` walks the chunk headers and keeps only offsets. On a 571 MB file it takes 0.8 s and about 100 MB RSS. `read_oct_volume()` takes about 7 s and holds the whole file decoded, several GB.
- **`read_oct_volume()` ordering.** It returns multi-slice volumes in directory order first, then single-image series. `describe_e2e`'s `octVolumeIndex` reproduces that order, because it is the `scan_index` that `/preprocess` takes.
- **B-scan geometry is per series.** The type 10004 records (`posX1`... `scaley`, `centrePosY`) belong to the series whose ids their chunk header carries. `oct-converter`'s `read_all_metadata()['bscan_data']` is one list for the whole file, and the old `read_e2e_volume` took its first `n` entries for every volume, so the second and later volumes of a file got the first volume's lateral and slice spacing (on one real export: slice 0.0654 mm for all four volumes against 0.0619-0.0629 mm from each volume's own records). `convert_series` reads each series' own records.
- **Converting a whole file** (`convert_series`) decodes one series at a time from the chunk index: a 599 MB export with 38 series took 16 s and 0.4 GB resident, where one `read_oct_volume()` takes 7 s and 2.1 GB.
- **OpenCV in containers.** In a slim Python container, `oct-converter` imports OpenCV, which needs display libraries. Install `opencv-python-headless` instead of `opencv-python`.
- **No real patient data in the repository.** Tests use synthetic E2E files (`muw-e2e-converter/tests/e2e_synth.py`). Real exports are only ever read from a read-only mount.
