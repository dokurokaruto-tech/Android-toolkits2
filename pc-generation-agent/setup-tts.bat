@echo off
setlocal
rem Interpreter detection must never let Python Install Manager download silently.
set "PYTHON_MANAGER_AUTOMATIC_INSTALL=false"
set "PYLAUNCHER_ALLOW_INSTALL="
cd /d "%~dp0"
set "PYTHON=.venv-tts\Scripts\python.exe"
set "CUDA_INDEX=https://download.pytorch.org/whl/cu126"
set "TORCH_VERSION=2.7.1+cu126"

if exist "%PYTHON%" goto inspect_packages
call :find_python
if not errorlevel 1 goto create_env
echo [INFO] 64-bit Python 3.12 was not found.
call :confirm "Download and install Python 3.12 for this Windows user?"
if errorlevel 1 goto canceled
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\install-python312.ps1 -Approved
if errorlevel 1 goto failed
call :find_python
if errorlevel 1 (
  echo [ERROR] Python 3.12 is still unavailable. Restart this window or check the installer log.
  goto failed
)

:create_env
call :confirm "Create the project's isolated .venv-tts environment?"
if errorlevel 1 goto canceled
%BOOTSTRAP% -m venv .venv-tts
if errorlevel 1 goto failed

:inspect_packages
"%PYTHON%" -c "import sys,struct; assert sys.version_info[:2] == (3,12) and struct.calcsize('P') == 8"
if errorlevel 1 (
  echo [ERROR] Existing .venv-tts is not usable as 64-bit Python 3.12.
  echo [INFO] Move that folder aside and rerun. No existing environment was deleted.
  goto failed
)
"%PYTHON%" tools\tts_setup.py --packages-check
if errorlevel 1 goto offer_packages
"%PYTHON%" -m pip check
if errorlevel 1 goto offer_packages
echo [OK] Required libraries are already installed. Skipping downloads.
goto prepare

:offer_packages
echo [INFO] Missing or incompatible libraries were detected.
echo [INFO] Downloads include CUDA PyTorch, Qwen TTS, Pillow and their dependencies.
echo [INFO] This may download several GB. Only .venv-tts will be changed.
call :confirm "Install or repair the required libraries?"
if errorlevel 1 goto canceled
"%PYTHON%" -m ensurepip --upgrade
if errorlevel 1 goto failed
"%PYTHON%" -m pip install --upgrade pip
if errorlevel 1 goto failed
rem The CUDA build suffix also replaces an already installed CPU-only wheel.
"%PYTHON%" -m pip install "torch==%TORCH_VERSION%" "torchaudio==%TORCH_VERSION%" --index-url "%CUDA_INDEX%"
if errorlevel 1 goto failed
"%PYTHON%" -m pip install -r requirements.txt -r requirements-tts.txt "torch==%TORCH_VERSION%" "torchaudio==%TORCH_VERSION%"
if errorlevel 1 goto failed
"%PYTHON%" tools\tts_setup.py --packages-check
if errorlevel 1 goto failed
"%PYTHON%" -m pip check
if errorlevel 1 goto failed

:prepare
"%PYTHON%" tools\tts_setup.py --sox-check
if not errorlevel 1 goto save_config
echo [INFO] SoX is an external command; installing the Python sox package alone is not enough.
call :confirm "Download and install portable SoX for this project?"
if errorlevel 1 goto canceled
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\install-sox.ps1 -Approved
if errorlevel 1 goto failed
"%PYTHON%" tools\tts_setup.py --sox-check
if errorlevel 1 goto failed

:save_config
if "%~1"=="" goto prepare_default
"%PYTHON%" tools\tts_setup.py --prepare --model-dir "%~1"
goto prepared

:prepare_default
"%PYTHON%" tools\tts_setup.py --prepare
:prepared
if errorlevel 1 goto failed
"%PYTHON%" tools\tts_setup.py --check
if errorlevel 1 (
  echo [INFO] Packages installed. Fix config.local.json or the reported errors.
  echo [INFO] Run check-tts.bat again after fixing them.
  goto failed
)
echo [OK] Ready for a real voice test. Run start-agent.bat.
echo [INFO] Optional speed mode: stop the agent, then run setup-tts-fast.bat (Y/N).
pause
exit /b 0

:canceled
echo [INFO] Canceled. This step was not performed. Earlier approved steps remain installed.
pause
exit /b 2

:failed
echo [ERROR] Setup or validation did not complete. See the output above.
pause
exit /b 1

:confirm
rem Fail closed on N, Ctrl+C, unavailable input, or a choice error.
choice /C YN /N /M "%~1 [Y/N]: "
if errorlevel 2 exit /b 1
if errorlevel 1 exit /b 0
exit /b 1

:find_python
set "BOOTSTRAP="
py -3.12 -c "import sys,struct; assert sys.version_info[:2] == (3,12) and struct.calcsize('P') == 8" >nul 2>nul
if not errorlevel 1 (
  set "BOOTSTRAP=py -3.12"
  exit /b 0
)
rem A just-installed Python is discoverable even without restarting CMD / PATH.
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" -c "import sys,struct; assert sys.version_info[:2] == (3,12) and struct.calcsize('P') == 8" >nul 2>nul
if not errorlevel 1 (
  set BOOTSTRAP="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
  exit /b 0
)
where python >nul 2>nul
if errorlevel 1 exit /b 1
python -c "import sys,struct; assert sys.version_info[:2] == (3,12) and struct.calcsize('P') == 8" >nul 2>nul
if errorlevel 1 exit /b 1
set "BOOTSTRAP=python"
exit /b 0
