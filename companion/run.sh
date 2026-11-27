#!/usr/bin/env bash
# Marginalia Companion on Linux: sets itself up the first time (in a folder of its own), then opens the window.
set -e
cd "$(dirname "$0")"
if ! python3 -c "import tkinter" 2>/dev/null; then
  echo "Python's window toolkit is missing. On Linux Mint / Ubuntu / Debian:  sudo apt install python3-tk python3-venv"
  exit 1
fi
if [ ! -x .venv/bin/python ]; then
  echo "First start: setting up (a few minutes, once)…"
  python3 -m venv .venv || { echo "Install the venv module first:  sudo apt install python3-venv"; exit 1; }
  .venv/bin/python -m pip install --upgrade pip >/dev/null
  .venv/bin/python -m pip install -r requirements.txt
fi
exec .venv/bin/python marginalia_companion.py "$@"
