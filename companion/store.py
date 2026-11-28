"""
The companion's own files, shared by the tray (which does the work) and the library window (which shows it):

    settings.json           tablet address, device key, speech model, start at login
    library.json            the tablet's library as last seen (so it can be shown away from the tablet)
    texts/<uuid>.json       each book's text, kept for matching away from the tablet
    jobs/<id>.json          an audiobook to transcribe, match and send
    transcripts/<id>.json   its transcript as it grows (a stopped job continues from here)
    maps/<id>.json          the finished map, until the tablet has it
    status.json             what the tray is doing now, for the window
"""
import json
import os
import tempfile
import time
import uuid as _uuid

import core

DIR = core._data_dir()


def path(*parts):
    p = os.path.join(DIR, *parts)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def read(rel, default=None):
    try:
        with open(path(rel), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def write(rel, data):
    """Atomic: the other process never reads half a file."""
    p = path(rel)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(p), prefix=".tmp-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    for _ in range(20):
        try:
            os.replace(tmp, p)
            return
        except PermissionError:  # Windows: the other process is reading it right now
            time.sleep(0.05)
    os.replace(tmp, p)


def remove(rel):
    try:
        os.remove(path(rel))
    except FileNotFoundError:
        pass


def mtime(rel):
    try:
        return os.path.getmtime(path(rel))
    except OSError:
        return 0


def settings():
    s = read("settings.json", None)
    if s is None:  # carry over the address from version 1.0
        old = core.load_settings()
        s = {"address": old.get("address", core.DEFAULT_ADDRESS), "model": old.get("model", "pc_en_exact")}
        write("settings.json", s)
    return s


def save_settings(s):
    write("settings.json", s)


# ------------------------------------------------------------------ jobs

STATES = {
    "queued": "Waiting its turn",
    "transcribing": "Transcribing",
    "matching": "Matching the sentences",
    "send": "Ready, waits for the tablet",
    "sending": "Sending to the tablet",
    "done": "Synced",
    "paused": "Paused",
    "waiting": "Waits for the tablet",
    "conflict": "The tablet has other audio",
    "error": "Stopped",
}


def jobs():
    out = []
    d = path("jobs", "x")
    for name in os.listdir(os.path.dirname(d)):
        if name.endswith(".json"):
            j = read(os.path.join("jobs", name))
            if j:
                out.append(j)
    return sorted(out, key=lambda j: (j.get("order", j.get("created", 0)), j.get("created", 0)))


def job(job_id):
    return read(os.path.join("jobs", job_id + ".json"))


def save_job(j):
    j["updated"] = time.time()
    write(os.path.join("jobs", j["id"] + ".json"), j)


def new_job(book, source, files=None, model="pc_en_exact"):
    """source: "tablet" (the audiobook already on the tablet) or "files" (audio files on this computer)."""
    j = {"id": _uuid.uuid4().hex[:12], "uuid": book.get("uuid"), "docId": book.get("id"), "title": book.get("title", ""),
         "source": source, "files": files or [], "model": model, "state": "queued", "frac": 0.0, "msg": "",
         "created": time.time(), "order": time.time()}
    save_job(j)
    return j


def job_for(book_uuid):
    """The newest job for a book."""
    js = [j for j in jobs() if j.get("uuid") == book_uuid]
    return js[-1] if js else None


def started(j):
    """Whether the job's transcription has begun (then its model can't change)."""
    if j.get("state") in ("transcribing", "matching", "sending", "send", "done"):
        return True
    tr = read(os.path.join("transcripts", j["id"] + ".json"))
    return bool(tr and tr.get("doneMs"))


def move(job_id, step):
    """Moves a job up (-1) or down (+1) in the queue (among the jobs not done)."""
    q = [j for j in jobs() if j.get("state") != "done"]
    i = next((k for k, j in enumerate(q) if j["id"] == job_id), None)
    if i is None or not 0 <= i + step < len(q):
        return
    a, b = q[i], q[i + step]
    a["order"], b["order"] = b.get("order", b["created"]), a.get("order", a["created"])
    if a["order"] == b["order"]:
        a["order"] += step * 0.001
    save_job(a)
    save_job(b)


# ------------------------------------------------------------------ the book's language

LANGS = {"no": "Norwegian", "nb": "Norwegian", "nn": "Norwegian", "en": "English", "sv": "Swedish", "da": "Danish", "de": "German", "fr": "French"}
_NO = set("og ikke det er som på til med har jeg for av at den de en et var seg om men så kan vi hun han dette ble".split())
_EN = set("the and of to is that in it with as was for on are be this by not but have from they which".split())


def book_language(book_uuid):
    """(code, name, how): from the book's own metadata, else guessed from its words; (None, "Unknown", "") without its text."""
    t = (read(os.path.join("texts", book_uuid + ".json")) or {}).get("text") or {}
    code = (t.get("language") or "").lower().split("-")[0].split("_")[0]
    if code:
        return code, LANGS.get(code, code), "from the book"
    words = []
    for u in (t.get("units") or [])[:400]:
        words.extend((u.get("text") or (" ".join(u.get("words") or []) if isinstance(u.get("words"), list) else "")).lower().split())
        if len(words) > 6000:
            break
    if len(words) < 50:
        return None, "Unknown", ""
    no = sum(w in _NO for w in words)
    en = sum(w in _EN for w in words)
    if max(no, en) < 0.03 * len(words):
        return None, "Unknown", ""
    return ("no", "Norwegian", "guessed from its text") if no > en else ("en", "English", "guessed from its text")


def model_for(code):
    """The speech model to suggest for a book in this language."""
    return {"no": "pc_no", "nb": "pc_no", "nn": "pc_no", "en": "pc_en_exact"}.get(code or "", "pc_other")
