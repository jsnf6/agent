# 启动服务
#   默认：基础设施 + 前端（后端留给你在 IDE 里跑，不占用 9082）
#   -Full：连同后端容器一起启动（整套跑在 Docker 里，会占用 9082）
param([switch]$Full)

$ErrorActionPreference = 'Stop'
$deployDir = Split-Path -Parent $PSScriptRoot
Push-Location $deployDir
if ($Full) {
    docker compose --profile app up -d
} else {
    docker compose up -d
}
docker compose ps
Pop-Location