@echo off
rem Marginalia Companion on Windows: sets itself up the first time (in a folder of its own), then opens the window.
cd /d "%~dp0"
where py >nul 2>nul && (set PY=py -3) || (set PY=python)
%PY% -c "import tkinter" 2>nul || (echo Install Python 3 from python.org first, with "tcl/tk" and "Add to PATH" ticked. & pause & exit /b 1)
if not exist ".venv\Scripts\python.exe" (
  echo First start: setting up ^(a few minutes, once^)...
  %PY% -m venv .venv || (pause & exit /b 1)
  .venv\Scripts\python.exe -m pip install --upgrade pip >nul
  .venv\Scripts\python.exe -m pip install -r requirements.txt || (pause & exit /b 1)
)
start "" .venv\Scripts\pythonw.exe marginalia_companion.py
