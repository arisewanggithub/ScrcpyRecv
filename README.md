# ScrcpyRecv

Android receiver for [ScrcpySend](https://github.com/arisewanggithub/ScrcpySend)
on a trusted family LAN. **SRT / fragmented MP4** playback uses **libsrt1.5.7**
for transport and **Media3 ExoPlayer1.11.1** for extraction, codecs and timing.
A **custom TCP** compatibility path uses MediaCodec and AudioTrack directly.
These are independent applications, not official scrcpy applications.

The Android application ID is `org.screencopy.receiver`. The on-wire
`SCPY` framing uses **wire protocol version 3**, a compatibility discriminator
separate from application release versions. Session IDs and track generations
identify internal resources.

## Architecture and supported formats

- SRT supports **H.264, H.265 or AV1**, with **Opus, AAC-LC or audio off**.
  Media3 handles the standard fragmented MP4 container and playback clocks;
  libsrt supplies a cancellable transport, not an alternative decoder.
- Custom TCP supports the negotiated video formats plus Opus/AAC/PCM audio.
  Bundled **libopus1.5.2** handles Opus initialization, pre-skip, gain and decode.
- Selected settings and actual codec metadata are validated before admission.
  Unsupported codecs/configurations fail explicitly; requested audio is not
  silently disabled, and media is not silently converted to another transport.
- Local SRT builds use `-PsrtTrial=true`; the default profile is custom TCP.
  Signed release builds include both options.
- The waiting screen is replaced by aspect-fit video only after actual rendered
  video and the required audio readiness. A socket connection alone is not
  playback success.

See [DESIGN.md](DESIGN.md) and [PROTOCOL.md](PROTOCOL.md) for pipeline ownership,
exact schemas, packet validation and resource bounds.

## Trust and interoperability

Use a reachable **trusted private LAN**. Discovery, control and media are
unencrypted and unauthenticated; SRT encryption is disabled. Names are labels,
not trusted identities. The sender selects the actual UDP-source IP/TCP port,
not an advertised redirect or matching name. Do not expose the listener to the
internet or assume protection against LAN observers or spoofed discovery.

Both applications must implement **wire protocol version 3**. Positive internal session
IDs, track generations and readiness checks are mandatory. One cast is admitted;
another receives `BUSY`. UDP discovery uses port **27183**, and the default
control TCP listener uses **27184**. Wi-Fi/AP client isolation may block
discovery or connections.

## Install and receive

1. Install ScrcpyRecv normally and connect it to the same reachable LAN as
   ScrcpySend. Receiving needs no ADB, root, Developer options or microphone
   permission.
2. Open ScrcpyRecv and keep its screen visible. The waiting page shows actual
   IP addresses, concise status and remote-accessible idle actions.
3. Select the discovered receiver in ScrcpySend and choose Play. ScrcpySend
   owns capture settings; ScrcpyRecv accepts or rejects them explicitly.
4. Stop on the sender. After owned cleanup, the receiver returns to Ready.
   Exit and ordinary Android Back/Home finish/leave through normal lifecycle
   cleanup, not a process kill.

The UI supports native portrait/landscape layouts, D-pad/Center/Enter focus,
window/cutout insets and overscan-safe margins. Playback is aspect-fit and
letterboxed rather than stretched or cropped again. The visible Activity stays
awake; it does not change system power or keyguard settings.

The sender may request receiver orientation `auto`, `landscape` or `portrait`
for a session. It affects presentation only, not capture dimensions, media
rotation or timestamps. The previous Activity policy is restored on teardown.
Fixed-orientation hardware, multi-window and large-screen OS policies may ignore
an orientation request.

## Current limitations

- Real-device **H.265 capture/Stop/recast safety on the sender remains
  unverified** because kernel-panic risk has been reported. Successful builds
  and synthetic codec tests do not establish safety of real screen capture.
- H.264/H.265 in-band configuration handling requires compatible decoder
  adaptation. AV1/audio configuration changes require a new supported session.
  AAC/AV1 hardware playback, audio routes and interruption cleanup need
  device validation.
- Endurance, A/V offset and glass-to-glass latency require measurements;
  transport/buffer settings are not performance guarantees.
- No recording, remote input injection, internet traversal, IPv6 discovery or
  background receiving is provided. Network changes end the cast and refresh
  addresses. Android TV/store or device-specific certification is not claimed.
- Media is not recorded; backups/device transfer are excluded and screenshots
  are blocked while media is visible. Update traffic has the separate HTTPS
  privacy/security contract documented in [UPDATES.md](UPDATES.md).

## Build and verification

Use **JDK21**, public **Android SDK36 / Build Tools36.0.0**,
**NDK27.2.12479018** and **CMake3.22.1**. CI also provisions **Node22**.
The **Gradle9.8.1** wrapper is checksum-pinned; **AGP9.4.1** supplies built-in
Kotlin support and JVM17 targets. Minimum SDK is **26**; target/compile SDK is
**36**. Native ABIs are **arm64-v8a**, **armeabi-v7a** and **x86_64**, not
hardware compatibility certifications.

From your checkout, with `JAVA_HOME` set to JDK21:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" # Or your existing SDK location.
.\gradlew.bat :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PsrtTrial=true --no-daemon --console plain
```

Also check the default profile with `-PsrtTrial=false`. Debug outputs are
`app\build\outputs\apk\debug\app-debug.apk` and
`app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`.
The optional `.\tools\interop.ps1 -SenderRoot ..\ScrcpySend` reads sender
sources without building/writing that checkout; `SCRCPY_SEND_ROOT` can
override the sibling default.

CI runs JVM tests, lint and debug builds in both profiles. Its disposable Linux
**API35 x86_64** emulator runs only
`dev.scrcpy.update.AppUpdateRemoteUiTest`, not capture or codec acceptance
tests. APKs are built before emulator boot. Failure/timeout retains partial
instrumentation output, targeted test logcat and the original exit status;
that emulator job uploads only disposable UI diagnostics. Verification claims must use the
current run, not historical totals.
CI also retains app JUnit XML when available for exact failure messages/stacks,
without uploading APKs or signing material.

## Updates and signed releases

The D-pad-friendly updater checks the latest stable release without interrupting
an active cast. Downloads and installation require user actions. Unknown-source
permission is app-scoped; installer cancellation/failure offers explicit retry,
never automatic uninstall/reinstallation. See [UPDATES.md](UPDATES.md).

CI supplies `releaseVersionCode=10000+github.run_number` and a version name
from the stable tag without `v`, or `dev-<short-sha>` for main. Local defaults
are versionCode1/versionName0.1.0. Preserve run-number continuity or choose
a higher offset than installed versions when replacing a workflow/repository.

Stable tags are `vMAJOR.MINOR.PATCH`. Main prereleases additionally require
`ENABLE_MAIN_PRERELEASES=true` and are excluded from latest-stable auto-updates.
**Both binary release paths are blocked by default** until the user manually
sets `BINARY_RELEASE_APPROVED=true` after reviewing the original-code
license/distribution choice and third-party obligations. This includes
corresponding-source requirements and the sender's LGPL application source,
build scripts and relinking/recombination materials; see its
[THIRD_PARTY.md](https://github.com/arisewanggithub/ScrcpySend/blob/main/THIRD_PARTY.md).
Adding a root LICENSE or configuring signing secrets is not approval or a
compliance guarantee. Source export and CI checks are unaffected.

Publication also requires manually configured Actions secrets
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`. Approval is checked before secret decoding. The
runner privately restores/cleans the keystore, never publishes an unsigned or
debug fallback, and never uploads signing material. Manual signed builds use
`ANDROID_KEYSTORE_PATH` or `-PreleaseSigningKeystorePath=...`, the three signing
environment variables, and `-PrequireReleaseSigning=true`. Ordinary debug
development needs no release key.

In-place updates require the **same application ID and signing certificate**
and a higher versionCode. Existing debug-key installs need the matching key,
or an explicitly planned one-time migration/backup. A changed certificate
cannot preserve an in-place installation; there is no automatic uninstall
workaround.

Assets are exactly **ScrcpyRecv.apk** and **update.json**. Mandatory fields:
`schema:1`, `applicationId:"org.screencopy.receiver"`, integer `versionCode`,
`versionName`, `apkAssetName:"ScrcpyRecv.apk"`, lowercase 64-hex `sha256`,
lowercase 64-hex `signingCertificateSha256`, and `protocolVersion:3`.
The manifest generator verifies the actual signed APK's identity/version.
The sender uses the same schema with `dev.screencopy.sender` and `ScrcpySend.apk`.

## Source export and licensing

Run `.\tools\export-publication.ps1` for the path/size/SHA-256 inventory.
Add `-Destination .\publication-snapshot` to copy into a **new** directory,
then review after changes stabilize. Hidden CI files, pinned archives/checksums,
updater sources, wrapper JAR and licensing materials are included. History,
caches, SDK/IDE/device settings, outputs and signing material are excluded
even if tracked.

No root license or reuse grant has been selected for original application code;
public visibility alone does not grant permission to reuse it. Existing
copyright notices remain unchanged. Third-party code retains its own licenses
and distribution obligations; the exporter invents no original-code license
and does not require one for technical source export.

Preserve all notices and required matching sources with distribution.
`third_party\opus-1.5.2.tar.gz` and `third_party\srt-1.5.7.tar.gz` have adjacent
SHA-256 pins enforced by CMake. Notices/license materials remain in the archives
and APK, including [OPUS-NOTICE.txt](app/src/main/assets/OPUS-NOTICE.txt) and
[SRT-NOTICE.txt](app/src/main/assets/SRT-NOTICE.txt).
Media3 and other components retain their own terms. Distributors must supply
the necessary notices/license texts and corresponding sources; codec patents,
store policy and legal/security review remain separate from successful builds.
