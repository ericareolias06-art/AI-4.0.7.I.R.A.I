#!/usr/bin/env bash
# Build Irai's test APK + test AAB and serve both over a Codespaces forwarded port.
# Run from the repository root: bash irai-android/serve-builds.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/irai-android"
chmod +x ./gradlew
./gradlew --no-daemon assembleDebug bundleDebug

PUBLIC_DIR="$ROOT/irai-android/public-builds"
mkdir -p "$PUBLIC_DIR"
cp app/build/outputs/apk/debug/app-debug.apk "$PUBLIC_DIR/IraiTec.apk"
cp app/build/outputs/bundle/debug/app-debug.aab "$PUBLIC_DIR/IraiTec.aab"
printf '\nBuilt both files. In Codespaces, forward port 8765 and open these paths:\n'
printf '  /IraiTec.apk\n  /IraiTec.aab\n'
printf '\nThese are DEBUG/test builds. Google Play requires a properly signed release AAB.\n'
printf 'Press Ctrl+C to stop the download server.\n\n'
exec python3 -m http.server 8765 --bind 0.0.0.0 --directory "$PUBLIC_DIR"
