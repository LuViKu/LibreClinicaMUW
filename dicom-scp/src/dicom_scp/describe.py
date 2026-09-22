"""HTTP entry for DICOM files that arrive as uploads rather than over C-STORE (DR-029).

The app has no DICOM parser; this sidecar has one. A file the upload page
stored on the shared volume is handed over by path, and the sidecar answers
with the same identity/exam tags it posts for a received C-STORE, a preview
PNG written next to the file, and the file itself pseudonymised in place (see
``deidentify``). The app keeps ownership of the database row — the sidecar
reads and rewrites a file, nothing more.

Posture: internal only. The port is never published by compose, the request
must carry the same shared secret as the ingest hand-off, and a path is acted
on only when it resolves to a regular file under one of the configured roots
— the store this sidecar writes and the unified ingest store the app writes.
Paths never reach the routine log: they carry an operator-supplied filename
fragment.
"""
from __future__ import annotations

import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from pydicom.errors import InvalidDicomError

from . import config, deidentify, store, tags

LOG = logging.getLogger("dicom_scp.describe")

TOKEN_HEADER = "X-MUW-Dicom-Token"
MAX_BODY = 64 * 1024


def confine(raw: str | None, roots: list[Path]) -> Path | None:
    """The file ``raw`` names, if it is a regular file under one of ``roots``."""
    if not raw or not isinstance(raw, str):
        return None
    try:
        candidate = Path(raw).resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    if not candidate.is_file():
        return None
    for root in roots:
        try:
            real_root = root.resolve()
        except (OSError, RuntimeError):
            continue
        if candidate == real_root or real_root in candidate.parents:
            return candidate
    return None


def describe_file(path: Path, pseudonym: str | None, drop_private: bool) -> dict:
    """Pseudonymise, preview, and describe one file. Raises on a non-DICOM file."""
    ds, changed = deidentify.rewrite(path, pseudonym, drop_private)
    png = store.render_preview(ds, path.with_suffix(".png"))
    payload = tags.describe(ds)
    payload["previewPngPath"] = str(png) if png else None
    payload["identityRemoved"] = True
    payload["changedTags"] = len(changed)
    return payload


class DescribeHandler(BaseHTTPRequestHandler):
    server_version = "dicom-scp-describe/1"

    # The default handler logs every request line, path included, at INFO.
    def log_message(self, format: str, *args) -> None:  # noqa: A002 — BaseHTTPRequestHandler's name
        LOG.debug("describe request handled")

    def _json(self, status: int, body: dict) -> None:
        raw = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self) -> None:  # noqa: N802 — http.server's naming
        settings = config.settings
        if self.path != "/describe":
            self._json(404, {"message": "not found"})
            return
        if not settings.ingest_token or self.headers.get(TOKEN_HEADER) != settings.ingest_token:
            self._json(401, {"message": "missing or wrong token"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            self._json(400, {"message": "a JSON body is required"})
            return
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._json(400, {"message": "malformed JSON"})
            return
        if not isinstance(body, dict):
            self._json(400, {"message": "malformed JSON"})
            return

        path = confine(body.get("path"), settings.describe_root_paths)
        if path is None:
            self._json(403, {"message": "path is not a file under the ingest store"})
            return
        pseudonym = body.get("pseudonym")
        if pseudonym is not None and not isinstance(pseudonym, str):
            self._json(400, {"message": "pseudonym must be a string"})
            return

        try:
            payload = describe_file(path, pseudonym, settings.deidentify_drop_private)
        except InvalidDicomError:
            self._json(422, {"message": "not a DICOM file"})
            return
        except Exception as exc:  # noqa: BLE001 — never let one bad file kill the thread
            LOG.error("describe failed (%s)", type(exc).__name__)
            self._json(500, {"message": "could not process the file"})
            return
        self._json(200, payload)


def start_in_background(settings: config.Settings) -> ThreadingHTTPServer | None:
    """Serve ``/describe`` on a daemon thread; None when the port is 0 (disabled)."""
    if settings.describe_port <= 0:
        LOG.info("describe endpoint: DISABLED (DICOM_SCP_DESCRIBE_PORT=0)")
        return None
    server = ThreadingHTTPServer((settings.describe_host, settings.describe_port), DescribeHandler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, name="dicom-scp-describe", daemon=True)
    thread.start()
    LOG.info("describe endpoint listening on %s:%s (roots: %s)",
             settings.describe_host, server.server_address[1],
             ", ".join(str(r) for r in settings.describe_root_paths))
    return server
