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


def _nvidia_libraries():
    """Windows + NVIDIA build: make NVIDIA's cuBLAS and cuDNN (shipped with the app, or pip-installed) findable."""
    if os.name != "nt":
        return
    dirs = []
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
    if getattr(sys, "frozen", False):
        return [sys.executable, arg]
    return [sys.executable, os.path.abspath(__file__), arg]


def _apple_silicon():
    import platform
    return platform.system() == "Darwin" and platform.machine() == "arm64"


def gpu_works(log=lambda m: None):
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
        r = subprocess.run(_self_command("--gpu-probe") + [device], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=900,
                           creationflags=0x08000000 if os.name == "nt" else 0)  # no console window on Windows
    except Exception:
        return None
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
            continue
    os._exit(3)

APP = "Marginalia Companion"
VERSION = "1.0"
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

class Tablet:
    """Talks to the tablet's web view server, signed in with its PIN like a browser."""

    def __init__(self, address):
        address = address.strip().rstrip("/")
        if not address.startswith("http"):
            address = "http://" + address
        self.base = address
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _req(self, method, path, body=None, timeout=120, raw=False):
        data = None
        headers = {"X-Marginalia": "1", "Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + "/api/" + path, data=data, method=method, headers=headers)
        for attempt in range(3):
            try:
                with self.opener.open(req, timeout=timeout) as r:
                    payload = r.read()
                    return payload if raw else json.loads(payload.decode("utf-8") or "{}")
            except urllib.error.HTTPError as e:
                try:
                    msg = json.loads(e.read().decode("utf-8")).get("error") or str(e)
                except Exception:
                    msg = str(e)
                if e.code >= 500 and attempt < 2:
                    time.sleep(2)
                    continue
                raise RuntimeError(msg) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt < 2:
                    time.sleep(3)
                    continue
                raise RuntimeError(f"The tablet does not answer at {self.base} ({getattr(e, 'reason', e)}). "
                                   "Is the web view on in the app, and is this computer on the same Wi-Fi?") from None

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

class Speech:
    """faster-whisper, on the graphics card when there is a CUDA one, else on every processor core."""

    def __init__(self, key, log):
        from faster_whisper import WhisperModel  # imported here so the window opens at once
        spec = MODELS[key]
        self.spec = spec
        self.key = key
        self.device = "cpu"
        log(f"Loading the speech model {spec['model']} (downloaded once, then kept on this computer)…")
        try:
            g = gpu_works(log)
            if not g:
                raise RuntimeError("no usable graphics device")
            self.model = WhisperModel(spec["model"], device=g[0], compute_type=g[1])
            self.device = g[0]
        except Exception:
            self.model = WhisperModel(spec["model"], device="cpu", compute_type="int8", cpu_threads=os.cpu_count() or 4)
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


# ------------------------------------------------------------------ the window

def run_gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    st = load_settings()
    root = tk.Tk()
    root.title(APP)
    root.geometry("760x640")
    root.minsize(640, 520)
    try:
        ttk.Style().theme_use("clam")
    except Exception:
        pass

    events = queue.Queue()
    stop = threading.Event()
    state = {"tab": None, "docs": [], "running": False, "files": []}

    outer = ttk.Frame(root, padding=14)
    outer.pack(fill="both", expand=True)
    ttk.Label(outer, text=APP, font=("TkDefaultFont", 16, "bold")).pack(anchor="w")
    ttk.Label(outer, text="Syncs an audiobook with its book's text here, on this computer, instead of in the browser.",
              wraplength=700).pack(anchor="w", pady=(2, 10))

    nb = ttk.Notebook(outer)
    nb.pack(fill="both", expand=True)

    # --- From the tablet
    t1 = ttk.Frame(nb, padding=10)
    nb.add(t1, text="From the tablet (Wi-Fi)")
    row = ttk.Frame(t1); row.pack(fill="x")
    ttk.Label(row, text="Address").pack(side="left")
    addr = ttk.Entry(row, width=34); addr.insert(0, st.get("address", DEFAULT_ADDRESS)); addr.pack(side="left", padx=6)
    ttk.Label(row, text="PIN").pack(side="left")
    pin = ttk.Entry(row, width=10, show="•"); pin.pack(side="left", padx=6)
    connect = ttk.Button(row, text="Connect"); connect.pack(side="left")
    ttk.Label(t1, text="The address and PIN are on the tablet: Settings → Sync & web view → Library in the browser. Use the same address you open in the browser.",
              foreground="#3f3f46").pack(anchor="w", pady=(4, 8))
    lst = tk.Listbox(t1, height=12, activestyle="none", exportselection=False)
    lst.pack(fill="both", expand=True)
    info = ttk.Label(t1, text="", foreground="#3f3f46", wraplength=700)
    info.pack(anchor="w", pady=(6, 0))
    restart_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(t1, text="Start the transcription over (otherwise it continues where it stopped)",
                    variable=restart_var).pack(anchor="w", pady=(4, 0))

    # --- From files
    t2 = ttk.Frame(nb, padding=10)
    nb.add(t2, text="From files on this computer")
    ttk.Label(t2, text="The audiobook's files, in reading order (as on the tablet):").pack(anchor="w")
    flist = tk.Listbox(t2, height=10, activestyle="none", exportselection=False)
    flist.pack(fill="both", expand=True, pady=4)
    fbtn = ttk.Frame(t2); fbtn.pack(fill="x")

    def refresh_files():
        flist.delete(0, "end")
        for p in state["files"]:
            flist.insert("end", os.path.basename(p))

    def add_files():
        ps = filedialog.askopenfilenames(title="Audiobook files", filetypes=[
            ("Audio", "*.mp3 *.m4b *.m4a *.aac *.ogg *.opus *.oga *.flac *.wav"), ("All files", "*.*")])
        state["files"] += sorted(ps, key=lambda s: os.path.basename(s).lower())
        refresh_files()

    def move(d):
        sel = flist.curselection()
        if not sel:
            return
        i = sel[0]; j = i + d
        if 0 <= j < len(state["files"]):
            state["files"][i], state["files"][j] = state["files"][j], state["files"][i]
            refresh_files(); flist.selection_set(j)

    def remove():
        for i in reversed(flist.curselection()):
            del state["files"][i]
        refresh_files()

    ttk.Button(fbtn, text="Add files…", command=add_files).pack(side="left")
    ttk.Button(fbtn, text="Up", command=lambda: move(-1)).pack(side="left", padx=4)
    ttk.Button(fbtn, text="Down", command=lambda: move(1)).pack(side="left")
    ttk.Button(fbtn, text="Remove", command=remove).pack(side="left", padx=4)
    ttk.Label(t2, text="You get a transcript file. Import it in the web view: the book → Audiobook → Sync with the "
                       "text… → Import transcript…; the sentences are then matched in seconds.",
              foreground="#3f3f46", wraplength=700).pack(anchor="w", pady=(8, 0))

    # --- Common: model, start, progress
    bottom = ttk.Frame(outer, padding=(0, 10, 0, 0)); bottom.pack(fill="x")
    mrow = ttk.Frame(bottom); mrow.pack(fill="x")
    ttk.Label(mrow, text="Speech model").pack(side="left")
    labels = [m["label"] for m in MODELS.values()]
    keys = list(MODELS.keys())
    model = ttk.Combobox(mrow, values=labels, state="readonly", width=58)
    model.current(keys.index(st.get("model", "pc_en_exact")) if st.get("model") in keys else 1)
    model.pack(side="left", padx=6)
    pbar = ttk.Progressbar(bottom, maximum=1000); pbar.pack(fill="x", pady=(10, 4))
    status = ttk.Label(bottom, text="Ready.", wraplength=720); status.pack(anchor="w")
    brow = ttk.Frame(bottom); brow.pack(fill="x", pady=(8, 0))
    start = ttk.Button(brow, text="Start"); start.pack(side="right")
    stopb = ttk.Button(brow, text="Stop (keeps what is done)", state="disabled"); stopb.pack(side="right", padx=6)

    def log(msg):
        events.put(("status", msg))

    def progress(frac, msg):
        events.put(("progress", frac, msg))

    def pump():
        try:
            while True:
                ev = events.get_nowait()
                if ev[0] == "status":
                    status.config(text=ev[1])
                elif ev[0] == "progress":
                    pbar["value"] = int(ev[1] * 1000); status.config(text=ev[2])
                elif ev[0] == "docs":
                    lst.delete(0, "end")
                    for d in state["docs"]:
                        lst.insert("end", f"{d.get('title') or '(untitled)'}  ·  {d.get('fileType', '')}")
                    status.config(text=f"Connected to {ev[1]}. Pick a book with an audiobook.")
                elif ev[0] == "info":
                    info.config(text=ev[1])
                elif ev[0] == "done":
                    state["running"] = False
                    start.config(state="normal"); stopb.config(state="disabled")
                    if ev[1]:
                        messagebox.showerror(APP, ev[1])
        except queue.Empty:
            pass
        root.after(150, pump)

    def do_connect():
        a, p = addr.get().strip(), pin.get().strip()
        st["address"] = a; save_settings(st)

        def work():
            try:
                tab = Tablet(a)
                tab.login(p)
                docs = [d for d in tab.library() if str(d.get("fileType", "")).upper() in ("EPUB", "PDF")]
                docs.sort(key=lambda d: (d.get("title") or "").lower())
                state["tab"], state["docs"] = tab, docs
                events.put(("docs", a))
            except Exception as e:
                events.put(("status", f"Could not connect: {e}"))
        log("Connecting…")
        threading.Thread(target=work, daemon=True).start()

    def on_pick(_e=None):
        sel = lst.curselection()
        if not sel or not state["tab"]:
            return
        d = state["docs"][sel[0]]

        def work():
            try:
                ab = state["tab"].audiobook(d["id"])
                if not ab.get("exists"):
                    events.put(("info", "No audiobook for this book on the tablet.")); return
                tr = state["tab"].transcript(d["id"]) or {}
                total = ab.get("durationMs", 0)
                done = tr.get("doneMs", 0)
                extra = f" · transcribed {clock(done)} so far" if done else ""
                events.put(("info", f"Audiobook: {clock(total)} in {len(ab.get('files', []))} file(s){extra}."))
            except Exception as e:
                events.put(("info", f"Could not read the audiobook: {e}"))
        threading.Thread(target=work, daemon=True).start()

    lst.bind("<<ListboxSelect>>", on_pick)
    connect.config(command=do_connect)
    pin.bind("<Return>", lambda _e: do_connect())

    def do_start():
        if state["running"]:
            return
        key = keys[model.current()]
        st["model"] = key; save_settings(st)
        stop.clear()
        if nb.index(nb.select()) == 0:
            sel = lst.curselection()
            if not state["tab"] or not sel:
                messagebox.showinfo(APP, "Connect to the tablet and pick a book first."); return
            doc = state["docs"][sel[0]]
            target = lambda: job_tablet(state["tab"], doc, key, restart_var.get(), stop, log, progress)
        else:
            if not state["files"]:
                messagebox.showinfo(APP, "Add the audiobook's files first."); return
            name = os.path.splitext(os.path.basename(state["files"][0]))[0]
            out = filedialog.asksaveasfilename(title="Save the transcript as", defaultextension=".json",
                                               initialfile=f"Transcript - {name}.json", filetypes=[("Transcript", "*.json")])
            if not out:
                return
            files = list(state["files"])
            target = lambda: job_files(files, key, out, stop, log, progress)

        def work():
            err = None
            try:
                target()
            except Exception as e:
                traceback.print_exc()
                err = str(e) or e.__class__.__name__
                events.put(("status", "Stopped: " + err))
            events.put(("done", err))
        state["running"] = True
        start.config(state="disabled"); stopb.config(state="normal")
        threading.Thread(target=work, daemon=True).start()

    start.config(command=do_start)
    stopb.config(command=lambda: (stop.set(), log("Stopping after this part…")))
    root.protocol("WM_DELETE_WINDOW", lambda: (stop.set(), root.after(300, root.destroy)))
    pump()
    root.mainloop()


# ------------------------------------------------------------------ self-test (used by the build, no window)

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
    g = gpu_works()
    step("5/5 graphics: " + (f"{g[0]} works ({g[1]})" if g else "none usable, the processor is used"))
    step("self-test ok")


if __name__ == "__main__":
    if "--gpu-probe" in sys.argv:
        gpu_probe(sys.argv[sys.argv.index("--gpu-probe") + 1] if len(sys.argv) > sys.argv.index("--gpu-probe") + 1 else "cuda")
    if "--self-test" in sys.argv:
        self_test()
        # The speech engine leaves background threads (on macOS) that keep the process alive: end it here.
        sys.stdout.flush()
        os._exit(0)
    elif "--version" in sys.argv:
        print(APP, VERSION)
    else:
        run_gui()
