@echo off
setlocal
set "BASE_DIR=%~dp0"
set "PROJECT_DIR=%BASE_DIR:~0,-1%"
set "WRAPPER_JAR=%BASE_DIR%.mvn\wrapper\maven-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
  where curl.exe >nul 2>nul
  if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar' -OutFile '%WRAPPER_JAR%'"
  ) else (
    curl.exe -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -o "%WRAPPER_JAR%"
  )
  if errorlevel 1 (
    echo Failed to download Maven Wrapper. Check network access to Maven Central.
    exit /b 1
  )
)
java "-Dmaven.multiModuleProjectDirectory=%PROJECT_DIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %ERRORLEVEL%
