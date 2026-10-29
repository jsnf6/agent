# 实时查看后端日志
param([string]$Service = 'backend')
$deployDir = Split-Path -Parent $PSScriptRoot
Push-Location $deployDir
docker compose logs -f $Service
Pop-Location