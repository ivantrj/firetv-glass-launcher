param([Parameter(Mandatory=$true)][string]$TvHost, [string]$Python)
$ErrorActionPreference='Stop'
if (-not $Python) { $Python=Join-Path $PSScriptRoot '.venv/Scripts/python.exe' }
if (-not (Test-Path $Python)) { throw 'Create .venv with tools/adb/requirements.txt, or pass -Python with that venv Python.' }
$previousHost=$env:TV_HOST
try {
    $env:TV_HOST=$TvHost
    & $Python (Join-Path $PSScriptRoot 'control-os7.py') redirect-off
    if ($LASTEXITCODE -ne 0) { throw 'Restore failed; keep local-state/before-glass.json and check the connection.' }
} finally {
    $env:TV_HOST=$previousHost
}
