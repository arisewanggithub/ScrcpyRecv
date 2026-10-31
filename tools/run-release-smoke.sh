#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] || { echo "Release smoke runs only on the disposable Linux Actions runner"; exit 1; }
for variable in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_BASE64 ANDROID_KEY_ALIAS ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD; do
  if [[ -v "$variable" ]]; then echo "Production signing configuration is forbidden in release smoke"; exit 1; fi
done
application_id=org.screencopy.receiver
classes=org.screencopy.receiver.media.NativeSrtTransportTest,org.screencopy.receiver.media.OpusDecoderTest,org.screencopy.receiver.ReleaseRuntimeSmokeTest
expected=8
app=app/build/outputs/apk/release/app-release.apk
test_apk=app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
output=publication-output/release-smoke
adb="$ANDROID_HOME/platform-tools/adb"
mkdir -p "$output"
[[ -s app/build/outputs/mapping/release/mapping.txt ]] || { echo "Optimized release mapping missing"; exit 1; }
pwsh -NoProfile -File tools/verify-release-smoke-apks.ps1 -App "$app" -TestApk "$test_apk" -ApplicationId "$application_id" > "$output/apk-policy.txt"
signer() {
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
app_signer="$(signer "$app")"
[[ "$app_signer" =~ ^[0-9a-fA-F]{64}$ && "$app_signer" == "$(signer "$test_apk")" ]] || { echo "App/test signing certificates must match"; exit 1; }
: > "$output/instrumentation-result.txt"
diagnostics() {
  status=$?
  trap - EXIT
  set +e
  printf '%s\n' "$status" > "$output/exit-status.txt"
  timeout 10 "$adb" -s emulator-5554 logcat -d -v threadtime AndroidRuntime:E TestRunner:V AndroidJUnitRunner:V '*:S' > "$output/test-logcat.txt" 2>&1
  cat "$output/instrumentation-result.txt"
  tail -n 100 "$output/emulator.log"
  timeout 10 "$adb" -s emulator-5554 emu kill
  if [[ -n "${emulator_pid:-}" ]] && kill -0 "$emulator_pid" 2>/dev/null; then kill "$emulator_pid"; fi
  exit "$status"
}
trap diagnostics EXIT
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo "Disposable emulator requires accessible KVM"; exit 1; }
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name release-smoke --package "system-images;android-35;google_apis;x86_64"
"$ANDROID_HOME/emulator/emulator" -avd release-smoke -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on > "$output/emulator.log" 2>&1 &
emulator_pid=$!
timeout 180 "$adb" -s emulator-5554 wait-for-device
booted=false
for attempt in $(seq 1 90); do
  kill -0 "$emulator_pid"
  if [[ "$("$adb" -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
[[ "$booted" == true ]] || { echo "Disposable release-smoke emulator did not boot"; exit 1; }
[[ "$("$adb" -s emulator-5554 shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "Not an emulator"; exit 1; }
[[ "$("$adb" -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]] || { echo "Not API35"; exit 1; }
"$adb" -s emulator-5554 install -r "$app"
"$adb" -s emulator-5554 install -r "$test_apk"
timeout 300 "$adb" -s emulator-5554 shell am instrument -w -r -e releaseSmoke true -e class "$classes" "$application_id.test/dev.scrcpy.smoke.ReleaseSmokeRunner" > "$output/instrumentation-result.txt" 2>&1
sed -i 's/\r$//' "$output/instrumentation-result.txt"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]|INSTRUMENTATION_CODE: 0' "$output/instrumentation-result.txt"; then exit 1; fi
grep -Eq "OK \\($expected tests?\\)" "$output/instrumentation-result.txt"
[[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: -1$' "$output/instrumentation-result.txt")" == "$expected" ]] || { echo "Every selected smoke test must execute successfully; skips are forbidden"; exit 1; }
