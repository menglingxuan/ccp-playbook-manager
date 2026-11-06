@echo off
setlocal
rem Multi tenant mode: one instance is bound to one tenant.
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined APP_DATA_ROOT set "APP_DATA_ROOT=%~dp0data"
if "%~1"=="" (
  set "TENANT_ID=hkex-otc"
) else (
  set "TENANT_ID=%~1"
)
if "%~2"=="" (
  set "PORT=8080"
) else (
  set "PORT=%~2"
)

"%JAVA_BIN%" -Dapp.data-root="%APP_DATA_ROOT%" -jar "%~dp0otc-clear-web\build\libs\otc-clear-testdata.jar" --tenant --tenant-id=%TENANT_ID% --server.port=%PORT%
endlocal
