@echo off
rem zam launcher: resolves the installed assembly jar in %USERPROFILE%\Software\zam_cli.
setlocal
set "JAR=%USERPROFILE%\Software\zam_cli\zam-assembly-0.1.0.jar"
if not exist "%JAR%" (
  echo zam: assembly jar not found at %JAR% 1>&2
  echo zam: build it with:  sbt assembly   then copy it to Software\zam_cli 1>&2
  exit /b 1
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%