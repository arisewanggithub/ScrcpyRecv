# App updates

ScrcpySend and ScrcpyRecv are display names only. Their existing Android application IDs are unchanged, preserving in-place upgrades and app data. They are independent projects, not official scrcpy applications; upstream attribution remains in the existing third-party notices.

## Remote-only flow

The home screen has **Check for updates** and, when available, an update-details button. Navigate with D-pad directions and select with Center/OK. Focus has a high-contrast border. Checks are asynchronous on foreground START, at most daily after success; unsuccessful automatic attempts are throttled for an hour. Cached metadata is private to the app. Nothing checks or downloads after the activity stops. Automatic results never open a dialog or steal cast focus.

The update dialog shows installed/latest versions, status, **Update**, **Retry check**, and **Later**. Downloads focus **Cancel**; Back also cancels. Cancellation, timeout and process loss never turn a partial file into an installable update. Configuration changes cancel in-flight work safely; a completed, verified download remains available in the running process. Ordinary process restart requires an explicit new download. After a user-initiated installation attempt, the completed APK is retained across process restart, but its hash, package/version, and signing certificate must be verified again on explicit retry.

Installing requires the cast to be idle. The updater never stops capture or receiver playback for installation. **Allow installs** opens Android's app-scoped unknown-source permission settings; return and explicitly choose **Install update**. This is not a root/Shizuku/silent installer. The OEM installer may require its own confirmation. Missing settings or installer activities show actionable errors. No notification permission or browser is needed for this flow.

Installer launch requests a standard activity result (`EXTRA_RETURN_RESULT`, request code 7312). Only public `RESULT_OK`, `RESULT_CANCELED`, and `RESULT_FIRST_USER` outcomes are used; no hidden installer extras or reflection are needed. The private pending-attempt record and activity UI state survive recreation/process restore without automatically installing again. Android cancellation, rejection, and an unavailable/unconfirmed result show explicit status plus **Retry installation** and **Later**; Back dismisses without retrying. A launch or even `RESULT_OK` alone is never reported as success: the app rereads its actually installed version code and current signing certificate against the verified attempt's signer. Returning during an active cast updates status without opening a focus-stealing dialog. Retried launches still require fresh APK verification and a final idle-cast guard.

A new check, download, or verification takes precedence over historical installer status. Completed installer outcomes are cleared before that operation, including cooldown/retry errors, while a genuinely pending installer record is preserved. Verification errors remove the invalid APK and show the fresh verification error rather than an old cancellation message. Retention messages check whether the APK actually exists; activity recreation cannot revive a cleared old outcome.

On the receiver's actual waiting screen, initial Exit focus is preserved. Down/Right leads to **Check for updates** (`check_app_updates`); Up/Left returns to Exit. When visible, the update-details action (`app_update_available`) is reachable Down/Right from Check and returns Up/Left to Check. Hidden update actions are excluded from explicit focus links. The waiting ScrollView reveals focused controls on compact projector screens. Center on Check checks for updates, Back cancels the dialog without closing the receiver, and Center on deliberately focused Exit still closes the receiver.

## Public release contract

The production endpoints are pinned to:

- `https://api.github.com/repos/arisewanggithub/ScrcpySend/releases/latest`
- `https://api.github.com/repos/arisewanggithub/ScrcpyRecv/releases/latest`

Only stable, non-draft releases qualify. Each release must contain exactly one `update.json` and its exactly named APK (`ScrcpySend.apk` or `ScrcpyRecv.apk`). No PAT, credentials, device identifiers, capture details, or signing keys are sent. GitHub receives the ordinary HTTPS request metadata and public updater User-Agent. HTTP 403/429 rate limits honor bounded server cooldowns; manual retries do not bypass them.

`update.json` is at most 64 KiB and has these mandatory fields:

```json
{
  "schema": 1,
  "applicationId": "dev.screencopy.sender",
  "versionCode": 2,
  "versionName": "1.1",
  "apkAssetName": "ScrcpySend.apk",
  "sha256": "<64 lowercase hexadecimal characters>",
  "signingCertificateSha256": "<64 lowercase hexadecimal characters>",
  "protocolVersion": 3
}
```

For the receiver use `org.screencopy.receiver` and `ScrcpyRecv.apk`. Version codes must be positive integers no greater than 2147483647; names are not used for ordering. The placeholders above are documentation, not a valid manifest. Optional extra fields do not authorize protocol or signer changes.

Assets initially belong to the pinned repository's GitHub release download path. HTTPS redirects are bounded and restricted to the literal hosts `github.com`, `api.github.com`, `objects.githubusercontent.com`, and `release-assets.githubusercontent.com`. APKs are bounded to 250 MiB. The app verifies SHA-256, archive application ID, archive version against the manifest, strictly newer version code, manifest signer fingerprint, and equality with the currently installed app's single current signer. It verifies again before granting the installer read access to the private APK through its narrowly scoped, non-exported FileProvider.

Different signing keys, missing signers, multi-signer packages, or certificate rotations are refused rather than uninstalling or risking data loss. A CI release cannot update a debug-key installation unless its signing certificate is the same. Publication therefore still requires manually configured permanent signing with the intended existing certificate; neither these apps nor their tests create or upload a signing key.

Binary publication additionally requires the user's explicit repository variable
`BINARY_RELEASE_APPROVED=true` after license/distribution and third-party
corresponding-source/relink review. This is separate from signing configuration
and does not create a license or compliance guarantee. Source export and CI
checks do not require binary publication approval.

## Test targets

Both repositories contain pure JVM `dev.scrcpy.update.ReleasePolicyTest` and `dev.scrcpy.update.ReleaseClientTest`.

Updater production code, debug fixtures and tests are Kotlin. The sender's
`verifyKotlinSources` invariant covers every source set under `app/src` and the
protocol harness. Use results from the current test run, not historical totals.

Both debug APKs include the non-exported `dev.scrcpy.update.UpdateTestActivity`. The instrumentation class `dev.scrcpy.update.AppUpdateRemoteUiTest` exercises actual D-pad key events with an in-memory updater and installer callback:

- `remoteCheckDownloadCancelAndBackNeedNoTouch`
- `installPermissionReturnRetainsDownloadAndRequiresExplicitInstall`
- `activeCastNeverStoppedOrInstalled`
- `missingOemSettingsAndInstallerRemainCancellable`
- `failureRetryIsExplicitAndDpadReachable`
- `dpadDirectionsPreserveVisibleFocusAndBackCancels`
- `installerCancellationShowsExplicitDpadRetryWithoutRedownload`
- `installerFailureShowsRetryAndBackDoesNotInstallAgain`
- `pendingInstallRestoreNeedsExplicitRetryWithoutAutoLaunch`
- `installerResultOkDoesNotClaimSuccessWithoutInstalledVersionChange`
- `cancelledInstallThenFailedVerificationShowsFreshErrorAfterRestore`
- `cancelledInstallThenFailedCheckKeepsFileAndShowsFreshError`

Run only that instrumentation selector for the Linux official API 35 x86_64 emulator updater job. It does not start capture, contact GitHub, download an APK, open OEM permission settings, or launch a real installer. Test injection is constructor/package-scoped and has no exported broadcast, intent-extra, URL override, or production verification bypass.

Receiver provides `dev.scrcpy.update.AppUpdateRemoteUiLocalTest` using
Robolectric4.16.1/resources for widget focus, Center key handling, activity
results, lifecycle restoration and explicit installation retries.
`dev.scrcpy.update.UpdateOperationOutcomeLocalTest` covers failed verification/
check/download after cancellation, missing retained files and pending installer
records using controlled connections and private fixtures. These tests do not
read production preferences or launch a real installer.

Receiver Robolectric classes use SDK28, Conscrypt OFF, LEGACY graphics and a
paused main looper with real app resources. This Java-shadow configuration avoids
Robolectric native graphics unsupported on Windows ARM64. It is not physical
rendering or API35 platform evidence; the separate official Linux API35 emulator
job supplies that instrumentation target.

`dev.scrcpy.update.ReceiverMainActivityUpdateEntryTest` additionally creates the actual receiver MainActivity and waiting-screen resources/lifecycle on a compact landscape fixture. It injects key events through Robolectric's focused-window input dispatch, rather than manually choosing focus targets. Cases `initialExitDpadDownChecksUpdatesAndBackKeepsReceiverOpen` and `visibleUpdateDpadDownAndUpReturnsToIntentionalExit` verify entry, automatic scroll visibility, optional-update navigation, dialog Back cancellation, and deliberate Exit. Only the in-process, debug-guarded updater factory is injected; the fixture leaves the video surface unavailable so no receiver sockets, discovery, playback, capture, settings, or installer are started. There is no intent-extra backdoor or global key interception.

Dialog cancellation/dismissal callbacks are bound to their originating dialog instance. A queued dismissal from before an install-permission round trip cannot clear or cancel the replacement dialog. `AppUpdateRemoteUiLocalTest.permissionReturnReplacementDialogSurvivesQueuedOldDismiss` covers that lifecycle race while preserving explicit Install selection, the verified file, read grants, ClipData, and activity-result return flags.
