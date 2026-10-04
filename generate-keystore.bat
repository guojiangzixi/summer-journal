@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================================
echo   Summer Journal  -  Generate release keystore
echo ============================================================
echo.
echo   This creates summer-journal.jks in the project root.
echo.
echo   !!  IMPORTANT  !!
echo   1. Back this file up to at least two places (cloud + email + USB).
echo      Lose it and you can NEVER update this app again.
echo   2. Never commit it to git (already excluded by .gitignore).
echo   3. Never generate a second one. The first one is the only one.
echo.
pause

REM ---- locate keytool ----
set "KEYTOOL="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if not defined KEYTOOL (
    for /f "delims=" %%i in ('where keytool 2^>nul') do (
        if not defined KEYTOOL set "KEYTOOL=%%i"
    )
)
if not defined KEYTOOL (
    echo [X] keytool not found.
    echo     Install Android Studio ^(it bundles a JDK^), or a standalone JDK 17+,
    echo     then run this script again.
    echo.
    pause
    exit /b 1
)
echo Using: %KEYTOOL%
echo.

if exist "summer-journal.jks" (
    echo [X] summer-journal.jks already exists.
    echo     Refusing to overwrite - generating a new one would break upgrades
    echo     for every phone that already has the app installed.
    echo.
    pause
    exit /b 1
)

REM ---- generate (keytool prompts for the password interactively) ----
"%KEYTOOL%" -genkeypair -v ^
    -keystore summer-journal.jks ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -alias summerjournal ^
    -dname "CN=Summer Journal, OU=Personal, O=Personal, L=Changsha, ST=Hunan, C=CN"

if errorlevel 1 (
    echo.
    echo [X] keytool failed. Nothing was written.
    echo.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   Keystore created: summer-journal.jks
echo ============================================================
echo.
echo   Now enter the SAME password again so I can write
echo   keystore.properties for you. ^(Or skip and fill it manually.^)
echo.
set /p PWD=Password (leave empty to skip): 

if "%PWD%"=="" (
    echo.
    echo   Skipped. Copy keystore.properties.example to keystore.properties
    echo   and fill in the passwords yourself.
) else (
    echo storeFile=../summer-journal.jks> keystore.properties
    echo storePassword=%PWD%>> keystore.properties
    echo keyAlias=summerjournal>> keystore.properties
    echo keyPassword=%PWD%>> keystore.properties
    echo.
    echo   keystore.properties written. ^(it is gitignored^)
)

echo.
echo ------------------------------------------------------------
echo   NEXT STEP - DO THIS NOW:
echo     Copy summer-journal.jks to your cloud drive and your email.
echo     Without it you cannot publish an update, ever.
echo ------------------------------------------------------------
echo.
pause
