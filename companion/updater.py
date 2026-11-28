"""
Updates for Marginalia Companion: looks for a newer companion release on GitHub, downloads the file for this
computer, and swaps it in when the app is closed, then starts the new version.

Only the ready-made builds update themselves (they know their version and variant from build_info.json,
written by the build). Run from the source folder, it only says that there is a newer release.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
import tarfile

REPO = "Kianosh-Solheim/marginalia-lectoris"
RELEASES = f"https://api.github.com/repos/{REPO}/releases?per_page=30"
PAGE = f"https://github.com/{REPO}/releases"


def build_info(default_version):
    """{"version", "variant"} of this build; variant is linux, macos, windows or windows-nvidia ("source" when run from the folder)."""
    base = getattr(sys, "_MEIPASS", None)
    if base:
        try:
            with open(os.path.join(base, "build_info.json"), encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"version": default_version, "variant": "source"}


def _key(v):
    out = []
    for part in v.split("."):
        num = "".join(ch for ch in part if ch.isdigit())
        out.append(int(num) if num else 0)
    return out + [0] * (4 - len(out))


def newer(current, timeout=10):
    """The newest companion release above [current], as {"version", "assets": {name: url}, "url", "notes"}, or None."""
    req = urllib.request.Request(RELEASES, headers={"Accept": "application/vnd.github+json", "User-Agent": "MarginaliaCompanion"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        releases = json.load(r)
    best = None
    for rel in releases:
        tag = rel.get("tag_name", "")
        if not tag.startswith("companion-v") or rel.get("draft"):
            continue
        v = tag[len("companion-v"):]
        if _key(v) > _key(current) and (best is None or _key(v) > _key(best["version"])):
            best = {"version": v, "url": rel.get("html_url", PAGE), "notes": rel.get("body", ""),
                    "assets": {a["name"]: a["browser_download_url"] for a in rel.get("assets", [])}}
    return best


def asset_for(variant, assets):
    """The file to update this build with: on Windows the installer when there is one, else the build's own archive."""
    if variant.startswith("windows") and "Marginalia-Companion-windows-setup.exe" in assets:
        return "Marginalia-Companion-windows-setup.exe", assets["Marginalia-Companion-windows-setup.exe"]
    for name, url in assets.items():
        stem = name.split(".")[0]
        if stem == f"Marginalia-Companion-{variant}":
            return name, url
    return None


def install_dir():
    """The folder (or, on macOS, the .app) this build runs from, which the update replaces."""
    exe = os.path.abspath(sys.executable)
    if sys.platform == "darwin":
        p = exe
        while p and not p.endswith(".app"):
            p = os.path.dirname(p)
            if p == os.path.dirname(p):
                break
        if p.endswith(".app"):
            return p
    return os.path.dirname(exe)


def download_and_stage(name, url, progress=lambda done, total: None):
    """Downloads and unpacks the update; returns the unpacked folder or .app to put in place."""
    work = tempfile.mkdtemp(prefix="marginalia-update-")
    archive = os.path.join(work, name)
    req = urllib.request.Request(url, headers={"User-Agent": "MarginaliaCompanion"})
    with urllib.request.urlopen(req, timeout=60) as r, open(archive, "wb") as out:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            progress(done, total)
    if name.endswith(".exe"):
        return archive  # the Windows installer: run as it is
    unpacked = os.path.join(work, "new")
    os.makedirs(unpacked)
    if name.endswith(".zip"):
        if sys.platform == "darwin":  # ditto keeps the app's links and permissions
            subprocess.run(["ditto", "-x", "-k", archive, unpacked], check=True)
        else:
            with zipfile.ZipFile(archive) as z:
                z.extractall(unpacked)
    else:
        with tarfile.open(archive) as t:
            t.extractall(unpacked)
    os.remove(archive)
    entries = [os.path.join(unpacked, e) for e in os.listdir(unpacked)]
    if len(entries) != 1:
        raise RuntimeError("The download did not hold one app folder.")
    return entries[0]


def swap_and_restart(new_path, wait_pids):
    """
    Starts a small helper that waits until every process in [wait_pids] has closed (the window and the tray app,
    which hold the app's files), puts the new version in place (on Windows by running its installer quietly),
    and opens the app again, also when the update could not be put in place. Start it before asking the tray
    app to quit: closing the tray app also closes the window.
    """
    target = install_dir()
    pids = [int(p) for p in wait_pids if p]
    work = os.path.dirname(new_path) if new_path.endswith(".exe") else os.path.dirname(os.path.dirname(new_path))
    if os.name == "nt":
        old_exe = os.path.join(target, os.path.basename(sys.executable))
        script = os.path.join(tempfile.gettempdir(), "marginalia-companion-update.cmd")
        lines = ["@echo off", "chcp 65001 >nul", ":wait", "set alive=0"]
        for p in pids:
            lines.append(f'tasklist /FI "PID eq {p}" 2>nul | find "{p}" >nul && set alive=1')
        lines += ["if %alive%==1 (ping -n 2 127.0.0.1 >nul & goto wait)", "ping -n 3 127.0.0.1 >nul"]
        if new_path.endswith(".exe"):
            installed = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "Marginalia Companion", "Marginalia Companion.exe")
            lines += [f'"{new_path}" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS',
                      f'set "EXE={installed}"', f'if not exist "%EXE%" set "EXE={old_exe}"']
        else:
            lines += [f'robocopy "{new_path}" "{target}" /MIR /R:10 /W:1 /NFL /NDL /NJH /NJS >nul', f'set "EXE={old_exe}"']
        lines += ['start "" "%EXE%"', f'rmdir /S /Q "{work}"', '(goto) 2>nul & del "%~f0"']
        with open(script, "w", encoding="utf-8", newline="\r\n") as f:
            f.write("\n".join(lines) + "\n")
        subprocess.Popen(["cmd", "/c", script], creationflags=0x08000000 | 0x00000200, close_fds=True)  # no window, own group
        return
    script = os.path.join(tempfile.gettempdir(), "marginalia-companion-update.sh")
    if sys.platform == "darwin":
        relaunch = f'xattr -dr com.apple.quarantine "{target}" 2>/dev/null; open "{target}"'
    else:
        relaunch = f'nohup "{os.path.join(target, os.path.basename(sys.executable))}" >/dev/null 2>&1 &'
    wait = "".join(f"while kill -0 {p} 2>/dev/null; do sleep 1; done\n" for p in pids)
    with open(script, "w") as f:
        f.write("#!/bin/sh\n" + wait + "sleep 1\n"
                f'rm -rf "{target}.old" && mv "{target}" "{target}.old" && mv "{new_path}" "{target}" && rm -rf "{target}.old" '
                f'|| {{ [ -e "{target}" ] || mv "{target}.old" "{target}"; }}\n'
                f"{relaunch}\n"
                f'rm -rf "{work}" "$0"\n')
    os.chmod(script, 0o755)
    subprocess.Popen(["/bin/sh", script], start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def cleanup_failed(path):
    shutil.rmtree(os.path.dirname(path) if path.endswith(".exe") else os.path.dirname(os.path.dirname(path)), ignore_errors=True)
