#!/usr/bin/env bash
# Build the APK inside Docker. Usage:
#   ./tools/build.sh                 # assembleDebug
#   ./tools/build.sh test            # any gradle task
#   ./tools/build.sh wrapper         # generate the gradle wrapper
#   ./tools/build.sh shell           # bash inside the container
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=${IMAGE:-freeps-android-dev}
GRADLE_VOL=${GRADLE_VOL:-freeps-gradle-home}
ANDROID_VOL=${ANDROID_VOL:-freeps-android-home}

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "==> building image $IMAGE (one-off, downloads ~2 GB)"
  docker build -f tools/Dockerfile.dev \
    --build-arg UID="$(id -u)" --build-arg GID="$(id -g)" \
    -t "$IMAGE" .
fi

TTY=()
if [[ -t 1 && -t 0 ]]; then TTY=(-it); fi

run() { docker run --rm "${TTY[@]}" -v "$PWD:/src" \
  -v "$GRADLE_VOL:/home/gradle/.gradle" \
  -v "$ANDROID_VOL:/home/gradle/.android" -w /src "$@"; }

# Use the wrapper once it exists, the image's own gradle until then.
if [[ -x ./gradlew ]]; then BIN=./gradlew; else BIN=gradle; fi

# The base image has ENTRYPOINT=gradle, so always override it explicitly.
# Default to "build"; a bare "shift" would fail under set -e with no arguments.
set -- "${1:-build}" "${@:2}"
case "$1" in
  shell)   shift; run --entrypoint bash "$IMAGE" "$@" ;;
  wrapper) shift; run --entrypoint gradle "$IMAGE" wrapper --gradle-version 8.11.1 "$@" ;;
  build)   shift; run --entrypoint "$BIN" "$IMAGE" assembleDebug "$@" ;;
  *)       run --entrypoint "$BIN" "$IMAGE" "$@" ;;
esac
