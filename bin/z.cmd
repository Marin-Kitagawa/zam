@echo off
rem zam launcher: resolves the assembly jar next to this script.
setlocal
set "DIR=%~dp0"
set "JAR=%DIR%..\target\out\jvm\scala-3.9.0\zam\zam-assembly-0.1.0.jar"
if not exist "%JAR%" (
  echo zam: assembly jar not found at %JAR% 1>&2
  echo zam: build it with:  sbt assembly 1>&2
  exit /b 1
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%