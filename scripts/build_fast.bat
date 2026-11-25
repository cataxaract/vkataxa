@echo off

:: F-00 fast path: replace classesN.dex / version.properties inside the last unsigned APK
:: (build-output\VTLite-base.apk) instead of running apktool b. Full build is still required
:: after any change under smali\ (sources, resources, manifest) - see docs\BUILD.md.

setlocal EnableDelayedExpansion
cd /d "%~dp0.."

echo [fast] F-00 fast path: patch existing APK, no apktool b

if not exist "build-output\VTLite-base.apk" (
    echo [fast] missing build-output\VTLite-base.apk - run a full build first: gradlew.bat buildVTLDev
    exit /B 1
)

set "BASE=build-output\VTLite-base.apk"
set "UNSIGNED=build-output\VTLite-fast-unsigned.apk"
set "ALIGNED=build-output\VTLite-fast-aligned.apk"
set "OUT=build-output\VTLite.apk"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$b=(Get-Item -LiteralPath 'build-output\VTLite-base.apk').LastWriteTime; $t=(Get-ChildItem -LiteralPath 'smali\apktool.yml','smali\AndroidManifest.xml' | Measure-Object LastWriteTime -Maximum).Maximum; $r=Get-ChildItem -LiteralPath 'smali\res' -Recurse -File -ErrorAction SilentlyContinue | Measure-Object LastWriteTime -Maximum; if ($r.Maximum -gt $t) { $t=$r.Maximum }; if ($t -gt $b) { exit 1 }"
if errorlevel 1 (
    echo [fast] smali resources/manifest are newer than the base APK - run a full build: gradlew.bat buildVTLDev
    exit /B 1
)

set "COUNT=0"
set "REPL="
for %%f in (app\classes*.dex) do (
    set /a COUNT+=1
    set "REPL=!REPL! %%~nxf %%f"
)
if "%COUNT%"=="0" (
    echo [fast] no app\classes*.dex - Gradle exportDex did not run
    exit /B 1
)
if exist "app\version.properties" set "REPL=!REPL! assets/version.properties app\version.properties"

echo [fast] dex entries to replace: %COUNT%
call java -Xmx2g scripts\ApkPatch.java "%BASE%" "%UNSIGNED%"!REPL!
if errorlevel 1 exit /B 1

set "ZIPALIGN="
for /f "delims=" %%z in ('where zipalign 2^>nul') do if not defined ZIPALIGN set "ZIPALIGN=%%z"
if not defined ZIPALIGN if defined ANDROID_HOME call :find_bt "%ANDROID_HOME%\build-tools"
if not defined ZIPALIGN if defined ANDROID_SDK_ROOT call :find_bt "%ANDROID_SDK_ROOT%\build-tools"
if not defined ZIPALIGN call :find_bt "%LOCALAPPDATA%\Android\Sdk\build-tools"
if not defined ZIPALIGN (
    echo [fast] zipalign not found ^(install build-tools or set ANDROID_HOME^)
    exit /B 1
)
echo [fast] zipalign: %ZIPALIGN%
call "%ZIPALIGN%" -p -f 4 "%UNSIGNED%" "%ALIGNED%"
if errorlevel 1 (
    echo [fast] zipalign failed
    exit /B 1
)
del /Q "%UNSIGNED%" >nul 2>nul

call java -jar scripts\apksigner.jar sign --ks scripts\debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android "%ALIGNED%"
if errorlevel 1 (
    echo [fast] signing failed
    exit /B 1
)
move /Y "%ALIGNED%" "%OUT%" >nul
del /Q "%ALIGNED%.idsig" >nul 2>nul
echo [fast] OK: %OUT%
exit /B 0

:find_bt
for /f "delims=" %%d in ('dir /b /ad /o-n "%~1" 2^>nul') do (
    if not defined ZIPALIGN if exist "%~1\%%d\zipalign.exe" set "ZIPALIGN=%~1\%%d\zipalign.exe"
)
exit /B 0
