FROM python:3.11-slim
WORKDIR /app
# opencv-python (pulled in transitively by oct_converter) loads libxcb,
# libGL and libglib at import time. The python:slim image ships none of
# them, so `import cv2` dies with `libxcb.so.1: cannot open shared
# object file` during the first E2E ingestion. Install the runtime libs
# alongside curl.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl libxcb1 libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Build context is the repo root (see compose.yaml). retinal-inference
# requirements + sources live under retinal-inference/; the DR-024 split
# converter lives under muw-e2e-converter/ (sibling).
COPY retinal-inference/requirements.txt /app/retinal-inference/requirements.txt
RUN pip install --no-cache-dir -r /app/retinal-inference/requirements.txt

# DR-024 — install the muw-e2e-converter package so /preprocess works in
# this image. The cluster's bare-metal cn5 deployment does NOT install
# this package (see docs/development/modernization/retinal-inference-remote-deployment.md);
# this Docker image is for the LOCAL app-VM retinal-preprocess service
# only, so the converter belongs here.
COPY muw-e2e-converter/pyproject.toml /app/muw-e2e-converter/pyproject.toml
COPY muw-e2e-converter/src/ /app/muw-e2e-converter/src/
RUN pip install --no-cache-dir -e /app/muw-e2e-converter

COPY retinal-inference/src/ /app/src/
# 2026-06-22 — bundle the backfill script so operators can derive
# projections for jobs that landed before the local /derive chain
# was wired. Lives next to the runner code in the source tree;
# imports `projection_fluid` from the same image-installed package.
COPY retinal-inference/runners/fluid/backfill_projections.py /app/runners/fluid/backfill_projections.py
COPY retinal-inference/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# DR-022: shared host-bind for /run tempdirs. Sidecar and runners mount the
# same path so the runner sees the sidecar's TemporaryDirectory natively.
RUN mkdir -p /var/lib/retinal-inference/tmp

ENV PYTHONPATH=/app/src
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["./docker-entrypoint.sh"]
