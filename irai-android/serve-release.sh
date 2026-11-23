#!/usr/bin/env bash
# Serve the signed release APK and Google Play AAB from the verified GitHub Actions build.
# Run from the repository root: bash irai-android/serve-release.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="ericareolias06-art/AI-4.0.7.I.R.A.I"
WORKFLOW=".github/workflows/irai-signed-release.yml"
BRANCH="feature/irai-hybrid-ai"
PUBLIC_DIR="$ROOT/irai-android/public-builds"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required. Open this inside your GitHub Codespace." >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "Please authenticate GitHub CLI first using: gh auth login" >&2
  exit 1
fi

# Only serve artifacts built from this exact checkout. An older release is unsafe.
CURRENT_SHA="$(git -C "$ROOT" rev-parse HEAD)"
RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" --limit 30 --json databaseId,headSha --jq ".[] | select(.headSha == \"$CURRENT_SHA\") | .databaseId" | head -n 1)"
if [[ -z "$RUN_ID" ]]; then
  echo "No signed release run found for checkout $CURRENT_SHA." >&2
  echo "Run git pull, then check GitHub Actions for the latest release build." >&2
  exit 1
fi
echo "Checking signed release run $RUN_ID for commit $CURRENT_SHA..."
STATUS="$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion')"
if [[ "$STATUS" != "success" ]]; then
  echo "Signed build is not successful yet (conclusion: ${STATUS:-pending})." >&2
  echo "Check https://github.com/$REPO/actions/runs/$RUN_ID" >&2
  echo "No old or debug build will be served in its place." >&2
  exit 1
fi

mkdir -p "$TEMP_DIR/apk" "$TEMP_DIR/aab"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-signed-release-APK --dir "$TEMP_DIR/apk"
gh run download "$RUN_ID" --repo "$REPO" --name IraiTec-PlayStore-signed-AAB --dir "$TEMP_DIR/aab"

APK="$TEMP_DIR/apk/app-release.apk"
AAB="$TEMP_DIR/aab/app-release.aab"
if [[ ! -s "$APK" || ! -s "$AAB" ]]; then
  echo "Expected signed release files were not found; refusing to serve stale builds." >&2
  exit 1
fi

mkdir -p "$PUBLIC_DIR"
cp "$APK" "$PUBLIC_DIR/IraiTec.apk"
cp "$AAB" "$PUBLIC_DIR/IraiTec.aab"

echo
echo "SIGNED release files for commit $CURRENT_SHA ready on port 8765."
echo "Forward port 8765 in the Codespaces PORTS tab, then open:"
echo "  /IraiTec.apk"
echo "  /IraiTec.aab"
echo "These are signed release files, NOT debug artifacts."
echo "Press Ctrl+C to stop the download server."
echo
exec python3 -m http.server 8765 --bind 0.0.0.0 --directory "$PUBLIC_DIR"
