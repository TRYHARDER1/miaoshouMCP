. (Join-Path $PSScriptRoot 'java-env.ps1')
$jar = Join-Path $projectRoot 'target/miaoshou-mcp-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build the server with scripts/build.ps1 first.'
}
Push-Location $projectRoot
try {
    & java -jar $jar
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
