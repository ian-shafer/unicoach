#!/usr/bin/env bash
# render-env: fetch the full /unicoach/prod/ SSM prefix (decrypted) and write it
# to /etc/unicoach/env as KEY=VALUE lines. That single file is consumed two ways:
# as the systemd EnvironmentFile for both services, and as ENV_FILE sourced by
# bin/common during the on-instance migration step (bin/common does
# `set -a; source`, so the plain KEY=VALUE format satisfies both).
#
# Owner root, mode 0600 — it holds decrypted secrets.
set -euo pipefail

# cloud-init writes SSM_PREFIX/AWS_REGION here (from the OpenTofu-templated
# values); fall back to the single-deployment defaults if it is absent.
if [ -f /etc/unicoach/deploy.env ]; then
  # shellcheck disable=SC1091
  source /etc/unicoach/deploy.env
fi

SSM_PREFIX="${SSM_PREFIX:-/unicoach/prod}"
ENV_PATH="${ENV_PATH:-/etc/unicoach/env}"
AWS_REGION="${AWS_REGION:-us-east-1}"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# Page through the prefix with decryption, emitting Name<TAB>Value rows, then
# rewrite to KEY=VALUE (the bare name after the prefix). Values may contain '='
# or spaces, so quote the value and keep only the leaf parameter name as the key.
aws ssm get-parameters-by-path \
  --region "$AWS_REGION" \
  --path "$SSM_PREFIX" \
  --with-decryption \
  --recursive \
  --query 'Parameters[].[Name,Value]' \
  --output text \
  | while IFS=$'\t' read -r name value; do
      key="${name##*/}"
      printf '%s=%s\n' "$key" "$value"
    done > "$TMP"

if [ ! -s "$TMP" ]; then
  echo "[FATAL] No parameters found under $SSM_PREFIX" >&2
  exit 1
fi

install -o root -g root -m 0600 "$TMP" "$ENV_PATH"
echo "Wrote $ENV_PATH from $SSM_PREFIX"
