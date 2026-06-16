# ONL model-runner — sese_onl (outer nuclear layer thickness), CPU-capable.
#
# Vendor source is NOT in git — populate ./code before building from:
#   <sese_onl>/code/outernuclearlayer-segmentation/    (process_input_for_optimus.py,
#                                                        architecture.py, ...)
# Model weights are mounted at /weights at runtime (compose), not baked in.
#
#   docker build -t retinal-runner-onl retinal-inference/runners/onl
#
# Vanilla_UNet loads a clean state_dict, so a modern torch is fine. Confirm the
# exact torch version the vendor code expects on first build.
FROM python:3.8-slim

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Vendor inference code (the script + its modules) → /opt/sese_onl.
COPY code/ /opt/sese_onl/

# Py3.8 availability notes (the base image): pydicom 3.x and SimpleITK 2.5.x
# both require py3.10+ → unconstrained `SimpleITK` pulls 2.5.x sdist which
# fails to build from source on this slim image (no CMake/ITK). Constrain
# both below their py3.10 cutoffs.
RUN pip install --no-cache-dir \
    "torch" "numpy" "scipy" "scikit-image" "SimpleITK<2.5" "pydicom<3" \
    "fastapi==0.115.6" "uvicorn[standard]==0.32.1"

COPY app.py ./
ENV RUNNER_ONL_CODE=/opt/sese_onl RUNNER_ONL_WEIGHTS=/weights
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["python", "app.py"]
