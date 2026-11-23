#!/bin/sh
# Regenerates every image in docs/readme/ from the console build. See README.md in this folder.
#   ./generate.sh           uses the existing web/dist
#   ./generate.sh --build   builds web/ first
set -e
cd "$(dirname "$0")"
if [ "$1" = "--build" ]; then (cd ../../web && npm run build); fi
[ -f ../../web/dist/index.html ] || { echo "web/dist not found: run ./generate.sh --build (after npm ci in web/)"; exit 1; }
[ -d node_modules ] || npm install --no-audit --no-fund
PY=${PYTHON:-python3}
[ -x .venv/bin/python ] && PY=.venv/bin/python
"$PY" -c 'import fontTools' 2>/dev/null || {
  echo "The SVG step needs Python with fontTools:"
  echo "  python3 -m venv scripts/readme/.venv && scripts/readme/.venv/bin/pip install fonttools"
  exit 1
}
node capture.mjs        # console crops, 2x, light + dark   -> .cache/shots/
node compose.mjs        # hero, features, how-it-works parts -> docs/readme/*.webp, .cache/parts/
"$PY" svg/build.py      # banner, how-it-works, architecture -> docs/readme/*.svg
ls -l ../../docs/readme
