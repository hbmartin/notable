#!/usr/bin/env bash
# Regenerates config/detekt/baseline.xml using the detekt CLI.
# Run after intentionally cleaning up baselined findings; do not use it to
# bury new issues. Keep the version in sync with [versions].detekt in
# gradle/libs.versions.toml.
set -euo pipefail
cd "$(dirname "$0")/.."

DETEKT_VERSION="1.23.8"
JAR="${TMPDIR:-/tmp}/detekt-cli-${DETEKT_VERSION}-all.jar"

if [ ! -f "$JAR" ]; then
  curl -sSLo "$JAR" "https://repo.maven.apache.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar"
fi

java -jar "$JAR" \
  --input app/src/main/java,app/src/test/java,app/src/androidTest/java \
  --config config/detekt/detekt.yml \
  --build-upon-default-config \
  --baseline config/detekt/baseline.xml \
  --create-baseline
