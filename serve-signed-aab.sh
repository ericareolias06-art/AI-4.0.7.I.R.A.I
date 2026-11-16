#!/usr/bin/env bash
set -euo pipefail

# Always download the current checked-out signed release, never a stale AAB.
REPO="ericareolias06-art/AI-4.0.7.I.R.A.I"
BRANCH="feature/irai-hybrid-ai"
WORKFLOW="irai-signed-release.yml"
EXPECTED_SHA1="64:F2:1C:73:69:A5:0E:57:E1:D7:13:B0:C4:F7:A1:79:2B:03:A2:29"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/downloads"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for cmd in gh keytool git python3; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd"; exit 1; }
done
gh auth status >/dev/null || { echo "Authenticate GitHub CLI with gh auth login."; exit 1; }

git -C "$ROOT" fetch origin "$BRANCH"
LOCAL_SHA="$(git -C "$ROOT" rev-parse HEAD)"
REMOTE_SHA="$(git -C "$ROOT" rev-parse "origin/$BRANCH")"
[[ "$LOCAL_SHA" == "$REMOTE_SHA" ]] || {
  echo "Codespace is not on the latest release commit. Run: git checkout $BRANCH && git pull origin $BRANCH"
  exit 1
}
GRADLE="$ROOT/irai-android/app/build.gradle.kts"
grep -Eq 'versionCode[[:space:]]*=[[:space:]]*14([[:space:]]|$)' "$GRADLE" || {
  echo "This checkout is not Google Play version code 14; refusing older release."
  exit 1
}
grep -Eq 'versionName[[:space:]]*=[[:space:]]*"1.6.6"' "$GRADLE" || {
  echo "This checkout is not Irai 1.6.6; refusing older release."
  exit 1
}
RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" --limit 30 --json databaseId,headSha,conclusion --jq ".[] | select(.headSha == \"$LOCAL_SHA\" and .conclusion == \"success\") | .databaseId" | head -n 1)"
[[ -n "$RUN_ID" ]] || {
  echo "No successful signed build for current v14 commit $LOCAL_SHA."
  echo "Check: https://github.com/$REPO/actions"
  exit 1
}

mkdir -p "$TMP/aab" "$TMP/apk"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-PlayStore-signed-AAB --dir "$TMP/aab"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-signed-release-APK --dir "$TMP/apk"
AAB="$TMP/aab/app-release.aab"
APK="$TMP/apk/app-release.apk"
[[ -s "$AAB" && -s "$APK" ]] || { echo "Release files missing."; exit 1; }

FINGERPRINT="$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -n 1 | tr '[:lower:]' '[:upper:]')"
[[ "$FINGERPRINT" == "$EXPECTED_SHA1" ]] || { echo "Incorrect AAB signing certificate."; exit 1; }

# Verify the binary's embedded Android version, not its filename.
# The APK and AAB were assembled together by this exact signed build.
AAPT=""
if command -v aapt >/dev/null 2>&1; then
  AAPT="$(command -v aapt)"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  AAPT="$(find "$ANDROID_HOME/build-tools" -type f -name aapt 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -n "$AAPT" ]]; then
  APK_METADATA="$("$AAPT" dump badging "$APK" | head -n 1)"
  [[ "$APK_METADATA" == *"versionCode='14'"* && "$APK_METADATA" == *"versionName='1.6.6'"* ]] || {
    echo "ERROR: Compiled Android version does not match 14 / 1.6.6: $APK_METADATA"
    exit 1
  }
else
  APKANALYZER="$(command -v apkanalyzer || true)"
  if [[ -z "$APKANALYZER" && -n "${ANDROID_HOME:-}" ]]; then
    APKANALYZER="$(find "$ANDROID_HOME" -type f -name apkanalyzer 2>/dev/null | head -n 1 || true)"
  fi
  [[ -n "$APKANALYZER" ]] || { echo "ERROR: Android build-tools (aapt or apkanalyzer) required to verify binary version."; exit 1; }
  APK_CODE="$("$APKANALYZER" manifest version-code "$APK")"
  [[ "$APK_CODE" == "14" ]] || { echo "ERROR: Compiled APK is version code $APK_CODE instead of 14."; exit 1; }
fi
python3 - "$AAB" "$APK" <<'PY'
import sys, zipfile
for path in sys.argv[1:]:
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise SystemExit("Corrupt signed release ZIP: " + path)
PY

mkdir -p "$DEST"
rm -f "$DEST"/*.aab "$DEST"/*.apk
# Do not let stale generic app-release files remain at the port.
cp "$AAB" "$DEST/IraiTec-1.6.6-v14.aab"
cp "$APK" "$DEST/IraiTec-1.6.6-v14.apk"
echo "Verified successful release build $RUN_ID for commit $LOCAL_SHA"
echo "Port 8765: /IraiTec-1.6.6-v14.aab and /IraiTec-1.6.6-v14.apk"
echo "Keep Codespaces port visibility Private unless intentionally sharing."
exec python3 -m http.server 8765 --bind 0.0.0.0 --directory "$DEST"
