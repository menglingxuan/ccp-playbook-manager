@echo off
setlocal enabledelayedexpansion
rem Multi tenant mode: one instance is bound to one tenant.
rem Usage: run-tenant.cmd [tenantId] [port] [--init-database | --help]
rem Flags starting with "-" are passed through: --init-database rebuilds the tenant database
rem (asks for YES first), --help prints the options and exits without starting.
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined APP_DATA_ROOT set "APP_DATA_ROOT=%~dp0data"

set "FLAGS="
set "POSITIONAL="
for %%A in (%*) do (
  set "ARG=%%~A"
  if "!ARG:~0,1!"=="-" (
    set "FLAGS=!FLAGS! %%A"
  ) else (
    set "POSITIONAL=!POSITIONAL! %%A"
  )
)
set "TENANT_ID=default"
set "PORT=8080"
if not "!POSITIONAL!"=="" (
  for /f "tokens=1,2" %%A in ("!POSITIONAL!") do (
    set "TENANT_ID=%%A"
    if not "%%B"=="" set "PORT=%%B"
  )
)

"%JAVA_BIN%" -Dapp.data-root="%APP_DATA_ROOT%" -jar "%~dp0otc-clear-web\build\libs\otc-clear-testdata.jar" --tenant --tenant-id=!TENANT_ID! --server.port=!PORT! !FLAGS!
rem keep the exit code of the application, the initialization prompt uses it
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
