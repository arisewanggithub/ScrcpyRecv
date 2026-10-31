#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux && -n "${ANDROID_AVD_HOME:-}" ]] || { echo "AVD userdata sizing is CI-only"; exit 1; }
config="${1:-}"
case "$config" in
  "$ANDROID_AVD_HOME/release-smoke.avd/config.ini"|"$ANDROID_AVD_HOME/debug-native.avd/config.ini"|"$ANDROID_AVD_HOME/update-ui.avd/config.ini") ;;
  *) echo "Refusing to edit an AVD outside the declared disposable profiles"; exit 1 ;;
esac
[[ -f "$config" ]] || { echo "Disposable AVD config missing: $config"; exit 1; }
# Official hardware-properties.ini: disk.dataPartition.size is a diskSize.
# Two GiB is ample for these small test APKs without the default ~7 GiB image.
sed -i '/^[[:space:]]*disk\.dataPartition\.size[[:space:]]*=/d' "$config"
printf '\ndisk.dataPartition.size=2G\n' >> "$config"
echo "Configured disposable AVD userdata: 2G"
