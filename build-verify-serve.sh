#!/usr/bin/env bash
set -euo pipefail
# Build from the checked-out stability branch, verify both signed artifacts,
# then serve ONLY those exact files. No stale release fallback.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
BRANCH="feature/irai-device-only-stability"
[[ "$(git branch --show-current)" == "$BRANCH" ]] || { echo "STOP: switch to $BRANCH"; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo "STOP: uncommitted tracked changes"; exit 1; }
git fetch origin "$BRANCH"
[[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/$BRANCH")" ]] || {
  echo "STOP: local branch differs from origin/$BRANCH; git pull --ff-only"; exit 1;
}
for cmd in java keytool python3 sha256sum; do command -v "$cmd" >/dev/null || { echo "Missing $cmd"; exit 1; }; done
for v in IRAI_UPLOAD_STORE_FILE IRAI_UPLOAD_STORE_PASSWORD IRAI_UPLOAD_KEY_ALIAS IRAI_UPLOAD_KEY_PASSWORD; do
  [[ -n "${!v:-}" ]] || { echo "STOP: release signing variable $v is not set"; exit 1; }
done
[[ -f "$IRAI_UPLOAD_STORE_FILE" ]] || { echo "STOP: upload keystore file missing"; exit 1; }
cd "$ROOT/irai-android"
chmod +x gradlew
./gradlew --no-daemon --stacktrace clean :app:assembleRelease :app:bundleRelease
APK="app/build/outputs/apk/release/app-release.apk"
AAB="app/build/outputs/bundle/release/app-release.aab"
[[ -s "$APK" && -s "$AAB" ]] || { echo "STOP: release outputs missing"; exit 1; }
# Verify package, version, and certificate of APK with Android SDK build tools.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
AAPT="$(find "$SDK/build-tools" -maxdepth 2 -name aapt -type f 2>/dev/null | sort -V | tail -1)"
APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1)"
[[ -x "$AAPT" && -x "$APKSIGNER" ]] || { echo "STOP: Android SDK aapt/apksigner unavailable"; exit 1; }
"$APKSIGNER" verify --verbose "$APK" || { echo "STOP: invalid APK signature"; exit 1; }
APK_BADGING="$("$AAPT" dump badging "$APK" | head -1)"
echo "APK manifest: $APK_BADGING"
[[ "$APK_BADGING" == *"name='com.Iraitec'"* ]] || { echo "STOP: wrong APK package"; exit 1; }
APK_CODE="$(echo "$APK_BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
APK_NAME="$(echo "$APK_BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
# Use the same exact signing certificate for both formats.
APK_CERT="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]')"
AAB_CERT="$(keytool -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]')"
[[ -n "$APK_CERT" && "$APK_CERT" == "$AAB_CERT" ]] || { echo "STOP: APK/AAB signing certificates do not match"; exit 1; }
# bundletool checks the *internal* AAB manifest, not the filename.
BUNDLETOOL="$ROOT/.build-tools/bundletool.jar"
mkdir -p "$(dirname "$BUNDLETOOL")"
if [[ ! -s "$BUNDLETOOL" ]]; then
  curl --fail --location --retry 3 --silent --show-error \
    "https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar" -o "$BUNDLETOOL"
fi
AAB_CODE="$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" --xpath='/manifest/@android:versionCode' | tr -d '\r\n[:space:]')"
AAB_NAME="$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" --xpath='/manifest/@android:versionName' | tr -d '\r\n[:space:]')"
AAB_PACKAGE="$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" --xpath='/manifest/@package' | tr -d '\r\n[:space:]')"
[[ "$APK_CODE" == "$AAB_CODE" && "$APK_NAME" == "$AAB_NAME" && "$AAB_PACKAGE" == "com.Iraitec" ]] || {
  echo "STOP: APK/AAB package or version mismatch"; exit 1;
}
echo "VERIFIED package=$AAB_PACKAGE versionCode=$AAB_CODE versionName=$AAB_NAME"
echo "VERIFIED certificate SHA256=$APK_CERT"
DEST="$ROOT/downloads/stability-verified"
mkdir -p "$DEST"
# Never leave stale artifacts in this endpoint.
rm -f "$DEST"/*.apk "$DEST"/*.aab "$DEST"/SHA256SUMS "$DEST"/build-info.txt
cp "$APK" "$DEST/IraiTec-v${AAB_CODE}.apk"
cp "$AAB" "$DEST/IraiTec-v${AAB_CODE}.aab"
(cd "$DEST" && sha256sum *.apk *.aab > SHA256SUMS && sha256sum -c SHA256SUMS)
printf 'Commit: %s\nPackage: %s\nVersion code: %s\nVersion name: %s\nSigning certificate SHA256: %s\n' \
  "$(git -C "$ROOT" rev-parse HEAD)" "$AAB_PACKAGE" "$AAB_CODE" "$AAB_NAME" "$APK_CERT" > "$DEST/build-info.txt"
echo "APK: /IraiTec-v${AAB_CODE}.apk"
echo "AAB: /IraiTec-v${AAB_CODE}.aab"
echo "SHA256 checksums: /SHA256SUMS"
echo "Starting private Codespaces download server on port 8765"
exec python3 - "$DEST" <<'PY'
import http.server, os, socketserver, sys
os.chdir(sys.argv[1])
class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        if self.path.endswith((".apk", ".aab")):
            self.send_header("Content-Disposition", 'attachment; filename="' + os.path.basename(self.path) + '"')
        super().end_headers()
class Server(socketserver.TCPServer):
    allow_reuse_address = True
with Server(("0.0.0.0", 8765), Handler) as server:
    server.serve_forever()
PY
