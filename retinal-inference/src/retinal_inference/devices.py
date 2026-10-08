"""Which OCT devices each inference task's model was trained on (vendor gating).

The single place that declares, per task, the manufacturers and device models
whose volumes the task may be run on. Every model behind the current tasks
was trained on Heidelberg Engineering Spectralis volumes; run on another
vendor's OCT (different axial resolution, speckle, intensity mapping) it
would still produce numbers, and those numbers would be wrong. ``/run``
therefore refuses a DICOM whose device does not match (422
``unsupported_device``), ``/health`` publishes the declaration as
``task_devices``, and ``/preprocess`` reports the tasks a normalised volume
may go to in ``X-MUW-Device-Tasks``.

Matching is case-insensitive substring matching on Manufacturer (0008,0070)
and Manufacturer's Model Name (0008,1090):

* Spectralis exports say ``Manufacturer = "Heidelberg Engineering"`` and a
  model such as ``"Spectralis"``. Our own ``.e2e``-derived ``bscan.dcm``
  carries the E2E device string (``"Heidelberg Retina Angiograph"``, or the
  fallback ``"Heidelberg Engineering"``) and, when the E2E has one, a model
  such as ``"SPECTRALISHX202"``.
* A missing model name is accepted (``model_required = False``) because an
  ``.e2e`` does not always carry one and every ``.e2e`` OCT volume is a
  Spectralis. A model name that is present must match: that is what keeps a
  Heidelberg ANTERION (anterior-segment OCT, same manufacturer string) out.
  ``hra`` is accepted alongside ``spectralis`` because the Spectralis
  HRA+OCT reports itself under the Heidelberg Retina Angiograph family name.

Adding a task trained on another device = one entry below.
"""

from __future__ import annotations

from dataclasses import dataclass

from retinal_inference.tasks import SUPPORTED_TASKS, TaskName


@dataclass(frozen=True)
class DeviceSupport:
    label: str
    manufacturers: tuple[str, ...]  # case-insensitive substrings of Manufacturer
    models: tuple[str, ...]  # case-insensitive substrings of the model; () = any
    model_required: bool = False

    def matches(self, manufacturer: str | None, model: str | None) -> bool:
        man = (manufacturer or "").strip().lower()
        if not man or not any(m in man for m in self.manufacturers):
            return False
        mod = (model or "").strip().lower()
        if not mod:
            return not self.model_required
        return not self.models or any(m in mod for m in self.models)

    def as_dict(self) -> dict:
        return {
            "label": self.label,
            "manufacturers": list(self.manufacturers),
            "models": list(self.models),
            "model_required": self.model_required,
        }


SPECTRALIS = DeviceSupport(
    label="Heidelberg Engineering Spectralis",
    manufacturers=("heidelberg",),
    models=("spectralis", "hra"),
    model_required=False,
)

TASK_DEVICES: dict[TaskName, DeviceSupport] = {
    "ga": SPECTRALIS,
    "fluid": SPECTRALIS,
    "onl": SPECTRALIS,
    "pr": SPECTRALIS,  # the runner also forces Manufacturer=Heidelberg into the .mhd
    "bm": SPECTRALIS,
    "layers": SPECTRALIS,
    "sdretinanet": SPECTRALIS,  # aot_models_spectralis
}

if set(TASK_DEVICES) != SUPPORTED_TASKS:  # pragma: no cover — a registry edit missed this file
    raise RuntimeError("every task in SUPPORTED_TASKS must declare its devices in TASK_DEVICES")


def device_supported(task: TaskName, manufacturer: str | None, model: str | None) -> bool:
    support = TASK_DEVICES.get(task)
    return support is not None and support.matches(manufacturer, model)


def tasks_for_device(manufacturer: str | None, model: str | None) -> list[str]:
    """The tasks whose models were trained on this device, sorted."""
    return sorted(t for t, s in TASK_DEVICES.items() if s.matches(manufacturer, model))


def unsupported_message(task: str, manufacturer: str | None, model: str | None) -> str:
    support = TASK_DEVICES.get(task)  # type: ignore[call-overload]
    expected = support.label if support else "no device"
    device = (manufacturer or "").strip() or "(no Manufacturer)"
    if (model or "").strip():
        device += f" / {model.strip()}"  # type: ignore[union-attr]
    return (
        f"Task '{task}' is validated only for {expected} volumes; "
        f"this volume comes from {device}. No result is produced for it."
    )


def task_devices_declaration() -> dict[str, dict]:
    return {t: s.as_dict() for t, s in sorted(TASK_DEVICES.items())}
