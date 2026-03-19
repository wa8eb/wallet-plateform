#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-stress.sh — run the Gatling simulation against both runtimes and print
# a side-by-side summary.
#
# Prerequisites: both services must be running.
#   Option A (local): ./gradlew bootRun / ./gradlew run in each repo
#   Option B (Docker): docker compose up
# ---------------------------------------------------------------------------
set -euo pipefail

USERS=${USERS:-50}
DURATION_SEC=${DURATION_SEC:-60}
JWT_SECRET=${JWT_SECRET:-"wallet-super-secret-key-32chars!!"}
SPRING_URL=${SPRING_URL:-"http://localhost:8080"}
VERTX_URL=${VERTX_URL:-"http://localhost:8081"}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPORTS_DIR="$SCRIPT_DIR/build/reports/stress"
mkdir -p "$REPORTS_DIR"

header() {
  echo ""
  echo "================================================================="
  echo "  $1"
  echo "================================================================="
  echo ""
}

run_against() {
  local runtime=$1
  local url=$2
  header "Running against $runtime  ($url)  |  users=$USERS  duration=${DURATION_SEC}s"

  BASE_URL="$url" \
  USERS="$USERS" \
  DURATION_SEC="$DURATION_SEC" \
  JWT_SECRET="$JWT_SECRET" \
    "$SCRIPT_DIR/gradlew" gatlingRun \
      --project-dir "$SCRIPT_DIR" \
      2>&1 | tee "$REPORTS_DIR/${runtime}.log" || true

  echo ""
  echo "--- $runtime summary ---"
  # Extract the Gatling stats table from the log
  grep -E "^\s*(Global|Requests|Response|percentile|Min |Max |Mean |Std)" \
    "$REPORTS_DIR/${runtime}.log" 2>/dev/null | head -20 || \
    echo "(Open build/reports/gatling/*/index.html for full report)"
}

run_against "spring" "$SPRING_URL"
run_against "vertx"  "$VERTX_URL"

header "DONE — HTML reports in build/reports/gatling/"
echo "  Spring : $REPORTS_DIR/spring.log"
echo "  Vert.x : $REPORTS_DIR/vertx.log"
echo ""
echo "Open build/reports/gatling/<timestamp>/index.html in your browser"
echo "for interactive charts (throughput, p99, error rate, active users)."
