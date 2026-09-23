#!/usr/bin/env bash
# DR-031 — walk the Remidio gateway chain by hand, before enabling the pull.
#
# Reads the same values the app uses from an env file (default ~/.remidio.env):
#
#   REMIDIO_BASE_URL=https://remidio-backend-germany.appspot.com
#   REMIDIO_CLIENT_NAME=PACS_GATEWAY
#   REMIDIO_CLIENT_TOKEN=<the JWT Remidio issued>
#   REMIDIO_EMAIL=<the integration account>
#   REMIDIO_PASSWORD=<its password>
#   REMIDIO_SITE_CUSTOM_ID=muw_vienna
#
# and calls: loginUser -> getAuthToken -> getSites -> getQueueItem ->
# getExamsByDate for the last LOOKBACK_DAYS (default 7). Tokens are never
# printed; the exam listing is summarised as counts, never patient fields.
#
# Reading the answers:
#   HTTP 500 "Something went wrong" on loginUser/getAuthToken -> wrong clientName
#     (must be PACS_GATEWAY) or a token for another backend.
#   loginUser 404 "User ... was not found" -> wrong account or wrong backend host.
#   getExamsByDate 404 "Site Custom ID ... cannot be found" -> set the custom
#     identifier on the site in the Remidio dashboard.
#   getQueueItem 404 NOT_FOUND -> normal; the queue is empty or not enabled.
#
# NOTE: getAuthToken invalidates the clientAuthToken the app currently holds;
# the app re-authenticates on its next pass, so a probe while the pull is live
# costs one 401 in the app log and nothing else.
set -u
ENV_FILE="${1:-$HOME/.remidio.env}"
[ -f "$ENV_FILE" ] || { echo "env file missing: $ENV_FILE" >&2; exit 1; }
# Read KEY=value lines ourselves rather than sourcing through a process
# substitution: under macOS's bash 3.2 `. <(...)` leaves every variable
# unset, so the probe reported the file as empty on the one machine an
# operator is most likely to try it from first. Comments and blank lines
# are skipped, a trailing CR is dropped, and values are taken literally
# (no quoting or $-expansion — the same bytes the app reads from
# datainfo.properties).
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in REMIDIO_*=*) export "$line" ;; esac
done < "$ENV_FILE"
B="${REMIDIO_BASE_URL%/}"
NAME="${REMIDIO_CLIENT_NAME:-PACS_GATEWAY}"
LOOKBACK_DAYS="${LOOKBACK_DAYS:-7}"

for v in REMIDIO_BASE_URL REMIDIO_CLIENT_TOKEN REMIDIO_EMAIL REMIDIO_PASSWORD REMIDIO_SITE_CUSTOM_ID; do
  [ -n "${!v:-}" ] || { echo "$v is empty in $ENV_FILE" >&2; exit 1; }
done
command -v python3 >/dev/null 2>&1 && PY=python3 || PY=python

HDR="$(mktemp)"; BODY="$(mktemp)"; trap 'rm -f "$HDR" "$BODY"' EXIT
{
  printf 'header = "clientName: %s"\n' "$NAME"
  printf 'header = "clientIdentificationToken: %s"\n' "$REMIDIO_CLIENT_TOKEN"
  printf 'header = "Accept: application/json"\n'
} > "$HDR"

mask() {
  sed -E 's/"(clientIdentificationToken|clientAuthToken|token|password|secret|authorization)":"[^"]*"/"\1":"<masked>"/Ig; s/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/<jwt>/g'
}
data_of() { sed -nE 's/.*"data":"([^"]+)".*/\1/p' "$BODY"; }
call() {  # method path [json]
  local m="$1" p="$2" d="${3:-}" code
  if [ -n "$d" ]; then
    code="$(curl -s -m 60 -K "$HDR" -o "$BODY" -w '%{http_code}' -X "$m" -H 'Content-Type: application/json' -d "$d" "$B$p")"
  else
    code="$(curl -s -m 90 -K "$HDR" -o "$BODY" -w '%{http_code}' -X "$m" "$B$p")"
  fi
  echo "=== $m ${p%%\?*}  -> HTTP $code"
  head -c 400 "$BODY" | mask; echo
  [ "$code" = 200 ]
}

echo "(host: $B | clientName: $NAME | site: $REMIDIO_SITE_CUSTOM_ID)"

login="$(REMIDIO_EMAIL="$REMIDIO_EMAIL" REMIDIO_PASSWORD="$REMIDIO_PASSWORD" $PY -c 'import json,os;print(json.dumps({"emailAddress":os.environ["REMIDIO_EMAIL"],"password":os.environ["REMIDIO_PASSWORD"],"deviceId":""}))')"
call POST /api/user/loginUser "$login" || exit 2
bearer="$(data_of)"; [ -n "$bearer" ] || { echo "no bearer in the login answer" >&2; exit 2; }
printf 'header = "Authorization: Bearer %s"\n' "$bearer" >> "$HDR"

call GET /api/gateway/getAuthToken || exit 3
cat="$(data_of)"; [ -n "$cat" ] || { echo "no clientAuthToken in the answer" >&2; exit 3; }
printf 'header = "clientAuthToken: %s"\n' "$cat" >> "$HDR"

call GET /api/gateway/getSites || exit 4
call GET /api/gateway/getQueueItem || true

FROM="$(date -d "-${LOOKBACK_DAYS} days" +%d-%m-%Y 2>/dev/null || date -v-"${LOOKBACK_DAYS}"d +%d-%m-%Y)"
TO="$(date +%d-%m-%Y)"
P="/api/gateway/getExamsByDate/$FROM/$TO/$REMIDIO_SITE_CUSTOM_ID?includeFilePaths=true"
code="$(curl -s -m 120 -K "$HDR" -o "$BODY" -w '%{http_code}' "$B$P")"
echo "=== GET ${P%%\?*}  -> HTTP $code"
BODY_PY="$BODY"; command -v cygpath >/dev/null 2>&1 && BODY_PY="$(cygpath -w "$BODY")"
BODY_PY="$BODY_PY" $PY - <<'PY'
import json, os, collections
p = os.environ["BODY_PY"]
try:
    d = json.load(open(p, encoding="utf-8"))
except Exception:
    print(" (not JSON)", open(p, encoding="utf-8", errors="replace").read()[:300]); raise SystemExit
print(" status:", d.get("status"))
ex = d.get("data")
if not isinstance(ex, list):
    print(" data:", str(ex)[:200]); raise SystemExit
dt = collections.Counter(); lats = collections.Counter(); imgs = withpath = 0
for e in ex:
    for t in (e.get("examDetails") or {}).get("deviceType") or []: dt[t] += 1
    for g in ((e.get("images") or {}).values()):
        for v in (g.values() if isinstance(g, dict) else []):
            for i in (v if isinstance(v, list) else []):
                imgs += 1; lats[i.get("laterality")] += 1; withpath += bool(i.get("path"))
print(" exams:", len(ex), "| deviceTypes:", dict(dt), "| images:", imgs, "| with path:", withpath, "| laterality:", dict(lats))
PY
