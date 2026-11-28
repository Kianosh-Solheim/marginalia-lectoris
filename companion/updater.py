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


def swap_and_restart(new_path):
    """
    Starts a small script that waits for this app to close, puts [new_path] in place of the installed app, and
    opens the new version. The caller then closes the app.
    """
    target = install_dir()
    pid = os.getpid()
    if os.name == "nt":
        exe = os.path.join(target, os.path.basename(sys.executable))
        script = os.path.join(tempfile.gettempdir(), "marginalia-companion-update.cmd")
        with open(script, "w", encoding="utf-8") as f:
            f.write("@echo off\r\n"
                    f":wait\r\ntasklist /FI \"PID eq {pid}\" | find \"{pid}\" >nul && (timeout /t 1 /nobreak >nul & goto wait)\r\n"
                    f"robocopy \"{new_path}\" \"{target}\" /MIR /R:5 /W:1 /NFL /NDL /NJH /NJS >nul\r\n"
                    f"start \"\" \"{exe}\"\r\n"
                    f"rmdir /S /Q \"{os.path.dirname(os.path.dirname(new_path))}\"\r\n")
        subprocess.Popen(["cmd", "/c", script], creationflags=0x08000000 | 0x00000008)  # no window, detached
        return
    script = os.path.join(tempfile.gettempdir(), "marginalia-companion-update.sh")
    if sys.platform == "darwin":
        relaunch = f'xattr -dr com.apple.quarantine "{target}" 2>/dev/null; open "{target}"'
    else:
        relaunch = f'nohup "{os.path.join(target, os.path.basename(sys.executable))}" >/dev/null 2>&1 &'
    with open(script, "w") as f:
        f.write("#!/bin/sh\n"
                f"while kill -0 {pid} 2>/dev/null; do sleep 1; done\n"
                f'rm -rf "{target}.old" && mv "{target}" "{target}.old" && mv "{new_path}" "{target}" && rm -rf "{target}.old" '
                f'|| {{ [ -e "{target}" ] || mv "{target}.old" "{target}"; }}\n'
                f"{relaunch}\n"
                f'rm -rf "{os.path.dirname(os.path.dirname(new_path))}" "$0"\n')
    os.chmod(script, 0o755)
    subprocess.Popen(["/bin/sh", script], start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def cleanup_failed(path):
    shutil.rmtree(os.path.dirname(os.path.dirname(path)), ignore_errors=True)
