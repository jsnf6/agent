# 构建后端 jar、前端静态资源，并打包成 Docker 镜像（含 ES、Docling）
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\build-images.ps1
$ErrorActionPreference = 'Stop'

$deployDir = Split-Path -Parent $PSScriptRoot
$repoDir = Split-Path -Parent $deployDir
$javaHome = 'E:\abjava'
$maven = 'E:\Maven\apache-maven-3.9.12\bin\mvn.cmd'

Write-Host '[1/4] 构建后端 jar（Maven，跳过测试）...' -ForegroundColor Cyan
$env:JAVA_HOME = $javaHome
& $maven -B -DskipTests -f "$repoDir\pom.xml" -pl super-agent-business/super-agent-business-chat -am package
if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败' }

$jar = Join-Path $repoDir 'super-agent-business\super-agent-business-chat\target\super-agent-business-chat-0.0.1-SNAPSHOT.jar'
Copy-Item -LiteralPath $jar -Destination (Join-Path $deployDir 'backend\app.jar') -Force

Write-Host '[2/4] 构建前端静态资源（vite build）...' -ForegroundColor Cyan
$npm = (Join-Path (Split-Path -Parent (Get-Command node).Source) 'npm.cmd')
Push-Location (Join-Path $repoDir 'vue')
& $npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '前端构建失败' }
Pop-Location

$distDir = Join-Path $deployDir 'frontend\dist'
if (Test-Path $distDir) { Remove-Item -LiteralPath $distDir -Recurse -Force }
Copy-Item -LiteralPath (Join-Path $repoDir 'vue\dist') -Destination $distDir -Recurse -Force

Write-Host '[3/4] 构建后端 / 前端 / ES / Docling 镜像...' -ForegroundColor Cyan
Push-Location $deployDir
docker compose build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '镜像构建失败' }
Pop-Location

Write-Host '[4/4] 完成。执行 scripts\up.ps1 启动整套服务。' -ForegroundColor Green
