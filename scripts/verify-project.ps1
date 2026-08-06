param(
    [switch]$SkipDocker,
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

Write-Host '== RoadMind backend verification ==' -ForegroundColor Cyan
& .\mvnw.cmd -B -ntp clean verify
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $SkipFrontend) {
    Write-Host '== RoadMind frontend checks ==' -ForegroundColor Cyan
    Push-Location .\roadmind-web
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        npm run type-check
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        npm run test -- --run
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        npm run build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}

Write-Host '== RoadMind offline evaluation ==' -ForegroundColor Cyan
python .\roadmind-evaluation\run_evaluation.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $SkipDocker) {
    Write-Host '== Docker Compose full configuration ==' -ForegroundColor Cyan
    docker compose --env-file .env.example --profile full config | Out-Null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host 'RoadMind local verification passed.' -ForegroundColor Green
