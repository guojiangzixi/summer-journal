@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "FILE=version.properties"
set "NEWNAME=%~1"

if "%NEWNAME%"=="" (
    echo.
    echo   Usage: bump-version.bat ^<new versionName^>
    echo      e.g. bump-version.bat 1.1.0
    echo.
    echo   This will:
    echo     - read  the current versionCode and add 1
    echo     - set   versionName to the value you passed
    echo     - write both back to version.properties
    echo.
    exit /b 1
)

if not exist "%FILE%" (
    echo [X] %FILE% not found. Run this from the project root ^(code/^).
    exit /b 1
)

set "OLDCODE="
set "OLDNAME="
for /f "usebackq tokens=1,2 delims==" %%A in ("%FILE%") do (
    if /i "%%A"=="versionCode" set "OLDCODE=%%B"
    if /i "%%A"=="versionName" set "OLDNAME=%%B"
)
set "OLDCODE=%OLDCODE: =%"
set "OLDNAME=%OLDNAME: =%"

if "%OLDCODE%"=="" (
    echo [X] Could not read versionCode from %FILE%.
    exit /b 1
)

set /a NEWCODE=OLDCODE+1

echo.
echo   versionCode : %OLDCODE%  -^>  %NEWCODE%
echo   versionName : %OLDNAME%  -^>  %NEWNAME%
echo.

> "%FILE%" (
    echo # Summer Journal - the single source of truth for the app version.
    echo # versionCode must strictly increase. versionName is for humans.
    echo versionCode=%NEWCODE%
    echo versionName=%NEWNAME%
)

echo   Wrote %FILE%.
echo.
echo   NEXT:
echo     1. If you changed the database schema -^> add a Room migration and bump
echo        @Database^(version^) too. See 版本更新指南.md section 6.
echo     2. Build:  build-apk.bat release
echo     3. Install over the old one:  adb install -r APK\SummerJournal-release.apk
echo     4. Verify the old data is still there.
echo.
