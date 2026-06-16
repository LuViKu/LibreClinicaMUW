# BMEIS model-runner — sese_pr (photoreceptor boundary), CPU-capable.
#
# Vendor source is NOT in git — populate ./code before building from:
#   <sese_pr>/code/photoreceptors-segmentation/   (process_input_for_optimus.py,
#                                                   segmentation/, util/, ...)
# Model weights (.ini + model.pkl) are mounted at /weights at runtime.
#
#   docker build -t retinal-runner-bmeis retinal-inference/runners/bmeis
#
# sese_pr is PyTorch 1.0 and ships FULL-PICKLE model.pkl, so the pickle needs a
# matching torch to load. Two routes (pick on the build host):
#   (a) install torch==1.0.x here so the .pkl loads as-is (matches production);
#   (b) convert the .pkl → state_dict offline in a torch-1.0 env, then load it
#       into the BayesianUnet arch on a modern torch and adjust app/model_path.
# Also patch the unconditional .cuda() in segmentation/model_factory.py for CPU.
FROM python:3.7-slim

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY code/ /opt/sese_pr/

# torch pin is intentionally left to the build host (see header). The rest:
RUN pip install --no-cache-dir \
    "numpy" "scipy" "scikit-image" "SimpleITK==1.2.4" "pydicom==3.0.2" \
    "fastapi==0.115.6" "uvicorn[standard]==0.32.1"
# e.g. add:  RUN pip install --no-cache-dir torch==1.0.1

COPY app.py ./
ENV RUNNER_BMEIS_CODE=/opt/sese_pr RUNNER_BMEIS_WEIGHTS=/weights/u2net-cirrus_v3
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["python", "app.py"]
