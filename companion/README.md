# Marginalia Companion

Syncs an audiobook with its book's text **on a computer** instead of in the browser, so the text can follow the
audio sentence by sentence.

In the browser, Whisper runs inside one tab: Firefox has no graphics-card support for it, so it falls back to a
slow mode and keeps the whole model and audio in that tab's memory, and the tab can crash. Here Whisper
(faster-whisper) runs natively, on every processor core (or a CUDA graphics card) and with the computer's own
memory: typically several times faster, and it does not crash the browser.

## Two ways to use it

**From the tablet, over Wi‑Fi** (nothing to move by hand)
1. On the tablet, turn on the web view: Settings → *Sync & web view* → *Library in the browser*. Note the
   address and the PIN.
2. In the companion: enter the address (the same one you open in the browser, e.g. `http://marginalia.local:8080`)
   and the PIN, press **Connect**, and pick the book.
3. Choose a speech model and press **Start**. The audio comes from the tablet five minutes at a time, and the
   transcript is saved on the tablet as it grows: you can press **Stop**, or close the window, and continue
   later (also from the web view).
4. At the end the sentences are matched and the map is saved in the book's folder on the tablet, the same way as
   the web view does it. The tablet then follows the audio sentence by sentence on its own.

**From files on this computer** (when the computer has the audiobook files too, e.g. through Syncthing)
1. Open the *From files on this computer* tab, add the audiobook's files in reading order and press **Start**.
2. You get a transcript file (`Transcript - <name>.json`), saved as it goes.
3. In the web view: the book → Audiobook → **Sync with the text…** → **Import transcript…**. The sentences are
   then matched in a few seconds and the map is saved on the tablet.

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

**Ready-made builds:** Releases → *Marginalia Companion* → the file for your computer. Unpack it and open
*Marginalia Companion*.
- Windows with an NVIDIA graphics card (e.g. RTX 3070): take **Marginalia-Companion-windows-nvidia**. It carries
  NVIDIA's libraries (about 1 GB more) and does the listening on the card, many times faster. At the start it
  tries the card on a second of silence; if that fails, it uses the processor instead, so it never stops.
  The window says which one it runs on.
- Mac with Apple Silicon (M1 and later): the Mac's graphics are tried the same way (experimental in the speech
  engine, often about twice as fast); otherwise every processor core is used. Intel Macs: not built yet.
- Windows may warn about an unknown publisher: *More info* → *Run anyway*.
- macOS: right-click the app → *Open* the first time.

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
