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
#
# Py3.7 availability notes (the base image): SimpleITK 1.2.4 is not published
# for py3.7 (PyPI jumps 1.2.0 → 2.1.0); 1.2.0 is the closest 1.x available and
# the 1.2.0→1.2.4 diff is patches-only, no API break that the segmentation
# code touches. pydicom 3.x requires py3.9+ → constrain to <3.
# fastapi >=0.104 and uvicorn >=0.22 both dropped py3.7 → constrain below the
# cutoffs to land on the last py3.7-compatible releases.
# matplotlib + scikit-learn + Pillow + imageio are imported unconditionally by
# the inference path through sese_pr's util/* (file_access.py wires pyplot at
# module load; util/metrics.py imports sklearn.metrics; data_loader and
# image_preprocessing import PIL; data_preparation imports imageio). torchvision
# and visdom appear only in train.py / train_interruptions_network.py / the
# plot helpers used during training — skipped here.
RUN pip install --no-cache-dir \
    "numpy" "scipy" "scikit-image" "scikit-learn" "SimpleITK==1.2.0" "pydicom<3" \
    "matplotlib" "Pillow" "imageio" \
    "fastapi<0.104" "uvicorn[standard]<0.22"
# Route (a) — install torch 1.0.1 so the vendor's full-pickle model.pkl loads
# as-is (matches the sese_pr production stack). torch 1.0.1 ships
# manylinux1 py3.7 amd64 wheels on PyPI, so no source build is needed.
RUN pip install --no-cache-dir "torch==1.0.1"

COPY app.py ./
ENV RUNNER_BMEIS_CODE=/opt/sese_pr RUNNER_BMEIS_WEIGHTS=/weights/u2net-cross-entropy
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1
CMD ["python", "app.py"]
