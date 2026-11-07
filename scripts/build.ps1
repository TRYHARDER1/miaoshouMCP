. (Join-Path $PSScriptRoot 'java-env.ps1')
Push-Location $projectRoot
try {
    & mvn -B -ntp clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
} finally {
    Pop-Location
}
