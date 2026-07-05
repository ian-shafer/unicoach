#!/usr/bin/env bash
# deploy-on-instance: invoked on the EC2 host via SSM Run Command with the path to
# an ALREADY-EXTRACTED release dir. The SSM command string that bin/deploy composes
# on the laptop and sends fresh each deploy is what fetches and unpacks the bundle,
# installs the deploy-managed on-instance files (this script, render-env, and the
# service units) from it, and then invokes this script — so a plain `bin/deploy`
# updates these files with no manual step. Because that install happens in the
# transmitted command (not a pre-installed script), the current release's copy of
# this script always runs. This script refreshes the env file from SSM, runs
# migrations against RDS, then atomically repoints the current symlink and restarts
# every service unit.
#
# The symlink swap and restart occur ONLY after migrations succeed, so a failed
# migration leaves the previous release serving.
set -euo pipefail

RELEASE_DIR="${1:?usage: deploy-on-instance.sh <release-dir>}"
# The SSM command that invokes this script extracts the bundle here first, but
# assert the dir exists before render-env / cd depend on it, so a future caller
# that skips the extraction fails loudly rather than acting on a bad path.
[ -d "$RELEASE_DIR" ] || { echo "[FATAL] release dir not found [$RELEASE_DIR]" >&2; exit 1; }

APP_ROOT="/opt/unicoach"
ENV_FILE="/etc/unicoach/env"

echo "Deploying release from [$RELEASE_DIR]..."

# ── 1. Refresh /etc/unicoach/env (bundle deploy-env merged under SSM) ─────────
# render-env merges this release's flat deploy-env (non-secret box config) UNDER
# the SSM prefix (secrets + RDS identity, SSM last-wins), writing the complete
# /etc/unicoach/env. Pass this release's dir so it reads the fresh deploy-env.
/opt/unicoach/bin/render-env "$RELEASE_DIR"

# ── 2. Run migrations against RDS (before the symlink swap) ────────────────────
# bin/common layers dotenv: the host carries an intentionally EMPTY base .env, and
# ENV_FILE pins the complete SSM-rendered /etc/unicoach/env as the delta, so the
# same `base -> delta` shape runs here as locally (the empty base contributes
# nothing). The master-role psql steps require PGPASSWORD (RDS rejects libpq trust
# auth); an absent PGPASSWORD fails here, before the symlink swap.
(
  cd "$RELEASE_DIR"
  ENV_FILE="$ENV_FILE" bin/db-create-role
  ENV_FILE="$ENV_FILE" bin/db-create
  ENV_FILE="$ENV_FILE" bin/db-migrate
)

# ── 3. Atomically repoint current and restart every service unit ──────────────
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"
systemctl restart unicoach-rest-server.service
systemctl restart unicoach-queue-worker.service
systemctl restart unicoach-cron.service
systemctl restart unicoach-public-web.service
systemctl restart unicoach-admin-web.service

echo "Deploy from [$RELEASE_DIR] complete."
