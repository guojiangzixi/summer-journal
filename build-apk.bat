@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"

echo ============================================================
echo   Summer Journal  -  Build APK
echo   mode: %MODE%
echo ============================================================
echo.

REM ---------------------------------------------------------------
REM  1. Check Java
REM ---------------------------------------------------------------
set "JAVA_EXE="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_EXE (
    for /f "delims=" %%i in ('where java 2^>nul') do (
        if not defined JAVA_EXE set "JAVA_EXE=%%i"
    )
)
if not defined JAVA_EXE (
    echo [X] Java not found.
    echo.
    echo     Install Android Studio first - it brings its own JDK:
    echo         https://developer.android.com/studio
    echo.
    echo     After installing, open this folder in Android Studio once.
    echo     Then you can either use the green Run button, or run this script.
    echo.
    pause
    exit /b 1
)
echo [1/5] Java ......... OK

REM ---------------------------------------------------------------
REM  2. Check Gradle wrapper
REM ---------------------------------------------------------------
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [X] gradle\wrapper\gradle-wrapper.jar is missing.
    echo     The file was not copied correctly. Re-copy the whole code folder.
    echo.
    pause
    exit /b 1
)
if not exist "gradlew.bat" (
    echo [X] gradlew.bat is missing. Re-copy the whole code folder.
    echo.
    pause
    exit /b 1
)
echo [2/5] Wrapper ...... OK

REM ---------------------------------------------------------------
REM  3. Make sure the Android SDK path is known
REM     Android Studio writes local.properties on first sync.
REM     If you build from the command line without Studio, we derive it.
REM ---------------------------------------------------------------
if not exist "local.properties" (
    set "SDK_DIR="
    if defined ANDROID_HOME set "SDK_DIR=%ANDROID_HOME%"
    if not defined SDK_DIR if defined ANDROID_SDK_ROOT set "SDK_DIR=%ANDROID_SDK_ROOT%"
    if not defined SDK_DIR if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"

    if defined SDK_DIR (
        echo sdk.dir=!SDK_DIR:\=\\!> "local.properties"
        echo [3/5] SDK .......... generated local.properties
    ) else (
        echo [3/5] SDK .......... NOT FOUND
        echo.
        echo     Open this folder in Android Studio once and let it finish
        echo     "Gradle Sync". Studio will create local.properties for you.
        echo.
        echo     Continuing anyway - if the build fails, that is the reason.
        echo.
    )
) else (
    echo [3/5] SDK .......... already configured
)

REM ---------------------------------------------------------------
REM  4. Build
REM ---------------------------------------------------------------
echo.
echo [4/5] Building %MODE% APK ^(first run downloads Gradle + dependencies,
echo       which can take 5-15 minutes^) ...
echo.

if /I "%MODE%"=="release" (
    call gradlew.bat assembleRelease
    set "SRC=app\build\outputs\apk\release\app-release.apk"
    set "DST=..\APK\SummerJournal-release.apk"
) else (
    call gradlew.bat assembleDebug
    set "SRC=app\build\outputs\apk\debug\app-debug.apk"
    set "DST=..\APK\SummerJournal-debug.apk"
)

if errorlevel 1 (
    echo.
    echo ============================================================
    echo   BUILD FAILED
    echo ============================================================
    echo.
    echo   Most common causes:
    echo     - No Android SDK. Open the project in Android Studio once.
    echo     - Network cannot reach Google Maven. Use a mirror or a proxy.
    echo     - Java version too old. Android Studio's bundled JDK 17+ works.
    echo.
    pause
    exit /b 1
)

REM ---------------------------------------------------------------
REM  5. Copy the APK somewhere easy to find
REM ---------------------------------------------------------------
if not exist "..\APK" mkdir "..\APK"
copy /Y "!SRC!" "!DST!" >nul
if errorlevel 1 (
    echo   [X] Built, but could not copy from !SRC!
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   DONE
echo ============================================================
echo.
echo   APK file:
echo     %CD%\..\APK\%DST:~12%
echo.
echo   Send THIS ONE FILE to your phone, then tap it to install.
echo   Source code and docs are NOT needed on the phone.
echo.
echo   Reminder: WeChat renames APK files to xxx.apk.1
echo             If it will not install, delete the trailing ".1".
echo.
pause
