. (Join-Path $PSScriptRoot 'java-env.ps1')
foreach ($name in @('LLM_PROVIDER', 'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL')) {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if ($value) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
    }
}
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
