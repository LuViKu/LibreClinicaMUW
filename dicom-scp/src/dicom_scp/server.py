"""pynetdicom Storage SCP: accept a fundus C-STORE, persist it, hand off to the
app. Plain C-STORE — no Modality Worklist (deferred, DR-025)."""
from __future__ import annotations

import logging

from pynetdicom import AE, AllStoragePresentationContexts, VerificationPresentationContexts, evt

from . import config, store, tags
from .ingest_client import post_ingest

LOG = logging.getLogger("dicom_scp.server")

# DICOM C-STORE response statuses.
_SUCCESS = 0x0000
_PROCESSING_FAILURE = 0xA700  # "out of resources" — modality should retry later.


def _calling_ae(event) -> str:
    ae = event.assoc.requestor.ae_title
    return ae.strip() if isinstance(ae, str) else str(ae).strip()


def _handle_store(event) -> int:
    settings = config.settings
    ds = event.dataset
    calling_ae = _calling_ae(event)

    try:
        dcm_path, png_path = store.persist(
            ds, event.file_meta, settings.store_path, tags.study_date_iso(ds))
    except Exception as exc:  # noqa: BLE001
        LOG.error("store failed (%s) — refusing C-STORE", type(exc).__name__)
        return _PROCESSING_FAILURE

    payload = tags.extract(ds, calling_ae)
    payload["dicomPath"] = dcm_path
    payload["previewPngPath"] = png_path

    if not post_ingest(settings.ingest_url, settings.ingest_token,
                       payload, settings.ingest_timeout_s):
        return _PROCESSING_FAILURE

    # AE titles are DICOM-constrained (<=16 chars, no control chars) — safe to log.
    LOG.info("stored + enqueued a fundus image from calling AE '%s'", calling_ae)
    return _SUCCESS


def build_ae() -> AE:
    settings = config.settings
    ae = AE(ae_title=settings.ae_title)
    # Accept every storage SOP class — we don't know the exact class the Optomed
    # emits until a real sample; the ingest is object-agnostic anyway. Plus
    # Verification (C-ECHO) so a modality's "test connection" succeeds — pynetdicom
    # auto-responds to C-ECHO on a supported Verification context.
    ae.supported_contexts = AllStoragePresentationContexts + VerificationPresentationContexts
    allowed = settings.allowed_calling_aes
    if allowed:
        ae.require_calling_aet = list(allowed)
    return ae


def serve() -> None:
    settings = config.settings
    if not settings.ingest_url or not settings.ingest_token:
        raise SystemExit(
            "dicom-scp: DICOM_SCP_INGEST_URL and DICOM_SCP_INGEST_TOKEN are required")
    ae = build_ae()
    LOG.info("dicom-scp Storage SCP listening on %s:%s as AE '%s' (allow-list: %s)",
             settings.host, settings.port, settings.ae_title,
             ", ".join(sorted(settings.allowed_calling_aes)) or "any")
    ae.start_server((settings.host, settings.port), block=True,
                    evt_handlers=[(evt.EVT_C_STORE, _handle_store)])
