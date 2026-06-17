#!/usr/bin/env python3
"""Validate a running retinal-inference ``/run`` endpoint against vendor reference scans.

Each model ships a reference ``bscan.dcm`` (+ expected outputs) on the OPTIMA
cluster under ``$PI = /home/optima/octreader/Processor_Implementations``. This
script POSTs one to ``POST /run`` for a given task and prints the returned
envelope — primary metric, payload, and the artifact filenames/sizes — so you can
eyeball that the model accepted the synthesized DICOM and produced sane output on
the **first real run** (the step the file listing alone can't verify).

Stdlib only (no requests/httpx) so it runs in the bare cluster venv. Run it ON the
cluster — or any host that can both read the reference DICOM and reach ``/run``.

Examples
--------
    # confirmed reference defaults (onl, fluid):
    python scripts/validate_against_reference.py --task onl \
        --base-url http://localhost:8000 --token "$RETINAL_INFERENCE_AUTH_TOKEN"

    # explicit DICOM (bmeis / ga, or any scan you want to push):
    python scripts/validate_against_reference.py --task bmeis \
        --dcm /path/to/bscan.dcm --laterality OD --token "$TOK"

    # assert the primary metric is within tolerance (CI / regression):
    python scripts/validate_against_reference.py --task onl \
        --expect-metric 220 --tol 15 --token "$TOK"

Exit codes: 0 = ran and (if asserted) within tolerance; 2 = HTTP/transport error;
3 = metric assertion failed; 4 = bad usage (e.g. no reference DICOM for the task).
"""

from __future__ import annotations

import argparse
import binascii
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

# Reference DICOMs that ship with each model on the OPTIMA cluster. Confirmed
# present in the June 2026 recursive listing. bmeis/ga have no enumerated
# per-test bscan.dcm here — pass --dcm explicitly (any Spectralis volume works;
# the onl reference below is a fine generic Spectralis scan for a smoke test).
PI = "/home/optima/octreader/Processor_Implementations"
DEFAULT_REFERENCES = {
    "onl": f"{PI}/sese_onl/test/TestCase1/Inputs/BscanPath/bscan.dcm",
    "fluid": f"{PI}/sese_retinsight_fluid/test/TestCase1/Inputs/BscanPath/bscan.dcm",
    # "bmeis": pass --dcm  (sese_pr test-case inner files were not enumerated)
    # "ga":    pass --dcm  (needs a Spectralis 49/97-bscan volume + GA enabled)
}


def _post_run(
    base_url: str,
    token: str,
    task: str,
    laterality: str,
    dcm_path: Path,
    timeout: float,
    idempotency_key: str | None,
) -> dict:
    """POST a multipart /run request and return the parsed JSON envelope."""
    boundary = "----muwref" + binascii.hexlify(os.urandom(8)).decode()
    sep = f"--{boundary}".encode()

    def _field(name: str, value: str) -> bytes:
        return (
            sep
            + b"\r\n"
            + f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
            + value.encode()
            + b"\r\n"
        )

    body = bytearray()
    body += _field("task", task)
    body += _field("laterality", laterality)
    body += sep + b"\r\n"
    body += (
        f'Content-Disposition: form-data; name="file"; filename="{dcm_path.name}"\r\n'
        f"Content-Type: application/dicom\r\n\r\n"
    ).encode()
    body += dcm_path.read_bytes()
    body += b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        base_url.rstrip("/") + "/run", data=bytes(body), method="POST"
    )
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("X-MUW-Inference-Token", token)
    if idempotency_key:
        req.add_header("Idempotency-Key", idempotency_key)

    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--task", required=True, choices=["fluid", "onl", "bmeis", "ga"])
    p.add_argument("--base-url", default="http://localhost:8000", help="sidecar base URL")
    p.add_argument(
        "--token",
        default=os.environ.get("RETINAL_INFERENCE_AUTH_TOKEN"),
        help="X-MUW-Inference-Token (or env RETINAL_INFERENCE_AUTH_TOKEN)",
    )
    p.add_argument("--dcm", help="path to a reference bscan.dcm (overrides the built-in default)")
    p.add_argument("--laterality", default="OD", choices=["OD", "OS"])
    p.add_argument("--expect-metric", type=float, help="assert primary_metric_value ~= this")
    p.add_argument("--tol", type=float, default=0.0, help="absolute tolerance for --expect-metric")
    p.add_argument("--timeout", type=float, default=3600.0, help="request timeout (s); GPU jobs are slow")
    p.add_argument("--idempotency-key", help="optional Idempotency-Key header")
    args = p.parse_args(argv)

    if not args.token:
        print("ERROR: no token (pass --token or set RETINAL_INFERENCE_AUTH_TOKEN)", file=sys.stderr)
        return 4

    dcm = Path(args.dcm) if args.dcm else (
        Path(DEFAULT_REFERENCES[args.task]) if args.task in DEFAULT_REFERENCES else None
    )
    if dcm is None:
        print(
            f"ERROR: no built-in reference DICOM for task '{args.task}'. Pass --dcm "
            f"(any Spectralis bscan.dcm; e.g. {DEFAULT_REFERENCES['onl']}).",
            file=sys.stderr,
        )
        return 4
    if not dcm.is_file():
        print(f"ERROR: reference DICOM not found: {dcm}", file=sys.stderr)
        return 4

    print(f"POST {args.base_url.rstrip('/')}/run  task={args.task} laterality={args.laterality}")
    print(f"  scan: {dcm}  ({dcm.stat().st_size / 1e6:.1f} MB)")
    try:
        env = _post_run(
            args.base_url, args.token, args.task, args.laterality, dcm,
            args.timeout, args.idempotency_key,
        )
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        print(f"HTTP {e.code} {e.reason}\n{detail}", file=sys.stderr)
        return 2
    except (urllib.error.URLError, OSError) as e:
        print(f"transport error: {e}", file=sys.stderr)
        return 2

    metric = env.get("primary_metric_value")
    unit = env.get("primary_metric_unit", "")
    print("\n--- result ---------------------------------------------------------")
    print(f"  model_version : {env.get('model_version')}")
    print(f"  primary_metric: {metric} {unit}")
    print(f"  confidence    : {env.get('confidence')}")
    print(f"  output_payload: {json.dumps(env.get('output_payload', {}), indent=2)}")
    artifacts = env.get("artifacts", [])
    print(f"  artifacts ({len(artifacts)}):")
    for a in artifacts:
        approx = int(len(a.get("content_base64", "")) * 3 / 4)
        print(f"    - {a.get('name')}  [{a.get('media_type')}]  ~{approx} bytes")
    print("--------------------------------------------------------------------")

    if args.expect_metric is not None:
        if metric is None:
            print("ASSERT FAIL: no primary_metric_value in response", file=sys.stderr)
            return 3
        delta = abs(float(metric) - args.expect_metric)
        ok = delta <= args.tol
        print(
            f"assert metric {metric} ~= {args.expect_metric} (tol {args.tol}): "
            f"{'PASS' if ok else 'FAIL'} (|Δ|={delta:.4g})"
        )
        if not ok:
            return 3

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
