#!/usr/bin/env bash
# Serves the built bundle to ReVanced Manager over the local network.
#
# Manager loads patches from a JSON manifest whose download_url points at the .rvp, so run this on the
# machine that has the build and add the printed stable.json address in Manager on the phone (both have
# to be on the same network).
#
# Environment:
#   PORT    port to listen on (default 8000)
#   HOST    address the phone should use, if it cannot be detected (default: first IPv4 address)
#   VERSION version shown in Manager (default 1.0.0)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build"
RVP="$BUILD/revanced-sync-fixes.rvp"
SERVE="$BUILD/serve"
PORT="${PORT:-8000}"
VERSION="${VERSION:-1.0.0}"

if [ ! -f "$RVP" ]; then
    echo "No $RVP yet; run scripts/build.sh first." >&2
    exit 1
fi

HOST="${HOST:-$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+(\.[0-9]+){3}$' | head -1 || true)}"
if [ -z "$HOST" ]; then
    echo "Could not detect a LAN address; set HOST to this machine's IPv4 address." >&2
    exit 1
fi

mkdir -p "$SERVE"
cp "$RVP" "$SERVE/revanced-sync-fixes.rvp"

# Keep the description free of double quotes so the heredoc produces valid JSON.
cat >"$SERVE/stable.json" <<EOF
{
  "created_at": "$(date -u +%Y-%m-%dT%H:%M:%S)",
  "description": "Patches that restore Reddit galleries, load more comments, Redgifs and imgur albums in Sync for Reddit v23.06.30-13:39 (free, Pro and dev).\n\nPatch your original Sync APK together with the official Spoof client patch; without it Sync cannot reach Reddit at all. The official Redgifs patch may stay selected: this bundle wraps the OkHttp client at a different point, so the two do not conflict.",
  "download_url": "http://$HOST:$PORT/revanced-sync-fixes.rvp",
  "signature_download_url": "N/A",
  "version": "v$VERSION"
}
EOF
cp "$SERVE/stable.json" "$SERVE/latest.json"

cat <<EOF

Serving $SERVE as http://$HOST:$PORT

On the phone, in ReVanced Manager:
  Patches -> pencil -> + -> Enter URL
  http://$HOST:$PORT/stable.json

If the phone cannot connect, allow port $PORT through this machine's firewall and check that both
devices are on the same network. Stop the server with Ctrl+C.
EOF

cd "$SERVE"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
