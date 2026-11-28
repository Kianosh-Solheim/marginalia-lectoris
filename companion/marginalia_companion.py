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


def main():
    args = sys.argv[1:]
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
    if "--window" in args:
        import window
        window.run()
        return
    import daemon
    daemon.run(open_window="--tray" not in args)


if __name__ == "__main__":
    main()
