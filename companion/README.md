# Marginalia Companion

Syncs an audiobook with its book's text **on a computer** instead of in the browser, so the text can follow the
audio sentence by sentence.

In the browser, Whisper runs inside one tab: Firefox has no graphics-card support for it, so it falls back to a
slow mode and keeps the whole model and audio in that tab's memory, and the tab can crash. Here Whisper
(faster-whisper) runs natively, on every processor core (or a CUDA graphics card) and with the computer's own
memory: typically several times faster, and it does not crash the browser.

## How it works

The companion lives in the **tray** by the clock (Windows, Linux Mint) or the **menu bar** (macOS) and starts with
the computer (turn that off in its menu). Its menu shows whether the tablet is on the same Wi‑Fi and what it is
working on; **Open the library** opens its window.

1. **Pair once:** on the tablet, Settings → *Library in the browser* (on) → *Pair a phone*. In the companion:
   Settings → the tablet's address and the 8‑letter code → **Pair**. The computer then appears among the tablet's
   paired phones (remove it there to unpair).
2. **The library:** every book on the tablet, with its audiobook and sync map. The companion keeps a copy of the
   list and of each book's text, refreshed whenever the tablet is on the Wi‑Fi, so it also works away from it.
3. **Make a sync map:**
   - *From the tablet's audiobook:* **Make the sync map from the tablet's audiobook**. The audio comes from the
     tablet five minutes at a time (it needs the tablet on the Wi‑Fi; a stopped job continues).
   - *From audio files on this computer:* **Add audiobook files from this computer…**, put them in reading order,
     pick the speech model. This works away from the tablet too.
4. **Sending:** the next time the tablet is on the same Wi‑Fi, the map goes to the book's folder there, with the
   audio files if the tablet doesn't have them yet (it checks: files of exactly the same size are not sent again).
   If the tablet has *other* audio for the book, the companion asks before replacing it.

Close the window whenever you like: the work goes on in the tray. **Pause transcribing** in the menu stops after
the current five minutes; **Quit** closes it altogether (what is done is kept).

## Speech models

| Model | For | Speed on a laptop processor |
|---|---|---|
| English · fast (base.en) | English, quick | fastest |
| English · more exact (small.en) | English | good default |
| Norwegian · fast (small) | Norwegian | moderate |
| Norwegian · more exact (medium) | Norwegian | slower |
| Other language, detected (small) | any language | moderate |
| Best, any language (large-v3-turbo) | best results | slow without a graphics card |

Each model is downloaded once (75 MB to 1.6 GB) and kept on the computer.

## Install

**Ready-made builds:** Releases → *Marginalia Companion* → the file for your computer.
- **Windows:** `Marginalia-Companion-windows-setup.exe`. It installs for you alone (no administrator), adds
  Marginalia Companion to the Start menu (so Windows search finds it) and can be removed in Settings → Apps.
  Windows may warn about an unknown publisher: *More info* → *Run anyway*.
- **Windows with an NVIDIA graphics card** (e.g. RTX 3070): the companion notices the card and offers to download
  its support from NVIDIA (about 1 GB, once; kept across updates). Then it does the listening on the card.
- **Mac (Apple Silicon):** open `Marginalia-Companion-macos.dmg` and drag the app into Applications. The first
  time: right-click it → *Open* (or System Settings → Privacy & Security → *Open Anyway*). It may use the Mac's
  graphics (tried first; otherwise every processor core).
- **Linux (Mint and others):** unpack `Marginalia-Companion-linux.tar.gz` somewhere to keep and open
  *Marginalia Companion*; it adds itself to the program menu.

**From this folder (any computer with Python 3.9 or newer):**
- Linux Mint / Ubuntu: `sudo apt install python3-tk python3-venv`, then `./run.sh`
- Windows: install Python from python.org (tick *tcl/tk* and *Add to PATH*), then double-click `run.bat`
- macOS: install Python from python.org, then double-click `run.command`

The first start sets everything up in a `.venv` folder here (a few minutes, once).

## Updates

The ready-made builds check GitHub for a newer companion release each time they start. When there is one, a
bar at the top says so: **Update and restart** downloads the file for this computer, puts it in place when the
window closes and opens the new version (settings and downloaded speech models are kept). **Later** hides it
until the next start. Run from this folder, it only links to the new release.

## For developers

`align.py` is a line-by-line port of `abAlign()` in `app/src/main/assets/web/audiobook.js`, so a map made here is
identical to one made in the browser. `python3 tests/parity.py` (needs Node.js) checks this on random books and
transcripts, and runs on every change in GitHub Actions together with the three builds and their self-test
(`--self-test`).
