#!/usr/bin/env bash
set -euo pipefail

# Pinned to successful signed Irai Tec v1.6.6 (versionCode 14) build.
REPO="ericareolias06-art/AI-4.0.7.I.R.A.I"
RUN_ID="43809202294"
EXPECTED_SHA="7e40884d0fc0c73a5030a2cfff3035838413ce91"
EXPECTED_SHA1="64:F2:1C:73:69:A5:0E:57:E1:D7:13:B0:C4:F7:A1:79:2B:03:A2:29"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/downloads"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

for command in gh keytool python3; do
  command -v "$command" >/dev/null || { echo "Missing $command"; exit 1; }
done
gh auth status >/dev/null || { echo "Log in: gh auth login"; exit 1; }

RUN_SHA="$(gh run view "$RUN_ID" --repo "$REPO" --json headSha --jq '.headSha')"
RUN_RESULT="$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion')"
[[ "$RUN_SHA" == "$EXPECTED_SHA" && "$RUN_RESULT" == "success" ]] || {
  echo "Wrong or unsuccessful GitHub Actions release run"
  exit 1
}

mkdir -p "$TEMP/aab" "$TEMP/apk"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-PlayStore-signed-AAB --dir "$TEMP/aab"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-signed-release-APK --dir "$TEMP/apk"

AAB="$TEMP/aab/app-release.aab"
APK="$TEMP/apk/app-release.apk"
[[ -s "$AAB" && -s "$APK" ]] || { echo "Missing signed release files"; exit 1; }
FINGERPRINT="$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -n 1 | tr '[:lower:]' '[:upper:]')"
[[ "$FINGERPRINT" == "$EXPECTED_SHA1" ]] || { echo "Wrong signing certificate"; exit 1; }

# Prove this really is a compiled v14 build, not a renamed v11.
AAPT="$(command -v aapt || true)"
if [[ -z "$AAPT" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk; do
    [[ -n "$sdk" && -d "$sdk/build-tools" ]] || continue
    AAPT="$(find "$sdk/build-tools" -type f -name aapt | sort -V | tail -n 1)"
    [[ -n "$AAPT" ]] && break
  done
fi
[[ -n "$AAPT" ]] || { echo "Android aapt build tool not found; refusing to serve unverified bundle."; exit 1; }
BADGING="$("$AAPT" dump badging "$APK" | head -n 1)"
[[ "$BADGING" == *"versionCode='14'"* ]] || { echo "WRONG APK version: $BADGING"; exit 1; }

# Atomically replace public downloads and drop any old, generic filenames.
mkdir -p "$DEST"
rm -f "$DEST"/*.apk "$DEST"/*.aab
cp "$AAB" "$DEST/IraiTec-1.6.6-v14.aab"
cp "$APK" "$DEST/IraiTec-1.6.6-v14.apk"

echo
echo "SUCCESS: Version code 14 verified, from GitHub Actions run $RUN_ID"
echo "Download AAB: /IraiTec-1.6.6-v14.aab"
echo "Download APK: /IraiTec-1.6.6-v14.apk"
echo "Starting port 8765. Keep the Codespaces port Private."
exec python3 -m http.server 8765 --bind 0.0.0.0 --directory "$DEST"
