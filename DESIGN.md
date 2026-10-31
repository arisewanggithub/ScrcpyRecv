# ScreenCopy Receiver for Android

Status: Native implementation. The user-approved trusted-family-LAN design
supersedes the prototype TLS/QR/persistent-pairing design. Sender-owned exact
wire schemas are in [PROTOCOL.md](PROTOCOL.md); setup/build/test results and
limitations are in [README.md](README.md).

## Scope and trust model

Receive screen video/audio from one sender on a reachable trusted LAN. No ADB,
root, Developer options, capture permissions, input injection, recording,
internet traversal, background receiving or desktop receiver is part of this app.

Use plain TCP. Media and controls are unencrypted and unauthenticated.
There is no human public code or confirmation step. Broadcast name/port/version
every two seconds over UDP. The sender selects the actual datagram source IP
and TCP port, never a name-based redirect. Do not claim security against network observers,
spoofed discovery, hostile-LAN clients or internet access.

Internal positive cast session IDs and track generations remain mandatory.
Restarting the listener at the same endpoint does not invalidate selection or
require a new human identifier. Activity exit/recreation and Surface retirement
close the listener.
No receiver TLS identity, QR secret, sender credentials, peer persistence,
revocation or identity-renewal workflow remains.

## Architecture

### Optional SRT trial

`-PsrtTrial=true` explicitly enables SRT. libsrt 1.5.7
is a transport-only JNI library: Media3 ExoPlayer 1.11.1 owns the standard live
fragmented MP4 extractor, codecs, AudioTrack, timing, A/V sync and late-frame policy.
There is no custom output/clock in
this APK. H.264/H.265/AV1 and Opus/AAC-LC (or audio off); enabled PCM selections fail
explicitly. H.264 sender rotation/configuration changes use standard `avc3`
in-band SPS/PPS at a real IDR; changed Opus configuration still needs a new session.
HEVC uses standard hev1/hvcC with actual complete VPS/SPS/PPS, checked by the
official FragmentedMp4Extractor and video/hevc platform-decoder gate before
SESSION_ACCEPT. Configuration/dimension changes carry complete actual VPS/SPS/PPS
before an authentic IRAP. The bounded bridge announces official HEVC SPS/profile/
CSD/dimensions before unchanged bytes/PTS/key flags; default Media3 decoder
adaptation/reinitialization preserves audio, clock and session. No repeated init,
fake CSD/keys or capture Stop. AV1/audio configuration changes remain unsupported.
Paired APK update, no v3 version bump; synthetic HEVC rotation validation pending.
Sender video hardware-only / all-compatible audio encoder policy does not
restrict receiver software decoders.
Only synthetic HEVC probes are permitted here; historical real OnePlus H265
display-capture/Stop kernel-panic safety is unresolved.

```text
v3 control -> strict SRT fMP4 metadata, pinned actual numeric peer/session
  -> explicit SESSION_ACCEPT mediaTransport.kind=srt
  -> native cancellable SRT caller -> unseekable uncached Media3 DataSource
  -> ProgressiveMediaSource / FragmentedMp4Extractor / video/mp4
  -> bounded AVC TrackOutput bridge / official Media3 SPS parser / real Format
  -> ExoPlayer renderers -> existing real Surface + Media3 AudioTrack
  -> first rendered video + requested audio advancing -> srt-playing/UI lease
```

The `SrtPlayback`/`srtOutput` boundary selects `SrtPlayer` explicitly. The absent
transport retains the custom path. No custom STREAM_CONFIG/READY applies to
the SRT backend. Present unsupported transport kinds are rejected with
`UNSUPPORTED_CONFIG`; they never select custom TCP.

The DataSource factory publishes owned handles before connect and admits only
one input/open per control session; seeking, automatic reconnects and retries
are disabled. Native close happens before main-looper player release,
unblocking the loader. No lifecycle monitor waits for main: FIFO main posts and
an atomic closed gate prevent cancelled/late startup or callbacks from
reviving a retired Surface. Existing session/orientation ownership checks
remain authoritative for presentation. Actual video size changes feed the
existing Activity aspect-fit path only after rendering readiness.
The bridge retains one bounded unmodified AVC access unit, announces actual
in-band CSD/profile/dimensions before committing its metadata, and forwards
original PTS/key flags. Default renderers perform decoder adaptation or
reinitialization, without speculative max-dimension hints, alternate backends,
audio resets, repeated MP4 init segments or a custom playback clock.

Canonical OpusHead and MP4 dOps/pre-skip/gain remain standard library metadata.
The source uses Media3's mature fragmented MP4 muxer with explicit 100 ms
flushes and standard tfdt timestamps; no manual box writer or fake keyframes.
The source's duration-based Opus presentation timestamps are anchored once;
source-PTS reversals remain diagnostics/FIFO payload order, not a custom
receiver clock. SRT latency is validated at 20–2000 ms (source uses 100);
ExoPlayer startup/rebuffer/max buffer targets are 0/0/1000 ms so the first
static video frame need not wait for another video sample. Neither is a measured
end-to-end latency guarantee. AES/encryption is disabled, matching the trusted
LAN policy. Too-late packet drop is disabled to preserve container bytes.
See README for fixture-driven host/instrumentation coverage and
unexecuted full-Activity acceptance requirements.

### Existing custom TCP backend

```text
Native remote screen: large actual IPs, concise status, Exit
  |
UDP advertisement + incoming plain TCP listener
  |
Bounded framing + listener-code setup + one-cast admission
  |                                      |
Video generation/queue                Audio generation/queue
  |                                      |
MediaCodec -> Surface                 libopus/AAC/PCM -> AudioTrack
  |                                      |
Video presentation <--------------- audio presentation clock
```

The sender owns requested capture/encoder settings. The receiver accepts or
rejects them explicitly, validates actual codec metadata, creates real decoders
and reports track readiness before media. No silent codec/quality changes,
disabled requested audio, or receiver-side recrop. H.264 is a default, not a
hardcoded codec restriction. Other codecs require actual device support.
Audio defaults to sender-selected Opus; bundled libopus handles packet decode,
canonical initialization, pre-skip and gain. AAC and PCM paths remain available.

## Remote-screen presentation

The idle UI is a quiet, high-contrast waiting screen with dominant actual IP
addresses and secondary textual status/Exit. No human code, port/name settings,
Disconnect or Licenses controls remain. The internal name is ScreenCopy Receiver,
TCP remains 27184, and discovery/manual sender protocol compatibility is unchanged.
Exit is the sole waiting-page action, with a large accessible D-pad/Enter target
and visible focus. It finishes normally through owned cleanup, never process kill.
An exit gate and Surface lifetime token reject late callbacks/startup.

Portrait/landscape native resource compositions use scalable address typography,
window/cutout insets and generous overscan-safe margins. The Surface stays
visible and initialized underneath the waiting page. A typed projection enters
full-screen playback only after enabled resources are initialized and the
decoder's actual frame-rendered callback; a socket/config event cannot hide it.
During casting, waiting text and Exit are gone and video aspect-fits the full
available area. Cleanup after sender Stop/disconnect restores Ready with current
IPs; errors retain concise recovery guidance. Screen-on is scoped to the
visible Activity. Android Back/Home retain normal lifecycle semantics.

Optional v3 SESSION_REQUEST `receiverOrientation` is strictly auto/landscape/
portrait, absent auto, invalid present values UNSUPPORTED_CONFIG. It affects
only this session's Activity presentation, not capture/video rotation, codec
metadata, dimensions, generations, timestamps or media. Apply after all enabled
tracks initialize at entry into Playing, never idle/negotiation. A UI-thread
lease captures/restores the prior requestedOrientation, suppresses duplicate
requests and ignores stale cleanup from older sessions. Auto leaves ordinary
OS policy untouched; system/fixed-screen/multi-window constraints may ignore
forced axes. SDK request errors are explicit, not silent format substitutions.

Handle orientation/screenSize/smallestScreenSize/screenLayout configuration
changes in place. Keep the Activity, root and SurfaceView/decoder/connection;
replace only the responsive waiting overlay and recompute insets/aspect-fit.
Do not re-request orientation from onConfigurationChanged. This prevents a
presentation-only request from entering the intentional Activity-recreation
disconnect path. Genuine unrelated teardown/Surface destruction still retires
owned decoders synchronously and reconnects through the existing lifecycle;
never claim an invalid Surface remains usable. Restore on terminal/exit and
preserve the prior policy in saved Activity state, not persistent preferences.

The manifest allows resizable/touchless use, but ordinary launcher support is
not Android TV launcher/store certification. Hardware/visual accessibility and
live playback still require validation. Required libopus notices remain packaged
in assets and source/distribution documentation without an in-screen license UI.

Preserve access-unit boundaries and the common microsecond capture timeline,
including independent per-track generations and Opus startup offsets. Decoder
preroll drains bounded queues, then commits the common live presentation epoch.
Do not drop dependent encoded video to hide congestion. Reject stale generations,
unsupported formats, invalid state transitions and malformed input explicitly.

## Lifecycle and bounds

```text
Available -> Setup -> Negotiating -> Initializing tracks -> Playing
          -> Stopping -> Available (same listener endpoint)
```

The UI owns receiving lifetime. A process-owned executor orders old listener
cleanup before a recreated Activity binds; stale Surface tokens cannot configure
new decoders. Surface-bound decoder teardown completes before Surface destruction
returns without requiring UI callbacks. Admission/shutdown share atomic socket
ownership, and teardown deadlines remain bounded.

Encoded media queues are 48 packets and 250 ms each (video 8 MiB, audio 512 KiB).
Admission/dequeue accounting is atomic. Temporary pressure waits cancellably
up to the existing 250 ms queue-duration budget, rather than immediately treating
an allowed initial sender burst as overload. No queue limits are expanded and
no compressed media packets are discarded. Persistent pressure still fails explicitly,
and halt wakes blocked offers. The deadline bounds shared-TCP head-of-line
delay instead of allowing an unbounded consumer wait.
AudioTrack/decoded PCM together stay within 250 ms. Header length validation
precedes allocation; a separate bounded serialized control writer cannot hide
stalls from the independent watchdog. Stop/EOF/errors close sockets, queues,
MediaCodec, AudioTrack and native Opus state. Media and credentials are not logged;
there are no ScreenCopy credentials in this design.

AudioRecord input observations are monotonic but need not exactly equal first
PTS plus sample count after a timestamp refresh. Encoded Opus observations may
also reverse because the pinned encoder can reconstruct output PTS separately.
Only accepted exact `opus` audio configuration grants nonnegative reversals,
without magnitude/startup restrictions; AAC/PCM/video remain strict. Retain
FIFO payload/source timestamps, pre-skip once and increasing decoded sample
positions. Existing queue bounds use actual min/max across queued media, not
last/first arrival timestamps. Release both corrected v3 apps together; no new
field/version or old-build compatibility signal exists.
The former 50 ms cumulative phase
guard was a receiver implementation mistake and is removed, not increased.
Associate source anchors with submitted sample positions and apply them as those
samples are presented. A monotonic source-time estimator slews at most 2% of
presented time; history is bounded to the output window and extrapolation cannot
advance beyond submitted PCM. Physical rate correction uses buffered PCM
occupancy with the existing +/-0.5% rate limits, not capture-clock phase.
No normal PCM is dropped. Existing render/queue/output/backpressure and malformed
input/output checks remain; no silent audio disable or video-only fallback.

The 20:10 native failure established that the former 120 ms output allocation
could not support the +0.5% correction (5,760 frames versus native 5,793 needed).
Output now reserves frame-aligned capacity within the existing 130 ms budget and
probes the actual platform at fast/slow/nominal rates before READY or PCM.
PCM format, actual capacity/effective frames, params readback and stopped state
are verified; nominal rate is restored. Unsupported fallback is FAIL, never mute
or nominal substitution. Any unsupported/rounded-over-budget case releases the
track and reports actionable startup UNSUPPORTED_CONFIG with controlled
OUTPUT_PREPARE/RATE_UNSUPPORTED numeric diagnostics. Future route changes remain
explicit failures and require new preparation. No queues or decode budgets grow.

The subsequent 20:39 AUDIO_CLOCK failure after successful preflight exposed a
different assumption: submitted-minus-DAC-presented PCM includes hardware transit
and is not client queue occupancy. For this streaming PCM path, the Android
playback head counts server consumption; the hardware timestamp estimates later
presentation. Use submitted-minus-consumed frames against actual client capacity
(<=130 ms), while A/V source mapping follows a separately qualified presentation
estimate. Require advancing timestamp frame/time reports, use at most the
existing 100 ms freshness window for extrapolation, and preserve the learned
consumption-to-presentation offset when falling back to head on stale/unavailable/
invalid reports. Presentation remains monotonic and never exceeds consumption.
An initially unavailable timestamp means an explicitly approximate clock, not
proof of zero hardware latency. Actual head resets/regressions, impossible
consumption and client overflow are explicit typed clock failures. Keep all
PCM and existing write-stall, queue, source-anchor and rate-envelope safeguards.
Relative numeric counter/occupancy/age/source diagnostics distinguish this
condition from rate unsupported, source-clock phase and actual output stalls.

Decoded video lateness is a presentation condition, not proof of unsupported
encoding. Beyond the existing 250 ms late threshold, inspect at most one newer
decoded output: skip the old decoded picture only when a successor is available,
otherwise render it immediately so a static screen does not disappear. No
compressed access units or decoder references are dropped; at most two codec
outputs are briefly owned and all existing encoded limits remain. Native frame
render callbacks, not decode/release counters, still gate fullscreen entry.
Future-clock waits and actual pending input/output without progress fail after
three seconds. Latest output PTS proves decoder progress without requiring 1:1
output counts. Invalid/out-of-order PTS, missing first keyframe, capacity/null
buffer, malformed metadata/EOS and actual codec/API/resource failures remain
explicit terminal errors.

A future decoded picture does not gate compressed input feeding. Keep its
original audio-master deadline and bounded held-output ownership while allowing
the codec's finite input capacity to absorb the lead. Otherwise video admission
can block the single TCP reader ahead of audio needed to advance that deadline.
Real-time actual-server/worker regressions reproduce PTS admission failure after
playback and a generation change with 1080x1920 H.265/20 Mbps, 60 or 30 fps,
200–300 ms hardware-transit models, a 100 ms output pause and a -149,651 us Opus
source-phase refresh. The fix changes neither the 250 ms queue/admission limits
nor the three-second clock/stall guards, and all compressed access units reach
the decoder in order. These adapter models do not measure JMGO throughput.
Pinned scrcpy 3.3.4 likewise separates
[compressed decode](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/decoder.c)
from its
[latest decoded-frame buffer](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/frame_buffer.c);
that is a decoded-presentation reference, not permission to drop encoded video
or to render future frames before this receiver's audio-master deadline.

AudioTrack's underrun count is a cumulative application-write-buffer statistic,
not an unrecoverable-error budget. Do not terminate an otherwise progressing
cast after a lifetime number of recovered occurrences. Keep sampled, rate-limited
count/delta/total, client-empty/recovery/queue, play-state and relative output
clock diagnostics; never infer a capture or Wi-Fi gap from the counter alone.
Native counter resets are reported, not used to reset an error allowance.
Successful normal PCM, write/stall/route/liveness guards and all clock/queue
bounds remain unchanged. The source-backed 22:32 fix adds no silence generator,
resampler, PTS rebasing or clock bridging. A bounded 120 ms host gap plus
resumption/generation test passes under the existing clock. Unproven long
packetless-audio/video-continuation policy remains separate from this root fix.

Video diagnostics retain stage/exception, bounded fault reason, numeric codec
error/recoverable/transient hints, generation/format context, relative delay/
hold/wait ages, input capacity/size and output flags, plus decode/render/skip/
late counters. Do not retain native diagnostic strings, absolute source PTS or
media/peer identifiers. Runtime errors are not mislabeled unsupported settings
in the UI; the first specific cause survives cleanup. The 21:59 older-build
VIDEO/OTHER failure lost this evidence, so its physical cause remains unknown.
Actual-worker simulated >65-second audio/video tests include the 149 ms phase
change and midstream rotation/resolution generation without resetting audio.
They establish receiver semantics, not hardware playback acceptance or H.265
Stop safety.

Retain the first terminal error through cleanup. Structured, privacy-limited
diagnostics identify local audio stage and exception/reason versus a peer error
without recording media, payloads, private names/endpoints or peer message text.
The later exact 64.112 ms SOURCE_TIMING failure was the invalid phase guard;
host reproduction confirms that code path and sustained recovery under the new
model. That is not live playback acceptance. Large/continuing corrections may
take time to converge; <=50 ms A/V offset is a measurement target, not a malformed
source fatal rule. Deployment and physical H.265 Stop/panic acceptance remain
parent-controlled/on hold pending safety and inactive-device verification.

Later read-only device evidence confirms local/session CONGESTION, but the
older diagnostic omitted its queue track/limit. A real AudioPlayer startup
burst reproduces immediate audio PTS-span rejection; bounded admission recovers
that case without dropping samples. This is not proof of the phone event's
exact track. Local queue failures now report controlled track/limit, queued/
attempted count/bytes, relative PTS span, limits and wait duration at the rejecting
check; valid peer errors remain distinctly peer-originated. No device replay
or deployment is authorized by the host result.

## Acceptance and limitations

Host checks must cover exact sender fixtures and actual plain TCP setup:
code-free HELLO/discovery, ignored obsolete JSON fields, internal cast session
ID/generation enforcement, BUSY, setup/version rejection and restart/reconnect
at the same endpoint, arbitrary framing fragmentation/coalescing,
abrupt disconnect and owned listener shutdown. Retain regression coverage for
Surface/lifecycle ordering and the real bounded audio startup pipeline.

Build both application/instrumentation APKs, all packaged native ABIs and lint.
No device installs/navigation occur for this UI task. Host reducer/resource
tests and compiled instrumentation rendering/remote Exit/cleanup tests cover
the new surfaces; no connected-device screenshot or render claim is made. UI lifecycle
testing requires an awake/unlocked test device; previous secure-locked attempts
are not passing UI acceptance evidence. Prototype TLS tests are obsolete and
must not be presented as validation of the new TCP protocol.

A compiled headless PCM-only instrumentation method exercises production output
and AudioPlayer for 2.4 seconds, including real counter queries and two runtime
correction ticks. It must report blocked/no-progress output explicitly, preserve
the client-capacity bound, and retire only its owned AudioTrack. It launches no
Activity, capture, network or video resources. Parent authorization and separate
idle/safety verification are prerequisites to selecting only this method;
compilation and fake-platform tests do not establish device acceptance.

Before release, run real sender capture and H.264/H.265 device decoding,
native Opus/AAC/PCM playback, audio-disabled sessions, crop/orientation/rotation,
network changes, route changes, unsupported formats, and interruption cleanup.
Measure rather than assume <=150 ms glass-to-glass latency and <=50 ms steady
A/V offset. Run 30-minute endurance and injected congestion/slow-decoder tests.
Device/store/codec obligations remain separate from source licensing.
