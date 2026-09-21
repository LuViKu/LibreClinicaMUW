"""Hand off the received-image metadata + shared-store paths to the app's
internal ingest endpoint (DicomIngestApiController), shared-secret gated."""
from __future__ import annotations

import logging

import requests

LOG = logging.getLogger("dicom_scp.ingest")


def post_ingest(url: str, token: str, payload: dict, timeout_s: int) -> bool:
    """POST the payload; return True on 2xx. Never raises — the caller maps
    False onto a C-STORE failure status so the modality can retry."""
    try:
        resp = requests.post(
            url,
            json=payload,
            headers={"X-MUW-Dicom-Token": token, "Content-Type": "application/json"},
            timeout=timeout_s,
        )
    except requests.RequestException as exc:
        LOG.error("ingest POST failed to reach the app: %s", type(exc).__name__)
        return False
    if resp.status_code // 100 == 2:
        return True
    # Never echo the (device-derived) response body into the log.
    LOG.error("ingest POST rejected by the app: HTTP %s", resp.status_code)
    return False
