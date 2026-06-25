# Fluid model-runner — RetInsight fluidseg 1.3.0 (IRF/SRF/PED), CPU-capable.
#
# Mirrors the vendor code_for_1_3 image (pytorch 1.9 base; weights baked at
# /workdir/model-weights-1-3-0 so fluidseg needs no --weights). Populate the
# build context before building (NOT in git):
#   runners/fluid/wheels/               <- dlfw* + fluidseg-1.3.0 wheels
#                                          (sese_retinsight_fluid/code/code_for_1_3/dlfw-packages)
#   runners/fluid/model-weights-1-3-0/  <- patch_brunet.pt, spacing_patch_brunet.pt, twostage-brunet/
#
#   docker build -t retinal-runner-fluid retinal-inference/runners/fluid
#
# NB: v2.5.0 (a ~2.9 GB Singularity .sif via run_inference.py) is newer but
# GPU/x86-only; 1.3.0 is the newest CPU-runnable wheel build.
ARG BASEIMAGE=pytorch/pytorch:1.9.0-cuda10.2-cudnn7-runtime
FROM ${BASEIMAGE}

WORKDIR /workdir
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Weights at fluidseg's default lookup path (no --weights needed), per vendor.
COPY model-weights-1-3-0/ /workdir/model-weights-1-3-0/

# Vendor wheels (deps first, fluidseg --no-deps last), as the 1.3.0 Dockerfile does.
COPY wheels/ /tmp/wheels/
RUN pip install --no-cache-dir /tmp/wheels/dlfwdata-*.whl \
 && pip install --no-cache-dir /tmp/wheels/dlfwtraining-*.whl \
 && pip install --no-cache-dir --no-deps /tmp/wheels/dlfweval-*.whl \
 && pip install --no-cache-dir --no-deps /tmp/wheels/dlfwconfig-*.whl \
 && pip install --no-cache-dir --no-deps /tmp/wheels/fluidseg-*.whl \
 && pip install --no-cache-dir imutils sklearn gitpython \
 && rm -rf /tmp/wheels

# Runner HTTP layer. pytorch/pytorch:1.9.0-* ships Python 3.7 — pydicom 3.x
# requires py3.9+; fastapi >=0.104 and uvicorn >=0.22 both dropped py3.7;
# constrained below each cutoff to land on the last py3.7-compatible release.
RUN pip install --no-cache-dir "fastapi<0.104" "uvicorn[standard]<0.22" "pydicom<3"

COPY app.py /workdir/app.py
# 2026-06-22 — projection.py intentionally NOT bundled in the cluster
# image. The cluster runner emits the raw segmentation only
# (fluidseg.npz); presentation artifacts (projection PNGs, per-slice
# overlays) are derived app-VM-side via the local sidecar's /derive
# endpoint. The projection source lives at
# retinal-inference/src/retinal_inference/projection_fluid.py and is
# imported by api.derive on the local container.
# fluidseg → dlfwconfig.version imports gitpython at module load. The
# pytorch:1.9.0 base ships no git binary, so gitpython aborts the import
# with "Bad git executable" before fluidseg ever starts inference. The
# error message itself documents this escape hatch.
ENV GIT_PYTHON_REFRESH=quiet
# pytorch:1.9.0-cuda10.2-cudnn7-runtime ships a CUDA-built torch, but
# there is no NVIDIA driver inside the container. fluidseg's vendor
# config (01_spectralis_patch_brunet.py) eagerly initializes a CUDA
# tensor at import time — even with --cpu — and crashes on
# torch._C._cuda_init(). CUDA_VISIBLE_DEVICES=-1 tells CUDA to ignore
# every device and is the standard workaround for the import-time path.
ENV CUDA_VISIBLE_DEVICES=-1
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["python", "/workdir/app.py"]
