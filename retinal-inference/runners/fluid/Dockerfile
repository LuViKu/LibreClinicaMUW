# Fluid model-runner — RetInsight `fluidseg` (IRF/SRF/PED), CPU-capable.
#
# The RetInsight code ships as py3.7 wheels, so this image is py3.7-based.
# Vendor wheels are NOT in git — populate ./wheels before building from:
#   <sese_retinsight_fluid>/code/code_for_1_3/dlfw-packages/
#     dlfwconfig-*.whl dlfwdata-*.whl dlfweval-*.whl dlfwtraining-*.whl fluidseg-*.whl
# Model weights are mounted at /weights at runtime (see compose), not baked in.
#
#   docker build -t retinal-runner-fluid retinal-inference/runners/fluid
FROM python:3.7-slim

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 1) Vendor wheels (order matters — deps first, fluidseg last with --no-deps as
#    the RetInsight ModelDocu instructs). torch is pulled in by the wheels.
COPY wheels/ /tmp/wheels/
RUN pip install --no-cache-dir /tmp/wheels/dlfwdata-*.whl \
 && pip install --no-cache-dir /tmp/wheels/dlfwtraining-*.whl \
 && pip install --no-cache-dir --no-deps /tmp/wheels/dlfweval-*.whl \
 && pip install --no-cache-dir --no-deps /tmp/wheels/dlfwconfig-*.whl \
 && pip install --no-cache-dir --no-deps /tmp/wheels/fluidseg-*.whl \
 && rm -rf /tmp/wheels

# 2) The runner HTTP layer.
RUN pip install --no-cache-dir "fastapi==0.115.6" "uvicorn[standard]==0.32.1" \
    "pydicom==3.0.2" "numpy"

COPY app.py ./
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["python", "app.py"]
