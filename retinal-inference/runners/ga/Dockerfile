# GA model-runner — sese_ga (RPEL -> geographic-atrophy area). GATED, GPU.
#
# The heaviest runner. It needs, at runtime (mounted, none in git):
#   * the proprietary IOWA OCTLayerSeg3.6 binary  -> /opt/iowa/OCTLayerSeg3.6
#       (source from the OPTIMA HPC /home/optima/octreader/; licensed Univ-of-Iowa)
#   * the sese_ga vendor code/                    -> /opt/sese_ga/code  (./code here)
#   * pytorch_optima_dl.v4.sif (~10 GB)           -> /opt/sese_ga/...   (mounted)
#   * the 5 checkpoints weights/filly_checkpoints/{0..4}/w.ckpt -> /weights
#   * a CUDA GPU (the .sif runs with --nv)
#
# Running Singularity inside Docker needs a privileged/special setup; the
# alternative (see README) is to REIMPLEMENT the model on modern torch after
# extracting common/ + prepare_data/ from the .sif, dropping Singularity+GPU.
# This Dockerfile is a best-effort scaffold — finalise the base on the build host.
FROM python:3.10-slim

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# NOTE: add apptainer/singularity here if running the .sif in-container, or a
# torch stack if reimplementing. Left out deliberately — decide on the build host.

COPY code/ /opt/sese_ga/code/

RUN pip install --no-cache-dir \
    "numpy" "pydicom==3.0.2" "fastapi==0.115.6" "uvicorn[standard]==0.32.1"

COPY app.py ./
COPY projection.py ./
ENV RUNNER_GA_CODE=/opt/sese_ga/code/infer_sample_filly.py \
    RUNNER_GA_WEIGHTS=/weights/filly_checkpoints
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["python", "app.py"]
