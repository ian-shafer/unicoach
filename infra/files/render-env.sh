#!/usr/bin/env bash
# render-env: fetch the full per-env SSM prefix (decrypted) and write it to
# /etc/unicoach/env as KEY=VALUE lines. That single file is consumed two ways:
# as the systemd EnvironmentFile for both services, and as ENV_FILE sourced by
# bin/common during the on-instance migration step (bin/common does
# `set -a; source`, so the plain KEY=VALUE format satisfies both).
#
# Owner root, mode 0600 — it holds decrypted secrets.
set -euo pipefail

# cloud-init writes SSM_PREFIX/AWS_REGION here (from the OpenTofu-templated
# per-env values). SSM_PREFIX is environment identity and has NO default: a
# default would let a host that fails to source its prefix fetch a different
# env's secrets (e.g. prod's) — a cross-env leak. So it is required and fatal if
# absent. AWS_REGION is not env identity (a wrong region fails closed: the SSM
# fetch errors rather than leaking), so it keeps a default.
if [ -f /etc/unicoach/deploy.env ]; then
  # shellcheck disable=SC1091
  source /etc/unicoach/deploy.env
fi

if [ -z "${SSM_PREFIX:-}" ]; then
  echo "[FATAL] SSM_PREFIX is unset; expected it sourced from /etc/unicoach/deploy.env" >&2
  exit 1
fi
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

# Refuse to render an env that still carries an unseeded out-of-band secret. The
# SecureString placeholders (ssm.tf) are stamped PLACEHOLDER_SEED_OUT_OF_BAND
# until the operator seeds the real value; rendering one would boot the app with
# a bogus DB password / API key. Fail closed and name the offending key(s).
if grep -q 'PLACEHOLDER_SEED_OUT_OF_BAND' "$TMP"; then
  echo "[FATAL] Unseeded secret(s) under $SSM_PREFIX (still PLACEHOLDER_SEED_OUT_OF_BAND):" >&2
  grep -F 'PLACEHOLDER_SEED_OUT_OF_BAND' "$TMP" | cut -d= -f1 | sed 's/^/  /' >&2
  exit 1
fi

install -o root -g root -m 0600 "$TMP" "$ENV_PATH"
echo "Wrote $ENV_PATH from $SSM_PREFIX"
