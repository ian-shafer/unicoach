#!/usr/bin/env bash
# deploy-on-instance: invoked on the EC2 host via SSM Run Command with the S3 URI
# of a release bundle. Unpacks the bundle into a fresh release dir, refreshes the
# env file from SSM, runs migrations against RDS, then atomically repoints the
# current symlink and restarts every service unit.
#
# The symlink swap and restart occur ONLY after migrations succeed, so a failed
# migration leaves the previous release serving.
set -euo pipefail

# cloud-init writes SSM_PREFIX/AWS_REGION here (from the OpenTofu-templated
# values); fall back to the single-deployment default if it is absent.
if [ -f /etc/unicoach/deploy.env ]; then
  # shellcheck disable=SC1091
  source /etc/unicoach/deploy.env
fi

BUNDLE_S3_URI="${1:?usage: deploy-on-instance.sh <s3-uri>}"
AWS_REGION="${AWS_REGION:-us-east-1}"

APP_ROOT="/opt/unicoach"
RELEASES_DIR="$APP_ROOT/releases"
ENV_FILE="/etc/unicoach/env"
RELEASE_ID="$(date -u +%Y%m%d%H%M%S)"
RELEASE_DIR="$RELEASES_DIR/$RELEASE_ID"

echo "Deploying release [$RELEASE_ID] from [$BUNDLE_S3_URI]..."

# ── 1. Fetch and unpack the bundle (preserves repo-relative paths) ────────────
mkdir -p "$RELEASE_DIR"
BUNDLE_TMP="$(mktemp)"
trap 'rm -f "$BUNDLE_TMP"' EXIT
aws s3 cp --region "$AWS_REGION" "$BUNDLE_S3_URI" "$BUNDLE_TMP"
tar -xzf "$BUNDLE_TMP" -C "$RELEASE_DIR"
chown -R unicoach:unicoach "$RELEASE_DIR"

# ── 2. Refresh /etc/unicoach/env (bundle deploy-env merged under SSM) ─────────
# render-env merges this release's flat deploy-env (non-secret box config) UNDER
# the SSM prefix (secrets + RDS identity, SSM last-wins), writing the complete
# /etc/unicoach/env. Pass this release's dir so it reads the fresh deploy-env.
/opt/unicoach/bin/render-env "$RELEASE_DIR"

# ── 3. Run migrations against RDS (before the symlink swap) ────────────────────
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

# ── 4. Atomically repoint current and restart every service unit ──────────────
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"
systemctl restart unicoach-rest-server.service
systemctl restart unicoach-queue-worker.service
systemctl restart unicoach-public-web.service
systemctl restart unicoach-admin-web.service

echo "Deploy of release [$RELEASE_ID] complete."
