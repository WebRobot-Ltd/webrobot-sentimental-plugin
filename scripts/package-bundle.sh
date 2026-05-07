#!/usr/bin/env bash
# package-bundle.sh — assemble the unified plugin bundle ZIP.
#
# Layout produced (see docs/BUNDLE_DISTRIBUTION_FORMAT.md in
# webrobot-elt-clouddashboard for the full spec):
#
#   sentimental-plugin-<version>.zip
#   ├── manifest.json         root manifest declaring all components
#   ├── api/  <jar> + manifest.json
#   ├── etl/  <jar> + manifest.json
#   ├── cli/  <jar>                          (optional, only if built)
#   ├── ui/   manifest.json + dist/*.js      (output of nextjs/yarn build)
#   └── db/migration/*.sql                   (if any)
#
# This script is a reference implementation. The Jersey endpoint
# `POST /admin/bundles/install` (TBD) will read the layout above.
#
# Usage:
#   scripts/package-bundle.sh           # builds everything from scratch
#   scripts/package-bundle.sh --skip-build  # reuse existing artefacts (faster local iteration)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SKIP_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# Read pluginId + version from the root manifest.json so the script
# stays in sync with the source of truth.
PLUGIN_ID=$(node -p "require('$ROOT_DIR/manifest.json').pluginId")
VERSION=$(node -p   "require('$ROOT_DIR/manifest.json').version")

BUNDLE_NAME="${PLUGIN_ID}-${VERSION}.zip"
STAGE_DIR="$ROOT_DIR/build/bundle-stage"
OUT_DIR="$ROOT_DIR/build"
OUT="$OUT_DIR/$BUNDLE_NAME"

echo "→ Packaging $PLUGIN_ID v$VERSION  (out: $OUT)"

# Clean stage and output
rm -rf "$STAGE_DIR" "$OUT"
mkdir -p "$STAGE_DIR" "$OUT_DIR"

# ── 1. ETL: Gradle ─────────────────────────────────────────────────
if [ -d "$ROOT_DIR/etl" ]; then
  if ! $SKIP_BUILD; then
    echo "→ Building etl/  (Gradle)"
    (cd "$ROOT_DIR/etl" && ./gradlew --quiet shadowJar) || (cd "$ROOT_DIR" && ./gradlew --quiet etl:shadowJar)
  fi
  ETL_JAR=$(ls -1 "$ROOT_DIR"/etl/build/libs/*-all.jar 2>/dev/null | head -1 || true)
  [ -z "$ETL_JAR" ] && ETL_JAR=$(ls -1 "$ROOT_DIR"/etl/build/libs/*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)
  if [ -n "$ETL_JAR" ]; then
    mkdir -p "$STAGE_DIR/etl"
    cp "$ETL_JAR" "$STAGE_DIR/etl/$(basename "$ETL_JAR")"
    [ -f "$ROOT_DIR/etl/manifest.json" ] && cp "$ROOT_DIR/etl/manifest.json" "$STAGE_DIR/etl/"
    echo "  + etl/$(basename "$ETL_JAR")"
  else
    echo "  ! no etl jar found, skipping" >&2
  fi
fi

# ── 2. API: Maven ──────────────────────────────────────────────────
if [ -d "$ROOT_DIR/api" ]; then
  if ! $SKIP_BUILD; then
    echo "→ Building api/  (Maven)"
    (cd "$ROOT_DIR/api" && mvn -q -DskipTests package)
  fi
  API_JAR=$(ls -1 "$ROOT_DIR"/api/target/*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)
  if [ -n "$API_JAR" ]; then
    mkdir -p "$STAGE_DIR/api"
    cp "$API_JAR" "$STAGE_DIR/api/$(basename "$API_JAR")"
    [ -f "$ROOT_DIR/api/manifest.json" ] && cp "$ROOT_DIR/api/manifest.json" "$STAGE_DIR/api/"
    echo "  + api/$(basename "$API_JAR")"
  else
    echo "  ! no api jar found, skipping" >&2
  fi
fi

# ── 3. CLI: Maven (optional) ───────────────────────────────────────
if [ -d "$ROOT_DIR/cli" ]; then
  if ! $SKIP_BUILD; then
    echo "→ Building cli/  (Maven)"
    (cd "$ROOT_DIR/cli" && mvn -q -DskipTests package) || echo "  ! cli build failed, treating as optional" >&2
  fi
  CLI_JAR=$(ls -1 "$ROOT_DIR"/cli/target/*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)
  if [ -n "$CLI_JAR" ]; then
    mkdir -p "$STAGE_DIR/cli"
    cp "$CLI_JAR" "$STAGE_DIR/cli/$(basename "$CLI_JAR")"
    echo "  + cli/$(basename "$CLI_JAR")"
  fi
fi

# ── 4. UI: Vite ────────────────────────────────────────────────────
if [ -d "$ROOT_DIR/nextjs" ]; then
  if ! $SKIP_BUILD; then
    echo "→ Building nextjs/  (Vite)"
    (cd "$ROOT_DIR/nextjs" && [ -d node_modules ] || npm install --silent)
    (cd "$ROOT_DIR/nextjs" && node ./scripts/build.mjs >/dev/null)
  fi
  if [ -d "$ROOT_DIR/nextjs/dist" ] && [ -f "$ROOT_DIR/nextjs/manifest.json" ]; then
    mkdir -p "$STAGE_DIR/ui"
    cp "$ROOT_DIR/nextjs/manifest.json" "$STAGE_DIR/ui/manifest.json"
    cp -r "$ROOT_DIR/nextjs/dist" "$STAGE_DIR/ui/dist"
    echo "  + ui/dist (per-view bundles) + manifest.json"
  else
    echo "  ! no ui dist/, skipping" >&2
  fi
fi

# ── 5. DB migrations ───────────────────────────────────────────────
for src in db/migration etl/src/main/resources/db/migration api/src/main/resources/db/migration; do
  if [ -d "$ROOT_DIR/$src" ] && ls "$ROOT_DIR/$src"/*.sql >/dev/null 2>&1; then
    mkdir -p "$STAGE_DIR/db/migration"
    cp "$ROOT_DIR/$src"/*.sql "$STAGE_DIR/db/migration/" 2>/dev/null || true
    echo "  + db/migration/ from $src"
    break
  fi
done

# ── 6. Root manifest ───────────────────────────────────────────────
cp "$ROOT_DIR/manifest.json" "$STAGE_DIR/manifest.json"

# ── 7. ZIP it up ───────────────────────────────────────────────────
echo "→ Packing $OUT"
(cd "$STAGE_DIR" && zip -qr "$OUT" . )
SIZE=$(stat -c %s "$OUT")
echo "✅ $OUT  ($SIZE bytes)"

# ── 8. Show the layout for review ──────────────────────────────────
echo
echo "Bundle contents:"
unzip -l "$OUT" | tail -n +4 | head -n -2
