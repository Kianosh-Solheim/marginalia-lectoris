#!/usr/bin/env bash
# Marginalia Companion on macOS: sets itself up the first time (in a folder of its own), then opens the window.
set -e
cd "$(dirname "$0")"
if ! python3 -c "import tkinter" 2>/dev/null; then
  echo "Python's window toolkit is missing. Install Python from python.org (it includes the window toolkit)."
  exit 1
fi
if [ ! -x .venv/bin/python ]; then
  echo "First start: setting up (a few minutes, once)…"
  python3 -m venv .venv || { echo "Install Python from python.org first."; exit 1; }
  .venv/bin/python -m pip install --upgrade pip >/dev/null
  .venv/bin/python -m pip install -r requirements.txt
fi
exec .venv/bin/python marginalia_companion.py "$@"
