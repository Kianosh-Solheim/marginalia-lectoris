"""
The library window: every book on the tablet (as last seen, so also away from it), its audiobook and sync map,
and what the tray app is doing. Add an audiobook from this computer, or transcribe the one on the tablet;
the tray app does the work and sends the result when the tablet is on the same Wi-Fi.
"""
import os
import re
import socket
import sys
import threading
import time

import core
import store
import daemon
import autostart

INK, GREY, RULE, NOTICE, GREEN = "#000000", "#3f3f46", "#d4d4d8", "#fef3c7", "#15803d"


def _natural(p):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", os.path.basename(p))]


def run():
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    import tkinter.font as tkfont
    import updater

    if not daemon.tell("wake"):  # the tray app does the work: start it if it isn't running
        import subprocess
        subprocess.Popen(core._self_command("--tray"), close_fds=True)

    info_build = updater.build_info(core.VERSION)
    root = tk.Tk()
    root.title(f"Marginalia Companion {info_build.get('version', core.VERSION)}")
    root.geometry("1120x720")
    root.minsize(860, 560)
    root.configure(bg="white")
    try:
        ttk.Style().theme_use("clam")
    except Exception:
        pass
    families = set(tkfont.families())
    base_font = tkfont.nametofont("TkDefaultFont").actual().get("family", "TkDefaultFont")
    serif = next((f for f in ("Iowan Old Style", "Palatino Linotype", "Palatino", "Georgia", "DejaVu Serif", "Times New Roman") if f in families), "Times")
    st = ttk.Style()
    st.configure(".", background="white")
    st.configure("TFrame", background="white")
    st.configure("TLabel", background="white", foreground=INK)
    st.configure("Muted.TLabel", foreground=GREY)
    st.configure("Notice.TLabel", background=NOTICE)
    st.configure("Notice.TFrame", background=NOTICE)
    st.configure("TButton", background="white", foreground=INK, bordercolor=INK, borderwidth=1.5, relief="solid", padding=(10, 5))
    st.map("TButton", background=[("active", "#f4f4f5")])
    st.configure("Fill.TButton", background=INK, foreground="white")
    st.map("Fill.TButton", background=[("active", "#27272a")])
    st.configure("Treeview", rowheight=34, background="white", fieldbackground="white", bordercolor=RULE)
    st.configure("Treeview.Heading", background="white", foreground=GREY, relief="flat")
    st.map("Treeview", background=[("selected", "#e4e4e7")], foreground=[("selected", INK)])
    st.configure("Ink.Horizontal.TProgressbar", troughcolor="#e4e4e7", background=INK, bordercolor=INK, lightcolor=INK, darkcolor=INK)

    state = {"filter": "all", "selected": None, "lib_m": -1, "rows": {}, "raise_m": store.mtime("raise.json"), "update": None}

    # --- top
    top = ttk.Frame(root, padding=(20, 14, 20, 10))
    top.pack(fill="x")
    ttk.Label(top, text="Library", font=(serif, 24, "bold")).pack(side="left")
    ttk.Button(top, text="Settings", command=lambda: open_settings()).pack(side="right")
    query = tk.StringVar()
    q = ttk.Entry(top, textvariable=query, width=36)
    q.pack(side="right", padx=10)
    ttk.Label(top, text="Find", style="Muted.TLabel").pack(side="right")
    query.trace_add("write", lambda *a: render(force=True))

    # --- notices (one at a time, most important first)
    notice = ttk.Frame(root, style="Notice.TFrame", padding=(20, 8))
    notice_text = ttk.Label(notice, text="", style="Notice.TLabel", wraplength=900, justify="left")
    notice_text.pack(side="left", fill="x", expand=True)
    notice_btn = ttk.Button(notice, text="")
    tk.Frame(root, height=1.5, bg=INK).pack(fill="x")

    body = ttk.Frame(root)
    body.pack(fill="both", expand=True)

    # --- filters
    nav = ttk.Frame(body, padding=(0, 10))
    nav.pack(side="left", fill="y")
    filter_buttons = {}
    for key, label in (("all", "All books"), ("audio", "With an audiobook"), ("need", "Needs a sync map"), ("wait", "Waiting for the tablet")):
        b = tk.Button(nav, text=label, anchor="w", relief="flat", bd=0, padx=18, pady=9, width=22, cursor="hand2",
                      command=lambda k=key: (state.update(filter=k), render(force=True)))
        b.pack(fill="x")
        filter_buttons[key] = b
    tk.Frame(body, width=1, bg=RULE).pack(side="left", fill="y")

    # --- detail pane (right)
    detail = ttk.Frame(body, padding=(20, 16), width=380)
    detail.pack(side="right", fill="y")
    detail.pack_propagate(False)
    tk.Frame(body, width=1, bg=RULE).pack(side="right", fill="y")

    # --- list
    mid = ttk.Frame(body)
    mid.pack(side="left", fill="both", expand=True)
    tree = ttk.Treeview(mid, columns=("audio", "map"), show="tree headings", selectmode="browse")
    tree.heading("#0", text="Book", anchor="w")
    tree.heading("audio", text="Audiobook", anchor="w")
    tree.heading("map", text="Sync map", anchor="w")
    tree.column("#0", width=330, stretch=True)
    tree.column("audio", width=170, stretch=False)
    tree.column("map", width=210, stretch=False)
    sb = ttk.Scrollbar(mid, orient="vertical", command=tree.yview)
    tree.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    tree.pack(fill="both", expand=True)
    tree.tag_configure("faint", foreground="#71717a")
    tree.bind("<<TreeviewSelect>>", lambda e: (state.update(selected=(tree.selection() or [None])[0]), render_detail()))

    # ------------------------------------------------------------------ data

    def library():
        return (store.read("library.json", {}) or {}).get("docs", [])

    def status():
        return store.read("status.json", {}) or {}

    def jobs_by_book():
        out = {}
        for j in store.jobs():
            out[j.get("uuid")] = j  # newest wins (sorted by created)
        return out

    def audio_text(d, j):
        if j and j.get("source") == "files" and j.get("state") != "done":
            return f"Added here, {len(j['files'])} file{'s' if len(j['files']) != 1 else ''}"
        return "On the tablet" if d.get("audiobook") else "None"

    def map_text(d, j):
        if j and j.get("state") not in ("done",):
            label = store.STATES.get(j.get("state"), j.get("state"))
            if j.get("state") in ("transcribing", "sending"):
                return f"{label}, {int(j.get('frac', 0) * 100)}%"
            return label
        m = d.get("map") or {}
        if m.get("exists"):
            return f"Synced, {round((m.get('coverage') or 0) * 100)}% of sentences"
        return "Not made yet" if d.get("audiobook") else "—"

    def visible(d, j):
        f = state["filter"]
        has_audio = d.get("audiobook") or (j and j.get("source") == "files")
        active = j and j.get("state") not in ("done", "error")
        needs = has_audio and not (d.get("map") or {}).get("exists")
        waiting = j and j.get("state") in ("send", "waiting", "conflict")
        ok = (f == "all" or (f == "audio" and has_audio) or (f == "need" and (needs or active)) or (f == "wait" and waiting))
        qq = query.get().strip().lower()
        return ok and (not qq or qq in (d.get("title") or "").lower() or qq in (d.get("displayAuthor") or d.get("author") or "").lower())

    # ------------------------------------------------------------------ rendering

    def render(force=False):
        docs = library()
        js = jobs_by_book()
        for k, b in filter_buttons.items():
            on = state["filter"] == k
            b.configure(bg=INK if on else "white", fg="white" if on else INK, activebackground="#27272a" if on else "#f4f4f5",
                        activeforeground="white" if on else INK, font=(base_font, 10, "bold" if on else "normal"))
        rows = {}
        for d in docs:
            j = js.get(d["uuid"])
            if not visible(d, j):
                continue
            who = d.get("displayAuthor") or d.get("author") or ""
            rows[d["uuid"]] = (f"{d.get('title') or '(untitled)'}" + (f"   ·   {who}" if who else ""), audio_text(d, j), map_text(d, j))
        if force or rows != state["rows"]:
            sel = state["selected"]
            existing = set(tree.get_children())
            for iid in existing - set(rows):
                tree.delete(iid)
            for i, (iid, (title, a, m)) in enumerate(rows.items()):
                if iid in existing:
                    tree.item(iid, text=title, values=(a, m))
                    tree.move(iid, "", i)
                else:
                    tree.insert("", i, iid=iid, text=title, values=(a, m))
            if sel in rows:
                tree.selection_set(sel)
            state["rows"] = rows
        render_notice(docs)
        render_detail()

    def render_notice(docs):
        s = status()
        settings = store.settings()
        msg, btn = None, None
        if not settings.get("key"):
            msg, btn = "Pair this computer with your tablet to see its library: on the tablet, Settings → Library in the browser → Pair a phone; then type the code here.", ("Pair with the tablet", open_settings)
        elif s.get("known") is False:
            msg, btn = "The tablet no longer knows this computer (it was removed there). Pair it again.", ("Pair again", open_settings)
        elif (s.get("gpu") or {}).get("busy") or ((s.get("gpu") or {}).get("msg") and not state.get("gpu_seen")):
            g = s["gpu"]
            msg = g.get("msg", "")
            if g.get("error"):
                btn = ("Try again", download_gpu)
            elif not g.get("busy"):
                btn = ("OK", lambda: (state.update(gpu_seen=True), render(True)))
        elif state["update"]:
            rel = state["update"]
            msg, btn = f"Version {rel['version']} is ready (you have {info_build.get('version', core.VERSION)}). It installs itself and starts again; your work is kept.", ("Update and restart", lambda: do_update(rel))
        elif s.get("reachable") is False:
            when = time.strftime("%H:%M", time.localtime(s.get("lastSync"))) if s.get("lastSync") else "the last time"
            msg = (f"The tablet is out of reach, so this is the library as it was at {when}. You can still add audiobooks and transcribe them. "
                   "The maps, and any audio the tablet doesn't have, are sent the next time it is on the same Wi-Fi.")
        elif state.get("gpu_offer"):
            msg, btn = "This computer has an NVIDIA graphics card. Download its support (about 1 GB, once) to transcribe many times faster.", ("Download", download_gpu)
        if msg:
            notice_text.configure(text=msg)
            if btn:
                notice_btn.configure(text=btn[0], command=btn[1], state="normal")
                notice_btn.pack(side="right", padx=(12, 0))
            else:
                notice_btn.pack_forget()
            if not notice.winfo_ismapped():
                notice.pack(fill="x", after=top)
        elif notice.winfo_ismapped():
            notice.pack_forget()

    def clear(w):
        for c in w.winfo_children():
            c.destroy()

    def render_detail():
        uuid = state["selected"]
        sig = None
        d = next((x for x in library() if x["uuid"] == uuid), None)
        j = jobs_by_book().get(uuid) if uuid else None
        sig = (uuid, repr(d), repr(j and {k: j.get(k) for k in ("state", "frac", "msg", "files")}), status().get("reachable"))
        if sig == state.get("detail_sig"):
            return
        state["detail_sig"] = sig
        clear(detail)
        if not d:
            ttk.Label(detail, text="Pick a book to add its audiobook or make its sync map.", style="Muted.TLabel", wraplength=330, justify="left").pack(anchor="w")
            s = status()
            if s.get("job"):
                ttk.Label(detail, text="Working on", style="Muted.TLabel").pack(anchor="w", pady=(24, 2))
                ttk.Label(detail, text=s["job"]["title"], font=(serif, 13, "bold"), wraplength=330).pack(anchor="w")
                pb = ttk.Progressbar(detail, style="Ink.Horizontal.TProgressbar", maximum=1000, value=int(s["job"]["frac"] * 1000))
                pb.pack(fill="x", pady=6)
                ttk.Label(detail, text=s["job"]["msg"], style="Muted.TLabel", wraplength=330).pack(anchor="w")
            return
        ttk.Label(detail, text=d.get("title") or "(untitled)", font=(serif, 17, "bold"), wraplength=330, justify="left").pack(anchor="w")
        who = d.get("displayAuthor") or d.get("author") or ""
        ttk.Label(detail, text=" · ".join(x for x in (who, d.get("fileType") or "") if x), style="Muted.TLabel", wraplength=330).pack(anchor="w", pady=(2, 12))
        has_text = bool(store.read(os.path.join("texts", d["uuid"] + ".json")))
        rows = [("Audiobook", audio_text(d, j)), ("Sync map", map_text(d, j)),
                ("Text kept here", "Yes" if has_text else "Not yet (fetched from the tablet)")]
        for k, v in rows:
            f = ttk.Frame(detail)
            f.pack(fill="x", pady=2)
            ttk.Label(f, text=k, style="Muted.TLabel", width=15).pack(side="left")
            ttk.Label(f, text=v, wraplength=220, justify="left").pack(side="left", fill="x")
        if j and j.get("state") not in ("done",):
            tk.Frame(detail, height=1, bg=RULE).pack(fill="x", pady=12)
            ttk.Label(detail, text=store.STATES.get(j["state"], j["state"]), font=(base_font, 10, "bold")).pack(anchor="w")
            if j.get("state") in ("transcribing", "matching", "sending"):
                ttk.Progressbar(detail, style="Ink.Horizontal.TProgressbar", maximum=1000, value=int(j.get("frac", 0) * 1000)).pack(fill="x", pady=6)
            if j.get("msg"):
                ttk.Label(detail, text=j["msg"], style="Muted.TLabel", wraplength=330, justify="left").pack(anchor="w")
            if j.get("source") == "files":
                ttk.Label(detail, text="\n".join(f"{i + 1}. {os.path.basename(p)}" for i, p in enumerate(j["files"][:8]))
                          + ("\n…" if len(j["files"]) > 8 else ""), style="Muted.TLabel", wraplength=330, justify="left").pack(anchor="w", pady=(8, 0))
            bar = ttk.Frame(detail)
            bar.pack(fill="x", pady=(12, 0))
            if j.get("state") == "conflict":
                ttk.Button(bar, text="Replace the tablet's audio", style="Fill.TButton", command=lambda: (j.update(replace=True, state="send"), store.save_job(j), daemon.tell("wake"), render(True))).pack(anchor="w", pady=2)
            if j.get("state") == "error":
                ttk.Button(bar, text="Try again", command=lambda: (j.update(state="queued", msg=""), store.save_job(j), daemon.tell("wake"), render(True))).pack(anchor="w", pady=2)
            if j.get("state") in ("transcribing", "matching"):
                ttk.Button(bar, text="Pause all transcribing", command=lambda: daemon.tell("pause")).pack(anchor="w", pady=2)
            if status().get("paused"):
                ttk.Button(bar, text="Resume transcribing", style="Fill.TButton", command=lambda: daemon.tell("resume")).pack(anchor="w", pady=2)
            ttk.Button(bar, text="Remove this job", command=lambda: remove_job(j)).pack(anchor="w", pady=2)
            return
        tk.Frame(detail, height=1, bg=RULE).pack(fill="x", pady=12)
        if d.get("audiobook"):
            ttk.Button(detail, text="Make the sync map from the tablet's audiobook", style="Fill.TButton",
                       command=lambda: start_tablet_job(d)).pack(anchor="w", pady=3)
        ttk.Button(detail, text="Add audiobook files from this computer…", style="TButton" if d.get("audiobook") else "Fill.TButton",
                   command=lambda: add_files(d)).pack(anchor="w", pady=3)
        if j and j.get("state") == "done" and j.get("msg"):
            ttk.Label(detail, text=j["msg"], style="Muted.TLabel", wraplength=330).pack(anchor="w", pady=(10, 0))

    # ------------------------------------------------------------------ actions

    def model_dialog(title, files=None):
        """Asks for the speech model (and the files' order). Returns (model, files) or None."""
        dlg = tk.Toplevel(root)
        dlg.title(title)
        dlg.configure(bg="white")
        dlg.transient(root)
        dlg.grab_set()
        fr = ttk.Frame(dlg, padding=18)
        fr.pack(fill="both", expand=True)
        ttk.Label(fr, text=title, font=(serif, 15, "bold")).pack(anchor="w", pady=(0, 10))
        lst = None
        order = list(files or [])
        if files is not None:
            ttk.Label(fr, text="The files, in reading order", style="Muted.TLabel").pack(anchor="w")
            lst = tk.Listbox(fr, height=min(12, max(4, len(order))), width=64, relief="solid", bd=1, highlightthickness=0, activestyle="none")
            lst.pack(fill="x", pady=4)
            for p in order:
                lst.insert("end", os.path.basename(p))

            def move(step):
                sel = lst.curselection()
                if not sel:
                    return
                i = sel[0]
                k = i + step
                if 0 <= k < len(order):
                    order[i], order[k] = order[k], order[i]
                    lst.delete(0, "end")
                    for p in order:
                        lst.insert("end", os.path.basename(p))
                    lst.selection_set(k)
            row = ttk.Frame(fr)
            row.pack(anchor="w", pady=(0, 10))
            ttk.Button(row, text="Up", command=lambda: move(-1)).pack(side="left")
            ttk.Button(row, text="Down", command=lambda: move(1)).pack(side="left", padx=6)
        ttk.Label(fr, text="Speech model", style="Muted.TLabel").pack(anchor="w")
        keys = list(core.MODELS)
        s = store.settings()
        mv = tk.StringVar(value=core.MODELS[s.get("model", "pc_en_exact") if s.get("model") in core.MODELS else "pc_en_exact"]["label"])
        ttk.Combobox(fr, textvariable=mv, values=[core.MODELS[k]["label"] for k in keys], state="readonly", width=52).pack(anchor="w", pady=(2, 14))
        out = {}

        def ok():
            key = next(k for k in keys if core.MODELS[k]["label"] == mv.get())
            s["model"] = key
            store.save_settings(s)
            out["r"] = (key, order)
            dlg.destroy()
        row = ttk.Frame(fr)
        row.pack(fill="x")
        ttk.Button(row, text="Start", style="Fill.TButton", command=ok).pack(side="right")
        ttk.Button(row, text="Cancel", command=dlg.destroy).pack(side="right", padx=8)
        dlg.wait_window()
        return out.get("r")

    def start_tablet_job(d):
        r = model_dialog("Make the sync map")
        if r:
            store.new_job(d, "tablet", model=r[0])
            daemon.tell("wake")
            render(True)

    def add_files(d):
        paths = filedialog.askopenfilenames(parent=root, title="The audiobook's files",
                                            filetypes=[("Audio", "*.mp3 *.m4a *.m4b *.aac *.ogg *.opus *.flac *.wav"), ("All files", "*.*")])
        if not paths:
            return
        r = model_dialog("Add the audiobook", sorted(paths, key=_natural))
        if r:
            store.new_job(d, "files", files=r[1], model=r[0])
            daemon.tell("wake")
            render(True)

    def remove_job(j):
        if j.get("state") in ("transcribing", "matching", "sending") and not messagebox.askyesno("Remove the job", "Stop and remove this job? What is transcribed so far is lost.", parent=root):
            return
        if j.get("state") in ("transcribing", "matching", "sending"):
            daemon.tell("stop-job")
            time.sleep(0.5)
        for rel in (os.path.join("jobs", j["id"] + ".json"), os.path.join("transcripts", j["id"] + ".json"), os.path.join("maps", j["id"] + ".json")):
            store.remove(rel)
        daemon.tell("wake")
        render(True)

    def open_settings():
        dlg = tk.Toplevel(root)
        dlg.title("Settings")
        dlg.configure(bg="white")
        dlg.transient(root)
        fr = ttk.Frame(dlg, padding=18)
        fr.pack(fill="both", expand=True)
        s = store.settings()
        ttk.Label(fr, text="The tablet", font=(serif, 15, "bold")).pack(anchor="w")
        ttk.Label(fr, text=("Paired with " + (s.get("tabletName") or "the tablet")) if s.get("key") else "Not paired yet.", style="Muted.TLabel").pack(anchor="w", pady=(2, 8))
        ttk.Label(fr, text="Address (as on the tablet: Settings → Library in the browser)").pack(anchor="w")
        addr = tk.StringVar(value=s.get("address", core.DEFAULT_ADDRESS))
        ttk.Entry(fr, textvariable=addr, width=44).pack(anchor="w", pady=(2, 8))
        ttk.Label(fr, text="Pairing code (on the tablet: Pair a phone)").pack(anchor="w")
        code = tk.StringVar()
        ttk.Entry(fr, textvariable=code, width=16).pack(anchor="w", pady=(2, 4))
        msg = ttk.Label(fr, text="", style="Muted.TLabel", wraplength=420)
        msg.pack(anchor="w")

        def pair():
            c = re.sub(r"[^A-Za-z0-9]", "", code.get()).upper()
            if len(c) != 8:
                msg.configure(text="The code has 8 letters and digits.")
                return
            msg.configure(text="Pairing…")

            def work():
                try:
                    t = core.Tablet(addr.get())
                    t.tries = 1
                    r = t.pair(c, f"Marginalia Companion on {socket.gethostname()}")
                    s2 = store.settings()
                    s2.update(address=t.base, key=r["key"], tabletName=r.get("tabletName", ""))
                    store.save_settings(s2)
                    daemon.tell("wake")
                    root.after(0, lambda: msg.configure(text=f"Paired with {r.get('tabletName') or 'the tablet'}. The library comes in a moment."))
                except Exception as e:
                    err = str(e)
                    root.after(0, lambda: msg.configure(text=err))
            threading.Thread(target=work, daemon=True).start()
        ttk.Button(fr, text="Pair", style="Fill.TButton", command=pair).pack(anchor="w", pady=(6, 14))
        tk.Frame(fr, height=1, bg=RULE).pack(fill="x", pady=6)
        auto = tk.BooleanVar(value=autostart.enabled())
        ttk.Checkbutton(fr, text={"nt": "Start with Windows"}.get(os.name, "Open at login" if sys.platform == "darwin" else "Start with the computer"),
                        variable=auto, command=lambda: autostart.set_enabled(auto.get())).pack(anchor="w", pady=4)
        import nvidia
        if nvidia.card_present():
            gs = (status().get("gpu") or {})
            ttk.Label(fr, text="Graphics card: " + ("downloading its support now…" if gs.get("busy") else "support installed" if nvidia.installed() else "NVIDIA card found, support not downloaded"),
                      style="Muted.TLabel").pack(anchor="w", pady=(8, 2))
            if not nvidia.installed() and not gs.get("busy"):
                ttk.Button(fr, text="Download graphics-card support (about 1 GB, once)", command=lambda: (dlg.destroy(), download_gpu())).pack(anchor="w")
        tk.Frame(fr, height=1, bg=RULE).pack(fill="x", pady=10)
        ttk.Label(fr, text=f"Version {info_build.get('version', core.VERSION)} ({info_build.get('variant', 'source')})", style="Muted.TLabel").pack(anchor="w")
        ttk.Button(fr, text="Close", command=dlg.destroy).pack(anchor="e", pady=(12, 0))

    def download_gpu():
        """The tray app downloads it (it goes on when this window is closed); its progress comes back in status.json."""
        state["gpu_offer"] = False
        if not daemon.tell("gpu-download"):
            notice_text.configure(text="The tray app is not running. Start Marginalia Companion again and retry.")
        render(True)

    def do_update(rel):
        variant = info_build.get("variant", "source")
        found = updater.asset_for(variant, rel["assets"]) if variant != "source" else None
        if not found:
            import webbrowser
            webbrowser.open(rel["url"])
            return
        notice_btn.configure(state="disabled")

        def work():
            try:
                new = updater.download_and_stage(found[0], found[1], lambda d, t: root.after(0, lambda: notice_text.configure(
                    text=f"Downloading version {rel['version']}… {d // 1_000_000} of {t // 1_000_000 or '?'} MB")))
                daemon.tell("quit")  # the tray app holds the app's files: it closes first
                for _ in range(40):
                    if not daemon.tell("wake", timeout=0.3):
                        break
                    time.sleep(0.25)
                time.sleep(1.5)
                updater.swap_and_restart(new)
                root.after(0, root.destroy)
            except Exception as e:
                err = str(e)
                root.after(0, lambda: notice_text.configure(text=f"The update could not be installed ({err}). Download it from GitHub instead."))
        threading.Thread(target=work, daemon=True).start()

    def check_updates():
        try:
            rel = updater.newer(info_build.get("version", core.VERSION))
            if rel:
                state["update"] = rel
        except Exception:
            pass
        try:
            import nvidia
            state["gpu_offer"] = nvidia.card_present() and not nvidia.installed()
        except Exception:
            pass

    def tick():
        try:
            if store.mtime("raise.json") != state["raise_m"]:
                state["raise_m"] = store.mtime("raise.json")
                root.deiconify()
                root.lift()
                root.attributes("-topmost", True)
                root.after(300, lambda: root.attributes("-topmost", False))
                root.focus_force()
            render()
        except Exception:
            import traceback
            traceback.print_exc()
        root.after(1500, tick)

    threading.Thread(target=check_updates, daemon=True).start()
    render(True)
    root.after(200, lambda: (root.lift(), root.focus_force()))
    tick()
    root.mainloop()
