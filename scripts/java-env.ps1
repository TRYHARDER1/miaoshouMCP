$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$candidates = @($env:JAVA21_HOME)
if (-not $env:JAVA21_HOME) {
    $localJdks = Join-Path $env:USERPROFILE '.jdks'
    $installedJdks = Get-ChildItem -LiteralPath $localJdks -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Match '21[.\-]' |
        Select-Object -ExpandProperty FullName
    $candidates = @($env:JAVA_HOME) + @($installedJdks)
}
$selectedJdk = $null
foreach ($candidate in $candidates) {
    if (-not $candidate) { continue }
    $javaBinary = Join-Path $candidate 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $javaBinary)) { continue }
    $versionOutput = (& $javaBinary -version 2>&1 | Out-String)
    if ($versionOutput -match 'version "21\.') {
        $selectedJdk = $candidate
        break
    }
}
if (-not $selectedJdk) {
    throw 'Set JAVA21_HOME to a Java 21 JDK before building or starting this project.'
}
$env:JAVA_HOME = $selectedJdk
$env:PATH = "$(Join-Path $selectedJdk 'bin');$env:PATH"
