#!/usr/bin/env bash
set -euo pipefail

REPO="ericareolias06-art/AI-4.0.7.I.R.A.I"
RUN_ID="43795515742"
ARTIFACT="IraiTec-1.6.3-signed-AAB"
EXPECTED_SHA1="64:F2:1C:73:69:A5:0E:57:E1:D7:13:B0:C4:F7:A1:79:2B:03:A2:29"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/downloads"
AAB="$DEST/app-release.aab"

command -v gh >/dev/null || { echo "Install/login to GitHub CLI (gh) first."; exit 1; }
command -v keytool >/dev/null || { echo "Java keytool is required to verify the upload certificate."; exit 1; }
gh auth status >/dev/null || { echo "Authenticate GitHub CLI with: gh auth login"; exit 1; }

mkdir -p "$DEST"
if [[ -f "$AAB" ]]; then
  mv "$AAB" "$AAB.previous"
fi

echo "Downloading IraiTec signed Google Play AAB from GitHub Actions..."
gh run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT" --dir "$DEST"

if [[ ! -s "$AAB" ]]; then
  echo "AAB was not found at $AAB"
  exit 1
fi

FINGERPRINT="$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -n 1 | tr '[:lower:]' '[:upper:]')"
if [[ "$FINGERPRINT" != "$EXPECTED_SHA1" ]]; then
  echo "ERROR: Downloaded AAB does not match Google Play expected upload SHA1."
  exit 1
fi

echo "Verified AAB signing certificate matches Google Play."
echo "IraiTec download ready on port 8765:"
echo "  /app-release.aab"
echo "Codespaces: set port 8765 visibility to Private unless intentionally sharing."
exec python3 -m http.server 8765 --bind 0.0.0.0 --directory "$DEST"
