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
