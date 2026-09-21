"""dicom-scp configuration. Every field maps to DICOM_SCP_<UPPER> (mirrors the
retinal-inference sidecar's pydantic-settings pattern)."""
from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DICOM_SCP_", extra="ignore")

    # --- DICOM Storage SCP listener ---
    ae_title: str = "LIBRECLINICA"
    host: str = "0.0.0.0"
    port: int = 11112
    # Comma-separated allow-list of calling AE titles; empty = accept any (TEST ONLY).
    allowed_calling_ae_titles: str = ""

    # --- Ingest store (shared volume with the app, which serves the previews) ---
    store_path: str = "/var/lib/libreclinica/dicom-ingest"

    # --- App-side internal ingest endpoint (DicomIngestApiController) ---
    # Must match core.dicom.ingest.token on the app side. Both required to run.
    ingest_url: str = ""
    ingest_token: str = ""
    ingest_timeout_s: int = 30

    # --- Modality Worklist (C-FIND) source — the app's internal worklist endpoint ---
    # The Optomed Lumo's DICOM flow is worklist-driven. Optional: when blank the
    # SCP still advertises the MWL context but answers C-FIND with a failure.
    # Shares ingest_token as the secret.
    worklist_url: str = ""
    worklist_timeout_s: int = 15

    # --- Describe endpoint (DR-029) — DICOM files that arrive as uploads ---
    # The app stores an uploaded .dcm on the shared volume and asks here for
    # its tags + a preview, and for the file to be pseudonymised in place.
    # Internal only (never published by compose); shares ingest_token. 0 = off.
    describe_host: str = "0.0.0.0"
    describe_port: int = 8081
    # Comma-separated roots a described path may lie under. Blank = the
    # C-STORE store above plus the app's unified ingest store.
    describe_roots: str = ""
    # Private (vendor) tags carry calibration a study may need, so they stay
    # unless a deployment says otherwise.
    deidentify_drop_private: bool = False

    @property
    def allowed_calling_aes(self) -> set[str]:
        return {t.strip() for t in self.allowed_calling_ae_titles.split(",") if t.strip()}

    @property
    def describe_root_paths(self) -> list[Path]:
        raw = [r.strip() for r in self.describe_roots.split(",") if r.strip()]
        if not raw:
            raw = [self.store_path, "/var/lib/libreclinica/ingest"]
        return [Path(r) for r in raw]


settings = Settings()
