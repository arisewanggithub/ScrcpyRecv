# ScreenCopy Receiver for Android

## SRT / Media3 trial

Build the coordinated trial with `gradlew.bat -PsrtTrial=true :app:assembleDebug`.
Custom TCP remains the default; SRT is enabled only by the explicit build flag.
RTSP/GStreamer integration and SDK auto-detection have been removed. A present
unsupported media transport is rejected with `UNSUPPORTED_CONFIG`, never
silently interpreted as custom TCP.
No new receiver controls or visible session IDs are added. Media3 1.11.1
(`media3-exoplayer`, `media3-datasource`, `media3-extractor`) supplies the
library playback implementation. Kotlin sources compile against these
libraries in all variants; only an SRT trial includes/loads `screencopy_srt`.
Min SDK 26, target/compile SDK 36 and the existing three ABIs are retained.
Media3 1.11.1's published AAR metadata checks pass at compile SDK 36.

The sender listens with pinned **libsrt 1.5.7**, and the receiver calls its
actual numeric control peer's explicit SRT port. The strict v3 transport is:

```json
{"kind":"srt","container":"fmp4","url":"srt://192.168.1.25:43517","latencyMs":100,"streamId":"sc-0000000000000001"}
```

The stream ID must encode the actual positive control session in 16 lowercase
hex digits. No DNS, credentials, URL paths, queries, fragments or cross-peer
endpoints are accepted. Latency is bounded to 20–2000 ms; the source uses
100 ms. Acceptance explicitly echoes `{"kind":"srt"}`; no implicit downgrade
is allowed. There are no custom STREAM_CONFIG/STREAM_READY exchanges, and
custom media/config packets in this session are protocol errors. BUSY,
PING/PONG, Stop, first-terminal-error handling and orientation leases remain.
`srt-playing` is sent only after actual first video rendering and, if enabled,
Media3's actual audio-position-advancing callback.

**Codecs: H.264, H.265 or AV1 + Opus or AAC-LC, or audio off.** Enabled
raw selections are explicitly rejected, not substituted. Disabled audio never
opens an audio track. The source uses Media3's fragmented MP4
muxer with an explicit 100 ms flush and standard `tfdt` timestamps, not a
manual box writer. Canonical OpusHead and standard MP4 `dOps` pre-skip/gain
are interpreted by Media3; no application PCM processing,
resampler, audio-rate controller, custom PlaybackClock, AudioPlayer or
VideoPlayer is instantiated for this backend. Media3 owns all demuxing,
MediaCodec/AudioTrack output, A/V sync and late-frame handling. It receives
the real existing Surface directly, without GLimagesink or another UI widget.
Its zero-ms startup/rebuffer / 1000 ms maximum load-control buffering targets
are not an end-to-end latency guarantee.
Zero-ms load control alone cannot start audio when a sparse video renderer
has exhausted its only frame. For audio-enabled SRT, an official Media3
`ForwardingRenderer` wraps the exact default video renderer: only after its
real first-frame callback may the held picture make video nonblocking.
The gate resets on position/stream changes, disable, reset, release and close;
stale queued callbacks cannot rearm it. Stream errors remain checked.
Decoding, rendering, audio readiness/sink and the media clock stay Media3-owned.
No frames, keyframes, durations or end-of-stream markers are fabricated;
`srt-playing` still requires actual first-frame and audio-position proof.

AAC and AV1 have a **fixed configuration per session**; AV1 sequence/dimension/
codec-private-data and audio initialization changes require explicit stop/reconnect.
H.264/HEVC use standard avc3/hev1 in-band rotation before authentic IDR/IRAP pictures.
The HEVC bridge uses official HEVC SPS/profile parsing, never the AVC SPS parser.
The source's Opus container timestamps are duration-based presentation anchored
once; original source-PTS reversals remain in diagnostics and FIFO payload
order. There is no receiver clock correction or reordered payload workaround.
The live SRT DataSource is unseekable, unknown-length and uncached; native
message leftovers are retained as real bytes, never padding. It opens once
per control session, with no automatic seek/reconnect or load-error retry.
Media3's `FragmentedMp4Extractor` consumes `video/mp4` directly. Standard `avc3`
in-band SPS/PPS supports sender rotation with one init segment. A bounded
`SrtAvcTrackOutput` uses the official Media3 SPS parser to announce actual new
CSD/profile/dimensions before the unchanged IDR sample; the default renderer
adapts/reinitializes its platform codec and updates Surface aspect diagnostics.
Audio timing and raw video PTS are not reset. Rebuild/release both trial apps
together; v3 framing is unchanged, but old trial pairs do not support rotation.
`receiver-diagnostics` now retains the current session's first load/read/EOF,
player or control failure as `lastFailure`, and its first close initiation as
`lastClose`. Both have decimal timestamps/session IDs, public codec/dimensions,
control-liveness times and controlled causes/stacks. Close owners distinguish
USER_STOP, REMOTE_STOP, ERROR, SURFACE_RETIRED and LOADER_CANCEL; intentional
teardown I/O cannot replace the first error, nor can stale session callbacks.
`ScreenCopySrt` logs the same sanitized records, never peer URLs, credentials,
exception/container text, filenames or media bytes. Media3 error UI includes
the safe inner native operation/code instead of losing it.
Native snapshots use pinned `srt_getsockstate`/`srt_bstats(clear=0)` before close:
packet/byte/loss/retransmission and ACK/NAK counters, send/receive buffers and RTT. Closed,
busy or failed statistics queries are explicitly unavailable; last peer-response
time is unavailable in this public API. These are diagnostics, not a claimed
network/playback fix or a change to transport timeouts/buffers/drop policy.

| SRT codec | Standard container | Hardware validation |
| --- | --- | --- |
| H.264 / Opus / audio off | `avc3` / `dOps`, real IDR rotation | Existing two-phone probes verified |
| H.265 / HEVC | Official `hev1` / `hvcC`, complete actual in-band VPS/SPS/PPS before IRAP; one init | User confirms initial portrait/landscape playback; synthetic rotation validation pending, Stop safety unresolved |
| AAC-LC | `mp4a` / `esds`, actual stereo 48 kHz ASC, raw access units; 1024/960 samples from ASC | Pending explicit synthetic renderer/audio-advancing validation |
| AV1 | `av01` / official `av1C`, actual sequence OBUs/key flags | Pending advertised encoder/decoder hardware validation |

The source requires official hardware encoders only for video; Opus/AAC accepts
all compatible hardware or software encoders. Raw PCM remains unencoded on TCP.
Receiver platform decoder inventory includes all compatible hardware or software
decoders and rejects unavailable selected codecs with
`UNSUPPORTED_CONFIG` before SESSION_ACCEPT/capture. SRT Opus uses the platform
Media3 decoder gate, not the unrelated custom TCP bundled decoder gate.
Extracted MIME and AAC ASC/rate/channels must match accepted/actual metadata.
AV1 payloads bypass AVC NAL parsing and cannot silently acquire AVC rotation
support. Source codec fixture args `srtFixtureVideoCodec=h264|h265|av1`,
`srtFixtureAudioCodec=aac|opus` select real synthetic encoders; absent AV1 hardware
logs `unsupported` / `NOT_VALIDATED` and a skip, never codec success.
Synthetic rotation uses source `serveHevcRotationFixtureForReceiverProbe` and
receiver `headlessLiveSrtHevcRotationRetainsVideoAudioAndSession`, explicit
`srtFixtureVideoCodec=h265`, `srtFixtureAudioCodec=opus|aac`, profile `screen`,
duration16s and matching `srtRotationTestUrl`/`srtRotationTestStreamId`.
Real 1080×1920→1920×1080→1080×1920 output, Surface frames and advancing audio
must persist across 4s/8s configuration changes on the same connection.
No real H.265 display capture/Stop probe is permitted in this investigation.
Receiver method
`SrtReceivePipelineTest#liveAdditionalCodecFmp4HasRealVideoAndAudioOutputProgression`
uses `srtTestUrl`, `srtTestStreamId`, the same codec/profile args and existing
bounded end/release helpers. It requires actual first frame, enabled audio
position/buffers advancing, additional Surface frames and exact 320x240 or
1080x1920 output. These methods have not yet been run for the new codecs.
`additionalCodecLocalFixtureUsesActualActivityControlSessionAndWaitingUi` pairs
with Source `saveCodecFixtureForReceiverActivity`, using `srtLocalFixtureUri`
and the same codec/profile args to exercise actual Activity control/readiness,
full-size Surface, six-second audio continuation, STOP and waiting UI.
Synthetic coverage: `SrtAvcTrackOutputTest`, Source `SrtAvcConfigurationTest` /
`NativeSrtTest`, and explicit `SrtFixtureDeviceTest#serveRotationFixtureForReceiverProbe`
with `SrtReceivePipelineTest#headlessLiveSrtRotationRetainsVideoAudioAndSession`.
The screen profile emits actual 1080x1920 → 1920x1080 → 1080x1920 H.264 at
4s/8s with continuous Opus; this is synthetic Surface output, not real capture
or actual-Activity acceptance.
The live progressive source requests a Media3 loading check after each completed
extractor read, rather than waiting for the default 1 MiB of input. A single small
video-only fragment therefore notifies playback before the next live read blocks;
startup never requires a second video sample, audio packet or source closure.

Lifecycle: caller handles are published before blocking connect. Closing
retires all native inputs first, cancelling connect/read, then releases the
ExoPlayer on its main looper. Main-handler handoff is FIFO and nonblocking:
no ResourceSlot/lifecycle monitor waits for main while Activity surface
retirement might need that monitor. A closed gate cancels queued startup and
ignores stale callbacks. ExoPlayer operations, renderer diagnostics and release
all run on main. `poll()` reports genuine decoder component names, video size,
rendered/dropped counters, audio advancement and current media position.

This is the existing **trusted-LAN, unauthenticated, unencrypted** policy.
libsrt encryption/AES is disabled (`ENABLE_ENCRYPTION=OFF`); there is no
passphrase UI or NAT traversal. The pinned source archive and SHA-256 are
verified/extracted under the CMake build directory; static libsrt/C++ runtime
and 16 KiB shared-library alignment are used. See packaged `SRT-NOTICE.txt`.
Too-late packet drop is disabled: dropping bytes would corrupt this container.

### SRT validation hooks (not yet a device acceptance claim)

Host coverage: `SrtTransportTest` and `SrtReceiverServerTest` exercise strict
metadata, session pinning, backend isolation (custom-clock factories fail if
called), explicit echo, first-frame gating, BUSY, PING, Stop, custom-packet
rejection and late Surface cleanup.
The shared `protocol/fixtures/srt-fmp4.json` drives accepted transports,
latency bounds, invalid descriptors, session/peer pinning and exact ready JSON.
Test-only player diagnostics separate native read calls/received bytes, completed
extractor reads, buffered AVC bytes and committed video samples. These bounded
counters distinguish incomplete live input from decoder/readiness stalls without
logging media payloads, endpoints or changing playback/proof requirements.
These additions are diagnostic only, not an audio-off playback fix. Audio-off
coverage still requires one genuine rendered frame with no following sample,
audio proof or synthetic end-of-stream; the focused bridge test is parser-only.

`SrtReceivePipelineTest` requires explicitly supplied bounded fixtures:

* `srtTestUrl` + `srtTestStreamId`: live H.264/Opus fMP4; headless test
  requires actual drained Surface video, renderer-buffer progression and
  advancing audio playback position, not just decoded input or native IO.
  The static-frame test observes six seconds of continuing audio; audio-off
  coverage requires a real first frame without audio proof.
* Optional `srtPendingConnectUrl`: an explicitly approved nonresponding SRT
  endpoint for prompt connect cancellation. Loopback connected-read
  cancellation, idempotent close/stale handles and queued-start cancellation
  are also covered. A real ExoPlayer loader blocked on an accepted loopback
  source must retire on main within five seconds after native input close.
* `srtLocalFixtureUri`: an existing receiver-accessible `file:`/`content:`
  fragmented MP4 H.264/Opus fixture. A debug-only internal hook feeds the
  actual receiver Activity player **after a real local v3 control handshake**;
  no Intent extra, setting, permission or visible ID enables this hook.
* `srtLocalStaticFixtureUri`: the same local-file path with exactly one video
  frame and continuing Opus. `srtProgressiveFixtureUrl` exercises that fixture
  through an explicitly approved progressive HTTP(S) URL and the actual
  Activity. Both require audio buffer and media-position progression over
  six seconds with exactly one rendered video frame, not duplicated video.
* `actualActivityLocalFixtureStopsAndReconnectsWithNewSession` uses
  `srtLocalStaticFixtureUri` (or `srtLocalFixtureUri`) for two control sessions
  in the same live Activity. Each requires six seconds of audio progression;
  STOP must restore the waiting UI and retire diagnostics without late
  callbacks reviving the previous session.
* `srtControlFixture=true`: the parent-controlled real source must initiate
  an actual v3/SRT control session with the running Activity, delivering a
  single static video frame plus continuing Opus. The test requires actual
  first-frame/audio proof, continuing audio over six seconds with no extra
  video, and the real waiting UI to leave the foreground.

**Synthetic validation evidence:** actual Activity/control/static six-second
playback and same-Activity two-session STOP/reconnect passed with a local fMP4
debug fixture. Actual two-phone SRT bytes, moving video, static-video audio
progression over six seconds and audio-off passed headlessly. The combined
external-source v3/SRT-to-Activity `srtControlFixture` method compiled but was
not executed. These results do not validate production capture.

Activity tests explicitly assume an **already unlocked** device. They never
unlock it or set show-when-locked. Fixture generation, device install/execution,
serial builds, SDK/AAR metadata checks and acceptance belong to the coordinating
parent. Kotlin instrumentation compilation and focused host tests can be run
without device access; device instrumentation remains a separate required
acceptance gate. Fixtures must supply at least 16 seconds of audio for startup
plus the six-second observation:
an ended live input is an explicit error. Native-only or headless synthetic
success is not full-app acceptance.

A native Kotlin **remote screen** for a trusted family LAN. The waiting page
puts the receiver's actual IP addresses first, with textual Ready/Connecting/
Not-ready status and a remote-focusable **Exit** action. There is no human
session code, comparison checkbox, pairing, fingerprint or saved peer trust.

Media and controls use **unencrypted, unauthenticated plain TCP**. Anyone on the
network can observe traffic or attempt to connect. Do not expose the listener
to the internet or use it on hostile networks. IP/name discovery is convenience,
not authenticated identity.

The sender alone controls capture, encoding, crop, capture orientation, bitrate and audio.
The receiver supports or explicitly rejects the requested/actual settings; it
does not silently change quality, disable requested audio or crop video again.
Receiver behavior is independent of the sender's Shizuku/other capture backend.

## Video admission while waiting for audio

A decoded picture ahead of the audio clock still waits for its correct
presentation time, but no longer stops feeding compressed packets to the
decoder. Only one future picture is held (at most two during existing decoded
late-frame catch-up); decoder input capacity supplies backpressure. Encoded
packets/references are never skipped, and all queue limits, admission deadlines,
aspect-fit rules and three-second stall/clock guards are unchanged.

Real-time TCP regressions use the actual receiver/audio/video workers, finite
130 ms PCM buffering, 200–300 ms hardware-transit models, refreshed Opus source
PTS, an output pause and a video-generation change after successful playback.
The old scheduling policy fails video PTS admission at a 266,667 µs span; the
corrected policy passes without losing compressed input. These host models do
not identify the JMGO decoder or establish the cause of its reported failure;
the manually installed projector APK has not been verified.

## Audio write-progress timeout

The output watchdog measures 250 ms without an accepted PCM write, not the
total time spent submitting a packet. Each positive partial write starts a new
no-progress interval; partial samples are retained in order. A continuous
zero-write stall, invalid native write result, or output error still fails.
Failure context includes packet duration, time since progress, remaining sample
count and the output timing snapshot.

This correction has production-worker host regressions. The reported failure
came from a manually installed receiver on a JMGO N5 Pro, not the Galaxy used
for earlier hardware tests. No projector output test or specific driver cause
is established without its logs; the correction is not a claim that every
projector stall is resolved.

## Opus source-PTS compatibility

The coordinated v3 correction permits **nonnegative Opus source PTS to decrease
anywhere within a generation**, without a magnitude/startup threshold. The
exception is granted only for audio packets whose accepted audio STREAM_CONFIG
codec is exactly `opus`. Video, AAC and PCM remain nondecreasing; missing/other
codec values grant no exception. Track readiness/generations, negative PTS,
payload validation and genuine decode/output errors remain strict.

The retained startup observation `1000000, 999551` was rejected at both the
old TrackState boundary and the common AudioPlayer decoded-PTS guard. The pinned
scrcpy encoder can reconstruct Opus output timestamps independently of its
monotonic input observations, while its desktop regulator decodes arrival order.
This supports the compatibility correction; it does **not** establish which
encoder/reconstruction branch produced that particular physical recording.
See the sender-owned [contract](PROTOCOL.md) and
[seven-case fixture](protocol/fixtures/opus-pts-order.json).

Packets, source PTS and payloads stay FIFO/unchanged: no sorting, clamping,
rebasing, dropping or synthetic generations. Pre-skip remains sample-count
based and applied once. Increasing decoded sample positions feed the existing
bounded source-anchor/monotonic-presentation model; no resampler or clock rewrite
was added. Queue span remains actual maximum minus minimum across queued packets
plus the proposed packet, with the same **250 ms / 48-packet / byte limits**.
A large reversal may still legitimately exceed the queue span if those packets
are simultaneously buffered; source-order acceptance is not a buffer exemption.

Host tests exercise actual wire parsing, configured TrackState and AudioPlayer
with a minimal synchronous Opus decode seam. The exact two three-byte packets
retain 1,608 audible frames (2x960 minus 312 pre-skip), original observations and
the expected -20,449 us source/sample offset without rewinding presentation.
Later large reversals, strict other codecs/generations, sample-count priming and
actual-worker 250,001 us queue rejection are covered. A separate real bundled-JNI
Opus test is **compiled only**, never run here:
`org.screencopy.receiver.media.NativeOpusPtsOrderTest#nativeOpusKeepsReversedStartupPtsPreskipAndMonotonicPresentation`.
It records decoded silence instead of creating AudioTrack/video/capture/UI
resources, and checks wire/state/decode/pre-skip/presentation together.
It does not prove a new live cast or the device's encoder identity.

Release updated sender and receiver together: earlier v3 receivers reject these
Opus reversals, and a v3 version match alone does not advertise this correction.
No framing, config field, capability flag or version bump is introduced.

## Session presentation orientation

The sender's Capture Settings may request receiver presentation `auto` (default),
`landscape` or `portrait`. This is independent of capture rotation: it requests
the Android Activity's axis only, never rotates/re-encodes media or changes
STREAM_CONFIG, source dimensions, track generations or PTS. Video remains
aspect-fit/letterboxed; it is not stretched or cropped again.

The optional v3 top-level SESSION_REQUEST `receiverOrientation` field defaults
to `auto` when absent. Present null, wrong types or other/case-altered values
are rejected with `UNSUPPORTED_CONFIG`. Old v3 requests remain valid; both apps
must support this optional extension for a forced receiver preference to work.
The sender-owned schema and 13 deterministic cases are copied unchanged in
[PROTOCOL.md](PROTOCOL.md) and
[receiver-orientation.json](protocol/fixtures/receiver-orientation.json).

The receiver applies a session-owned orientation lease only when all enabled
tracks are ready to enter Playing, not during negotiation/idle. `auto` makes
no override and preserves the Activity's ordinary OS/sensor policy. Forced
axes are requested once, not reapplied on every frame/configuration/generation.
Terminal Stop/failure/disconnect restores the exact prior Activity policy;
exit/background teardown also restores it, and stale cleanup cannot override
a newer session. An actual SDK request failure is an explicit presentation
error, not a codec-format claim. TV/fixed-orientation hardware, multi-window
and newer large-screen Android policies may ignore the requested axis.
This feature does not promise overriding those OS restrictions.

Orientation/screen-size configuration changes are handled in the same Activity.
Only the waiting-overlay subtree is reinflated; the root, SurfaceView, accepted
connection, decoder and audio clock are retained. Insets and aspect-fit geometry
are refreshed without repeatedly requesting orientation. Genuine unrelated
Activity recreation/background exit or Surface loss still uses the existing
owned teardown/rebind path. An invalid/destroyed Surface is never ignored to
preserve a cast. The extension does not require retaining media across arbitrary
Activity recreation.

## Install and receive

1. Install the APK normally using Android's package installer or your app
   distribution channel. Receiver operation needs no ADB, root, Developer
   options, microphone permission or dangerous runtime permission.
2. Connect both devices to a reachable trusted Wi-Fi/Ethernet LAN. Open
   **ScreenCopy Receiver** and keep the screen visible.
3. Select this screen's actual IP address on the sender. Discovery uses the UDP
   packet's source address, never an advertised host or a same-name redirect.
   If broadcasts are blocked, enter the IP manually on the sender and leave
   its port at the default **27184**. No public identifier is required.
4. Start capture on the sender. Only one cast is accepted; another gets `BUSY`.
   Actual internal cast session IDs and per-track generation/readiness checks
   remain mandatory and are not the removed human session code.
5. Stop casting **on the sender**. After cleanup the receiver returns to Ready
   with its current IPs. **Exit** is the receiver's only waiting/status action:
   touch it, or use D-pad/remote center/Enter. It finishes the Activity normally,
   closes owned resources and releases keep-screen-on. Exit is hidden while
   casting; Android Back/Home can leave the receiver during playback.

The internal discovery name is **ScreenCopy Receiver**, TCP **27184**, UDP
**27183**. Normal UI does not show the port, settings, Disconnect or Licenses
controls. Old receiver settings/preferences are not read. A listener restart
at the same IP/port needs no new human identifier; a new cast still requires a
new positive protocol session ID. Refresh discovery if reachability changes.
`ScreenCopyReceiver` logs Activity/Surface and TCP listener start/stop/failure,
plus ten-second discovery target/send counters and rate-limited maintenance
failure stages/types. These contain no peer names or advertisement payloads.
Discovery summaries identify the actual IPv4 broadcast destinations and UDP port.
An app process alone does not prove advertising: Receiver must be started with
a valid Surface; Sender must also be started with valid privileged readiness.

The responsive waiting page uses large auto-sized IP text, native portrait and
landscape compositions, window/cutout insets and generous overscan-safe margins.
Multiple actual IPs get separate readable lines. Errors offer concise recovery
guidance, not device IDs or decoder log dumps. The initialized Surface remains
behind the waiting page; only a real MediaCodec frame-rendered callback after
enabled track initialization hides it. Audio-disabled video follows the same
rule. Playing uses the full available area with aspect-fit letterboxing and
hidden bars; no IP/status/Exit overlay obscures content.

The manifest allows resizing and does not require touch. The ordinary Android
launcher is retained: no Leanback launcher/banner or Android TV store
certification is claimed. Phone/tablet/TV/projector hardware and remote focus
still need device validation.

AP isolation may block both discovery and manual TCP. IPv6 discovery, internet
traversal, recording and background receiving are not implemented. Network
changes end the cast and refresh displayed IPs. Old v1 TLS/v2 human-code clients
are incompatible: update both apps to **v3**.

## Build and tests

### Public snapshot and signed releases

The migration targets fresh `arisewanggithub/ScrcpyRecv` and
`arisewanggithub/ScrcpySend` repositories without existing Git history.
Run `.\tools\export-publication.ps1` to inventory reviewed paths/sizes/SHA-256;
add `-Destination .\publication-snapshot` to export into a **new** directory.
Re-run after concurrent edits and inspect the snapshot before publishing.
History, caches, SDK/IDE/device settings, outputs and credentials are excluded
even if tracked. Pinned Opus/SRT archives, checksums, wrapper JAR, complete
sources and licensing notices remain included. Keep the matching source and
third-party source/license obligations with every distribution.

No root license or reuse grant has been selected for the original application
code; public visibility alone does not grant permission to reuse it. Existing
copyright notices remain unchanged, and third-party code retains its own
licenses and their distribution obligations. The exporter does not invent a
license or require a new original-code license for technical publication.

PR/main CI runs JVM tests, lint and debug builds with both custom TCP/default
and SRT enabled. A separate disposable Linux API35 x86_64 emulator runs **only**
`AppUpdateRemoteUiTest` through an explicit instrumentation class selector;
no physical-device, capture, Shizuku or codec acceptance tests run. Signed
publication requires both jobs to pass. It pins JDK21, Node22,
SDK36/BuildTools36.0.0, NDK27.2.12479018 and CMake3.22.1.
`vMAJOR.MINOR.PATCH` tags publish signed SRT/custom TCP releases.
Main development prereleases require repository variable
`ENABLE_MAIN_PRERELEASES=true`; latest-stable update checks ignore them.
Global workflow permissions are read-only; only the publish job can write
releases, and PR jobs never receive signing secrets.

Both stable and prerelease APK publication are **blocked by default** until
the user explicitly sets repository variable `BINARY_RELEASE_APPROVED=true`.
This manual approval requires review of the original-code license/distribution
choice and third-party obligations, including corresponding-source requirements
and the sender's LGPL application source/build scripts and relinking materials
(see its **THIRD_PARTY.md**). Merely adding a root LICENSE or configuring signing
secrets is not approval or a compliance guarantee. CI fails explicitly before
decoding signing secrets when approval is unset/false; it never substitutes
unsigned or debug APKs. Source export, JVM tests, lint and isolated emulator
checks remain unaffected. No license is selected or granted automatically.

The user must manually configure Actions secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`.
No agent reads, creates or uploads signing keys. Publication explicitly fails
without complete signing configuration; CI restores a private runner-only
keystore, cleans it on exit and publishes no unsigned/debug APK or credentials.
Manual release builds accept `ANDROID_KEYSTORE_PATH` or
`-PreleaseSigningKeystorePath=...`, the three signing environment variables and
`-PrequireReleaseSigning=true`. Unsigned local debug development is unaffected.

CI supplies `releaseVersionCode=10000+github.run_number` and
`releaseVersionName=<tag without v>` or `dev-<short-sha>`; local versionCode1
and versionName0.1.0 defaults are unchanged. Preserve run-number continuity or
raise the offset above every installed version if replacing the workflow/repo.
Updates require unchanged application ID, **the same signing certificate** and
a higher versionCode. For existing debug-key installs, manually supply the
matching key, or explicitly plan a one-time migration/backup for a new key.
Changing certificates does not preserve an in-place installation; no automatic
uninstall/data-loss workaround is provided.

Releases contain only `ScrcpyRecv.apk` and `update.json`. Exact manifest fields:
`schema:1`, `applicationId:"org.screencopy.receiver"`, integer `versionCode`,
`versionName`, `apkAssetName:"ScrcpyRecv.apk"`, lowercase 64-hex `sha256`,
lowercase 64-hex `signingCertificateSha256`, and `protocolVersion:3`.
The manifest script verifies the signed APK's identity/version and publishes
only the public signing-certificate fingerprint. The sender uses the same
schema with `dev.screencopy.sender` and `ScrcpySend.apk`. Update both to v3.
Public repository creation and signing secret setup remain manual operations.

Requirements: JDK **21**, Android SDK **36**, Build Tools **36.0.0**, NDK
**27.2.12479018**, CMake **3.22.1**. Android Gradle Plugin **9.4.1** uses its
built-in Kotlin compiler; the Gradle **9.8.1** wrapper has a pinned checksum.
Accept SDK licenses/install tools through Android Studio's SDK Manager. Initial
dependency/tool downloads need internet.

```powershell
Set-Location .\ScrcpyRecv # Run from the directory containing your clones.
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
.\tools\interop.ps1 -SenderRoot ..\ScrcpySend
```

App: `app\build\outputs\apk\debug\app-debug.apk`.
Tests: `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`.
Debug signing is for development; configure your own release signing before
distribution. Minimum SDK **26**; target/compile **36**. Native ABIs:
**arm64-v8a**, **armeabi-v7a**, **x86_64**. These are build configurations,
not hardware compatibility certifications.

Only normal `INTERNET` permission is declared. Newer Android local-network
policies/OEM restrictions require validation before raising target SDK.
Screenshots are blocked while media is visible. Media and controls are not
recorded or logged, the remote screen saves no user settings, and backups/device
transfer are excluded. The visible Activity keeps the display awake and clears
that flag on exit.

## Playback and resource ownership

- H.264, H.265 and AV1 select a compatible MediaCodec decoder for actual metadata,
  preferring hardware. Annex B H.264/H.265 access units/CSD and AV1 low-overhead
  OBUs retain packet boundaries and common source microsecond timestamps.
- Bundled **libopus 1.5.2** validates canonical OpusHead, channels, pre-skip,
  output gain and delay metadata. AAC uses MediaCodec where supported; raw audio
  is stereo 48 kHz signed 16-bit little-endian PCM. No silent codec conversion.
- Initial decoder preroll keeps consumers draining without dropping dependent
  encoded video or rewriting timestamps. Pending audio retains one latest
  decoded packet until video is known, trims PCM preceding the shared epoch
  after normal codec/pre-skip decode, and starts with an 80 ms presentation lead.
  AudioTrack is clock master; video-only uses a monotonic clock.
- Startup remains bounded to three seconds. Tiny 0.995/1.005 audio-rate
  corrections regulate actual submitted-minus-server-consumed client PCM,
  not submitted-minus-DAC-presented samples. Invalid timestamps, write errors,
  actual output stalls and audio-route changes produce explicit errors.
  Lifetime underrun occurrences are recovery telemetry, not a fatal error budget.
- A decoded picture over 250 ms late no longer cancels the cast by itself.
  If a newer decoded picture is already available, release only the older
  decoded output without rendering; otherwise present the last available
  picture immediately, including a static screen. Catchup examines at most
  one additional decoder output per step, briefly owning at most two outputs.
  Encoded access units are never discarded to catch up, so decoder reference
  dependencies remain intact. Queues and timing budgets are not enlarged.
  Future-clock waits remain bounded to three seconds; pending input/output
  with no decoder progress also fails explicitly after three seconds.
- Output allocation now uses the **existing 130 ms capacity budget**, not the
  former 120 ms request. Minimum byte counts are rounded up to whole PCM frames;
  the fastest-rate documented minimum must fit within that budget. Actual native
  effective size and capacity, sample rate/channels/PCM16, initialized state and
  stopped playback state are checked. The real platform is probed at 1.005,
  0.995 and reset to 1.0, with pitch 1 and unsupported-parameter fallback set to
  FAIL; readback is verified before output initialization returns or track READY.
  Preparation calls no play/pause/write. At 48 kHz the request is 6,240 frames,
  computed from 130 ms, not a device-specific frame constant.
- Output plus one decoded packet remains <=250 ms (130 ms output + <=120 ms
  decoded packet). If the SDK rounds allocation above 130 ms or rejects any
  intended rate, startup fails explicitly with `UNSUPPORTED_CONFIG`; there is
  no silent 1.0 fallback, sample-rate change, resampler replacement or larger
  global queue. Later route/format/capability changes can still cause an explicit
  runtime error; preflight validates the current configuration, not every future
  device route. The 130 ms bound is client-buffer capacity, not a guarantee of
  total AudioFlinger/hardware or end-to-end latency.
- Client capacity and A/V presentation use separate frame counters. The PCM
  playback head measures server consumption; qualified AudioTimestamp reports
  estimate presentation downstream of hardware transit. Timestamp position/time
  must advance before selection. A repeated report can be extrapolated for up
  to the existing 100 ms freshness window, at the actual correction speed and
  never beyond consumed PCM. Unavailable, stale, future or regressing timestamp
  reports use the advancing client head with the last learned transit offset.
  Without a qualified timestamp the head is an explicitly approximate clock.
  Switching sources cannot rewind presentation. Counter resets/regressions,
  consumption beyond submitted PCM and client occupancy beyond actual capacity
  still fail explicitly; timestamp availability alone is not a buffer bound.
- Capture PTS and output sample time are separate clock domains. Source anchors
  are associated with their PCM sample positions and applied only when those
  samples reach presentation, not when a newer packet is buffered. The mapped
  source clock slews phase by at most **2% of presented sample time**, remains
  monotonic across positive/negative refreshes and is capped at submitted PCM
  when the device timestamp extrapolates. Anchor history is pruned after
  presentation and capped at `sampleRate / 4 + 2` entries. PCM is not discarded
  or retimestamped to enforce a phase equality.
- There is **no 5 ms, 50 ms or higher cumulative source-phase fatal threshold**.
  A 64.112 ms step converges in approximately 3.21 seconds of presented audio
  plus the bounded output transit. This is a recovery policy, not a guarantee
  of <=50 ms A/V error during recovery. Continuing corrections faster than the
  slew limit may not converge; existing render deadlines, queue/output bounds
  and real output errors still apply. Physical rate compensation remains
  limited to +/-0.5%, independently of source phase.
- Encoded queues: **48 packets / 250 ms** each, video **8 MiB**, audio **512 KiB**.
  Payload maxima: video **4 MiB**, audio **256 KiB**, controls/config **64 KiB**,
  validated before allocation. Decoded PCM/output together remain <=250 ms.
  Controls: **64 messages / 256 KiB**, serialized independently with a 10-second
  watchdog. Arbitrary fragmented/coalesced reads preserve framing.
- Admission and dequeue update queue/budget state atomically. A temporarily full
  media queue applies cancellable backpressure for at most a **250 ms admission
  deadline**, allowing a bounded READY backlog to drain instead of declaring
  instant overload. Counts/bytes/PTS spans are never admitted above their
  existing limits; one currently read packet remains outside the queue as
  permitted by the wire contract. Halt wakes a waiting offer immediately.
  Persistent pressure still ends the session explicitly. Shared-TCP head-of-line
  blocking is bounded by that deadline; controls/liveness retain their watchdog.
- Positive internal cast session IDs, track IDs, independent generations and
  readiness remain enforced. Video rotation does not reset unchanged audio.
  Explicitly disabled audio creates no audio output.
- Atomic socket admission/shutdown includes accepted-but-unregistered sockets.
  At most **32** clients; setup/liveness **10 seconds**, Stop acknowledgement
  **two seconds**. Surface callbacks synchronously retire video decoders without
  UI-dependent waits; a process-owned executor orders Activity teardown/rebind.
  Exit is terminal for late startup/frame callbacks; cleanup failure is not Ready.

## Video failure investigation and diagnostics

The later parent-collected live trace on the prior `973C` build showed healthy
audio counters/rate corrections for approximately 52 seconds, followed by
`DECODER_FAILED origin=VIDEO reason=OTHER phase=Playing`. It is a new video
failure path, not evidence that the earlier audio startup defect returned.
The old video diagnostic discarded the stage, exception and measurements, so
**the exact 21:59 device cause remains unknown**. Codec configuration logs alone
do not prove a generation restart, and framework BAD_INDEX queries alone do
not prove decoder failure. Positive 85.272/149.651 ms audio source-phase changes
are supported by the audio clock model and do not by themselves prove bad media.

Before this change, the actual VideoPlayer/TrackWorker aborted a controlled
valid-IDR test after a 500 ms decoder/scheduling delay, solely because the decoded
picture was over 250 ms behind the clock. There was no codec exception.
Pinned scrcpy 3.3.4
[screen.c](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/screen.c)
counts skipped decoded pictures, while
[frame_buffer.c](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/frame_buffer.c)
replaces an unconsumed decoded AVFrame with its newer successor. These are
decoded outputs, not compressed reference packets. The bounded catchup policy
above adopts that distinction, not an arbitrary larger lateness allowance.
This intentionally changes transient-lateness behavior; it does not guarantee
a <=50 ms A/V offset or resolve unmeasured device/decoder resource failures.

`VIDEO_PRESENTATION` emits one controlled START snapshot per actual generation,
then at most one catchup snapshot per second while counters change. It includes
generation/reconfigured status, a bounded codec label, width/height/rotation/FPS,
relative frame-to-clock delay, held/input/output wait ages, input bytes/capacity/
keyframe status, output size/flags, queued/decoded/render-submitted counts,
decoded skips and late renders. `render_submitted` is not proof of a Surface
frame-rendered callback; the actual callback still controls fullscreen entry.
No absolute PTS, media, buffer IDs, peer identifiers or private vendor text is
emitted. The native adapter preserves only CodecException's numeric error code
and recoverable/transient hints, not its diagnostic string.

Fatal video records retain `stage`, exception class and `video_fault`, plus the
same measurements. Stages distinguish selector/create/configure/start, clock,
input acquisition/buffer/queue, output dequeue/validation/release and cleanup.
Causes distinguish unsupported selection, missing first keyframe, input
capacity/null buffer, invalid/decreasing timestamps, unexpected EOS, clock wait,
no input/output progress, codec resource/reclaim and other API failures.
The output watchdog uses latest decoded PTS progress, not an assumed 1:1
input/output count that could falsely fail after an earlier omitted picture.
No codec exception is swallowed or retried. Actual bad metadata, resource
failures, stalls and bounded queue pressure remain fatal.

Runtime `DECODER_FAILED` no longer means "this screen cannot play those settings"
in the UI. Specific retained video causes have corresponding recovery text;
an unknown runtime decoder error is honestly reported as unexpected decoding
failure. Only explicit unsupported selection/configuration warrants a format
claim. Sender-owned CSD changes, including repeated CSD on encoder restart,
still advance only that track's generation. New video decoding does not reset
the running audio/source clock. No sender or wire-contract change is needed.

The host regression runs the actual AudioPlayer and VideoPlayer workers for
65.32 seconds of simulated capture time, with 167 ms synthetic hardware transit,
an 800 ms static-screen decoder delay, source-phase steps at 51/52 seconds and
a 640x360 -> 360x640/90-degree generation change while audio continues.
All 3,135,360 PCM frames and 3,187 compressed video inputs are preserved; late
decoded output recovers and source phase converges. This uses a minimal
deterministic codec adapter, **not real MediaCodec decoding, physical elapsed
time, live casting or device acceptance of the new build**. No new hardware
fixture test is run or claimed. Deployment and H.265 Stop safety remain
parent-controlled after separate inactive-device checks.

## Audio failure investigation and diagnostics

The later 22:32 failure is specifically `AUDIO_FAILED / AUDIO_UNDERRUN /
UNDERRUN_CHECK`, after approximately 26 seconds of otherwise successful audio
progress. The former worker added every AudioTrack underrun delta for the whole
session and cancelled the cast when that sum exceeded eight, even if subsequent
PCM writes and consumption succeeded. A production AudioPlayer/TrackWorker/
PlatformAudioOutput-seam test reproduced that exact guard before its removal.
The old record omitted the native count and sampling history: it proves the
guard fired, not the size or cause of a fullscreen/capture/network gap.

Android documents
[getUnderrunCount](https://developer.android.com/reference/android/media/AudioTrack#getUnderrunCount())
as application-level write-buffer occurrences **since the track was created**,
with a potential glitch/pop. It does not declare the track unusable.
Pinned scrcpy's
[audio regulator](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/audio_regulator.c)
treats underflow as recoverable rather than a lifetime fatal count and preserves
later input samples. This surgical fix removes only the invalid count-as-fatal
assumption; it does not raise/reset an error allowance, generate silence, add a
resampler/rebuffer policy, rewrite PTS, or change the shared clock or queues.
Normal PCM is preserved. Real write errors, the existing 250 ms blocked-write
deadline, route failures, malformed data and connection liveness remain explicit.

Nonfatal `AUDIO_UNDERRUN_RECOVERY` diagnostics sample at most every 20 ms and
emit at most once per second while events or recovery state change. Fields
include native current count, current-observation delta, accumulated delta since
the prior event, observed session total, explicit native counter reset,
BUFFERED/EMPTY/DECODING state, current/last sampled client-empty duration,
observed client recoveries, queued/held packets, native playback state when
available, and relative submitted/consumed/presented clock/buffer measurements.
Durations are sampled application-client emptiness, **not an inferred capture
gap, longest interval, audible-DAC starvation duration or Wi-Fi diagnosis**.
The last interval can be replaced before a rate-limited event; short events may
fall between samples. These records do not become terminal errors or UI overlays.

New actual-worker tests cover eleven cumulative events with successful PCM,
twelve slowly accumulated events over 50 seconds of simulated capture,
clustered events plus a controlled 120 ms empty/resumption interval and video
rotation/resolution generation without resetting audio, checksum-preserved PCM,
and still-fatal write/stall/malformed-data cases. Monitor tests verify exact
sampled durations, rate limiting, pending-work state and counter-reset reporting.
These are host/model results, not a measured fullscreen source gap or physical
acceptance of the candidate. Arbitrary multi-second packetless audio while
video continues is **not addressed or verified by this narrow fix**; the
existing audio-master/video-clock deadline may still apply. No zero-generator,
clock bridging or new hardware gap test is added without a proved requirement.

Several different device failures have evidence, and **physical acceptance of the
new build remains unverified**. Earlier read-only diagnostics establish
`CONGESTION origin=SESSION phase=Playing` after AAC/video startup and before
teardown. That build did not record the local queue track or rejected limit;
the exact historical audio/video/control queue cannot be inferred. Its 11,520
delivered PCM frames and framework timestamp corrections do not prove an initial
backlog, Wi-Fi overload or a fatal audio-clock event. H.265 Stop/kernel panic
safety acceptance and deployment remain **on hold**. No capture, physical Stop,
phone operations, installation or deployment is performed for this investigation.
Separately, the parent accepted the prior `973C` build's isolated real output
test on a locked/background receiver: one test, 2.514 seconds, all 115,200 silent
PCM frames and both correction ticks. Real client occupancy was 100 ms versus
approximately 167 ms downstream transit. The later live trace also retained
healthy audio counters for approximately 52 seconds before the video failure
above. These results validate that earlier output path, not new-build live
casting, codec/endurance acceptance or audibility of a silent test.

The later 19:02 diagnostic identifies the exact failure:
`AUDIO_FAILED / SOURCE_TIMING / TIMESTAMP_CHECK / offset_us=64112`.
The receiver's previous 50 ms source/sample phase check cancelled sharing.
That check was an **incorrect implementation assumption**, not proof of malformed
user audio, unsupported codecs, or network overload. Raising an earlier 5 ms
check had not corrected the underlying clock model.

The pinned scrcpy
[AudioRecordReader](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/audio/AudioRecordReader.java)
switches between refreshed capture timestamps and sample-duration fallback;
the sender adapter forwards those PTS unchanged. Upstream
[audio_player](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/audio_player.c)
delegates to
[audio_regulator](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/audio_regulator.c),
which treats discontinuities as regulation state changes and bases resampling
on buffered samples rather than lifetime PTS/sample equality.
[clock.c](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/clock.c)
also estimates the source/system mapping. This receiver adopts that separation,
not upstream's buffer-dropping policy or a new resampling dependency.

The exact +64.112 ms production-worker test failed before the clock-model fix.
The new played-sample/source mapping and occupancy-only regulator preserve all
normal PCM through positive, negative, multiple and time-varying corrections.
Tests cover sustained eight-/sixteen-second correction scenarios, thirty seconds
of independently varied source/output clock rates, decoded Opus pre-skip and
AAC frame cadence, and interleaved video/shared-clock presentation. Malformed
decreasing video/AAC/PCM PTS, negative Opus PTS, bad output writes and real buffer
pressure remain fatal. Nonnegative Opus reversals follow the coordinated
source-observation exception above.
Host decoded-format tests do not execute Android AAC/Opus decoders; real Opus
regressions are compiled into the instrumentation APK but not run here.

The first failure is retained through cleanup/Ready rather than replaced by a
generic cleanup/discovery message. Error UI distinguishes local source timing,
playback clock, underrun, route, output and peer-reported errors; normal waiting
UI remains uncluttered. On a future **explicitly authorized** diagnostic run,
the `ScreenCopyReceiver` log tag emits one structured failure per connection:
controlled error code/origin/reason/phase, local audio stage/exception class,
and a numeric relative timing offset where relevant. It does not log peer
message text, media, codec bytes, names, endpoints or absolute source timestamps.
This cannot recover an error discarded by an already-running older APK.
Recoverable source corrections additionally emit rate-limited informational
`event=AUDIO_SOURCE_CLOCK_ADJUST stage=SOURCE_MAP` records with observation
sequence, relative before/after/delta and the applied offset at that observation. The 1 ms
logging significance filter and one-second log rate limit are **not playback
validation thresholds**. No absolute source timestamps or media are emitted.

The later 20:10 platform log identifies another concrete cause:
`setPlaybackRate(1.005, 1.0)` was denied because the allocated 5,760 frames were
below the current route's 5,793-frame native minimum. That triggered the first
post-one-second RATE_CORRECTION exception. It was an output-buffer policy failure,
not evidence of Wi-Fi overload or an unsupported audio encoder.
[Android's setPlaybackParams documentation](https://developer.android.com/reference/android/media/AudioTrack#setPlaybackParams(android.media.PlaybackParams))
explicitly warns that speed >1 requires buffer size greater than speed times
the estimated minimum. Native
[AudioTrack](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libaudioclient/AudioTrack.cpp)
recomputes eligibility from output latency, output frame count, sample rate and
speed, so `getMinBufferSize()` alone cannot certify the full rate envelope.
The bounded allocation and actual preflight above replace that assumption.

Successful preparation logs one controlled `AUDIO_OUTPUT_PREPARED` event with
sample rate/channels, minimum/requested/effective/capacity frames and speed
envelope/reset. Failure records use `stage=OUTPUT_PREPARE` and
`reason=RATE_UNSUPPORTED` or `OUTPUT_PREPARE`, with controlled `output_reason`,
numeric frame/rate/speed fields and no raw platform text. `minimum_frames` is
the estimated nominal SDK minimum, not a private native requirement at speed.
Unsupported later corrections
retain `stage=RATE_CORRECTION` and the same controlled numeric context. Error UI
points to the output/latency policy, not capture quality. No new hardware test
or physical playback success is claimed.

The 20:39 read-only device log then showed successful 6,240-frame/full-envelope
preparation followed by `AUDIO_CLOCK / RATE_CORRECTION`. **Continued failure is
not expected or accepted functionality.** That log omitted its measured lead,
so the exact device value is unavailable. Two actual prepared-output/AudioPlayer
host traces reproduce the same former stage/exception at 48,128 written frames:
129 ms of hardware transit falsely produced 258,666 us of "buffered PCM", and
a timestamp returning true but remaining stale produced 902,666 us. Both traces
kept the client queue within 6,240 frames. These reproduce a receiver defect,
not the missing physical measurement.

For pure streaming PCM, native
[getPosition/updateAndGetPosition_l](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libaudioclient/AudioTrack.cpp)
reads server-consumed frames. Its getTimestamp path separately accounts for the
server-to-kernel presentation pipeline. Android's
[timestamp contract](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp))
also describes warmup, unavailable timestamps and committed presentation times.
Thus 130 ms of client storage plus 129 ms downstream can legitimately exceed
the old 250 ms submitted-minus-presented check without overflowing client PCM.
The fix separates these quantities; it does not raise that check or a queue
limit. Client PCM remains bounded by the actual prepared output (<=130 ms),
plus the single <=120 ms decoded packet. Hardware transit is neither counted
as client storage nor promised to fit an unmeasured end-to-end latency bound.

One-second `AUDIO_OUTPUT_CLOCK` correction records and typed `AUDIO_CLOCK`
failures contain controlled `clock_reason` (on failure), sample rate, buffer
capacity, relative submitted/consumed/presented frame counts, `buffered_us`,
`transit_us`, `timestamp_available`, `timestamp_age_us` and `clock_source`.
Negative timestamp age identifies a future report; unavailable age uses zero
with `timestamp_available=false`. Invalid head counts also retain their signed
`head_delta_frames`. Clock modes distinguish a
qualified timestamp from unavailable/warmup/stale/invalid head-based estimates.
The legacy local "buffered PCM" error classifier also retains its numeric value.
Peer text cannot populate these fields. The original failure still wins cleanup.

An actual AudioPlayer/TrackWorker startup reproduction also failed before the
admission fix: during its normal 80 ms preroll, 13 queued 20 ms packets fit
(240 ms PTS span, 49,920 bytes), but the next packet was rejected immediately
at 260 ms span. The sender's fair mux may flush up to 500 ms of pre-READY media
without pacing, so a short healthy startup burst need not be sustained overload.
The new bounded admission wait lets this reproduce-and-recover case drain while
retaining every PCM sample and encoded video packet. An interleaved shared-clock
test covers a 480 ms initial backlog followed by 20 ms capture-cadence arrivals
using the real PCM worker and synthetic decoded video, not physical MediaCodec.
This proves a local failure mechanism, **not** the exact phone event's track.

Every local media/control queue rejection now includes structured `track`
(0 control / 1 video / 2 audio), `queue_limit` (PACKETS / BYTES / PTS),
queued and attempted packet/byte counts, `relative_pts_span_us`, configured
limits and `wait_ms`. No absolute PTS, media or peer text is logged. Local offer
pressure remains `origin=SESSION`; a valid sender fatal ERROR is recorded as
`origin=PEER` and cannot manufacture local queue metadata. The waiting error
page retains the specific track/limit reason through cleanup without blaming
Wi-Fi by default. Persistent stalls still fail near the admission deadline;
byte/count/PTS and cancellation regressions verify bounds and prompt halt.

## Protocol and verified results

[PROTOCOL.md](PROTOCOL.md) and the seven JSON fixtures plus `opus-head.hex` in
[protocol/fixtures](protocol/fixtures) are sender-owned protocol copies. There is no
human `code`/`sessionCode` in HELLO/discovery. Unknown legacy JSON fields are
ignored, not validated or reflected. Old v1/v2 headers and TLS magic fail early;
type 2 AUTH is still reserved. Error objects still have their required error
`code`, and internal cast session IDs/generations are unchanged.

The verification totals below are historical snapshots, not a new run of the current tree.

- **166 host tests pass**, including **15 real ReceiverServer TCP/UDP tests**
  and **15 protocol tests** consuming all **13** shared wire fixtures.
  Coverage includes code-free HELLO/discovery, obsolete JSON-field ignorance,
  BUSY/concurrent one-cast admission, same-endpoint restart/reconnect,
  internal session-ID/readiness rejection, partial/coalesced reads, v1/v2/TLS/
  reserved-AUTH early rejection, sender Stop/disconnect and owned cleanup.
  One preceding full run received Windows `Connection reset` instead of EOF in
  the unchanged idle-setup socket shutdown assertion. That test passed in
  isolation and the final full rerun passed; no socket behavior or assertion
  was changed for this audio fix.
- Audio tests exercise the production worker rather than just clock helpers.
  Diagnostic tests verify first-cause retention, stage/reason classification,
  redaction, and peer-error origin through the actual receiver TCP/cleanup path.
- Eleven rate-envelope tests run the production PlatformAudioOutput preparation
  and delegation via a minimal platform seam, including the 5,760/5,793-frame
  rejection fixture, mono/stereo/rate/frame alignment, minimum/cap checks,
  fast/slow/nominal reset, unsupported/altered params, no early playback and
  release on prepare failure. The actual AudioPlayer >one-second correction test
  requires the validated platform buffer and succeeds at +0.5%.
- A real Android AudioTrack rate-envelope test is compiled for later authorized
  headless validation; it calls no play/write and is **not run in this task**.
- Three production PlatformAudioOutput/AudioPlayer clock traces cover 129 ms
  hardware transit, stale-but-true timestamps and a real bounded client-output
  stall. Nine counter tests cover qualification, timestamp loss/recovery,
  negative/future/regressing reports, reset versus unsigned wrap, accurate
  occupancy, extrapolation and impossible counters/capacity. Controlled clock
  diagnostics have a peer-redaction/numeric-retention regression.
  A shared-clock failure observed by the video scheduling worker also retains
  its AUDIO_FAILED code and numeric audio-clock cause, rather than becoming a
  misleading video decoder failure.
- Eleven video tests use the real VideoPlayer/TrackWorker with a minimal codec
  adapter: isolated late render, decoded-only lookahead replacement, first
  keyframe/input-capacity/null-buffer faults, malformed output/PTS/EOS, actual
  input/dequeue/release exception stages, numeric resource/reclaim hints,
  bounded clock/progress stalls, initialization differences and owned cleanup.
  A 65-second simulated interleaved audio/video generation regression and two
  video diagnostic/first-cause UI tests preserve all 128 previous host cases.
- Four underrun recovery/pipeline tests and two monitor tests retain all 142
  prior cases without adding a silence generator or changing clock semantics.
- Eleven orientation tests preserve all 148 prior cases: all 13 shared schema
  variants; default/forced/repeated/ignored policies; exact restoration and
  stale-owner cleanup; actual TCP rejection/no negotiation-time application;
  real ReceiverServer Playing/generation/Stop/failure lease ownership via the
  existing decoder seam; explicit platform-request failure; and in-place
  manifest configuration handling. A virtual-time/counter update in the old
  gap test is now atomic to avoid a test-only sampling race; production audio
  behavior is unchanged.
- Native orientation UI tests are **compiled only**, not run. They check the
  presentation UI boundary, same Activity/SurfaceView and accepted TCP heartbeat
  across configuration handling, policy restore and unrelated recreation.
  They require a separately authorized awake/unlocked receiver and do not
  establish live MediaCodec playback/physical-axis acceptance. No device
  installation, navigation or orientation change was performed for this task.
- Six Opus-order pipeline/conformance/budget tests and one pre-skip regression
  retain all 159 orientation/audio/video baselines. All seven shared Opus cases
  are checked alongside the 13 wire frames and 13 orientation schema cases.
  Native JNI Opus source-order instrumentation is compiled only. The orientation
  rejection/retry test waits for actual server cleanup before a new admission,
  rather than racing the one-active-sender BUSY gate.
- A separate **compiled-only** headless hardware test sends 2.4 seconds of
  silent PCM through the real PlatformAudioOutput and AudioPlayer, checks all
  115,200 frames are submitted and consumed, actual client capacity, monotonic
  presentation and at least two successful runtime corrections. It creates no
  Activity, video decoder, network, capture or sender resources, requests no
  privileges, and releases only its owned PCM track. Missing/blocked output or
  no progress is an explicit test failure, not an assumed pass. Its 10-second
  timeout bounds setup/failure cleanup; normal data lasts 2.4 seconds. It does
  not prove audibility, hardware transit latency or live casting.
  Only after the parent separately verifies idle/safety and authorizes testing,
  select exactly
  `org.screencopy.receiver.media.SilentAudioClockTest#productionPcmOutputRunsPastTwoSecondsWithRealClockAndRateCorrection`
  with runner
  `org.screencopy.receiver.test/androidx.test.runner.AndroidJUnitRunner`.
  Do not substitute the entire connected instrumentation suite.
- Five startup/backpressure tests cover real PCM startup, interleaved synthetic
  video plus live-cadence PCM, sustained byte/count/PTS overload and halt.
  Two actual control-writer/receiver-reader tests verify rejection metadata
  originates at the real queue check and reaches error/UI/diagnostic handling.
- Existing lifecycle/admission/media tests and UI projection/resource checks
  pass: IP/status/Exit, first-frame gating, error recovery, late-callback exit
  guards, safe margins, portrait/landscape resources and no removed controls.
- Debug and instrumentation APKs compile for all three native ABIs; APK signing
  verifies. Rendering/Canvas/remote-focus/Exit cleanup instrumentation is
  **compiled only**, not executed during this task.
- Lint has **zero errors, 7 warnings** for pinned tool/dependency versions.
- The read-only sender-source interop harness checks actual ReceiverLink,
  ReceiverTarget, Settings, Wire and codec configs against this receiver over
  loopback TCP, including BUSY, ping/Stop, internal cast IDs and restart at the
  same endpoint. It also checks both parsers against all shared frames and
  serialized Opus/AAC/PCM config. It writes only receiver build output and
  verifies sender hashes remain stable.

No device installs, navigation, instrumentation or screenshots occur during
this task. No live capture/casting, actual MediaCodec video display, A/V latency,
output-device latency, long-session drift or 30-minute endurance/congestion is
claimed verified. Passing host tests/fixtures is not end-to-end playback proof.
The receiver remains independent of sender privilege/backend selection.

## Bundled software

The redistributable source archive `third_party\opus-1.5.2.tar.gz` has an adjacent
SHA-256 pin enforced by CMake. Required notices remain in the archive and APK
at `assets/OPUS-NOTICE.txt`, also available as
[OPUS-NOTICE.txt](app/src/main/assets/OPUS-NOTICE.txt). There is deliberately no
in-screen Licenses control. Distributors must preserve notices and supply
license text with their documentation/materials; codec patent/store obligations
remain separate.
