"""
The part that lives in the tray (Windows, Linux) or the menu bar (macOS) and does the work:

- keeps a copy of the tablet's library and each book's text, refreshed whenever the tablet is on the Wi-Fi;
- transcribes one audiobook at a time (from the tablet's audiobook, or from files on this computer);
- matches the sentences and sends the map, plus the audio when the tablet doesn't have it yet.

The library window is a separate process (window.py); the two share the files in store.py and a tiny
control channel on 127.0.0.1 (open the window, wake up, quit), which also keeps this to one copy.
"""
import os
import platform
import socket
import subprocess
import sys
import threading
import time
import traceback

import core
import store
import autostart

PORT = 47815
MODEL_MSG = ("Loading the speech model. The first time, it is downloaded (75 MB to 1.6 GB, depending on the model), "
             "which can take a few minutes; then it is kept on this computer.")
TEXT_KINDS = ("EPUB", "PDF")


def tell(cmd, timeout=1.0):
    """Sends a command to the running tray app; False when none is running."""
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=timeout) as s:
            s.sendall((cmd + "\n").encode())
            return True
    except OSError:
        return False


def _remap(m, local_starts, tablet_starts):
    """Moves sentence times from this computer's file timeline onto the tablet's (its own file lengths)."""
    if not local_starts or len(local_starts) != len(tablet_starts):
        return m
    for s in m.get("sentences", []):
        t = s.get("t", 0)
        i = 0
        while i + 1 < len(local_starts) and t >= local_starts[i + 1]:
            i += 1
        s["t"] = int(tablet_starts[i] + (t - local_starts[i]))
    return m


class Daemon:
    def __init__(self):
        self.quit = threading.Event()
        self.wake_net = threading.Event()
        self.wake_jobs = threading.Event()
        self.stop_job = threading.Event()
        self.paused = bool(store.read("paused.json", {}).get("paused"))
        prev = store.read("status.json", {}) or {}
        self.reachable = None
        self.known = True
        self.last_sync = prev.get("lastSync", 0)
        self.current = None  # (job id, title, frac, msg)
        self.icon = None
        self.window = None
        self.speech = None
        self._icon_key = None
        self._texts_busy = False
        self.gpu = None  # {"busy", "msg", "error"} while/after downloading NVIDIA's support

    # ------------------------------------------------------------------ status, shared with the window and the tray

    def tablet(self, tries=1):
        s = store.settings()
        if not s.get("key"):
            return None
        t = core.Tablet(s.get("address", core.DEFAULT_ADDRESS), s["key"])
        t.tries = tries
        return t

    def publish(self):
        s = store.settings()
        js = store.jobs()
        store.write("status.json", {
            "paired": bool(s.get("key")), "known": self.known, "reachable": self.reachable, "tabletName": s.get("tabletName", ""),
            "lastSync": self.last_sync, "paused": self.paused, "pid": os.getpid(),
            "job": None if not self.current else {"id": self.current[0], "title": self.current[1], "frac": self.current[2], "msg": self.current[3]},
            "waiting": [j["title"] for j in js if j.get("state") in ("send", "waiting", "conflict")],
            "gpu": self.gpu,
            "at": time.time(),
        })
        self.refresh_icon()

    def tablet_line(self):
        s = store.settings()
        if not s.get("key"):
            return "Not paired with a tablet yet"
        if not self.known:
            return "The tablet no longer knows this computer"
        if self.reachable:
            return f"Tablet on this Wi-Fi, synced {time.strftime('%H:%M', time.localtime(self.last_sync))}" if self.last_sync else "Tablet on this Wi-Fi"
        if self.reachable is False:
            return "Tablet out of reach · work waits here"
        return "Looking for the tablet…"

    def job_line(self):
        if self.gpu and self.gpu.get("busy"):
            return self.gpu.get("msg", "Downloading graphics-card support…")
        if not self.current:
            return ""
        return f"{self.current[1]}: {int(self.current[2] * 100)}%"

    def refresh_icon(self):
        if not self.icon:
            return
        import tray_icon
        frac = None if not self.current else round(self.current[2], 2)
        key = (frac, self.reachable is False)
        try:
            if key != self._icon_key:
                self._icon_key = key
                self.icon.icon = tray_icon.image(frac, away=self.reachable is False)
            self.icon.title = "Marginalia Companion — " + (self.job_line() or self.tablet_line())
            self.icon.update_menu()
        except Exception:
            pass

    def set_job(self, j, state=None, frac=None, msg=None):
        if state is not None:
            j["state"] = state
        if frac is not None:
            j["frac"] = frac
        if msg is not None:
            j["msg"] = msg
        store.save_job(j)
        if j.get("state") in ("transcribing", "matching", "sending"):
            self.current = (j["id"], j["title"], j.get("frac", 0.0), j.get("msg", ""))
        elif self.current and self.current[0] == j["id"]:
            self.current = None
        self.publish()

    # ------------------------------------------------------------------ the tablet: library, texts, sending

    def net_loop(self):
        while not self.quit.is_set():
            try:
                self.check_tablet()
            except Exception:
                traceback.print_exc()
            self.wake_net.wait(60)
            self.wake_net.clear()

    def check_tablet(self):
        t = self.tablet()
        if not t:
            self.reachable = None
            self.publish()
            return
        try:
            ses = t.session()
        except (core.Unreachable, core.TabletError):
            self.reachable = False
            self.publish()
            return
        self.reachable = True
        self.known = bool(ses.get("signedIn"))
        if not self.known:
            self.publish()
            return
        s = store.settings()
        if ses.get("device") and s.get("tabletName") != ses.get("device"):
            s["tabletName"] = ses.get("device")
            store.save_settings(s)
        try:
            self.refresh_library(t)
            for j in store.jobs():
                if j.get("state") == "send":
                    self.send(t, j)
            self.last_sync = time.time()
        except core.Unreachable:
            self.reachable = False
        self.publish()
        self.wake_jobs.set()  # a job waiting for the tablet can go on
        if self.reachable and not self._texts_busy:
            threading.Thread(target=self.cache_texts, daemon=True).start()

    def refresh_library(self, t):
        docs = []
        for d in t.library():
            if d.get("folder") or d.get("placeholder") or not d.get("uuid"):
                continue
            keep = {k: d.get(k) for k in ("id", "uuid", "title", "displayAuthor", "author", "fileType", "audiobook", "updatedAt", "category", "progress")}
            if d.get("audiobook"):
                try:
                    info = t.sync_info(d["id"])
                    keep["map"] = {"exists": bool(info.get("exists")), "coverage": info.get("coverage", 0)}
                except core.TabletError:
                    pass
            docs.append(keep)
        store.write("library.json", {"at": time.time(), "tablet": store.settings().get("tabletName", ""), "docs": docs})

    def cache_texts(self):
        """Each book's text, so audio added away from the tablet can still be matched. Books with jobs first."""
        self._texts_busy = True
        try:
            lib = (store.read("library.json", {}) or {}).get("docs", [])
            wanted = {j.get("uuid") for j in store.jobs() if j.get("state") not in ("done",)}
            order = sorted(lib, key=lambda d: (d["uuid"] not in wanted, not d.get("audiobook"), d.get("fileType") != "EPUB"))
            for d in order:
                if self.quit.is_set() or not self.reachable:
                    return
                if (d.get("fileType") or "").upper() not in TEXT_KINDS:
                    continue
                rel = os.path.join("texts", d["uuid"] + ".json")
                have = store.read(rel)
                if have and have.get("updatedAt", 0) >= (d.get("updatedAt") or 0):
                    continue
                t = self.tablet()
                try:
                    text = t.text(d["id"])
                except core.Unreachable:
                    return
                except Exception:
                    continue
                store.write(rel, {"updatedAt": d.get("updatedAt") or 0, "text": text})
                if d["uuid"] in wanted:
                    self.wake_jobs.set()
        finally:
            self._texts_busy = False

    def send(self, t, j):
        lib = {d["uuid"]: d for d in (store.read("library.json", {}) or {}).get("docs", [])}
        doc = lib.get(j.get("uuid"))
        if not doc:
            return self.set_job(j, "error", msg="The book is no longer on the tablet.")
        m = store.read(os.path.join("maps", j["id"] + ".json"))
        if not m:
            return self.set_job(j, "queued", msg="The map is missing; making it again.")
        if j.get("source") == "files":
            missing = [p for p in j["files"] if not os.path.isfile(p)]
            if missing:
                return self.set_job(j, "error", msg=f"The audio file {os.path.basename(missing[0])} has been moved or deleted on this computer.")
            sizes = [os.path.getsize(p) for p in j["files"]]
            ab = t.audiobook(doc["id"])
            if ab.get("exists"):
                remote = [f.get("size", -1) for f in ab.get("files", [])]
                if sorted(remote) == sorted(sizes):
                    pass  # the tablet already has exactly these files: only the map goes
                elif not j.get("replace"):
                    return self.set_job(j, "conflict", msg="The tablet already has other audio for this book. Replace it with these files, or remove this job.")
                else:
                    t.delete_audiobook(doc["id"])
                    ab = {"exists": False}
            if not ab.get("exists"):
                total = sum(sizes) or 1
                sent = 0
                for i, p in enumerate(j["files"]):
                    def prog(done, size, i=i, base=sent):
                        self.set_job(j, "sending", (base + done) / total,
                                     f"Sending the audio, file {i + 1} of {len(j['files'])}: {(base + done) // 1_000_000} of {total // 1_000_000} MB") if done % (8 << 20) < (1 << 20) else None
                    t.upload_audio(doc["id"], p, prog)
                    sent += sizes[i]
                ab = t.audiobook(doc["id"])
            # The tablet's file starts, in the order of the files here (matched by size).
            starts, used = [], set()
            for sz in sizes:
                k = next((k for k, f in enumerate(ab.get("files", [])) if f.get("size") == sz and k not in used), None)
                if k is None:
                    starts = []
                    break
                used.add(k)
                starts.append(ab["files"][k].get("startMs", 0))
            m = _remap(m, j.get("localStarts"), starts)
            m["durationMs"] = ab.get("durationMs") or m.get("durationMs")
        t.save_map(doc["id"], m)
        self.set_job(j, "done", 1.0, f"Synced: {round(m.get('coverage', 0) * 100)}% of the sentences have a place in the audio.")
        try:
            self.refresh_library(t)
        except Exception:
            pass

    # ------------------------------------------------------------------ jobs

    def job_loop(self):
        while not self.quit.is_set():
            if self.paused:
                self.wake_jobs.wait(5)
                self.wake_jobs.clear()
                continue
            j = self.next_job()
            if not j:
                self.wake_jobs.wait(10)
                self.wake_jobs.clear()
                continue
            self.stop_job.clear()
            try:
                self.run_job(j)
            except core.Unreachable:
                self.reachable = False
                self.set_job(j, "waiting", msg="Continues when the tablet is back on this Wi-Fi. What is done is kept.")
            except Exception as e:
                traceback.print_exc()
                self.set_job(j, "error", msg=(str(e) or e.__class__.__name__) + " (Details: Settings → Open the log.)")
            if self.stop_job.is_set() and j.get("state") in ("transcribing", "matching"):
                self.set_job(j, "queued", msg="Paused. What is done is kept.")
            self.current = None
            self.publish()

    def next_job(self):
        for j in store.jobs():
            st = j.get("state")
            if st in ("queued", "transcribing", "matching"):
                if j.get("source") == "tablet" and not self.reachable:
                    if st != "waiting":
                        self.set_job(j, "waiting", msg="Starts when the tablet is on this Wi-Fi.")
                    continue
                return j
            if st == "waiting" and self.reachable:
                return j
            if st == "waiting" and j.get("source") == "files" and j.get("needs") == "text" and store.read(os.path.join("texts", j["uuid"] + ".json")):
                return j
        return None

    def speech_for(self, model, j=None):
        if not self.speech or self.speech.key != model:
            self.speech = core.Speech(model, self.job_log(j))
        return self.speech

    def job_log(self, j):
        """Lines from the speech engine (model download progress) shown on the job, at most every 2 s."""
        last = [0.0]

        def log(msg):
            if j is not None and time.time() - last[0] > 2:
                last[0] = time.time()
                self.set_job(j, "transcribing", msg=msg)
        return log

    def run_job(self, j):
        model = j.get("model") or "pc_en_exact"
        if j.get("source") == "tablet":
            t = self.tablet(3)
            lib = {d["uuid"]: d for d in (store.read("library.json", {}) or {}).get("docs", [])}
            doc = lib.get(j["uuid"]) or {"id": j["docId"]}
            self.set_job(j, "transcribing", msg=MODEL_MSG)

            def prog(frac, msg):
                self.set_job(j, "transcribing" if frac < 1 else "matching", frac, msg)
            ok = core.job_tablet(t, doc, model, False, self.stop_job, self.job_log(j), prog)
            if ok:
                self.set_job(j, "done", 1.0, j.get("msg") or "Synced.")
                try:
                    self.refresh_library(t)
                except Exception:
                    pass
            return
        self.run_files_job(j, model)

    def run_files_job(self, j, model):
        import av
        trp = os.path.join("transcripts", j["id"] + ".json")
        durations = []
        for p in j["files"]:
            if not os.path.isfile(p):
                return self.set_job(j, "error", msg=f"The audio file {os.path.basename(p)} has been moved or deleted.")
            with av.open(p) as c:
                durations.append(int((c.duration or 0) / 1000))
        starts = [sum(durations[:i]) for i in range(len(durations))]
        total = sum(durations) or 1
        tr = store.read(trp) or {"modelKey": model, "segments": [], "doneMs": 0}
        if tr.get("doneMs", 0) < total - 1000:
            self.set_job(j, "transcribing", tr.get("doneMs", 0) / total, MODEL_MSG)
            speech = self.speech_for(model, j)
            tr["engine"], tr["language"] = speech.engine, core.MODELS[model]["lang"] or "auto"
            began, at_start = time.time(), tr.get("doneMs", 0)
            for p, g0 in zip(j["files"], starts):
                off = 0
                for win in core.file_windows(p, self.stop_job):
                    if self.stop_job.is_set() or self.quit.is_set():
                        return
                    g = g0 + off
                    n = int(len(win) * 1000 / 16000)
                    off += n
                    if g + n <= tr.get("doneMs", 0) + 500:
                        continue  # done before a pause
                    left = ""
                    if tr["doneMs"] > at_start:
                        rate = (time.time() - began) / (tr["doneMs"] - at_start)
                        left = f" · about {max(1, round(rate * (total - tr['doneMs']) / 60))} min left"
                    self.set_job(j, "transcribing", g / total, f"{core.clock(g)} of {core.clock(total)}{left} · {self.where(speech)}")
                    tr["segments"].extend(speech.transcribe(win, g, self.stop_job))
                    if self.stop_job.is_set():
                        return
                    tr["doneMs"] = g + n
                    store.write(trp, tr)
            tr["doneMs"] = max(tr["doneMs"], total)
            store.write(trp, tr)
        self.set_job(j, "matching", 1.0, "Matching the sentences…")
        cached = store.read(os.path.join("texts", j["uuid"] + ".json"))
        text = cached.get("text") if cached else None
        if not text and self.reachable:
            lib = {d["uuid"]: d for d in (store.read("library.json", {}) or {}).get("docs", [])}
            d = lib.get(j["uuid"])
            if d:
                text = self.tablet(3).text(d["id"])
                store.write(os.path.join("texts", j["uuid"] + ".json"), {"updatedAt": d.get("updatedAt") or 0, "text": text})
        if not text:
            j["needs"] = "text"
            return self.set_job(j, "waiting", msg="Transcribed. The book's text comes from the tablet the next time it is on this Wi-Fi; then it is matched and sent.")
        j.pop("needs", None)
        from align import align
        m = align(text, tr["segments"])
        m["engine"] = tr.get("engine") or "faster-whisper on a computer"
        m["language"] = core.MODELS.get(model, {}).get("lang") or "auto"
        m["durationMs"] = total
        store.write(os.path.join("maps", j["id"] + ".json"), m)
        j["localStarts"] = starts
        self.set_job(j, "send", 1.0, f"Matched: {round(m['coverage'] * 100)}% of the sentences. Sent the next time the tablet is on this Wi-Fi.")
        self.wake_net.set()

    @staticmethod
    def where(speech):
        return {"cuda": "on the graphics card", "mps": "on the Mac's graphics"}.get(speech.device, "on the processor")

    # ------------------------------------------------------------------ the window, the tray, commands

    def open_window(self, *_):
        if self.window and self.window.poll() is None:
            store.write("raise.json", {"at": time.time()})
            return
        self.window = subprocess.Popen(core._self_command("--window"), close_fds=True)

    def download_gpu(self):
        """NVIDIA's support, downloaded here in the tray app so it goes on when the window is closed."""
        if self.gpu and self.gpu.get("busy"):
            return
        import nvidia
        self.gpu = {"busy": True, "msg": "Downloading graphics-card support…"}
        self.publish()

        def work():
            last = [0.0]

            def prog(msg):
                self.gpu = {"busy": True, "msg": msg}
                if time.time() - last[0] > 1:
                    last[0] = time.time()
                    self.publish()
            try:
                nvidia.download(prog)
                self.gpu = {"busy": False, "done": True, "msg": "Graphics-card support is installed. The next transcription runs on the card."}
                self.speech = None  # load the speech model again, now on the card
                core._GPU.clear()
            except Exception as e:
                traceback.print_exc()
                self.gpu = {"busy": False, "error": True, "msg": f"The download of graphics-card support did not finish ({e}). Try again."}
            self.publish()
        threading.Thread(target=work, daemon=True).start()

    def toggle_pause(self, *_):
        self.paused = not self.paused
        store.write("paused.json", {"paused": self.paused})
        if self.paused:
            self.stop_job.set()
        self.wake_jobs.set()
        self.publish()

    def toggle_autostart(self, *_):
        try:
            autostart.set_enabled(not autostart.enabled())
        except Exception:
            traceback.print_exc()
        self.refresh_icon()

    def quit_app(self, *_):
        self.quit.set()
        self.stop_job.set()
        self.wake_net.set()
        self.wake_jobs.set()
        if self.window and self.window.poll() is None:
            try:
                self.window.terminate()
            except Exception:
                pass
        if self.icon:
            try:
                self.icon.stop()
            except Exception:
                pass
        threading.Timer(3.0, lambda: os._exit(0)).start()

    def serve(self, srv):
        while not self.quit.is_set():
            try:
                c, _ = srv.accept()
            except OSError:
                return
            with c:
                try:
                    cmd = c.recv(64).decode().strip()
                except OSError:
                    continue
                if cmd == "open":
                    self.open_window()
                elif cmd == "wake":
                    self.wake_net.set()
                    self.wake_jobs.set()
                elif cmd == "gpu-download":
                    self.download_gpu()
                elif cmd == "stop-job":
                    self.stop_job.set()
                elif cmd == "pause":
                    if not self.paused:
                        self.toggle_pause()
                elif cmd == "resume":
                    if self.paused:
                        self.toggle_pause()
                elif cmd == "quit":
                    self.quit_app()

    def run_tray(self):
        import pystray
        import tray_icon
        start_label = {"nt": "Start with Windows"}.get(os.name, "Open at login" if sys.platform == "darwin" else "Start with the computer")
        menu = pystray.Menu(
            pystray.MenuItem("Marginalia Companion", None, enabled=False),
            pystray.MenuItem(lambda i: self.tablet_line(), None, enabled=False),
            pystray.MenuItem(lambda i: self.job_line(), None, enabled=False, visible=lambda i: bool(self.job_line())),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Open the library", self.open_window, default=True),
            pystray.MenuItem(lambda i: "Resume transcribing" if self.paused else "Pause transcribing", self.toggle_pause),
            pystray.MenuItem(start_label, self.toggle_autostart, checked=lambda i: autostart.enabled()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit", self.quit_app),
        )
        self.icon = pystray.Icon("MarginaliaCompanion", tray_icon.image(None), "Marginalia Companion", menu)
        self.icon.run(setup=lambda icon: (setattr(icon, "visible", True), self.publish()))


def run(open_window=True):
    if tell("open" if open_window else "wake"):
        return  # already running: it opens the window
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        srv.bind(("127.0.0.1", PORT))
    except OSError:
        time.sleep(1)
        tell("open" if open_window else "wake")
        return
    srv.listen(8)
    d = Daemon()
    s = store.settings()
    if "autostart" not in s and getattr(sys, "frozen", False):
        try:
            autostart.set_enabled(True)  # starts with the computer unless turned off in the menu
        except Exception:
            traceback.print_exc()
        s["autostart"] = True
        store.save_settings(s)
    autostart.refresh()
    autostart.linux_menu_entry()
    for target in (d.net_loop, d.job_loop, lambda: d.serve(srv)):
        threading.Thread(target=target, daemon=True).start()
    d.publish()
    if open_window:
        d.open_window()
    try:
        d.run_tray()
    except Exception:
        # No tray here (e.g. a Linux desktop without one): keep the window open and work while it is.
        traceback.print_exc()
        if not d.window:
            d.open_window()
        while not d.quit.is_set():
            if d.window and d.window.poll() is not None and not d.current:
                break
            time.sleep(2)
    os._exit(0)
