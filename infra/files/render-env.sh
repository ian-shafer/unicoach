#!/usr/bin/env bash
# render-env <release-dir>: materialize the COMPLETE /etc/unicoach/env by merging
# two sources — the release bundle's flat `deploy-env` (non-secret box config,
# authored as .env -> .env.<env> and flattened on the laptop by
# bin/gen-deployed-env) UNDER the env's SSM prefix (secrets + Terraform-owned RDS
# identity, SSM last-wins on any overlap). The result is one flat KEY=VALUE file
# both on-host consumers read identically: the systemd EnvironmentFile for every
# service, and ENV_FILE sourced by bin/common during the on-instance migration
# step (bin/common does `set -a; source`, and every value is already a literal, so
# there is nothing left to expand).
#
# Owner root, mode 0600 — it holds decrypted secrets.
set -euo pipefail

RELEASE_DIR="${1:?usage: render-env.sh <release-dir>}"
DEPLOY_ENV="$RELEASE_DIR/deploy-env"
if [ ! -f "$DEPLOY_ENV" ]; then
  echo "[FATAL] deploy-env not found in release dir [$DEPLOY_ENV]" >&2
  exit 1
fi

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
# Owner/group of the rendered file. Defaults to root:root (production: it holds
# decrypted secrets). Overridable ONLY so a non-root test can drive the real merge
# and assert mode 0600 without chowning to root; production never sets these.
ENV_OWNER="${ENV_OWNER:-root}"
ENV_GROUP="${ENV_GROUP:-root}"

SSM_TMP="$(mktemp)"
MERGED_TMP="$(mktemp)"
trap 'rm -f "$SSM_TMP" "$MERGED_TMP"' EXIT

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
    done > "$SSM_TMP"

if [ ! -s "$SSM_TMP" ]; then
  echo "[FATAL] No parameters found under $SSM_PREFIX" >&2
  exit 1
fi

# Refuse to render an env that still carries an unseeded out-of-band secret. The
# SecureString placeholders (ssm.tf) are stamped PLACEHOLDER_SEED_OUT_OF_BAND
# until the operator seeds the real value; rendering one would boot the app with
# a bogus DB password / API key. Fail closed and name the offending key(s).
if grep -q 'PLACEHOLDER_SEED_OUT_OF_BAND' "$SSM_TMP"; then
  echo "[FATAL] Unseeded secret(s) under $SSM_PREFIX (still PLACEHOLDER_SEED_OUT_OF_BAND):" >&2
  grep -F 'PLACEHOLDER_SEED_OUT_OF_BAND' "$SSM_TMP" | cut -d= -f1 | sed 's/^/  /' >&2
  exit 1
fi

# Merge: deploy-env first, SSM second, so SSM wins on any key present in both
# (secrets and RDS identity are SSM-owned; the non-secret box config is deploy-env-
# owned, and the two sets are disjoint by the key-ownership rule). Both files are
# already flat KEY=<literal-value>, so this is a plain concatenation whose last
# occurrence of a key wins when bin/common / systemd read it top-to-bottom — but
# to keep the file itself unambiguous, emit deploy-env keys that SSM does NOT
# redefine, then all SSM keys.
awk -F= '
  NR == FNR { ssm_keys[$1] = 1; next }         # first pass: record SSM key names
  { if (!($1 in ssm_keys)) print }             # deploy-env lines SSM does not override
' "$SSM_TMP" "$DEPLOY_ENV" > "$MERGED_TMP"
cat "$SSM_TMP" >> "$MERGED_TMP"

install -o "$ENV_OWNER" -g "$ENV_GROUP" -m 0600 "$MERGED_TMP" "$ENV_PATH"
echo "Wrote $ENV_PATH from $DEPLOY_ENV merged under $SSM_PREFIX (SSM last-wins)"
