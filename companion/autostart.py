"""Start with the computer: Windows (Run key), macOS (LaunchAgent), Linux (~/.config/autostart)."""
import os
import sys

NAME = "MarginaliaCompanion"


def _command():
    if getattr(sys, "frozen", False):
        return [sys.executable, "--tray"]
    return [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "marginalia_companion.py"), "--tray"]


def _mac_plist():
    return os.path.expanduser("~/Library/LaunchAgents/no.marginalia.companion.plist")


def _linux_desktop():
    return os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "autostart", "marginalia-companion.desktop")


def enabled():
    try:
        if os.name == "nt":
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
                winreg.QueryValueEx(k, NAME)
                return True
        if sys.platform == "darwin":
            return os.path.isfile(_mac_plist())
        return os.path.isfile(_linux_desktop())
    except Exception:
        return False


def set_enabled(on):
    cmd = _command()
    if os.name == "nt":
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, NAME, 0, winreg.REG_SZ, " ".join(f'"{c}"' if " " in c else c for c in cmd))
            else:
                try:
                    winreg.DeleteValue(k, NAME)
                except FileNotFoundError:
                    pass
        return
    if sys.platform == "darwin":
        p = _mac_plist()
        if on:
            os.makedirs(os.path.dirname(p), exist_ok=True)
            args = "".join(f"<string>{c}</string>" for c in cmd)
            with open(p, "w") as f:
                f.write('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
                        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>'
                        f"<key>Label</key><string>no.marginalia.companion</string><key>ProgramArguments</key><array>{args}</array>"
                        "<key>RunAtLoad</key><true/><key>ProcessType</key><string>Interactive</string></dict></plist>\n")
        elif os.path.isfile(p):
            os.remove(p)
        return
    p = _linux_desktop()
    if on:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        exe = " ".join(f'"{c}"' if " " in c else c for c in cmd)
        with open(p, "w") as f:
            f.write(f"[Desktop Entry]\nType=Application\nName=Marginalia Companion\nExec={exe}\nX-GNOME-Autostart-enabled=true\nNoDisplay=false\n")
    elif os.path.isfile(p):
        os.remove(p)


def refresh():
    """Keeps "start with the computer" pointing at this copy of the app (it may have been installed elsewhere since)."""
    try:
        if enabled():
            set_enabled(True)
    except Exception:
        pass


def linux_menu_entry():
    """Linux: puts Marginalia Companion in the program menu (no installer there), pointing at this copy."""
    if os.name == "nt" or sys.platform == "darwin" or not getattr(sys, "frozen", False):
        return
    try:
        import tray_icon
        data = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
        icon = os.path.join(data, "icons", "hicolor", "256x256", "apps", "marginalia-companion.png")
        os.makedirs(os.path.dirname(icon), exist_ok=True)
        if not os.path.isfile(icon):
            tray_icon.image(None, size=256).save(icon)
        desk = os.path.join(data, "applications", "marginalia-companion.desktop")
        os.makedirs(os.path.dirname(desk), exist_ok=True)
        exe = sys.executable
        with open(desk, "w") as f:
            f.write("[Desktop Entry]\nType=Application\nName=Marginalia Companion\n"
                    "Comment=Syncs audiobooks with their books' text for the Marginalia tablet\n"
                    f'Exec="{exe}"\nIcon=marginalia-companion\nCategories=AudioVideo;Education;\nTerminal=false\n')
    except Exception:
        pass
