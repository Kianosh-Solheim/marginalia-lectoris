"""
Marginalia Companion: syncs an audiobook with its book's text on a computer, for the Marginalia tablet.

    (no arguments)   start in the tray (menu bar on macOS) and open the library window
    --tray           start in the tray only (used when it starts with the computer)
    --window         the library window (started by the tray app)
    --self-test      check the packaged app (used by the build)
    --version        print the version
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import core  # noqa: E402  (sets up the graphics-card libraries first)


def _log_to_file():
    """
    A windowed app has no console: sys.stdout/stderr are None, and anything that writes to them (the speech
    model's download progress bars, error traces) fails. Send them to a log file instead, and quiet the bars.
    """
    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
    os.environ.setdefault("TQDM_DISABLE", "1")
    if sys.stdout is not None and sys.stderr is not None:
        return
    try:
        path = os.path.join(core._data_dir(), "companion.log")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if os.path.isfile(path) and os.path.getsize(path) > 5_000_000:
            os.replace(path, path + ".old")
        f = open(path, "a", buffering=1, encoding="utf-8", errors="replace")
    except Exception:
        f = open(os.devnull, "w")
    if sys.stdout is None:
        sys.stdout = f
    if sys.stderr is None:
        sys.stderr = f


_log_to_file()


def _wait_for_update():
    """While an update is being put in place, its files come and go: wait (up to 10 minutes) instead of starting."""
    import time
    flag = os.path.join(core._data_dir(), "updating.flag")
    for _ in range(300):
        try:
            if time.time() - os.path.getmtime(flag) > 600:
                return
        except OSError:
            return
        time.sleep(2)


def main():
    args = sys.argv[1:]
    if not ({"--gpu-probe", "--self-test", "--version"} & set(args)):
        _wait_for_update()
    if "--gpu-probe" in args:
        core.gpu_probe(args[args.index("--gpu-probe") + 1] if len(args) > args.index("--gpu-probe") + 1 else "cuda")
    if "--self-test" in args:
        core.self_test()
        sys.stdout.flush()
        os._exit(0)  # the speech engine's threads (macOS) would keep it alive
    if "--version" in args:
        import updater
        b = updater.build_info(core.VERSION)
        print(core.APP, b.get("version", core.VERSION), b.get("variant", ""))
        return
    if os.name == "nt":  # tells the installer that the app is running (installer.iss: AppMutex)
        try:
            import ctypes
            ctypes.windll.kernel32.CreateMutexW(None, False, "MarginaliaCompanionRunning")
        except Exception:
            pass
    if "--window" in args:
        import window
        window.run()
        return
    import daemon
    daemon.run(open_window="--tray" not in args)


if __name__ == "__main__":
    main()
