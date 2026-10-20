#!/usr/bin/env bash
# Fetches the Node.js runtime for Android and bundles the WhatsApp engine into the app.
# Run once before building: ./scripts/prepare.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NODEJS_MOBILE_VERSION="18.20.4"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "› Downloading nodejs-mobile $NODEJS_MOBILE_VERSION (Node.js runtime for Android)"
(cd "$TMP" && npm pack "nodejs-mobile-react-native@$NODEJS_MOBILE_VERSION" --silent >/dev/null && tar xzf nodejs-mobile-react-native-*.tgz)
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$TMP/package/android/libnode/bin/$abi/libnode.so" "$ROOT/app/src/main/jniLibs/$abi/libnode.so"
done

echo "› Bundling the WhatsApp engine"
cd "$ROOT/engine"
npm ci --no-audit --no-fund
npm run build
echo "✓ Ready to build"
