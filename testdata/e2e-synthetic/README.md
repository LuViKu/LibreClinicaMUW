# Synthetic Heidelberg `.E2E` fixtures

Tiny `.e2e` files built by `muw-e2e-converter/tests/e2e_synth.py`, each with its expected
`describe_e2e` output as `<name>.describe.json`. They are **synthetic**: constant pixel values,
made-up labels (`SYNTH001` ...), and no real data, nothing derived from a real export.

| File | Covers |
|---|---|
| `oct-volume-localizer` | OD OCT volume (5 B-scans) with its IR localizer; surname is a label token |
| `oct-line-scan` | OCT line scans of both eyes; no patient chunk (no header label) |
| `enface-multi-image` | BAF / IRAF / FA / BR series with several images; surname is not a token |
| `time-sequence` | qAF and ICGA time sequences; two agreeing patient chunks; microseconds in a time |
| `both-eyes-mixed` | ids out of eye/modality/time order, unknown modality, no eye, no time, equal times, a series without an image |
| `no-oct` | no OCT at all; empty surname slot |
| `large-pixels` | about 180 KB of pixel data, so tests can show that a parser reads only a few KB |

Both parsers are asserted against these files, so they cannot drift apart:

- Python: `muw-e2e-converter/tests/test_parity_fixtures.py` (`describe_e2e(file) == json`).
- Browser: `web/src/spa/src/lib/__tests__/e2eSeries.test.ts` (`lib/e2eSeries.ts`).

## Regenerate

Only when the fixtures are meant to change (the JSON files are the contract):

```sh
pip install -e muw-e2e-converter[dev]
python testdata/e2e-synthetic/generate.py
```

`generate.py` rewrites every `.e2e` and `.describe.json` from the definitions in the script, using
the real `describe_e2e`. Review the JSON diff before committing.
