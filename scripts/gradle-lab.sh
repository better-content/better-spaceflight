#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.17-tem"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$repo/.gradle-user-home"
export TMPDIR="$repo/.tmp/java"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMPDIR"
mkdir -p "$TMPDIR" "$GRADLE_USER_HOME"
cd "$repo"
# Same-project outputs/cache/runtime only. Independent labs may run in parallel.
# Fail fast; never queue or bypass another process using these VS resources.
exec flock -n -E 75 "$repo/.tmp/spaceflight-runtime.lock" ./gradlew "$@" --no-daemon --max-workers=2
