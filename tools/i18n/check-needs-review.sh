#!/usr/bin/env bash
# Future "translations done" gate. Exits 1 if any [NEEDS_REVIEW] markers remain.
#
# Wave 1C (2026-06-18) seeded the en.json mirror with machine-translated
# strings tagged "[NEEDS_REVIEW] " so a clinical translator can sweep
# them later. This script is the gate that flips green once the sweep
# is complete — wire it into CI then.
set -e
if grep -RIn '\[NEEDS_REVIEW\]' web/src/spa/src/locales; then
  echo "::error::[NEEDS_REVIEW] markers found in locales. A translator must sweep these."
  exit 1
fi
echo "OK — no NEEDS_REVIEW markers."
