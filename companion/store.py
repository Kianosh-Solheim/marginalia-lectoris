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
    return sorted(out, key=lambda j: j.get("created", 0))


def job(job_id):
    return read(os.path.join("jobs", job_id + ".json"))


def save_job(j):
    j["updated"] = time.time()
    write(os.path.join("jobs", j["id"] + ".json"), j)


def new_job(book, source, files=None, model="pc_en_exact"):
    """source: "tablet" (the audiobook already on the tablet) or "files" (audio files on this computer)."""
    j = {"id": _uuid.uuid4().hex[:12], "uuid": book.get("uuid"), "docId": book.get("id"), "title": book.get("title", ""),
         "source": source, "files": files or [], "model": model, "state": "queued", "frac": 0.0, "msg": "",
         "created": time.time()}
    save_job(j)
    return j


def job_for(book_uuid):
    """The newest job for a book."""
    js = [j for j in jobs() if j.get("uuid") == book_uuid]
    return js[-1] if js else None
