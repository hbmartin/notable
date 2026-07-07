#!/usr/bin/env bash
# Regenerates config/detekt/baseline.xml using the detekt CLI.
# Run after intentionally cleaning up baselined findings; do not use it to
# bury new issues. Keep the version in sync with [versions].detekt in
# gradle/libs.versions.toml.
set -euo pipefail
cd "$(dirname "$0")/.."

DETEKT_VERSION="1.23.8"
JAR="${TMPDIR:-/tmp}/detekt-cli-${DETEKT_VERSION}-all.jar"
JAR_SHA256="3afe89a11120303c73c9bdda3d8fe558dd9070a6937d27819ddc04b275381245"

if [ ! -f "$JAR" ]; then
  curl -sSLo "$JAR" "https://repo.maven.apache.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar"
fi

actual_sha256="$(shasum -a 256 "$JAR" | awk '{print $1}')"
if [ "$actual_sha256" != "$JAR_SHA256" ]; then
  rm -f "$JAR"
  echo "detekt-cli checksum mismatch for $JAR" >&2
  echo "expected: $JAR_SHA256" >&2
  echo "actual:   $actual_sha256" >&2
  exit 1
fi

java -jar "$JAR" \
  --input app/src/main/java,app/src/test/java,app/src/androidTest/java \
  --config config/detekt/detekt.yml \
  --build-upon-default-config \
  --baseline config/detekt/baseline.xml \
  --create-baseline
