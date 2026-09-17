# dicom-scp — DICOM C-STORE Storage SCP (DR-025).
#
# Receives fundus C-STORE from the Optomed modality, persists the Part-10 +
# a preview to the shared ingest store, and hands off to the app's internal
# ingest endpoint. Build CONTEXT is the repo root (see compose.yaml), so the
# COPY paths are dicom-scp/-relative.
FROM python:3.11-slim

WORKDIR /app

COPY dicom-scp/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY dicom-scp/src /app/src
ENV PYTHONPATH=/app/src

# DICOM Storage SCP listener (map to the published host port in compose).
EXPOSE 11112

CMD ["python", "-m", "dicom_scp"]
