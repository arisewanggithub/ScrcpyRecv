#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$root/publication-output/avd-property-fixtures-$$"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT
for profile in release-smoke debug-native; do
  mkdir -p "$scratch/$profile.avd"
  config="$scratch/$profile.avd/config.ini"
  printf 'hw.gpu.enabled=yes\ndisk.dataPartition.size=7372M\n  disk.dataPartition.size = 7G\n' > "$config"
  for repeat in 1 2; do
    GITHUB_ACTIONS=true RUNNER_OS=Linux ANDROID_AVD_HOME="$scratch" bash "$root/tools/set-ci-avd-userdata.sh" "$config" > /dev/null
    [[ "$(grep -c '^[[:space:]]*disk\.dataPartition\.size[[:space:]]*=' "$config")" == 1 ]]
    grep -qx 'disk.dataPartition.size=2G' "$config"
    grep -qx 'hw.gpu.enabled=yes' "$config"
  done
done
mkdir -p "$scratch/unowned.avd"
printf 'disk.dataPartition.size=7G\n' > "$scratch/unowned.avd/config.ini"
if GITHUB_ACTIONS=true RUNNER_OS=Linux ANDROID_AVD_HOME="$scratch" bash "$root/tools/set-ci-avd-userdata.sh" "$scratch/unowned.avd/config.ini" > /dev/null; then exit 1; fi
grep -qx 'disk.dataPartition.size=7G' "$scratch/unowned.avd/config.ini"
if GITHUB_ACTIONS=false RUNNER_OS=Linux ANDROID_AVD_HOME="$scratch" bash "$root/tools/set-ci-avd-userdata.sh" "$config" > /dev/null; then exit 1; fi
echo "PASS AVD userdata policy: 2G, duplicate removal, idempotence, unrelated settings and CI/profile guards"
