# 停止并移除容器（保留数据卷与镜像）
$ErrorActionPreference = 'Stop'
$deployDir = Split-Path -Parent $PSScriptRoot
Push-Location $deployDir
docker compose down
Pop-Location