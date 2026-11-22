#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ ! -f dist/lostfound.jar ]]; then ./build.sh; fi
if [[ ! -f lib/h2-2.3.232.jar || ! -f lib/imageio-webp-3.15.3.jar ]]; then
  printf '%s\n' 'Downloading SQL and image readers for the first run...'
  ./scripts/download-dependencies.sh
fi
exec java -cp 'dist/lostfound.jar:lib/*' edu.usc.lostfound.App "$@"
