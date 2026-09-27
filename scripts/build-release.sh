#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
secrets_dir="$project_root/.local-secrets"
export HIVE_RELEASE_STORE_FILE="$secrets_dir/hive-release.p12"
export HIVE_RELEASE_STORE_PASSWORD="$(cat "$secrets_dir/store-password")"
export HIVE_RELEASE_KEY_ALIAS="$(cat "$secrets_dir/key-alias")"
export HIVE_RELEASE_KEY_PASSWORD="$(cat "$secrets_dir/key-password")"
cd "$project_root/android"
"${GRADLE_BIN:-gradle}" --no-daemon :app:testDebugUnitTest :app:assembleRelease
