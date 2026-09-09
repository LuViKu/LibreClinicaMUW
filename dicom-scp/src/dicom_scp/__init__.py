"""dicom-scp — DICOM C-STORE Storage SCP for the HealthAEye study (DR-025).

Receives fundus images the Optomed modality pushes over C-STORE, writes the
Part-10 object + a rendered preview into the shared ingest store, and hands off
to the app's internal ingest endpoint, which enqueues an image_ingest row
(source_kind='dicom'). Plain C-STORE only — a Modality Worklist SCP is a
deferred follow-up.
"""

__all__ = ["config", "server", "store", "tags", "ingest_client"]
