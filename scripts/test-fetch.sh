#!/usr/bin/env bash
# Run the openthumb-fetch checks on the host, the same file the phone runs.
# Offline and dependency-free, so CI can run it without an emulator.
#
#   scripts/test-fetch.sh
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
exec python3 \
  "$ROOT/src/android/app/src/main/assets/default_mount/usr/local/lib/openthumb-fetch/test_fetch.py"
