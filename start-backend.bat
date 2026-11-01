@echo off
setlocal

rem Load local environment variables from deploy\.env when present.
if exist "%~dp0deploy\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0deploy\.env") do (
    if /i not "%%A"=="SPRING_DATASOURCE_URL" set "%%A=%%B"
  )
)

if not defined ALI_BAI_LIAN_API_KEY (
  echo [ERROR] ALI_BAI_LIAN_API_KEY is not set. Configure deploy\.env or your system environment.
  exit /b 1
)
if not defined TAVILY_API_KEY (
  echo [ERROR] TAVILY_API_KEY is not set. Configure deploy\.env or your system environment.
  exit /b 1
)
if not defined ES_PASSWORD (
  echo [ERROR] ES_PASSWORD is not set. Configure deploy\.env or your system environment.
  exit /b 1
)
if not defined SUPER_AGENT_ADMIN_PASSWORD (
  echo [ERROR] SUPER_AGENT_ADMIN_PASSWORD is not set. Configure deploy\.env or your system environment.
  exit /b 1
)
if not defined SUPER_AGENT_ADMIN_TOKEN_SECRET (
  echo [ERROR] SUPER_AGENT_ADMIN_TOKEN_SECRET is not set. Configure deploy\.env or your system environment.
  exit /b 1
)

set JAVA_HOME=E:\abjava
cd /d E:\nex-agent\super-agent\super-agent-business\super-agent-business-chat\target
E:\abjava\bin\java -jar super-agent-business-chat-0.0.1-SNAPSHOT.jar > E:\nex-agent\super-agent\app.log 2>&1
