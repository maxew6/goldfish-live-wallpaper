@rem Gradle wrapper launcher (Windows). Runs the version pinned in
@rem gradle\wrapper\gradle-wrapper.properties (Gradle 8.5).
@echo off
setlocal

set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if exist "%WRAPPER_JAR%" goto haveJar

where gradle >nul 2>nul
if errorlevel 1 goto noJar

echo gradle-wrapper.jar not found - generating it with the local gradle command...
set "TMP_DIR=%TEMP%\gwrap_%RANDOM%"
mkdir "%TMP_DIR%"
pushd "%TMP_DIR%"
call gradle wrapper --gradle-version 8.5 -q
popd
copy /y "%TMP_DIR%\gradle\wrapper\gradle-wrapper.jar" "%WRAPPER_JAR%" >nul
rmdir /s /q "%TMP_DIR%"
if exist "%WRAPPER_JAR%" goto haveJar

:noJar
echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing. 1>&2
echo. 1>&2
echo Fix (any one of these): 1>&2
echo   1. Open this folder in Android Studio and let it sync, then Build - Build APK(s). 1>&2
echo   2. Install Gradle from https://gradle.org/install and run: gradle wrapper --gradle-version 8.5 1>&2
echo   3. Copy gradle-wrapper.jar from any other Gradle project into gradle\wrapper\. 1>&2
exit /b 1

:haveJar
if defined JAVA_HOME (
    set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVACMD=java.exe"
)

"%JAVACMD%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
