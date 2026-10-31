$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
function Assert($condition, $message) {
    if (!$condition) { throw $message }
}
foreach ($file in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1')) {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    Assert (!$errors) "PowerShell syntax failed: $($file.Name)"
}
$inventory = (& (Join-Path $PSScriptRoot 'export-publication.ps1') | ConvertFrom-Json)
Assert ($inventory.files.Count -gt 100) 'Snapshot unexpectedly omitted project sources'
Assert ('gradle/wrapper/gradle-wrapper.jar' -in $inventory.files.path) 'Wrapper missing'
Assert ('.github/workflows/build-release.yml' -in $inventory.files.path) 'CI workflow missing'
if (Test-Path (Join-Path $root 'app/build.gradle')) {
    Assert ('app/src/main/java/dev/screencopy/sender/Secrets.kt' -in $inventory.files.path) 'Privacy helper source must not be confused with credentials'
}
foreach ($entry in $inventory.files) {
    Assert ($entry.sha256 -cmatch '^[0-9a-f]{64}$') 'Inventory hash format changed'
    Assert ($entry.path -notmatch '(^|/)(\.git|build|\.gradle|\.idea|\.vscode|\.copilot|node_modules)(/|$)|local\.properties$|\.(apk|jks|keystore)$') 'Forbidden snapshot path'
}
$workflow = Get-Content -LiteralPath (Join-Path $root '.github/workflows/build-release.yml') -Raw
Assert ($workflow -notmatch 'pull_request_target|connectedAndroidTest|adb connect') 'Unsafe CI trigger/device test'
Assert ($workflow -match "srt: \['false', 'true'\]") 'Both transport build modes must be checked'
Assert ($workflow -match 'requireReleaseSigning=true') 'Publication signing guard missing'
Assert ($workflow -match '-e class dev\.scrcpy\.update\.AppUpdateRemoteUiTest ') 'Only the agreed remote UI instrumentation class may run'
Assert ($workflow -match 'needs: \[checks, update-ui\]') 'Release must wait for both test jobs'
Assert ($workflow -match 'protocolVersion' -or (Get-Content (Join-Path $PSScriptRoot 'make-update-manifest.ps1') -Raw) -match 'protocolVersion = 3') 'Manifest protocol missing'
Assert ($workflow -match 'if: always\(\)') 'Keystore cleanup must run on failure'
$publishIndex = $workflow.IndexOf("`n  publish:")
Assert ($publishIndex -gt 0) 'Publish job missing'
Assert ($workflow.Substring(0, $publishIndex) -notmatch 'BINARY_RELEASE_APPROVED') 'Binary approval must not gate source/CI checks'
$gateIndex = $workflow.IndexOf('name: Require explicit binary distribution approval', $publishIndex)
$decodeIndex = $workflow.IndexOf('base64 --decode', $publishIndex)
Assert ($gateIndex -gt $publishIndex -and $decodeIndex -gt $gateIndex) 'Binary approval must run before signing-secret decoding'
Assert ($workflow -match 'BINARY_RELEASE_APPROVED: \$\{\{ vars\.BINARY_RELEASE_APPROVED \}\}') 'Binary approval must use the explicit repository variable'
$gateBlock = [regex]::Match($workflow, '(?ms)      - name: Require explicit binary distribution approval.*?        run: \|\r?\n(?<body>(?:          [^\r\n]*\r?\n)+)')
Assert $gateBlock.Success 'Binary approval shell block missing'
$gateCode = $gateBlock.Groups['body'].Value -replace '(?m)^          ', '' -replace "`r`n", "`n"
$bash = if ($env:OS -eq 'Windows_NT') {
    Join-Path (Split-Path (Split-Path (Get-Command git).Source -Parent) -Parent) 'bin/bash.exe'
} else { (Get-Command bash).Source }
$previousApproval = $env:BINARY_RELEASE_APPROVED
try {
    foreach ($approval in @('', 'false', 'TRUE', 'true')) {
        $env:BINARY_RELEASE_APPROVED = $approval
        $gateCode | & $bash --noprofile --norc | Out-Null
        Assert (($LASTEXITCODE -eq 0) -eq ($approval -ceq 'true')) 'Binary approval must fail closed except for literal true'
    }
} finally { $env:BINARY_RELEASE_APPROVED = $previousApproval }

# Fake SDK command output exercises manifest plumbing, never real signing keys/APKs.
$scratch = Join-Path $root ('publication-output/test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    $windowsHost = $env:OS -eq 'Windows_NT'
    $applicationId = if (Test-Path (Join-Path $root 'app/build.gradle')) { 'dev.screencopy.sender' } else { 'org.screencopy.receiver' }
    $asset = if ($applicationId -eq 'dev.screencopy.sender') { 'ScrcpySend.apk' } else { 'ScrcpyRecv.apk' }
    $certificate = 'ab' * 32
    $signerText = "Signer #1 certificate SHA-256 digest: $certificate"
    $packageText = "package: name='$applicationId' versionCode='10001' versionName='1.2.3'"
    $signerPath = Join-Path $scratch $(if ($windowsHost) { 'apksigner.bat' } else { 'apksigner' })
    $aaptPath = Join-Path $scratch $(if ($windowsHost) { 'aapt2.exe' } else { 'aapt2' })
    if ($windowsHost) {
        Set-Content -LiteralPath $signerPath -Value "@echo off`necho $signerText`nexit /b 0" -Encoding ASCII
        # aapt2.exe is a command name, so use a test function rather than create an executable.
        Set-Item -Path "Function:global:$aaptPath" -Value ([scriptblock]::Create("`$global:LASTEXITCODE = 0; `"$packageText`""))
    } else {
        [IO.File]::WriteAllText($signerPath, "#!/bin/sh`nprintf '%s\n' '$signerText'`n", [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($aaptPath, "#!/bin/sh`nprintf '%s\n' `"${packageText}`"`n", [Text.UTF8Encoding]::new($false))
        & chmod +x $signerPath $aaptPath
        Assert ($LASTEXITCODE -eq 0) 'Cannot initialize fake SDK fixture'
    }
    $apk = Join-Path $scratch 'fixture.apk'
    [IO.File]::WriteAllText($apk, 'not-an-android-package; mock SDK test fixture')
    $parameters = @{
        Apk = $apk; ApplicationId = $applicationId; VersionCode = 10001; VersionName = '1.2.3'
        ApkAssetName = $asset; BuildTools = $scratch; OutputDirectory = (Join-Path $scratch 'result')
    }
    & (Join-Path $PSScriptRoot 'make-update-manifest.ps1') @parameters
    $result = Get-Content -LiteralPath (Join-Path $parameters.OutputDirectory 'update.json') -Raw | ConvertFrom-Json
    Assert (($result.PSObject.Properties.Name -join ',') -eq 'schema,applicationId,versionCode,versionName,apkAssetName,sha256,signingCertificateSha256,protocolVersion') 'Exact updater schema changed'
    Assert ($result.versionCode -is [int] -or $result.versionCode -is [long]) 'versionCode must be an integer'
    Assert ($result.schema -eq 1 -and $result.protocolVersion -eq 3) 'Manifest schema/protocol mismatch'
    Assert ($result.signingCertificateSha256 -ceq $certificate) 'Certificate digest mismatch'
    Assert ($result.sha256 -ceq (Get-FileHash $apk).Hash.ToLowerInvariant()) 'APK digest mismatch'
    foreach ($invalid in @(
        @{ VersionCode = 1 }, @{ VersionCode = 10002 }, @{ VersionName = 'wrong' },
        @{ ApplicationId = 'invalid.package' },
        @{ ApkAssetName = $(if ($asset -eq 'ScrcpySend.apk') { 'ScrcpyRecv.apk' } else { 'ScrcpySend.apk' }) }
    )) {
        $badParameters = $parameters.Clone()
        foreach ($key in $invalid.Keys) { $badParameters[$key] = $invalid[$key] }
        $failed = $false
        try { & (Join-Path $PSScriptRoot 'make-update-manifest.ps1') @badParameters } catch { $failed = $true }
        Assert $failed 'Invalid APK/version/asset must fail closed'
    }
    if ($windowsHost) {
        Set-Content -LiteralPath $signerPath -Value '@echo off', 'exit /b 1' -Encoding ASCII
    } else {
        [IO.File]::WriteAllText($signerPath, "#!/bin/sh`nexit 1`n", [Text.UTF8Encoding]::new($false))
    }
    $unsignedRejected = $false
    try { & (Join-Path $PSScriptRoot 'make-update-manifest.ps1') @parameters } catch { $unsignedRejected = $true }
    Assert $unsignedRejected 'Signature verification failure must forbid a manifest'
    $existingDestinationRejected = $false
    try { & (Join-Path $PSScriptRoot 'export-publication.ps1') -Destination $scratch | Out-Null } catch { $existingDestinationRejected = $true }
    Assert $existingDestinationRejected 'Exporter must never overwrite an existing directory'
    $exportPath = Join-Path $scratch 'snapshot'
    $export = (& (Join-Path $PSScriptRoot 'export-publication.ps1') -Destination $exportPath | ConvertFrom-Json)
    $copied = @(Get-ChildItem -LiteralPath $exportPath -Recurse -File -Force)
    Assert ($copied.Count -eq $export.files.Count) 'Export file count differs from allowlist'
    Assert (!(Test-Path (Join-Path $exportPath '.git'))) 'Git history must never be exported'
    foreach ($entry in $export.files) {
        Assert ((Get-FileHash -LiteralPath (Join-Path $exportPath $entry.path)).Hash.ToLowerInvariant() -ceq $entry.sha256) 'Copied file hash differs from inventory'
    }
    Write-Output "PASS publication scripts: verified export $($export.files.Count) files; exact schema, hashes, metadata and six rejection cases"
} finally {
    if ($env:OS -eq 'Windows_NT') { Remove-Item -LiteralPath "Function:global:$aaptPath" -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
