"""dicom-scp configuration. Every field maps to DICOM_SCP_<UPPER> (mirrors the
retinal-inference sidecar's pydantic-settings pattern)."""
from __future__ import annotations

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

    @property
    def allowed_calling_aes(self) -> set[str]:
        return {t.strip() for t in self.allowed_calling_ae_titles.split(",") if t.strip()}


settings = Settings()
