#!/usr/bin/env bash
#
# Regenerate docs/development/modernization/phase-b5-hibernate6-manifest.md
# inputs (the per-API / per-file deprecation tallies) from a fresh tip.
#
# Workflow:
#   1) Strip @SuppressWarnings("deprecation") from
#      core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate/
#      (multi-key annotations keep their other keys; backups land as <file>.bak).
#   2) Run `mvn -DskipTests=true clean compile` via the Docker image declared in
#      Dockerfile (CLAUDE.md pattern), capturing stderr+stdout into build.log.
#   3) Parse build.log into a deprecation tally (by Hibernate API, by file)
#      and write it to docs/development/modernization/phase-b5-hibernate6-manifest.tally.txt.
#   4) Restore the .bak files so the working tree is left compile-clean.
#
# The manifest .md file itself is hand-edited around the tally — re-run this
# script, diff the new tally against the old, and update the prose +
# recipe section if the API mix has shifted.
#
# Intended to be re-run on the lc-develop tip immediately before Phase B.5 starts
# (cliff-deferral guideline — regenerate the scope before swinging at it).
#
# Usage:
#   ./scripts/regenerate-phase-b5-manifest.sh
#
# Assumes a writable .m2-cache/ at the parent of this worktree (see CLAUDE.md);
# falls back to ./.m2-cache when the parent is absent.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DAO_DIR="core/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/dao/hibernate"
OUT_DIR="docs/development/modernization"
BUILD_LOG="${REPO_ROOT}/build-b5-manifest.log"
TALLY_OUT="${REPO_ROOT}/${OUT_DIR}/phase-b5-hibernate6-manifest.tally.txt"

if [ ! -d "${REPO_ROOT}/${DAO_DIR}" ]; then
  echo "FAIL: DAO directory not found: ${DAO_DIR}" >&2
  exit 1
fi

# Pick the m2 cache: parent-of-repo (modernization layout) or in-repo.
if [ -d "${REPO_ROOT}/../.m2-cache" ]; then
  M2_CACHE="$(cd "${REPO_ROOT}/.." && pwd)/.m2-cache"
else
  M2_CACHE="${REPO_ROOT}/.m2-cache"
  mkdir -p "${M2_CACHE}"
fi

echo "==> Strip @SuppressWarnings(\"deprecation\") from ${DAO_DIR}"
python3 "${REPO_ROOT}/scripts/strip_deprecation_suppressions.py" "${REPO_ROOT}/${DAO_DIR}"

# Always restore on exit, even if the build fails. Otherwise the working tree
# would be left in the stripped state and a follow-up `git status` lies.
cleanup() {
  echo "==> Restore @SuppressWarnings annotations from .bak files"
  python3 "${REPO_ROOT}/scripts/restore_deprecation_suppressions.py" \
    "${REPO_ROOT}/${DAO_DIR}" >/dev/null
}
trap cleanup EXIT

echo "==> mvn -DskipTests=true clean compile (Docker, captures stderr+stdout)"
docker run --rm \
  -v "${REPO_ROOT}":/app \
  -v "${M2_CACHE}":/root/.m2 \
  -w /app \
  maven:3-eclipse-temurin-25 \
  mvn -B -DskipTests=true -ntp -pl core -am clean compile \
  > "${BUILD_LOG}" 2>&1

echo "==> Parse warnings into ${TALLY_OUT}"
mkdir -p "$(dirname "${TALLY_OUT}")"
python3 - "${BUILD_LOG}" > "${TALLY_OUT}" <<'PY'
import collections, re, sys
from pathlib import Path

WARN_RE = re.compile(
    r"^\[WARNING\]\s+(?P<path>/[^\s:]+\.java):\[(?P<line>\d+),\d+\]\s+(?P<rest>.*)$"
)
SYMBOL_OWNER_RE = re.compile(
    r"(?P<symbol>[A-Za-z_][\w]*(?:\([^)]*\))?)\s+in\s+(?P<owner>[\w.$]+)"
)

by_api = collections.Counter()
by_file = collections.Counter()
samples = {}

for raw in Path(sys.argv[1]).read_text(errors="replace").splitlines():
    m = WARN_RE.match(raw)
    if not m:
        continue
    rest = m.group("rest")
    if "deprecat" not in rest.lower() and "[removal]" not in rest:
        continue
    file_path = m.group("path")
    if "/dao/hibernate/" not in file_path:
        continue
    by_file[file_path] += 1
    sm = SYMBOL_OWNER_RE.search(rest)
    key = f"{sm.group('owner')}.{sm.group('symbol')}" if sm else \
        rest.split(" has been deprecated")[0].strip()
    by_api[key] += 1
    samples.setdefault(key, (file_path, m.group("line"), rest))

total = sum(by_file.values())
print(f"# Total deprecation warnings in dao/hibernate: {total}\n")
print("## By Hibernate API (top 30)\n")
for k, n in by_api.most_common(30):
    print(f"  {n:4d}  {k}")
print()
print("## By DAO file\n")
for k, n in sorted(by_file.items(), key=lambda kv: -kv[1]):
    short = k.split("/dao/hibernate/", 1)[-1]
    print(f"  {n:4d}  {short}")
print()
print("## Samples (first call site per API)\n")
for k, n in by_api.most_common(30):
    path, line, rest = samples[k]
    short = path.split("/dao/hibernate/", 1)[-1]
    print(f"  - {k}: {short}:{line}  --  {rest[:120]}")
PY

echo
echo "==> Tally written: ${TALLY_OUT}"
echo "==> Full build log: ${BUILD_LOG}"
echo "==> Hand-edit ${OUT_DIR}/phase-b5-hibernate6-manifest.md to fold in the new numbers."
