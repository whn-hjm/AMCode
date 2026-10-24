@echo off
setlocal
set "SDK=D:\Android\Sdk"
set "AVD=AMCode_Tablet_API34"
set "PORT=5558"

if not exist "%SDK%\emulator\emulator.exe" (
    echo Android emulator not found at %SDK%\emulator\emulator.exe
    pause
    exit /b 1
)

echo Starting %AVD% ...
start "" "%SDK%\emulator\emulator.exe" -avd "%AVD%" -gpu auto -no-audio -port %PORT%
