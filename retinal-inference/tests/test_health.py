"""GET /health smoke test + the node/GPU facts it reports."""

from __future__ import annotations

from retinal_inference.api.health import parse_gpu_name


def test_health_ok(app_client) -> None:
    resp = app_client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["adapter"] == "placeholder"
    assert body["model_version"] == "placeholder-v1"
    assert "ga" in body["supported_tasks"]


def test_health_reports_node_and_tolerates_no_gpu(app_client) -> None:
    # The placeholder adapter pins no device, and the test host has no
    # nvidia-smi: both must read as "unknown", never as an error.
    body = app_client.get("/health").json()
    assert body["node"]  # short hostname, whatever it is
    assert body["gpu_device"] is None
    assert body["gpu_name"] is None


LISTING = (
    "0, NVIDIA GeForce RTX 2080 Ti\n"
    "1, NVIDIA GeForce RTX 2080 Ti\n"
    "2, Tesla K80\n"
)


def test_parse_gpu_name_picks_the_pinned_index() -> None:
    assert parse_gpu_name("2", LISTING) == "Tesla K80"
    assert parse_gpu_name("0", LISTING) == "NVIDIA GeForce RTX 2080 Ti"


def test_parse_gpu_name_is_none_when_unpinned_absent_or_multi() -> None:
    assert parse_gpu_name(None, LISTING) is None
    assert parse_gpu_name("", LISTING) is None
    assert parse_gpu_name("7", LISTING) is None
    assert parse_gpu_name("0,1", LISTING) is None
    assert parse_gpu_name("0", "") is None
