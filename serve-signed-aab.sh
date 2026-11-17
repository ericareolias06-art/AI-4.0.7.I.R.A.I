#!/usr/bin/env bash
set -euo pipefail

# Pinned to successful signed Irai Tec v1.6.7 (versionCode 15) build.
REPO="ericareolias06-art/AI-4.0.7.I.R.A.I"
RUN_ID="43965112467"
EXPECTED_SHA="2aad4c8d6ef0a2837c6aca3954a4ad183e9a2553"
EXPECTED_SHA1="64:F2:1C:73:69:A5:0E:57:E1:D7:13:B0:C4:F7:A1:79:2B:03:A2:29"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/downloads"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

for command in gh keytool python3 java curl; do
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

# Inspect the actual Google Play .aab manifest using Google's bundletool.
# Never serve a bundle whose internal version code isn't 15.
BUNDLETOOL="$TEMP/bundletool.jar"
BUNDLETOOL_URL="https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar"
command -v curl >/dev/null || { echo "curl is needed to verify the AAB"; exit 1; }
curl --fail --location --retry 3 --silent --show-error "$BUNDLETOOL_URL" -o "$BUNDLETOOL"
AAB_CODE="$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" --xpath='/manifest/@android:versionCode' | tr -d '\r\n[:space:]')"
AAB_NAME="$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" --xpath='/manifest/@android:versionName' | tr -d '\r\n[:space:]')"
[[ "$AAB_CODE" == "15" && "$AAB_NAME" == "1.6.7" ]] || {
  echo "REFUSING to serve wrong Google Play AAB: versionCode=$AAB_CODE, versionName=$AAB_NAME"
  exit 1
}
echo "Google Play AAB INTERNAL manifest verified: versionCode=15, versionName=1.6.7"

# Atomically replace public downloads and drop any old, generic filenames.
mkdir -p "$DEST"
rm -f "$DEST"/*.apk "$DEST"/*.aab
cp "$AAB" "$DEST/IraiTec-1.6.7-v15.aab"
cp "$APK" "$DEST/IraiTec-1.6.7-v15.apk"

echo
echo "SUCCESS: Google Play AAB itself verified as version code 14, from GitHub Actions run $RUN_ID"
echo "Download AAB: /IraiTec-1.6.7-v15.aab"
echo "Download APK: /IraiTec-1.6.7-v15.apk"
echo "Starting port 8765. Keep the Codespaces port Private."
# Disable browser caching and make the requested release download an attachment.
exec python3 - "$DEST" <<'PY'
import http.server, os, socketserver, sys
os.chdir(sys.argv[1])
class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        if self.path.endswith((".aab", ".apk")):
            self.send_header("Content-Disposition", 'attachment; filename="' + os.path.basename(self.path) + '"')
        super().end_headers()
class Server(socketserver.TCPServer):
    allow_reuse_address = True
with Server(("0.0.0.0", 8765), Handler) as server:
    print("Serving verified Google Play version 15 at port 8765", flush=True)
    server.serve_forever()
PY
