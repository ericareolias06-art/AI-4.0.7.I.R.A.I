#!/usr/bin/env bash
set -euo pipefail

# Serve ONLY the verified Irai 1.6.5 / Play versionCode 13 signed release.
REPO="ericareolias06-art/AI-4.0.7.I.R.A.I"
RUN_ID="43808058001"
EXPECTED_COMMIT="9ecb0a4ddc61d48c5f6493040252b32ad17816e9"
EXPECTED_SHA1="64:F2:1C:73:69:A5:0E:57:E1:D7:13:B0:C4:F7:A1:79:2B:03:A2:29"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/downloads"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v gh >/dev/null || { echo "GitHub CLI (gh) is required."; exit 1; }
command -v keytool >/dev/null || { echo "Java keytool is required."; exit 1; }
gh auth status >/dev/null || { echo "Authenticate GitHub CLI with gh auth login."; exit 1; }

echo "Verifying signed IraiTec 1.6.5 (version code 13) release..."
RUN_SHA="$(gh run view "$RUN_ID" --repo "$REPO" --json headSha --jq '.headSha')"
RUN_STATUS="$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion')"
[[ "$RUN_SHA" == "$EXPECTED_COMMIT" ]] || { echo "Unexpected build commit; refusing outdated release."; exit 1; }
[[ "$RUN_STATUS" == "success" ]] || { echo "Release build is not successful: $RUN_STATUS"; exit 1; }

mkdir -p "$TMP/aab" "$TMP/apk" "$DEST"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-PlayStore-signed-AAB --dir "$TMP/aab"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-signed-release-APK --dir "$TMP/apk"
AAB="$TMP/aab/app-release.aab"
APK="$TMP/apk/app-release.apk"
[[ -s "$AAB" && -s "$APK" ]] || { echo "Signed release files missing."; exit 1; }

FINGERPRINT="$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -n 1 | tr '[:lower:]' '[:upper:]')"
[[ "$FINGERPRINT" == "$EXPECTED_SHA1" ]] || { echo "AAB signing certificate mismatch."; exit 1; }

cp "$AAB" "$DEST/IraiTec-1.6.5-v13.aab"
cp "$APK" "$DEST/IraiTec-1.6.5-v13.apk"
echo "Verified release files ready on port 8765:"
echo "  /IraiTec-1.6.5-v13.aab  (Google Play)"
echo "  /IraiTec-1.6.5-v13.apk  (direct Android install)"
echo "Open Codespaces PORTS and keep port 8765 Private unless intentionally sharing."
exec python3 -m http.server 8765 --bind 0.0.0.0 --directory "$DEST"
