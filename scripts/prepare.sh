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

echo "› Downloading the offline wake-word model (Vosk small English)"
MODEL_NAME="vosk-model-small-en-us-0.15"
MODEL_DIR="$ROOT/app/src/main/assets/vosk-model"
if [ ! -f "$MODEL_DIR/conf/model.conf" ]; then
  curl -fsSL --retry 3 -o "$TMP/model.zip" "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
  (cd "$TMP" && unzip -q model.zip)
  rm -rf "$MODEL_DIR"
  mkdir -p "$(dirname "$MODEL_DIR")"
  mv "$TMP/$MODEL_NAME" "$MODEL_DIR"
  rm -f "$MODEL_DIR/README"
fi
du -sh "$MODEL_DIR"

echo "› Bundling the WhatsApp engine"
cd "$ROOT/engine"
npm ci --no-audit --no-fund
npm run build
echo "✓ Ready to build"
