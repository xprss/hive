#!/usr/bin/env bash
set -euo pipefail
repo="${1:-xprss/hive}"
secrets_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.local-secrets" && pwd)"
for name in hive-release.p12 store-password key-alias key-password; do
  test -s "$secrets_dir/$name"
done
base64 < "$secrets_dir/hive-release.p12" | tr -d '\r\n' | gh secret set -R "$repo" HIVE_RELEASE_KEYSTORE_BASE64
gh secret set -R "$repo" HIVE_RELEASE_STORE_PASSWORD < "$secrets_dir/store-password"
gh secret set -R "$repo" HIVE_RELEASE_KEY_ALIAS < "$secrets_dir/key-alias"
gh secret set -R "$repo" HIVE_RELEASE_KEY_PASSWORD < "$secrets_dir/key-password"
gh secret list -R "$repo"
