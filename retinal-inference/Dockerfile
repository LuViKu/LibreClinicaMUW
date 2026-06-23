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
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY src/ ./src/
# 2026-06-22 — bundle the backfill script so operators can derive
# projections for jobs that landed before the local /derive chain
# was wired. Lives next to the runner code in the source tree;
# imports `projection_fluid` from the same image-installed package.
COPY runners/fluid/backfill_projections.py ./runners/fluid/
COPY docker-entrypoint.sh ./
RUN chmod +x ./docker-entrypoint.sh

# DR-022: shared host-bind for /run tempdirs. Sidecar and runners mount the
# same path so the runner sees the sidecar's TemporaryDirectory natively.
RUN mkdir -p /var/lib/retinal-inference/tmp

ENV PYTHONPATH=/app/src
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["./docker-entrypoint.sh"]
