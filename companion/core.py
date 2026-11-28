#!/usr/bin/env python3
"""
Marginalia Companion: syncs an audiobook with its book's text on a computer instead of in the browser.

Whisper (faster-whisper) runs natively here, on every processor core and with the computer's own memory, so it is
several times faster than Whisper in a browser tab and does not crash it. Two ways to work:

  * From the tablet, over Wi-Fi: sign in with the PIN shown on the tablet, pick the book; the audio comes from the
    tablet, the transcript is saved there as it grows (stop and continue any time), and at the end the sentences
    are matched and the map is saved in the book's folder, exactly as the web view does it.
  * From files on this computer: pick the audiobook's files; the transcript is saved as a file you import in the
    web view ("Sync with the text…" → "Import transcript…"), which then matches the sentences in seconds.

Run it with the run script next to this file (it sets everything up the first time), or: python3 marginalia_companion.py
"""
import http.cookiejar
import json
import os
import queue
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from align import align  # noqa: E402


def _data_dir():
    """Where the companion keeps its library copy, jobs and settings."""
    if os.name == "nt":
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        return os.path.join(base, "Marginalia Companion")
    if sys.platform == "darwin":
        return os.path.expanduser("~/Library/Application Support/Marginalia Companion")
    return os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "marginalia-companion")


def _nvidia_libraries():
    """Windows + NVIDIA build: make NVIDIA's cuBLAS and cuDNN (shipped with the app, or pip-installed) findable."""
    if os.name != "nt":
        return
    dirs = [os.path.join(_data_dir(), "nvidia")]  # downloaded on request (Settings → graphics card)
    base = getattr(sys, "_MEIPASS", None)
    if base:
        dirs.append(os.path.join(base, "nvidia_dlls"))
    try:
        import importlib.util
        spec = importlib.util.find_spec("nvidia")
        for loc in (spec.submodule_search_locations if spec else []) or []:
            for sub in ("cublas", "cudnn", "cuda_runtime", "cuda_nvrtc"):
                dirs.append(os.path.join(loc, sub, "bin"))
    except Exception:
        pass
    for d in dirs:
        if os.path.isdir(d):
            try:
                os.add_dll_directory(d)
            except Exception:
                pass
            os.environ["PATH"] = d + os.pathsep + os.environ.get("PATH", "")


_nvidia_libraries()


def _self_command(arg):
    """This app again, with [arg] (the packaged app, or the entry script run with this Python)."""
    if getattr(sys, "frozen", False):
        return [sys.executable, arg]
    return [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "marginalia_companion.py"), arg]


def _apple_silicon():
    import platform
    return platform.system() == "Darwin" and platform.machine() == "arm64"


_GPU = {}  # this process's answer, so the card is tried once (cleared when its support is downloaded)


def gpu_works(log=lambda m: None):
    if "r" not in _GPU:
        _GPU["r"] = _gpu_works(log)
    return _GPU["r"]


def _gpu_works(log=lambda m: None):
    """
    The graphics device speech recognition really runs on here, as (device, compute_type), or None for the
    processor: an NVIDIA card (cuda) or Apple Silicon graphics (mps, experimental). Tried in a separate process,
    as a missing library can end a process outright instead of raising an error, and this app must not stop.
    """
    if _apple_silicon():
        device = "mps"
    else:
        try:
            import ctranslate2
            if ctranslate2.get_cuda_device_count() <= 0:
                return None
        except Exception:
            return None
        device = "cuda"
    log("Trying the graphics card…" if device == "cuda" else "Trying the Mac's graphics (Apple Silicon)…")
    try:
        r = subprocess.run(_self_command("--gpu-probe") + [device], timeout=300,
                           creationflags=0x08000000 if os.name == "nt" else 0)  # no console window; its errors go to the log
    except Exception as e:
        print(f"graphics check: {e}", file=sys.stderr)
        return None
    print(f"graphics check ({device}): exit code {r.returncode}", file=sys.stderr)
    return {0: (device, "float16"), 10: (device, "float32")}.get(r.returncode)


def gpu_probe(device):
    """--gpu-probe cuda|mps: a small model on that device, a second of silence. Exit 0 (float16), 10 (float32) or 3."""
    import numpy as np
    from faster_whisper import WhisperModel
    for code, ct in ((0, "float16"), (10, "float32")):
        try:
            w = WhisperModel("tiny", device=device, compute_type=ct)
            list(w.transcribe(np.zeros(16000, dtype="float32"))[0])
            os._exit(code)
        except Exception:
            traceback.print_exc()
            sys.stderr.flush()
            continue
    os._exit(3)

APP = "Marginalia Companion"
VERSION = "1.3"
WINDOW_MS = 300_000  # transcribe in five-minute windows, as the web view does (and resume at a window)
DEFAULT_ADDRESS = "http://marginalia.local:8080"

# Speech models. Keys start with "pc_" so the web view knows the transcript was made on a computer.
MODELS = {
    "pc_en_fast": {"label": "English · fast (base.en)", "model": "base.en", "lang": "en", "beam": 1},
    "pc_en_exact": {"label": "English · more exact (small.en)", "model": "small.en", "lang": "en", "beam": 5},
    "pc_no_fast": {"label": "Norwegian · fast (small)", "model": "small", "lang": "no", "beam": 1},
    "pc_no": {"label": "Norwegian · more exact (medium)", "model": "medium", "lang": "no", "beam": 5},
    "pc_other": {"label": "Other language, detected (small)", "model": "small", "lang": None, "beam": 5},
    "pc_best": {"label": "Best, any language (large-v3-turbo; slow without a graphics card)", "model": "large-v3-turbo", "lang": None, "beam": 5},
}

SETTINGS_FILE = os.path.join(os.path.expanduser("~"), ".marginalia-companion.json")


def clock(ms):
    s = int(ms // 1000)
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60}:{s % 60:02d}"


def load_settings():
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(s):
    try:
        with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
            json.dump(s, f)
    except Exception:
        pass


# ------------------------------------------------------------------ the tablet's web view API

class TabletError(RuntimeError):
    def __init__(self, msg, code=0):
        super().__init__(msg)
        self.code = code


class Unreachable(RuntimeError):
    """The tablet did not answer (off, asleep, or another network)."""


class Tablet:
    """Talks to the tablet's web view server: with a paired device key, or signed in with its PIN like a browser."""

    def __init__(self, address, key=None):
        address = address.strip().rstrip("/")
        if not address.startswith("http"):
            address = "http://" + address
        if not urllib.parse.urlparse(address).port:
            address += ":8080"
        self.base = address
        self.key = key
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _req(self, method, path, body=None, timeout=120, raw=False):
        data = None
        headers = {"X-Marginalia": "1", "Accept": "application/json"}
        if self.key:
            headers["Authorization"] = "Bearer " + self.key
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + "/api/" + path, data=data, method=method, headers=headers)
        for attempt in range(self.tries):
            try:
                with self.opener.open(req, timeout=timeout) as r:
                    payload = r.read()
                    return payload if raw else json.loads(payload.decode("utf-8") or "{}")
            except urllib.error.HTTPError as e:
                try:
                    msg = json.loads(e.read().decode("utf-8")).get("error") or str(e)
                except Exception:
                    msg = str(e)
                if e.code >= 500 and attempt < self.tries - 1:
                    time.sleep(2)
                    continue
                raise TabletError(msg, e.code) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                if attempt < self.tries - 1:
                    time.sleep(3)
                    continue
                raise Unreachable(f"The tablet does not answer at {self.base} ({getattr(e, 'reason', e)}). "
                                  "Is the web view on in the app, and is this computer on the same Wi-Fi?") from None

    tries = 3

    def session(self):
        return self._req("GET", "session", timeout=8)

    def pair(self, code, name):
        return self._req("POST", "pair", {"code": code, "name": name}, timeout=20)

    def sync_info(self, doc_id):
        return self._req("GET", f"doc/{doc_id}/audiobook/sync-info", timeout=60)

    def delete_audiobook(self, doc_id):
        return self._req("DELETE", f"doc/{doc_id}/audiobook", timeout=300)

    def upload_audio(self, doc_id, path, progress=lambda done, total: None):
        """Sends one audio file to the book's audiobook on the tablet (streamed)."""
        size = os.path.getsize(path)
        name = os.path.basename(path)

        class Reader:
            def __init__(self, f):
                self.f, self.done = f, 0

            def read(self, n=-1):
                b = self.f.read(1 << 20 if n is None or n < 0 else n)
                self.done += len(b)
                progress(self.done, size)
                return b
        q = urllib.parse.urlencode({"name": name})
        headers = {"X-Marginalia": "1", "Content-Type": "application/octet-stream", "Content-Length": str(size)}
        if self.key:
            headers["Authorization"] = "Bearer " + self.key
        with open(path, "rb") as f:
            req = urllib.request.Request(f"{self.base}/api/doc/{doc_id}/audiobook/file?{q}", data=Reader(f), method="POST", headers=headers)
            try:
                with self.opener.open(req, timeout=1800) as r:
                    return json.loads(r.read().decode("utf-8") or "{}")
            except urllib.error.HTTPError as e:
                try:
                    msg = json.loads(e.read().decode("utf-8")).get("error") or str(e)
                except Exception:
                    msg = str(e)
                raise TabletError(msg, e.code) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                raise Unreachable(f"The upload to the tablet broke off ({getattr(e, 'reason', e)}).") from None

    def download_audio(self, doc_id, index, path, progress=lambda done, total: None, stop=None):
        """
        Copies one of the book's audiobook files to [path], continuing a part copy ([path].part) where it stopped
        (the tablet serves byte ranges). Returns False when stopped.
        """
        part = path + ".part"
        have = os.path.getsize(part) if os.path.isfile(part) else 0
        headers = {"X-Marginalia": "1"}
        if self.key:
            headers["Authorization"] = "Bearer " + self.key
        if have:
            headers["Range"] = f"bytes={have}-"
        req = urllib.request.Request(f"{self.base}/api/doc/{doc_id}/audiobook/file/{index}", headers=headers)
        try:
            with self.opener.open(req, timeout=60) as r:
                if have and r.status != 206:
                    have = 0  # no range: from the start
                total = have + int(r.headers.get("Content-Length") or 0)
                with open(part, "ab" if have else "wb") as f:
                    done = have
                    while True:
                        if stop is not None and stop.is_set():
                            return False
                        b = r.read(1 << 20)
                        if not b:
                            break
                        f.write(b)
                        done += len(b)
                        progress(done, total)
                if total and done < total:
                    raise Unreachable("The copy from the tablet broke off.")
        except urllib.error.HTTPError as e:
            if e.code == 416 and have:  # already complete
                pass
            else:
                raise TabletError(str(e), e.code) from None
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
            raise Unreachable(f"The copy from the tablet broke off ({getattr(e, 'reason', e)}).") from None
        os.replace(part, path)
        return True

    def login(self, pin):
        self._req("POST", "login", {"pin": pin}, timeout=20)

    def library(self):
        return self._req("GET", "library", timeout=60).get("documents", [])

    def audiobook(self, doc_id):
        return self._req("GET", f"doc/{doc_id}/audiobook", timeout=60)

    def transcript(self, doc_id):
        return self._req("GET", f"doc/{doc_id}/audiobook/transcript", timeout=60)

    def save_transcript(self, doc_id, tr):
        self._req("PUT", f"doc/{doc_id}/audiobook/transcript", tr, timeout=180)

    def pcm(self, doc_id, file_index, from_ms, len_ms):
        q = urllib.parse.urlencode({"file": file_index, "from": from_ms, "len": len_ms})
        return self._req("GET", f"doc/{doc_id}/audiobook/pcm?{q}", timeout=300, raw=True)

    def text(self, doc_id):
        return self._req("GET", f"doc/{doc_id}/audiobook/text", timeout=900)

    def save_map(self, doc_id, m):
        return self._req("POST", f"doc/{doc_id}/audiobook/sync", m, timeout=300)


# ------------------------------------------------------------------ speech

MODEL_MB = {"base.en": 145, "small.en": 484, "small": 484, "medium": 1530, "large-v3-turbo": 1620, "tiny": 75}


def ensure_model(size, log=lambda m: None):
    """
    The speech model's folder, downloaded the first time (with progress) and checked: a download that was
    interrupted leaves a folder without model.bin, which the speech engine would keep opening; it is removed
    and the model downloaded again.
    """
    import shutil
    from faster_whisper.utils import _MODELS, download_model
    from huggingface_hub import constants
    repo = _MODELS.get(size, size)
    cache = os.path.join(constants.HF_HUB_CACHE, "models--" + repo.replace("/", "--"))
    total = MODEL_MB.get(size)
    for attempt in range(2):
        done = threading.Event()

        def watch():
            while not done.wait(2):
                got = 0
                for d, _, fs in os.walk(cache):
                    for f in fs:
                        try:
                            got += os.path.getsize(os.path.join(d, f))
                        except OSError:
                            pass
                if got > 1_000_000:
                    log(f"Downloading the speech model ({size}, once): {got // 1_000_000} of {f'about {total}' if total else '?'} MB")
        threading.Thread(target=watch, daemon=True).start()
        try:
            path = download_model(size)
        finally:
            done.set()
        if os.path.isfile(os.path.join(path, "model.bin")):
            return path
        print(f"speech model {size}: no model.bin in {path}; removing the cache and downloading again", file=sys.stderr)
        shutil.rmtree(cache, ignore_errors=True)
    raise RuntimeError(f"The speech model ({size}) could not be downloaded. Check the internet connection and press Try again.")


class Speech:
    """faster-whisper, on the graphics card when there is a CUDA one, else on every processor core."""

    def __init__(self, key, log):
        from faster_whisper import WhisperModel  # imported here so the window opens at once
        spec = MODELS[key]
        self.spec = spec
        self.key = key
        self.device = "cpu"
        log(f"Loading the speech model {spec['model']} (downloaded once, then kept on this computer)…")
        path = ensure_model(spec["model"], log)
        log("Loading the speech model…")
        try:
            g = gpu_works(log)
            if not g:
                raise RuntimeError("no usable graphics device")
            self.model = WhisperModel(path, device=g[0], compute_type=g[1])
            self.device = g[0]
        except Exception:
            self.model = WhisperModel(path, device="cpu", compute_type="int8", cpu_threads=os.cpu_count() or 4)
        log({"cuda": "Running on the graphics card.", "mps": "Running on the Mac's graphics (Apple Silicon)."}.get(self.device, f"Running on the processor ({os.cpu_count() or '?'} threads)."))
        where = {"cuda": "graphics card", "mps": "Apple Silicon graphics"}.get(self.device, "processor")
        self.engine = f"faster-whisper on a computer ({where}, {spec['model']})"

    def transcribe(self, pcm_float32, offset_ms, stop):
        """Segments ({s, e, t} in book time) for one window of 16 kHz mono audio."""
        segs, _info = self.model.transcribe(
            pcm_float32, language=self.spec["lang"], beam_size=self.spec["beam"], task="transcribe",
            vad_filter=True, condition_on_previous_text=False)
        out = []
        for sg in segs:
            if stop.is_set():
                break
            t = (sg.text or "").strip()
            if not t or (t.startswith("[") and t.endswith("]")) or (t.startswith("(") and t.endswith(")")):
                continue
            out.append({"s": int(round(offset_ms + sg.start * 1000)), "e": int(round(offset_ms + sg.end * 1000)), "t": t})
        return out


def pcm16_to_float(buf):
    import numpy as np
    return np.frombuffer(buf, dtype="<i2").astype("float32") / 32768.0


def file_windows(path, stop):
    """A local audio file as five-minute windows of 16 kHz mono float32 (streamed, so a long book fits in memory)."""
    import av
    import numpy as np
    win = 16000 * WINDOW_MS // 1000
    with av.open(path) as cont:
        stream = next(s for s in cont.streams if s.type == "audio")
        res = av.AudioResampler(format="s16", layout="mono", rate=16000)
        buf, have = [], 0
        for frame in cont.decode(stream):
            if stop.is_set():
                return
            for f in res.resample(frame):
                a = f.to_ndarray().reshape(-1)
                buf.append(a)
                have += a.size
                while have >= win:
                    cat = np.concatenate(buf)
                    yield cat[:win].astype("float32") / 32768.0
                    rest = cat[win:]
                    buf, have = ([rest] if rest.size else []), rest.size
        for f in res.resample(None):
            a = f.to_ndarray().reshape(-1)
            buf.append(a)
            have += a.size
        if have:
            yield np.concatenate(buf).astype("float32") / 32768.0


# ------------------------------------------------------------------ the jobs (run on a worker thread)

def job_tablet(tab, doc, key, restart, stop, log, progress):
    ab = tab.audiobook(doc["id"])
    if not ab.get("exists"):
        raise RuntimeError("This book has no audiobook on the tablet. Add it in the book's details first.")
    total = ab.get("durationMs") or sum(f["durationMs"] for f in ab["files"])
    tr = tab.transcript(doc["id"]) or {}
    # Continue where it stopped (also a transcription begun in the browser), unless asked to start over.
    if restart or not tr.get("segments"):
        tr = {"modelKey": key, "segments": [], "doneMs": 0}
    tr["modelKey"] = key
    if tr.get("doneMs", 0) < total - 1000:
        speech = Speech(key, log)
        tr["engine"] = speech.engine
        tr["language"] = MODELS[key]["lang"] or "auto"
        started, done_at_start = time.time(), tr.get("doneMs", 0)
        for f in ab["files"]:
            for off in range(0, int(f["durationMs"]), WINDOW_MS):
                if stop.is_set():
                    log("Stopped. What is done is saved on the tablet; press Start to continue.")
                    return False
                g = int(f["startMs"]) + off
                length = min(WINDOW_MS, int(f["durationMs"]) - off)
                if g + length <= tr.get("doneMs", 0) + 500:
                    continue
                left = ""
                if tr["doneMs"] > done_at_start:
                    rate = (time.time() - started) / (tr["doneMs"] - done_at_start)
                    left = f" · about {max(1, round(rate * (total - tr['doneMs']) / 60))} min left"
                progress(g / total, f"Transcribing {clock(g)} of {clock(total)}{left}")
                pcm = pcm16_to_float(tab.pcm(doc["id"], f["index"], off, length))
                segs = speech.transcribe(pcm, g, stop)
                if stop.is_set():
                    log("Stopped. What is done is saved on the tablet; press Start to continue.")
                    return False
                tr["segments"].extend(segs)
                tr["doneMs"] = g + length
                tab.save_transcript(doc["id"], tr)
    progress(1, "Reading the book's text on the tablet…")
    text = tab.text(doc["id"])
    progress(1, "Matching the sentences with the audio…")
    m = align(text, tr["segments"])
    m["engine"] = tr.get("engine") or "faster-whisper on a computer"
    m["language"] = MODELS.get(key, {}).get("lang") or "auto"
    m["durationMs"] = total
    tab.save_map(doc["id"], m)
    progress(1, f"Synced: {round(m['coverage'] * 100)}% of the sentences have a place in the audio. The map is in the book's folder on the tablet.")
    return True


def job_files(paths, key, out_path, stop, log, progress):
    import av
    durations = []
    for p in paths:
        with av.open(p) as c:
            durations.append(int((c.duration or 0) / 1000))  # µs → ms
    total = sum(durations) or 1
    speech = Speech(key, log)
    tr = {"modelKey": key, "segments": [], "doneMs": 0, "engine": speech.engine, "language": MODELS[key]["lang"] or "auto",
          "files": [os.path.basename(p) for p in paths], "durationMs": total}
    started = time.time()
    g0 = 0
    for p, dur in zip(paths, durations):
        off = 0
        for win in file_windows(p, stop):
            if stop.is_set():
                break
            g = g0 + off
            left = ""
            if tr["doneMs"] > 0:
                rate = (time.time() - started) / tr["doneMs"]
                left = f" · about {max(1, round(rate * (total - tr['doneMs']) / 60))} min left"
            progress(min(g / total, 1), f"Transcribing {clock(g)} of {clock(total)} ({os.path.basename(p)}){left}")
            tr["segments"].extend(speech.transcribe(win, g, stop))
            off += int(len(win) * 1000 / 16000)
            tr["doneMs"] = g0 + off
            with open(out_path, "w", encoding="utf-8") as f:  # saved as it goes
                json.dump(tr, f, ensure_ascii=False)
        if stop.is_set():
            log(f"Stopped. The part done is saved in {out_path}.")
            return False
        g0 += max(dur, off)
    tr["doneMs"] = max(tr["doneMs"], total)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(tr, f, ensure_ascii=False)
    progress(1, f"Done. Import {os.path.basename(out_path)} in the web view: the book → Audiobook → Sync with the text… → Import transcript…")
    return True


# ------------------------------------------------------------------ self-test (used by the build, no window)

def _tray_check():
    import tkinter  # noqa: F401
    from PIL import Image, ImageDraw  # noqa: F401
    import tray_icon
    tray_icon.image(0.5)
    try:
        import pystray  # noqa: F401  (needs a screen on Linux: a missing one is fine here)
        return "ok"
    except Exception as e:
        return f"ok (tray backend not loaded here: {e.__class__.__name__})"


def self_test():
    """Checks that the packaged app has everything: the matcher, the audio decoder and the speech engine."""
    def step(t):
        print(t, flush=True)
    import numpy as np
    step("1/4 matcher")
    text = {"kind": "epub", "units": [{"c": 0, "p": 0, "h": "", "text": "The court said little. Review was rare in Norway."}]}
    segs = [{"s": 0, "e": 1500, "t": "The court said little"}, {"s": 1600, "e": 3600, "t": "Review was rare in Norway"}]
    m = align(text, segs)
    assert len(m["sentences"]) == 2 and m["sentences"][1]["t"] >= 1500, m
    step("2/4 audio decoder")
    import av  # noqa: F401
    step("3/4 speech engine: loading the tiny model")
    from faster_whisper import WhisperModel
    w = WhisperModel("tiny", device="cpu", compute_type="int8", cpu_threads=2)
    step("4/4 speech engine: transcribing 2 s of silence")
    segs, _ = w.transcribe(np.zeros(16000 * 2, dtype="float32"))
    list(segs)
    step("5/6 window and tray: " + _tray_check())
    g = gpu_works()
    step("6/6 graphics: " + (f"{g[0]} works ({g[1]})" if g else "none usable, the processor is used"))
    step("self-test ok")


