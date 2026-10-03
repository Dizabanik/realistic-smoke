#!/usr/bin/env sh
set -eu
if [ -x "./gradlew" ]; then
    exec ./gradlew build "$@"
fi
exec gradle build "$@"

