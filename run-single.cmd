@echo off
setlocal
rem Single instance mode: tenant is fixed to "default".
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined APP_DATA_ROOT set "APP_DATA_ROOT=%~dp0data"

"%JAVA_BIN%" -Dapp.data-root="%APP_DATA_ROOT%" -jar "%~dp0otc-clear-web\build\libs\otc-clear-testdata.jar" --no-tenant --server.port=8080
endlocal
