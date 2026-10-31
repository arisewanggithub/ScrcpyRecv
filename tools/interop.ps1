param([string]$SenderRoot = $(if ($env:SCRCPY_SEND_ROOT) { $env:SCRCPY_SEND_ROOT } else {
    Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'ScrcpySend'
}))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21, and run receiver host tests/build first to populate compiler/dependency caches.' }
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
function CachedJar($module) {
    $jar = Get-ChildItem (Join-Path $cache $module) -Recurse -Filter '*.jar' |
        Where-Object Name -NotMatch '-sources|-javadoc' | Select-Object -First 1
    if (-not $jar) { throw "Missing cached jar $module; run receiver host tests/build first." }
    return $jar.FullName
}
$stdlib = CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.4.21'
$compiler = @(
    (CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.4.21'), $stdlib,
    (CachedJar 'org.jetbrains.kotlin\kotlin-reflect\1.6.10'),
    (CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\2.4.21'),
    (CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2'),
    (CachedJar 'org.jetbrains\annotations\13.0')
) -join ';'
$receiver = Join-Path $root 'app\build\intermediates\runtime_app_classes_jar\debug\bundleDebugClassesToRuntimeJar\classes.jar'
if (!(Test-Path $receiver)) { throw 'Build the receiver debug app first. This tool never builds/writes the sender repository.' }
$classpath = @($receiver, $stdlib, (CachedJar 'org.json\json\20240303')) -join ';'
$sources = @('Wire.kt', 'Json.kt', 'Settings.kt', 'CodecConfig.kt', 'ReceiverTarget.kt', 'ReceiverLink.kt') |
    ForEach-Object { Join-Path $SenderRoot "app\src\main\java\dev\screencopy\sender\$_" }
$before = $sources | ForEach-Object { (Get-FileHash $_).Hash }
$outputDirectory = Join-Path $root 'build\interop'
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$output = Join-Path $outputDirectory 'sender-receiver-v3-interop.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath "-Xfriend-paths=$receiver" -d $output @sources (Join-Path $root 'protocol\SenderInterop.kt')
if ($LASTEXITCODE -ne 0) { throw 'Read-only source interoperability compile failed' }
& $java -cp "$output;$classpath" SenderInteropKt (Join-Path $root 'protocol\fixtures')
if ($LASTEXITCODE -ne 0) { throw 'Actual source loopback interoperability failed' }
$after = $sources | ForEach-Object { (Get-FileHash $_).Hash }
for ($i = 0; $i -lt $sources.Count; $i++) {
    if ($before[$i] -ne $after[$i]) { throw "Sender source changed during integration: $($sources[$i]); rerun against a stable snapshot." }
    Write-Output "Stable read-only source: $($sources[$i]) $($after[$i])"
}
