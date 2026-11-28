"""
Graphics-card support on Windows, downloaded on request instead of shipped: NVIDIA's cuBLAS and cuDNN DLLs
(about 1 GB) from their official wheels on PyPI, kept in the companion's data folder, so app updates stay small.
"""
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile

import core

DIR = os.path.join(core._data_dir(), "nvidia")
PACKAGES = (("nvidia-cublas-cu12", "12."), ("nvidia-cudnn-cu12", "9."))


def card_present():
    if os.name != "nt":
        return False
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def installed():
    return os.path.isdir(DIR) and any(n.lower().startswith("cudnn") for n in os.listdir(DIR)) and any(n.lower().startswith("cublas") for n in os.listdir(DIR))


def _wheel(package, major):
    with urllib.request.urlopen(f"https://pypi.org/pypi/{package}/json", timeout=30) as r:
        meta = json.load(r)

    def key(v):
        return [int(x) if x.isdigit() else 0 for x in v.split(".")]
    for v in sorted(meta.get("releases", {}), key=key, reverse=True):
        if not v.startswith(major):
            continue
        for f in meta["releases"][v]:
            if f.get("filename", "").endswith("win_amd64.whl"):
                return f["url"], f.get("size", 0)
    raise RuntimeError(f"No Windows download of {package} {major}x found.")


def download(progress=lambda msg: None):
    """Downloads and unpacks the DLLs into DIR. progress gets a line to show."""
    work = tempfile.mkdtemp(prefix="marginalia-nvidia-")
    try:
        out = DIR + ".new"
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        for i, (pkg, major) in enumerate(PACKAGES, 1):
            url, size = _wheel(pkg, major)
            whl = os.path.join(work, os.path.basename(url))
            with urllib.request.urlopen(url, timeout=60) as r, open(whl, "wb") as f:
                done = 0
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    f.write(b)
                    done += len(b)
                    progress(f"Graphics-card support, part {i} of {len(PACKAGES)}: {done // 1_000_000} of {size // 1_000_000 or '?'} MB")
            with zipfile.ZipFile(whl) as z:
                for name in z.namelist():
                    if name.lower().endswith(".dll") and "/bin/" in name:
                        with z.open(name) as src, open(os.path.join(out, os.path.basename(name)), "wb") as dst:
                            shutil.copyfileobj(src, dst, 1 << 20)
            os.remove(whl)
        shutil.rmtree(DIR, ignore_errors=True)
        os.replace(out, DIR)
        if sys.platform == "win32":
            try:
                os.add_dll_directory(DIR)
            except Exception:
                pass
            os.environ["PATH"] = DIR + os.pathsep + os.environ.get("PATH", "")
        progress("Graphics-card support is installed.")
    finally:
        shutil.rmtree(work, ignore_errors=True)
