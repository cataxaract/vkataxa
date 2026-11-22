@echo off

:: ANSI Codes (ONLY windows 10 support)
::RED - [31m
::GREEN - [92m
::NC - [0m
::BOLD - [1m
::UNDERLINE - [4m (Not supporting)

setlocal
call :setESC

cls
echo.
echo %ESC%[1;4mBuild apk...%ESC%[0m
echo.
cd smali\
if not exist ..\build-output mkdir ..\build-output
call java -Xmx4g -Djava.awt.headless=true -jar ../scripts/apktool.jar b . -o ../build-output/VTLite.apk --use-aapt2 -p ../scripts/framework/
if "%ERRORLEVEL%" == "0" (
    echo %ESC%[92mSuccessful%ESC%[0m
) else (
    echo %ESC%[31mFailed%ESC%[0m
    exit /B 1
)

echo.
echo %ESC%[1;4mZipaligning...%ESC%[0m
echo.
where zipalign >nul 2>nul
if errorlevel 1 (
    echo %ESC%[33mzipalign not found, alignment skipped%ESC%[0m
) else (
    call zipalign -p -f 4 ..\build-output\VTLite.apk ..\build-output\VTLite.apk_aligned
    if errorlevel 1 (
        echo %ESC%[31mZipalign failed%ESC%[0m
        exit /B 1
    )
    move /Y ..\build-output\VTLite.apk_aligned ..\build-output\VTLite.apk >nul
    echo %ESC%[92mZipalign successful%ESC%[0m
)

echo.
echo %ESC%[1;4mSign apk...%ESC%[0m
echo.
call java -jar ../scripts/apksigner.jar sign --ks ../scripts/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android ../build-output/VTLite.apk
if "%ERRORLEVEL%" == "0" (
    echo %ESC%[92mSuccessful%ESC%[0m
) else (
    echo %ESC%[31mFailed%ESC%[0m
    exit /B 1
)

copy /Y ..\RELEASE_NOTES.md ..\build-output\RELEASE_NOTES.md >nul

:setESC
for /F "tokens=1,2 delims=#" %%a in ('"prompt #$H#$E# & echo on & for %%b in (1) do rem"') do (
  set ESC=%%b
  exit /B 0
)
exit
